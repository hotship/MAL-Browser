package com.shixu.minibrowser.web

import android.net.Uri

object UrlTools {
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null

        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return try {
            val uri = Uri.parse(candidate)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()) {
                uri.toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isHttpOrHttps(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }
}
