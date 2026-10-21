package com.xpress.app.engine

/**
 * Where a video lives, spelled the platform's way: a content:// or file:// Uri on Android,
 * an absolute path on the desktop. Shared code only passes it around and compares it.
 */
@JvmInline
value class MediaRef(val value: String)
