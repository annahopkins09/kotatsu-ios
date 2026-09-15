package org.koitharu.kotatsu.core.network.webview

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import kotlin.coroutines.Continuation

class CaptchaContinuationClient(
	private val cookieJar: MutableCookieJar,
	private val targetUrl: String,
	continuation: Continuation<Unit>,
) : ContinuationResumeWebViewClient(continuation) {

	private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
	private val handler = Handler(Looper.getMainLooper())
	private var webViewRef: WebView? = null
	private val cookieCheckRunnable: Runnable = object : Runnable {
		override fun run() {
			syncCookiesFromWebView()
			if (isClearanceObtained()) {
				val wv = webViewRef
				if (wv != null) {
					handler.removeCallbacks(this)
					resumeContinuation(wv)
				}
			} else {
				handler.postDelayed(this, COOKIE_CHECK_INTERVAL)
			}
		}
	}

	// Do NOT call super — parent's onPageFinished calls resumeContinuation which
	// would prematurely resolve before the CF challenge is actually solved.
	override fun onPageFinished(view: WebView?, url: String?) {
		if (view != null) webViewRef = view
		syncCookiesFromWebView()
		if (view != null && isClearanceObtained()) {
			handler.removeCallbacks(cookieCheckRunnable)
			resumeContinuation(view)
		}
	}

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		webViewRef = view
		syncCookiesFromWebView()
		if (view != null && isClearanceObtained()) {
			handler.removeCallbacks(cookieCheckRunnable)
			resumeContinuation(view)
			return
		}
		// Start periodic cookie polling to catch Turnstile solutions
		handler.removeCallbacks(cookieCheckRunnable)
		handler.postDelayed(cookieCheckRunnable, COOKIE_CHECK_INTERVAL)
	}

	private fun isClearanceObtained(): Boolean {
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		if (!clearance.isNullOrBlank() && clearance != oldClearance) return true
		// The jar can lag one sync behind the WebView; consult the live store too,
		// mirroring AutoCaptchaWebViewClient. Otherwise a solved challenge times out
		// here while the cookie is sitting in the WebView unsaved.
		val httpUrl = targetUrl.toHttpUrlOrNull() ?: return false
		val raw = CookieManager.getInstance().getCookie(targetUrl) ?: return false
		return raw.split(';').any { part ->
			val c = AndroidCookieJar.parseWebViewCookie(httpUrl, part)
				?: AndroidCookieJar.buildWebViewCookie(httpUrl, part)
				?: return@any false
			c.name == CF_CLEARANCE && c.value.isNotBlank() && c.value != oldClearance
		}
	}

	/**
	 * Sync cookies from Android WebView CookieManager back into OkHttp's CookieJar.
	 * This ensures cf_clearance obtained by the WebView is available to OkHttp requests.
	 *
	 * Queries both the challenge URL and the WebView's live URL: after a challenge
	 * redirect they are often different hosts, and `getCookie` filters by host.
	 */
	private fun syncCookiesFromWebView() {
		AndroidCookieJar.syncFromWebView(cookieJar, targetUrl, webViewRef?.url)
	}

	companion object {
		private const val COOKIE_CHECK_INTERVAL = 500L
		private const val CF_CLEARANCE = "cf_clearance"
	}
}
