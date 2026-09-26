package com.shixu.minibrowser.data

data class SavedWebsite(
    val id: Long = 0,
    val title: String,
    val url: String,
    val iconPath: String?,
    val category: String,
    val createdAt: Long,
    val lastOpenedAt: Long = 0
)
