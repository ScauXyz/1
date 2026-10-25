package dev.restricted.backup

import java.net.URI

/** Top-level navigation guard only; the official website retains account authority. */
object QuarkPolicy {
    const val LOGIN = "https://pan.quark.cn/"
    private val folder = Regex("/list/all/[A-Za-z0-9_-]{1,128}")
    fun official(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "pan.quark.cn" &&
            uri.rawUserInfo == null && uri.port == -1
    } catch (_: Exception) { false }
    fun binding(url: String): Boolean = try {
        val uri = URI(url)
        official(url) && uri.rawPath in listOf("/", "/list") && uri.rawQuery == null &&
            folder.matches(uri.rawFragment ?: "") && uri.rawFragment != "/list/all/0"
    } catch (_: Exception) { false }
    fun allows(url: String, bound: String?, owner: Boolean): Boolean =
        if (owner) official(url) else bound != null && binding(bound) && url == bound

    // Fragments are not sent in HTTP requests. Only the bound document may omit its fragment here.
    // Navigation/history callbacks still require the complete folder URL.
    fun allowsDocument(url: String, bound: String?, owner: Boolean): Boolean {
        if (owner) return official(url)
        if (bound == null || !binding(bound) || !official(url)) return false
        return url == bound || (URI(url).rawFragment == null && url == bound.substringBefore('#'))
    }

    fun accepts(mime: String, requested: List<String>): Boolean {
        val types = requested.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        return types.isEmpty() || types.any {
            it == "*/*" || it.equals(mime, true) ||
                (it.endsWith("/*") && mime.startsWith(it.dropLast(1), true))
        }
    }
}
