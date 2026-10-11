package com.xpress.app.ui

import com.xpress.app.engine.EncodePlan
import com.xpress.app.engine.EncodePlanner
import com.xpress.app.engine.Stage
import com.xpress.app.engine.VideoCodec
import java.util.Locale
import kotlin.math.roundToInt

private val RU = Locale.forLanguageTag("ru")

/** Decimal units, the same ones the target size is entered in. */
fun formatBytes(bytes: Long): String {
    val mb = bytes.toDouble() / EncodePlanner.BYTES_PER_MB
    return when {
        mb >= 100 -> String.format(RU, "%.0f МБ", mb)
        mb >= 1 -> String.format(RU, "%.2f МБ", mb)
        else -> String.format(RU, "%.0f КБ", bytes / 1000.0)
    }
}

/** m:ss.d — tenths matter when trimming. */
fun formatTimePrecise(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0) + 50) / 100
    val s = tenths / 10
    return String.format(RU, "%d:%02d,%d", s / 60, s % 60, tenths % 10)
}

fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 500) / 1000
    return if (s >= 3600) String.format(RU, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(RU, "%d:%02d", s / 60, s % 60)
}

fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "$seconds с"
    seconds < 3600 -> "${seconds / 60} мин ${seconds % 60} с"
    else -> "${seconds / 3600} ч ${seconds / 60 % 60} мин"
}

fun formatFps(fps: Double): String =
    if (kotlin.math.abs(fps - fps.roundToInt()) < 0.05) fps.roundToInt().toString()
    else String.format(RU, "%.2f", fps)

fun codecLabel(codec: VideoCodec) = when (codec) {
    VideoCodec.H264 -> "H.264"
    VideoCodec.HEVC -> "H.265"
    VideoCodec.AV1 -> "AV1"
}

fun stageLabel(stage: Stage, attempt: Int = 1): String = when (stage) {
    Stage.PREPARING -> "Подготовка…"
    Stage.ANALYSIS -> "Анализ видео (проход 1 из 2)"
    Stage.ENCODING -> "Кодирование"
    Stage.REFINING -> "Подгонка под размер (попытка $attempt)"
    Stage.SAVING -> "Сохранение в галерею…"
}

fun planSummary(plan: EncodePlan, videoKbps: Int? = plan.videoKbps): String = buildString {
    append("${plan.width}×${plan.height} · ${formatFps(plan.fps)} к/с · ${codecLabel(plan.codec)}")
    if (videoKbps != null) append(" ${String.format(RU, "%,d", videoKbps)} кбит/с")
    else append(" CRF ${plan.crf}")
    if (plan.hasAudio) append(" · AAC ${plan.audioKbps}") else append(" · без звука")
    if (plan.tonemap) append(" · HDR→SDR")
}

fun codecName(ffmpegName: String): String = when (ffmpegName.lowercase(Locale.ROOT)) {
    "h264", "video/avc" -> "H.264"
    "hevc", "video/hevc" -> "HEVC"
    "av1", "video/av01" -> "AV1"
    "vp9", "video/x-vnd.on2.vp9" -> "VP9"
    "vp8" -> "VP8"
    "mpeg4" -> "MPEG-4"
    "prores" -> "ProRes"
    else -> ffmpegName.uppercase(Locale.ROOT)
}
