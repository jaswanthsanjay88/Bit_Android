package com.bit.ui.screen.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

import me.rerere.workspace.ProotLaunchMode
import me.rerere.workspace.ProotLaunchModes
import me.rerere.workspace.ProotLaunchPreferences
import me.rerere.workspace.ProotRuntime
import me.rerere.workspace.ProotRuntimes
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.hasUsableRootfs
import java.io.File

internal fun createWorkspaceTerminalSession(
    context: Context,
    root: String,
    launch: WorkspaceTerminalProotLaunch,
    client: TerminalSessionClient,
): TerminalSession {
    val appContext = context.applicationContext
    val workspaceDir = File(File(appContext.filesDir, "workspaces"), root)
    val filesDir = File(workspaceDir, "files").apply { mkdirs() }
    val linuxDir = File(workspaceDir, "linux").apply { mkdirs() }
    val tempDir = File(workspaceDir, "tmp").apply {
        mkdirs()
        setReadable(true, true)
        setWritable(true, true)
        setExecutable(true, true)
    }
    val skillsDir = File(appContext.filesDir, "skills").apply { mkdirs() }

    val args = mutableListOf<String>()
    if (launch.mode.useAshmemMemfd && launch.runtime.supportsAshmemMemfd) {
        args += "--ashmem-memfd"
    }
    args += "--root-id"
    args += "--link2symlink"
    args += "--kill-on-exit"

    args += listOf(
        "-r",
        linuxDir.absolutePath,
        "-w",
        WORKSPACE_DIR,
        "-b",
        "${filesDir.absolutePath}:$WORKSPACE_DIR",
        "-b",
        "${skillsDir.absolutePath}:$SKILLS_DIR",
    )
    listOf("/dev", "/proc", "/sys").forEach { path ->
        if (File(path).exists()) {
            args += "-b"
            args += path
        }
    }

    // Mock /proc compatibility files (required for Ubuntu 24.04 libgcrypt, apt, procps on Android)
    val fipsFile = File(tempDir, "fips_enabled").apply { if (!exists()) writeText("0\n") }
    val statFile = File(tempDir, "stat").apply { if (!exists()) writeText("cpu  0 0 0 0 0 0 0 0 0 0\n") }
    val versionFile = File(tempDir, "version").apply { if (!exists()) writeText("Linux version 6.1.0-android-bit (proot@bit) #1 SMP PREEMPT\n") }
    val loadavgFile = File(tempDir, "loadavg").apply { if (!exists()) writeText("0.00 0.00 0.00 1/100 1000\n") }

    args += listOf(
        "-b", "${fipsFile.absolutePath}:/proc/sys/crypto/fips_enabled",
        "-b", "${statFile.absolutePath}:/proc/stat",
        "-b", "${versionFile.absolutePath}:/proc/version",
        "-b", "${loadavgFile.absolutePath}:/proc/loadavg",
    )
    val shellCommand = linuxDir.rootfsShellCommand()
    args += shellCommand

    val env = buildList {
        add("PROOT_LOADER=${launch.runtime.loader.absolutePath}")
        launch.runtime.loader32?.let { add("PROOT_LOADER_32=${it.absolutePath}") }
        add("PROOT_TMP_DIR=${tempDir.absolutePath}")
        add("PROOT_TMPDIR=${tempDir.absolutePath}")
        add("TMPDIR=/tmp")
        add("LD_LIBRARY_PATH=${launch.runtime.executable.parentFile?.absolutePath.orEmpty()}")
        add("HOME=/root")
        add("PATH=$ROOTFS_PATH")
        add("TERM=xterm-256color")
        add("LANG=C.UTF-8")
        add("LC_ALL=C.UTF-8")
        add("USER=root")
        add("SHELL=${shellCommand.firstOrNull() ?: "/bin/sh"}")
        add("DEBIAN_FRONTEND=noninteractive")
        add("DEBCONF_NONINTERACTIVE_SEEN=true")
        add("DEBCONF_NOWARNINGS=yes")
        add("NEEDRESTART_MODE=a")
        add("NEEDRESTART_SUSPEND=1")
        launch.mode.environment.forEach { (name, value) ->
            add("$name=$value")
        }
    }.toTypedArray()

    return TerminalSession(
        launch.runtime.executable.absolutePath,
        filesDir.absolutePath,
        args.toTypedArray(),
        env,
        2_000,
        client,
    ).apply {
        mSessionName = root
    }
}

internal data class WorkspaceTerminalProotLaunch(
    val runtime: ProotRuntime,
    val mode: ProotLaunchMode,
)

internal fun prepareWorkspaceTerminalSession(context: Context, root: String): WorkspaceTerminalProotLaunch? {
    val appContext = context.applicationContext
    val workspaceDir = File(File(appContext.filesDir, "workspaces"), root)
    val linuxDir = File(workspaceDir, "linux")
    val tempDir = File(workspaceDir, "tmp")
    File(workspaceDir, "files").mkdirs()
    tempDir.apply {
        mkdirs()
        setReadable(true, true)
        setWritable(true, true)
        setExecutable(true, true)
    }
    File(appContext.filesDir, "skills").mkdirs()
    RootfsPatcher().patch(
        linuxDir,
        RootfsPatchOptions(nameservers = appContext.activeDnsServers())
    )
    return resolveWorkspaceTerminalLaunch(
        nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir),
        tempDir = tempDir,
    )
}

internal fun workspaceRootfsReady(context: Context, root: String): Boolean {
    val linuxDir = File(File(File(context.applicationContext.filesDir, "workspaces"), root), "linux")
    return linuxDir.hasUsableRootfs()
}

internal class WorkspaceTerminalSessionClient(
    private val context: Context,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal error", e)
    }
}

internal class WorkspaceTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        if (openUrlAtTap(e)) return
        focusAndShowKeyboard()
    }

    private fun openUrlAtTap(e: MotionEvent): Boolean {
        val view = terminalView ?: return false
        if (view.isSelectingText) return false
        val emulator = view.mEmulator ?: return false
        val screen = emulator.getScreen()
        val columns = emulator.mColumns
        val columnAndRow = view.getColumnAndRow(e, true)
        val column = columnAndRow[0]
        val row = columnAndRow[1]
        val rows = emulator.mRows
        val minAccessibleRow = -screen.activeTranscriptRows
        val maxAccessibleRow = rows - 1
        if (column < 0 || column >= columns) return false
        if (row < minAccessibleRow || row > maxAccessibleRow) return false

        val minRow = (row - URL_MAX_WRAP_ROWS).coerceAtLeast(minAccessibleRow)
        val maxRow = (row + URL_MAX_WRAP_ROWS).coerceAtMost(maxAccessibleRow)
        var startRow = row
        while (startRow > minRow && screen.getLineWrap(startRow - 1)) startRow--
        var endRow = row
        while (endRow < maxRow && screen.getLineWrap(endRow)) endRow++

        val line = StringBuilder()
        var tapIndex = -1
        for (r in startRow..endRow) {
            if (r == row) {
                tapIndex = line.length + (screen.getSelectedText(0, r, column, r).length - 1).coerceAtLeast(0)
            }
            line.append(screen.getSelectedText(0, r, columns - 1, r))
        }
        if (tapIndex < 0) return false

        val match = URL_REGEX.findAll(line).firstOrNull { tapIndex in it.range } ?: return false
        val url = match.value.trimEnd(*URL_TRAILING_TRIM)
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w("WorkspaceTerminal", "Failed to open url: $url", it)
            false
        }
    }

    @Suppress("DEPRECATION")
    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        view.post {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = controlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal view error", e)
    }
}

private const val WORKSPACE_DIR = "/workspace"
private const val SKILLS_DIR = "/skills"
private const val ROOTFS_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
private const val URL_MAX_WRAP_ROWS = 50

private val URL_REGEX =
    Regex("""(https?|ftp)://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""", RegexOption.IGNORE_CASE)

private val URL_TRAILING_TRIM = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

private val ROOTFS_SHELLS = listOf(
    "/bin/bash",
    "/usr/bin/bash",
    "/bin/sh",
    "/usr/bin/sh",
    "/bin/ash",
    "/usr/bin/ash",
    "/bin/busybox",
    "/usr/bin/busybox",
)

private fun File.rootfsShellCommand(): List<String> {
    val shell = ROOTFS_SHELLS.firstOrNull { 
        val f = File(this, it.removePrefix("/"))
        f.exists() || java.nio.file.Files.isSymbolicLink(f.toPath())
    } ?: "/bin/sh"
    return if (shell.endsWith("bash")) {
        listOf(shell, "-l")
    } else {
        listOf(shell)
    }
}

private fun resolveWorkspaceTerminalLaunch(
    nativeLibraryDir: File,
    tempDir: File,
): WorkspaceTerminalProotLaunch? {
    val runtimes = ProotRuntimes.resolve(nativeLibraryDir)
    val preferred = ProotLaunchPreferences.read(tempDir)
    val preferredRuntime = runtimes.firstOrNull { it.name == preferred?.runtimeName }
    val preferredMode = ProotLaunchModes.all.firstOrNull { it.name == preferred?.launchModeName }
    if (preferredRuntime != null && preferredMode != null) {
        return WorkspaceTerminalProotLaunch(preferredRuntime, preferredMode)
    }
    return runtimes.firstOrNull()?.let { runtime ->
        WorkspaceTerminalProotLaunch(runtime, ProotLaunchModes.noSeccomp)
    }
}

private fun Context.activeDnsServers(): List<String> {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
    val network = connectivityManager.activeNetwork ?: return emptyList()
    return connectivityManager.getLinkProperties(network)
        ?.dnsServers
        ?.mapNotNull { it.hostAddress }
        .orEmpty()
}
