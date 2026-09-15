package org.koitharu.kotatsu.core.network.webview

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class WebViewExecutor @Inject constructor(
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

	val defaultUserAgent: String by lazy {
		ChromeTlsIdentity.USER_AGENT
	}

	suspend fun evaluateJs(baseUrl: String?, script: String): String? = mutex.withLock {
		withContext(Dispatchers.Main.immediate) {
			val webView = obtainWebView()
			try {
				if (!baseUrl.isNullOrEmpty()) {
					suspendCoroutine { cont ->
						webView.webViewClient = ContinuationResumeWebViewClient(cont)
						webView.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
					}
				}
				suspendCoroutine { cont ->
					webView.evaluateJavascript(script) { result ->
						cont.resume(result?.takeUnless { it == "null" })
					}
				}
			} finally {
				webView.reset()
			}
		}
	}

	suspend fun tryResolveCaptcha(exception: CloudFlareException, timeout: Long): Boolean = mutex.withLock {
		// A solve is only real if the clearance value actually changes. Resuming the
		// continuation on page finish alone reported success with no cookie saved,
		// which retried into another challenge — the same phantom-success loop fixed
		// in AutoCaptchaSolver.
		val clearanceBefore = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
		// Retry up to MAX_RESOLVE_ATTEMPTS times with increasing timeout
		for (attempt in 1..MAX_RESOLVE_ATTEMPTS) {
			val attemptTimeout = timeout + (attempt - 1) * RETRY_TIMEOUT_INCREMENT
			runCatchingCancellable {
				withContext(Dispatchers.Main.immediate) {
					val webView = obtainWebView()
					try {
						// Must match tls-client UA or Cloudflare rejects cf_clearance.
						val userAgent = exception.source.getUserAgent() ?: ChromeTlsIdentity.USER_AGENT
						webView.settings.userAgentString = userAgent
						// Stealth at document-start so Cloudflare's challenge scripts read a
						// consistent Windows Chrome 133 identity instead of the real Android one.
						installDocumentStartStealth(webView, userAgent)
						// Sync existing cookies to WebView before loading
						syncCookiesToWebView(exception.url)
						withTimeout(attemptTimeout) {
							suspendCancellableCoroutine { cont ->
								webView.webViewClient = CaptchaContinuationClient(
									cookieJar = cookieJar,
									targetUrl = exception.url,
									continuation = cont,
								)
								webView.loadUrl(exception.url)
							}
						}
						// Flush and sync cookies back. Include the live URL: after a
						// challenge redirect it often differs from exception.url.
						android.webkit.CookieManager.getInstance().flush()
						syncCookiesFromWebView(exception.url, webView.url)
					} finally {
						removeDocumentStartStealth()
						webView.reset()
					}
				}
			}.onFailure { e ->
				e.printStackTraceDebug()
				if (attempt == MAX_RESOLVE_ATTEMPTS) {
					exception.addSuppressed(e)
				}
			}
			val clearanceAfter = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
			if (!clearanceAfter.isNullOrBlank() && clearanceAfter != clearanceBefore) {
				return@withLock true
			}
		}
		false
	}

	/**
	 * Sync cookies from OkHttp CookieJar to Android WebView CookieManager
	 * so the WebView starts with any existing session cookies.
	 */
	private fun syncCookiesToWebView(url: String) {
		val httpUrl = url.toHttpUrlOrNull() ?: return
		val cookies = cookieJar.loadForRequest(httpUrl)
		val cookieManager = android.webkit.CookieManager.getInstance()
		for (cookie in cookies) {
			cookieManager.setCookie(url, cookie.toString())
		}
		cookieManager.flush()
	}

	/**
	 * Sync cookies from Android WebView CookieManager back to OkHttp CookieJar
	 * to ensure cf_clearance and other session cookies are available.
	 *
	 * Queries every given URL: `getCookie` filters by host, so clearance set on a
	 * redirect host is invisible when asked only for the challenge URL.
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
				webViewCached = WeakReference(webView)
				proxyProvider.applyWebViewConfig()
				// A WebView that is never attached to a window reports
				// document.visibilityState = "hidden", does not render and never
				// fires requestAnimationFrame — Cloudflare Turnstile treats that
				// as an instant bot signal and never issues cf_clearance.
				// Attach (invisible) to the visible activity window instead.
				attachToWindow(webView)
				// Fallback for when there is no foreground activity to attach to: an unmeasured
				// WebView is 0×0, which makes the challenge widget invisible to itself.
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
	 * the page's own scripts (and inside cross-origin Turnstile iframes).
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
		/**
		 * Two attempts at 45 s + 55 s already keeps the caller waiting for the better part of two
		 * minutes; a third only delays the visible resolve screen the user needs anyway.
		 */
		private const val MAX_RESOLVE_ATTEMPTS = 2
		private const val RETRY_TIMEOUT_INCREMENT = 10_000L // Add 10s per retry
		/** Nearly invisible but still rendered/attached — Turnstile needs a real window. */
		private const val INVISIBLE_ALPHA = 0.01f
	}
}
