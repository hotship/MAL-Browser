package com.shixu.minibrowser.data

data class WebsiteShortcut(
    val id: Long,
    val shortcutId: String,
    val title: String,
    val url: String,
    val iconPath: String?,
    val displayMode: String,
    val createdAt: Long,
    val pinConfirmed: Boolean
)
