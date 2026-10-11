package com.xpress.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Puts a phone-like HDR clip into the gallery for manual UI checks on an emulator.
 * Skipped unless run with `-Pandroid.testInstrumentationRunnerArguments.makeSample=true`.
 */
@RunWith(AndroidJUnit4::class)
class GallerySampleTool {

    @Test
    fun makeGallerySample() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("makeSample") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = File(ctx.cacheDir, "sample_raw.mp4")
        val out = File(ctx.cacheDir, "sample.mp4")
        run(
            "-f", "lavfi", "-i", "testsrc2=s=1920x1080:r=60:d=25",
            "-f", "lavfi", "-i", "sine=f=330:d=25:sample_rate=48000",
            "-vf", "format=yuv420p,noise=alls=10:allf=t+u",
            "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
            "-color_primaries", "bt2020", "-color_trc", "arib-std-b67", "-colorspace", "bt2020nc",
            "-x265-params", "log-level=error", "-b:v", "40M", "-tag:v", "hvc1",
            "-c:a", "aac", "-b:a", "256k", "-shortest", raw.absolutePath,
        )
        run("-display_rotation", "90", "-i", raw.absolutePath, "-map", "0", "-c", "copy", out.absolutePath)

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_20261011_120000.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { o -> out.inputStream().use { it.copyTo(o) } }
        raw.delete()
        out.delete()
        println("SAMPLE_URI=$uri")
    }

    private fun run(vararg args: String) {
        val s = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-y", *args))
        assertTrue(s.allLogsAsString.takeLast(2000), ReturnCode.isSuccess(s.returnCode))
    }
}
