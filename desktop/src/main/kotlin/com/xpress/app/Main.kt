package com.xpress.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.xpress.app.engine.CompressResult
import com.xpress.app.engine.MediaRef
import com.xpress.app.engine.Stage
import com.xpress.app.job.JobManager
import com.xpress.app.job.JobState
import com.xpress.app.ui.AppHost
import com.xpress.app.ui.MainViewModel
import com.xpress.app.ui.XPressApp
import com.xpress.app.ui.theme.XPressTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.awt.Taskbar
import java.awt.datatransfer.DataFlavor
import java.io.File
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    // Closing the app mid-encode must not leave an orphaned ffmpeg.exe burning the CPU.
    Runtime.getRuntime().addShutdownHook(Thread { ProcessHandle.current().descendants().forEach { it.destroyForcibly() } })

    val platform = DesktopPlatform(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    val vm = MainViewModel(platform)
    // "Open with" and drag-onto-the-shortcut both arrive as the first argument.
    args.firstOrNull()?.let(::File)?.takeIf { it.isFile }?.let { vm.open(MediaRef(it.absolutePath)) }

    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("xpress.png")!!.use {
        BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }

    application {
        val job by JobManager.state.collectAsState()
        var confirmExit by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(size = DpSize(600.dp, 920.dp), position = WindowPosition(Alignment.Center))

        Window(
            onCloseRequest = { if (job is JobState.Running) confirmExit = true else exitApplication() },
            state = windowState,
            title = (job as? JobState.Running)?.let { "${(it.progress.fraction * 100).roundToInt()}% · XPress" } ?: "XPress",
            icon = icon,
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(440, 640) }
            TaskbarProgress(window, job)

            val host = remember {
                AppHost(
                    onPickVideo = {
                        WindowsShell.chooseVideo(window, platform.lastFolder)?.let { file ->
                            platform.lastFolder = file.parentFile
                            vm.open(MediaRef(file.absolutePath))
                        }
                    },
                    onPickFile = null,
                    homeHint = "Или перетащите видео в это окно",
                    homeHintIcon = Icons.Outlined.FileDownload,
                    onCompress = vm::compress,
                    resultActions = { result -> ResultActions(result) },
                )
            }

            XPressTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    DropArea(enabled = job !is JobState.Running, onFile = { vm.open(MediaRef(it.absolutePath)) }) {
                        // The screens are laid out for a phone; on a wide window keep them a readable column.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 720.dp).fillMaxHeight()) {
                                XPressApp(vm, host)
                            }
                        }
                    }
                    if (confirmExit) {
                        AlertDialog(
                            onDismissRequest = { confirmExit = false },
                            title = { Text("Сжатие ещё идёт") },
                            text = { Text("Закрыть XPress и прервать сжатие?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    platform.cancelJob()
                                    exitApplication()
                                }) { Text("Прервать и закрыть") }
                            },
                            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Продолжить") } },
                        )
                    }
                }
            }
        }
    }
}

/** Copy for pasting straight into a chat, or find the file on disk. */
@Composable
private fun ResultActions(result: CompressResult) {
    val file = File(result.output.value)
    var copied by remember(result) { mutableStateOf(false) }
    Button(
        onClick = {
            WindowsShell.copyToClipboard(file)
            copied = true
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null)
        Spacer(Modifier.width(10.dp))
        Text(if (copied) "Скопировано" else "Скопировать файл", style = MaterialTheme.typography.titleMedium)
    }
    if (copied) {
        Text(
            "Вставьте в Discord или Telegram через Ctrl+V",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedButton(onClick = { WindowsShell.reveal(file) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Outlined.FolderOpen, null)
        Spacer(Modifier.width(10.dp))
        Text("Показать в папке")
    }
}

/** Accepts a video file dropped anywhere on the window. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DropArea(enabled: Boolean, onFile: (File) -> Unit, content: @Composable () -> Unit) {
    var hovering by remember { mutableStateOf(false) }
    val canDrop by rememberUpdatedState(enabled)
    val open by rememberUpdatedState(onFile)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                hovering = true
            }

            override fun onExited(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val files = event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                val file = files?.filterIsInstance<File>()?.firstOrNull { it.isFile } ?: return false
                open(file)
                return true
            }
        }
    }
    Box(
        Modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = { canDrop && it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
            target = target,
        ),
    ) {
        content()
        if (hovering) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f), RoundedCornerShape(24.dp))
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Отпустите, чтобы открыть видео",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Mirrors the job in the taskbar button: a progress bar while encoding, a flash when it is done. */
@Composable
private fun TaskbarProgress(window: java.awt.Window, job: JobState) {
    val taskbar = remember {
        runCatching { Taskbar.getTaskbar() }.getOrNull()?.takeIf { it.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW) }
    } ?: return
    LaunchedEffect(job) {
        when (job) {
            is JobState.Running -> {
                val p = job.progress
                if (p.stage == Stage.PREPARING || p.stage == Stage.SAVING) {
                    taskbar.setWindowProgressState(window, Taskbar.State.INDETERMINATE)
                } else {
                    taskbar.setWindowProgressState(window, Taskbar.State.NORMAL)
                    taskbar.setWindowProgressValue(window, (p.fraction * 100).roundToInt())
                }
            }
            is JobState.Done, is JobState.Failed -> {
                taskbar.setWindowProgressState(window, Taskbar.State.OFF)
                if (!window.isFocused && taskbar.isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW)) {
                    taskbar.requestWindowUserAttention(window)
                }
            }
            JobState.Idle -> taskbar.setWindowProgressState(window, Taskbar.State.OFF)
        }
    }
}
