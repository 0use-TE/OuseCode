package io.github.ouse.ousecode

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.RemotePty
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs only in the Shizuku UserService, under its actual shell/root identity. */
class AndroidShellService(context: Context) : IAndroidShell.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private val terminals = ConcurrentHashMap<Int, ParcelFileDescriptor>()
    private val commands = ConcurrentHashMap.newKeySet<Process>()
    private fun pidOf(process: Process) = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process)
    private fun checkCaller() { check(Binder.getCallingUid() == ownerUid) { "CALLER_DENIED" } }
    private fun killGroup(pid: Int) {
        try { Os.kill(-pid, OsConstants.SIGKILL) }
        catch (error: android.system.ErrnoException) { if (error.errno != OsConstants.ESRCH) throw error }
    }
    override fun destroy() {
        terminals.keys.forEach(::killGroup)
        commands.forEach { killGroup(pidOf(it)); it.destroyForcibly() }
        kotlin.system.exitProcess(0)
    }
    override fun execute(command: String, timeoutSeconds: Int): Bundle {
        checkCaller()
        require(command.isNotBlank() && command.length <= 32768 && !command.contains('\u0000')) { "INVALID_COMMAND" }
        require(timeoutSeconds in 1..120) { "INVALID_TIMEOUT" }
        val process = ProcessBuilder("/system/bin/toybox", "setsid", "/system/bin/sh", "-c", command).directory(java.io.File("/data/local/tmp")).apply {
            environment().apply { clear(); put("PATH", "/system/bin:/system/xbin:/product/bin:/vendor/bin"); put("HOME", "/data/local/tmp"); put("TMPDIR", "/data/local/tmp"); put("LANG", "C.UTF-8") }
        }.start()
        commands += process
        process.outputStream.close()
        val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream()
        var truncated = false
        fun drain(input: InputStream, output: ByteArrayOutputStream) = thread(isDaemon = true) {
            try { input.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer); if (count < 0) break
                    synchronized(output) { val keep = minOf(count, 196608 - output.size()); if (keep > 0) output.write(buffer, 0, keep); if (keep < count) truncated = true }
                }
            } } catch (_: java.io.IOException) { /* The owner closes the pipe on timeout or completion. */ }
        }
        val outReader = drain(process.inputStream, stdout); val errReader = drain(process.errorStream, stderr)
        try {
            val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                killGroup(pidOf(process)); process.destroyForcibly()
                process.waitFor()
            }
            outReader.join(1000); errReader.join(1000)
            return Bundle().apply {
                putInt("exitCode", if (finished) process.exitValue() else 124)
                putString("stdout", synchronized(stdout) { stdout.toString("UTF-8") })
                putString("stderr", synchronized(stderr) { stderr.toString("UTF-8") })
                putBoolean("timedOut", !finished); putBoolean("truncated", truncated)
            }
        } finally { commands -= process; process.inputStream.close(); process.errorStream.close() }
    }
    override fun openTerminal(rows: Int, columns: Int, cellWidth: Int, cellHeight: Int): Bundle {
        checkCaller()
        require(rows in 2..1000 && columns in 2..2000)
        val pid = IntArray(1)
        val fd = RemotePty.open("/system/bin/sh", "/data/local/tmp", arrayOf("/system/bin/sh", "-i"), arrayOf(
            "PATH=/system/bin:/system/xbin:/product/bin:/vendor/bin", "HOME=/data/local/tmp", "TMPDIR=/data/local/tmp", "TERM=xterm-256color", "LANG=C.UTF-8", "PS1=android:\\w $ "
        ), pid, rows, columns, cellWidth, cellHeight)
        val descriptor = ParcelFileDescriptor.adoptFd(fd)
        terminals[pid[0]] = descriptor
        return Bundle().apply { putInt("pid", pid[0]); putParcelable("fd", ParcelFileDescriptor.dup(descriptor.fileDescriptor)) }
    }
    override fun resizeTerminal(pid: Int, rows: Int, columns: Int, cellWidth: Int, cellHeight: Int) {
        checkCaller(); require(rows in 2..1000 && columns in 2..2000)
        terminals[pid]?.let { RemotePty.resize(it.fd, rows, columns, cellWidth, cellHeight) }
    }
    override fun waitForTerminal(pid: Int): Int {
        checkCaller(); check(terminals.containsKey(pid)) { "TERMINAL_CLOSED" }
        try { return RemotePty.waitFor(pid) } finally { terminals.remove(pid)?.close() }
    }
    override fun closeTerminal(pid: Int) { checkCaller(); if (terminals.containsKey(pid)) killGroup(pid) }
}
