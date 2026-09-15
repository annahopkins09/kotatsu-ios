package org.koitharu.kotatsu.core.network.webview

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.network.tls.ChromeTlsIdentity
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.layoutOffscreen
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Automatically solves CloudFlare JS challenges (Turnstile, Managed Challenge)
 * by loading the challenge page in an invisible WebView and injecting JavaScript
 * to interact with challenge elements.
 *
 * This runs BEFORE the existing [WebViewExecutor.tryResolveCaptcha] as a first
 * line of defense. If auto-solving fails, the existing flow continues unchanged.
 *
 * Key anti-detection measures:
 * - WebView is given realistic screen dimensions (0x0 is an instant bot signal)
 * - Stealth script masks navigator.webdriver, adds window.chrome, fixes plugins/languages
 * - User-Agent matches [ChromeTlsIdentity.USER_AGENT] so cf_clearance is accepted
 * - Original request headers (Referer, etc.) are forwarded to the challenge page
 */
@Singleton
class AutoCaptchaSolver @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
	private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
) {

	private var webViewCached: WeakReference<WebView>? = null
	private var startScriptHandler: ScriptHandler? = null
	private val mutex = Mutex()

	@Volatile
	private var topActivity: Activity? = null

	private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
		override fun onActivityStarted(activity: Activity) {
			if (topActivity == null) {
				topActivity = activity
			}
		}

		override fun onActivityResumed(activity: Activity) {
			topActivity = activity
		}

		override fun onActivityPaused(activity: Activity) {
			if (topActivity === activity) {
				topActivity = null
			}
		}

		override fun onActivityStopped(activity: Activity) {
			if (topActivity === activity) {
				topActivity = null
			}
		}

		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
		override fun onActivityDestroyed(activity: Activity) {
			if (topActivity === activity) {
				topActivity = null
			}
		}
	}

	init {
		(context as? Application)?.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
	}

	/**
	 * Attempt to automatically solve a CloudFlare captcha challenge.
	 *
	 * @param exception The [CloudFlareProtectedException] containing the blocked URL and source
	 * @param timeout Maximum time in milliseconds to wait for the challenge to be solved
	 * @return `true` if the challenge was solved (cf_clearance cookie obtained), `false` otherwise
	 */
	suspend fun trySolve(exception: CloudFlareProtectedException, timeout: Long): Boolean = mutex.withLock {
		// The clearance held before any attempt. A solve is only real if this value changes: the
		// WebView finishing, the challenge screen disappearing, or the continuation resuming prove
		// nothing on their own, and reporting success without a new cookie is what made the caller
		// clear the captcha state, dismiss the notification, retry, get a 403 and prompt all over
		// again — forever.
		val clearanceBefore = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
		// Retry a few times — Turnstile widgets often appear after a delay / soft fail.
		for (attempt in 1..MAX_SOLVE_ATTEMPTS) {
			val attemptTimeout = timeout + (attempt - 1) * RETRY_TIMEOUT_INCREMENT
			runCatchingCancellable {
				withContext(Dispatchers.Main.immediate) {
					val webView = obtainWebView()
					try {
						// Must match network stack UA or Cloudflare rejects cf_clearance.
						val userAgent = exception.source.getUserAgent() ?: ChromeTlsIdentity.USER_AGENT
						webView.settings.userAgentString = userAgent
						// Inject stealth at document-start so it runs before the page's own
						// scripts read the real (Android) fingerprints — and in every frame,
						// including Cloudflare's cross-origin Turnstile iframe.
						installDocumentStartStealth(webView, userAgent)
						// Seed WebView with existing session cookies before challenge load.
						syncCookiesToWebView(exception.url)
						withTimeout(attemptTimeout) {
							suspendCancellableCoroutine { cont ->
								webView.webViewClient = AutoCaptchaWebViewClient(
									cookieJar = cookieJar,
									targetUrl = exception.url,
									userAgent = userAgent,
									continuation = cont,
								)
								// Forward original request headers (Referer, etc.)
								val extraHeaders = buildExtraHeaders(exception)
								if (extraHeaders.isEmpty()) {
									webView.loadUrl(exception.url)
								} else {
									webView.loadUrl(exception.url, extraHeaders)
								}
								// Belt-and-braces: also inject stealth after load. This runs in
								// the page realm even if document-start injection is unavailable.
								webView.evaluateJavascript(CaptchaSolverScript.stealthScript(userAgent), null)
							}
						}
						// Persist and pull clearance back into OkHttp CookieJar.
						// Include the WebView's live URL: after a challenge redirect it
						// is often a different host than exception.url, and getCookie
						// only returns cookies for the host asked about.
						CookieManager.getInstance().flush()
						syncCookiesFromWebView(exception.url, webView.url)
					} finally {
						removeDocumentStartStealth()
						webView.reset()
					}
				}
			}.onFailure { e ->
				e.printStackTraceDebug()
				if (attempt == MAX_SOLVE_ATTEMPTS) {
					exception.addSuppressed(e)
				}
			}
			val clearanceAfter = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
			if (!clearanceAfter.isNullOrBlank() && clearanceAfter != clearanceBefore) {
				return@withLock true
			}
			android.util.Log.w(
				TAG,
				"trySolve attempt $attempt/${MAX_SOLVE_ATTEMPTS} for ${exception.url}: " +
					"no fresh cf_clearance in jar " +
					"(hadBefore=${!clearanceBefore.isNullOrBlank()} hasAfter=${!clearanceAfter.isNullOrBlank()})",
			)
		}
		false
	}

	/**
	 * Build extra headers to forward from the original failed request to the WebView.
	 * Only forwards safe/useful headers — CloudFlare may reject mismatched headers.
	 */
	private fun buildExtraHeaders(exception: CloudFlareProtectedException): Map<String, String> {
		val headers = mutableMapOf<String, String>()
		// Forward Referer if present — some sources require it
		exception.headers["Referer"]?.let { headers["Referer"] = it }
		// Forward Accept-Language if present
		exception.headers["Accept-Language"]?.let { headers["Accept-Language"] = it }
		return headers
	}

	/**
	 * Sync cookies from OkHttp CookieJar to Android WebView CookieManager
	 * so the WebView starts with any existing session cookies.
	 */
	private fun syncCookiesToWebView(url: String) {
		val httpUrl = url.toHttpUrlOrNull() ?: return
		val cookies = cookieJar.loadForRequest(httpUrl)
		val cookieManager = CookieManager.getInstance()
		for (cookie in cookies) {
			cookieManager.setCookie(url, cookie.toString())
		}
		cookieManager.flush()
	}

	/**
	 * Sync cookies from Android WebView CookieManager back to OkHttp CookieJar
	 * so cf_clearance (and related CF session cookies) are available to network calls.
	 *
	 * Goes through [AndroidCookieJar.syncFromWebView], which queries every given URL:
	 * [CookieManager.getCookie] filters by host, so clearance set on `www.site.com`
	 * is invisible when asked only for `site.com`. Always pass the WebView's live URL
	 * alongside the challenge URL.
	 */
	private fun syncCookiesFromWebView(vararg urls: String?) {
		AndroidCookieJar.syncFromWebView(cookieJar, *urls)
	}

	private suspend fun obtainWebView(): WebView {
		webViewCached?.get()?.let {
			return it
		}
		return withContext(Dispatchers.Main.immediate) {
			webViewCached?.get()?.let {
				return@withContext it
			}
			WebView(context).also { webView ->
				webView.configureForParser(ChromeTlsIdentity.USER_AGENT)
				// Set WebChromeClient — required for some JS challenge operations
				// (console messages, JS dialogs, etc.)
				webView.webChromeClient = WebChromeClient()
				webViewCached = WeakReference(webView)
				proxyProvider.applyWebViewConfig()
				// A WebView that is never attached to a window reports
				// document.visibilityState = "hidden", does not render and never
				// fires requestAnimationFrame — Cloudflare Turnstile treats that
				// as an instant bot signal and never issues cf_clearance.
				// Attach (invisible) to the visible activity window instead.
				attachToWindow(webView)
				// No foreground activity to attach to (background sync, notification-triggered
				// load): measure and lay the view out by hand so it is at least not 0×0, which
				// would make the widget invisible and every touch land on (0, 0).
				webView.layoutOffscreen()
				webView.onResume()
				webView.resumeTimers()
			}
		}
	}

	/**
	 * Attach the WebView to the topmost activity's window so it renders and runs
	 * rAF like a real browser tab. It is kept visually imperceptible via a tiny alpha,
	 * so the user never sees it.
	 */
	@MainThread
	private fun attachToWindow(webView: WebView) {
		val activity = topActivity ?: return
		val content = (activity.findViewById<android.view.View>(android.R.id.content) as? ViewGroup)
			?: (activity.window?.decorView as? ViewGroup)
			?: return
		if (content.isAttachedToWindow.not()) return
		val parent = webView.parent
		if (parent === content) return
		(parent as? ViewGroup)?.removeView(webView)
		try {
			// Place BEHIND the app's own content: fully composited (so the page reports
			// document.visibilityState = "visible" and rAF fires) but visually covered by
			// the opaque app UI. alpha=0.01 is a fallback cloak in case the app content is
			// translucent, while still rendering to Turnstile.
			webView.alpha = INVISIBLE_ALPHA
			webView.visibility = android.view.View.VISIBLE
			content.addView(webView, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	/**
	 * Inject [CaptchaSolverScript.stealthScript] at document-start so it executes before
	 * the page's own scripts (and inside cross-origin Turnstile iframes). Falls back to
	 * the post-load injection in [trySolve] on WebViews that lack the feature.
	 */
	@MainThread
	private fun installDocumentStartStealth(webView: WebView, userAgent: String) {
		removeDocumentStartStealth()
		if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
		runCatching {
			startScriptHandler = WebViewCompat.addDocumentStartJavaScript(
				webView,
				CaptchaSolverScript.stealthScript(userAgent),
				setOf("*"),
			)
		}.onFailure { it.printStackTraceDebug() }
	}

	/**
	 * `ScriptHandler.remove` itself requires DOCUMENT_START_SCRIPT. The handler can only be non-null
	 * when the feature was available, but re-checking keeps that guarantee local instead of implied.
	 */
	@MainThread
	private fun removeDocumentStartStealth() {
		val handler = startScriptHandler ?: return
		startScriptHandler = null
		if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
		runCatching { handler.remove() }.onFailure { it.printStackTraceDebug() }
	}

	private fun MangaSource.getUserAgent(): String? {
		val repository = mangaRepositoryFactoryProvider.get().create(this) as? ParserMangaRepository
		return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
			?: ChromeTlsIdentity.USER_AGENT
	}

	@MainThread
	private fun WebView.reset() {
		stopLoading()
		webViewClient = WebViewClient()
		settings.userAgentString = ChromeTlsIdentity.USER_AGENT
		loadDataWithBaseURL(null, " ", "text/html", null, null)
		clearHistory()
		// Detach from the activity window so we don't leak the view.
		(parent as? ViewGroup)?.removeView(this)
	}

	companion object {
		private const val TAG = "CaptchaCookies"
		/**
		 * Two, not three. Each attempt costs its full timeout, and a headless WebView that failed the
		 * same challenge twice is not going to pass it on the third try — it is going to keep the caller
		 * blocked for another minute and put one more request on an endpoint Cloudflare is already
		 * challenging. Handing off to the visible screen is both faster and likelier to work.
		 */
		private const val MAX_SOLVE_ATTEMPTS = 2
		private const val RETRY_TIMEOUT_INCREMENT = 5_000L
		/** Nearly invisible but still rendered/attached — Turnstile needs a real window. */
		private const val INVISIBLE_ALPHA = 0.01f
	}
}
