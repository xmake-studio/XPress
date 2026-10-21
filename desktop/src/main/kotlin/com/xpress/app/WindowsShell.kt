package com.xpress.app

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.WinBase
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.util.concurrent.Executors

/** The handful of Windows shell services the app needs. Each degrades quietly elsewhere. */
object WindowsShell {

    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** The user's Videos folder, wherever Windows has moved it (OneDrive, another drive). */
    val videosFolder: File by lazy {
        val known = if (isWindows) runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Videos)) }.getOrNull() else null
        known?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"), "Videos")
    }

    // Execution state belongs to the thread that set it, so a single thread owns it.
    private val power = Executors.newSingleThreadExecutor { Thread(it, "keep-awake").apply { isDaemon = true } }

    /** Keeps the PC from going to sleep in the middle of an encode. */
    fun keepAwake(on: Boolean) {
        if (!isWindows) return
        power.execute {
            val flags = if (on) WinBase.ES_CONTINUOUS or WinBase.ES_SYSTEM_REQUIRED else WinBase.ES_CONTINUOUS
            runCatching { Kernel32.INSTANCE.SetThreadExecutionState(flags) }
        }
    }

    /** Opens Explorer with [file] selected. */
    fun reveal(file: File) {
        runCatching {
            if (isWindows) ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            else java.awt.Desktop.getDesktop().open(file.parentFile)
        }
    }

    /** Puts [file] on the clipboard the way Explorer's Copy does, so Ctrl+V in Discord or Telegram attaches it. */
    fun copyToClipboard(file: File) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(FileSelection(listOf(file)), null)
    }

    /** The native Open dialog, filtered to video files. */
    fun chooseVideo(parent: Frame?, startDir: File?): File? {
        val dialog = FileDialog(parent, "Выберите видео", FileDialog.LOAD)
        startDir?.takeIf { it.isDirectory }?.let { dialog.directory = it.absolutePath }
        // On Windows this pattern doubles as the dialog's file type filter.
        dialog.file = VIDEO_EXTENSIONS.joinToString(";") { "*.$it" }
        dialog.isVisible = true
        return dialog.files.firstOrNull()
    }

    val VIDEO_EXTENSIONS = listOf("mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp", "ts", "mts", "m2ts", "wmv", "flv", "mpg", "mpeg")
}

private class FileSelection(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return files
    }
}
