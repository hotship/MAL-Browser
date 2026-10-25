package com.shixu.minibrowser

/**
 * Dedicated activity used by pinned website shortcuts.
 * It reuses MainActivity's WebView behavior but runs in a separate task affinity,
 * so browser-mode history never leaks into a fullscreen website session.
 */
class ShortcutActivity : MainActivity()
