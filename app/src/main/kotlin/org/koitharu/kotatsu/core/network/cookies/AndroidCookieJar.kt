package org.koitharu.kotatsu.core.network.cookies

import android.webkit.CookieManager
import androidx.annotation.WorkerThread
import androidx.core.util.Predicate
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidCookieJar : MutableCookieJar {

	private val cookieManager = CookieManager.getInstance()

	@WorkerThread
	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val rawCookie = runCatching { cookieManager.getCookie(url.toString()) }.getOrNull() ?: return emptyList()
		return rawCookie.split(';').mapNotNull {
			parseWebViewCookie(url, it)
		}
	}

	@WorkerThread
	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (cookies.isEmpty()) {
			return
		}
		val urlString = url.toString()
		runCatching {
			for (cookie in cookies) {
				cookieManager.setCookie(urlString, cookie.toString())
			}
			safeFlush(cookieManager)
		}
	}

	override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
		val cookies = loadForRequest(url)
		if (cookies.isEmpty()) {
			return
		}
		val urlString = url.toString()
		runCatching {
			for (c in cookies) {
				if (predicate != null && !predicate.test(c)) {
					continue
				}
				val nc = c.newBuilder()
					.expiresAt(System.currentTimeMillis() - 100000)
					.build()
				cookieManager.setCookie(urlString, nc.toString())
			}
			safeFlush(cookieManager)
		}
	}

	override suspend fun clear() = suspendCoroutine<Boolean> { continuation ->
		runCatching {
			cookieManager.removeAllCookies(continuation::resume)
		}.onFailure {
			continuation.resume(false)
		}
	}

	companion object {
		private const val TAG = "CaptchaCookies"
		private const val CF_CLEARANCE = "cf_clearance"

		fun safeFlush(cookieManager: CookieManager) {
			try {
				cookieManager.flush()
			} catch (e: Throwable) {
				runCatching {
					java.util.concurrent.Executors.newSingleThreadExecutor().execute {
						runCatching { cookieManager.flush() }
					}
				}
			}
		}

		/**
		 * Convert one entry of [CookieManager.getCookie]'s `name=value; name=value` output into an
		 * OkHttp [Cookie].
		 *
		 * The WebView deliberately hides every cookie attribute, so the missing ones have to be
		 * synthesised — and they have to be synthesised to the *same* values the server originally
		 * sent, otherwise the same cookie ends up stored twice under two different identities
		 * (a cookie is keyed by name + domain + path). Two `cf_clearance` entries then go out in one
		 * `Cookie` header, Cloudflare reads the stale one and challenges again, forever.
		 *
		 * - `path`: `/`, not OkHttp's default-path. Without it a cookie harvested while solving a
		 *   challenge at `/manga/x/1` is scoped to `/manga/x`, so it is not even sent to `/`.
		 * - `secure`: mirrors the URL scheme, so an https cookie keeps the flag the server set.
		 */
		fun parseWebViewCookie(url: HttpUrl, rawCookie: String): Cookie? {
			val trimmed = rawCookie.trim()
			if (trimmed.isEmpty()) return null
			// Only the part after the first ';' can hold attributes; a value that happens to contain
			// "path=" must not be mistaken for one.
			val attrs = trimmed.substringAfter(';', "")
			val topDomain = runCatching { url.topPrivateDomain() }.getOrNull()
				?: extractRootDomain(url.host)
			val builder = StringBuilder(trimmed)
			if (!attrs.contains("domain=", ignoreCase = true)) {
				builder.append("; domain=.").append(topDomain)
			}
			if (!attrs.contains("path=", ignoreCase = true)) {
				builder.append("; path=/")
			}
			if (url.isHttps && !attrs.contains("secure", ignoreCase = true)) {
				builder.append("; secure")
			}
			return Cookie.parse(url, builder.toString())
				?: Cookie.parse(url, trimmed)
		}

		private fun extractRootDomain(host: String): String {
			val parts = host.split('.')
			if (parts.size >= 2 && !host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
				return parts.takeLast(2).joinToString(".")
			}
			return host
		}

		/**
		 * Direct [Cookie.Builder] fallback for one `name=value` piece of
		 * [CookieManager.getCookie] output. Unlike [parseWebViewCookie] it never depends on
		 * [Cookie.parse] accepting the synthesised attribute string, so a clearance value
		 * with unexpected characters still produces a sendable cookie instead of being
		 * silently dropped (which surfaced as "solved but cf_clearance never saved").
		 */
		fun buildWebViewCookie(url: HttpUrl, rawPart: String): Cookie? {
			val trimmed = rawPart.trim()
			if (trimmed.isEmpty()) return null
			val eq = trimmed.indexOf('=')
			if (eq <= 0) return null
			val name = trimmed.substring(0, eq).trim()
			var value = trimmed.substring(eq + 1).trim()
			if (name.isEmpty()) return null
			if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
				value = value.substring(1, value.length - 1)
			}
			if (name == CF_CLEARANCE && value.isBlank()) {
				// An emptied clearance is purge residue, never a solve. Saving it would
				// overwrite the fresh value the jar may already hold.
				return null
			}
			if (value.isEmpty()) return null
			val topDomain = runCatching { url.topPrivateDomain() }.getOrNull()
				?: extractRootDomain(url.host)
			return try {
				Cookie.Builder()
					.name(name)
					.value(value)
					.domain(topDomain)
					.path("/")
					.apply { if (url.isHttps) secure() }
					.build()
			} catch (_: IllegalArgumentException) {
				try {
					Cookie.Builder()
						.name(name)
						.value(value)
						.hostOnlyDomain(url.host)
						.path("/")
						.apply { if (url.isHttps) secure() }
						.build()
				} catch (_: IllegalArgumentException) {
					null
				}
			}
		}

		/**
		 * Pull every cookie the WebView currently holds for [urlStrings] into [cookieJar].
		 *
		 * Queries each URL (challenge URL, the WebView's live URL, domain root) because
		 * [CookieManager.getCookie] filters by host: clearance set on `www.site.com`
		 * is invisible when asked only for `site.com` and vice versa. Each piece is
		 * parsed via [parseWebViewCookie] with [buildWebViewCookie] as fallback so a
		 * value [Cookie.parse] rejects is still saved.
		 *
		 * @return the `cf_clearance` value seen in the WebView, if any (never logged).
		 */
		fun syncFromWebView(
			cookieJar: MutableCookieJar,
			vararg urlStrings: String?,
		): String? {
			val manager = try {
				CookieManager.getInstance()
			} catch (e: Exception) {
				android.util.Log.w(TAG, "CookieManager unavailable", e)
				return null
			}
			val httpUrls = urlStrings.filterNotNull()
				.mapNotNull { it.toHttpUrlOrNull() }
				.distinct()
			if (httpUrls.isEmpty()) return null
			var clearance: String? = null
			var saved = 0
			for (httpUrl in httpUrls) {
				val raw = runCatching { manager.getCookie(httpUrl.toString()) }.getOrNull()
					?: continue
				val parsed = ArrayList<Cookie>()
				for (part in raw.split(";")) {
					val cookie = parseWebViewCookie(httpUrl, part)
						?: buildWebViewCookie(httpUrl, part)
						?: continue
					if (cookie.name == CF_CLEARANCE) {
						// Never persist an emptied clearance: it is purge residue and
						// would overwrite the fresh value the jar may already hold.
						if (cookie.value.isBlank()) continue
						clearance = cookie.value
					}
					parsed.add(cookie)
				}
				if (parsed.isNotEmpty()) {
					runCatching { cookieJar.saveFromResponse(httpUrl, parsed) }
					saved += parsed.size
				}
			}
			if (saved > 0) {
				safeFlush(manager)
			}
			if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
				android.util.Log.d(
					TAG,
					"syncFromWebView: urls=${httpUrls.size} cookiesSaved=$saved " +
						"clearancePresent=${clearance != null} len=${clearance?.length ?: 0}",
				)
			}
			return clearance
		}
	}
}
