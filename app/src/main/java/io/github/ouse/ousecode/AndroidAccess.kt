package io.github.ouse.ousecode

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

enum class AndroidMode(val effect: String) { OFF("deny"), ASK("allow"), SMART("allow"), DIRECT("allow") }

class AndroidApproval internal constructor(val command: String, val purpose: String) {
    val id: String = java.util.UUID.randomUUID().toString()
    private val deadline = SystemClock.elapsedRealtime() + 30000L
    val remainingMillis get() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    internal val result = CompletableFuture<Boolean>()
}

class AndroidAccess private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("android-access", 0)
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private val terminals = CopyOnWriteArraySet<TerminalSession>()
    private val args = Shizuku.UserServiceArgs(ComponentName(context, AndroidShellService::class.java))
        .tag("android-shell").processNameSuffix("android_shell").daemon(false).version(1)
    private var connection: CompletableFuture<IAndroidShell>? = null
    private var bridge: AndroidMcpServer? = null
    // Requests live only until a reply, mode/runtime change or 30-second timeout; nothing is persisted.
    private val approvals = mutableListOf<AndroidApproval>()
    val approvalUi by lazy { AndroidApprovalUi(context, this) }
    val pendingApproval get() = synchronized(this) { approvals.firstOrNull() }
    val mode get() = AndroidMode.valueOf(prefs.getString("mode", AndroidMode.OFF.name)!!)
    val installed get() = try { context.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    val running get() = Shizuku.pingBinder()
    val authorized get() = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    val serviceUid get() = if (authorized) Shizuku.getUid() else -1
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(this@AndroidAccess) { connection?.complete(IAndroidShell.Stub.asInterface(binder)) }
            changed()
        }
        override fun onServiceDisconnected(name: ComponentName) { disconnected("SHIZUKU_DISCONNECTED") }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky { changed() }
        Shizuku.addBinderDeadListener { disconnected("SHIZUKU_STOPPED") }
        Shizuku.addRequestPermissionResultListener { _, _ -> changed() }
    }
    private fun changed() { main.post { approvalUi.sync(); observers.forEach { it() } } }
    fun observe(observer: () -> Unit) { observers += observer }
    fun unobserve(observer: () -> Unit) { observers -= observer }
    fun requestPermission() {
        check(running) { "SHIZUKU_NOT_RUNNING" }
        check(Shizuku.getVersion() >= 12) { "SHIZUKU_UPDATE_REQUIRED" }
        Shizuku.requestPermission(4201)
    }
    @Synchronized private fun disconnected(reason: String) {
        connection?.completeExceptionally(IllegalStateException(reason)); connection = null
        cancelApprovals(reason)
        changed()
    }
    /** Called on a worker thread, never while the UI is waiting on a Binder callback. */
    fun shell(): IAndroidShell {
        check(mode != AndroidMode.OFF) { "ANDROID_ACCESS_DISABLED" }
        check(running) { "SHIZUKU_NOT_RUNNING" }
        check(authorized) { "SHIZUKU_PERMISSION_REQUIRED" }
        val future = synchronized(this) {
            connection ?: CompletableFuture<IAndroidShell>().also {
                connection = it
                try { Shizuku.bindUserService(args, serviceConnection) }
                catch (error: RuntimeException) { connection = null; it.completeExceptionally(error) }
            }
        }
        try { return future.get(10, TimeUnit.SECONDS) }
        catch (error: java.util.concurrent.TimeoutException) {
            synchronized(this) { if (connection === future) connection = null }
            throw IllegalStateException("SHIZUKU_CONNECTION_TIMEOUT", error)
        }
    }
    fun execute(command: String, timeout: Int, purpose: String = ""): JSONObject {
        require(command.isNotBlank() && command.length <= 32768 && !command.contains('\u0000')) { "INVALID_COMMAND" }
        require(timeout in 1..120) { "INVALID_TIMEOUT" }
        val selected = mode
        check(selected != AndroidMode.OFF) { "ANDROID_ACCESS_DISABLED" }
        check(running) { "SHIZUKU_NOT_RUNNING" }
        check(authorized) { "SHIZUKU_PERMISSION_REQUIRED" }
        if (selected == AndroidMode.ASK || (selected == AndroidMode.SMART && !AndroidCommandPolicy.isQuery(command))) awaitApproval(command, purpose, selected)
        check(mode == selected) { "ANDROID_MODE_CHANGED" }
        val remote = shell()
        check(mode == selected) { "ANDROID_MODE_CHANGED" }
        val result = remote.execute(command, timeout)
        return JSONObject().put("exitCode", result.getInt("exitCode")).put("stdout", result.getString("stdout"))
            .put("stderr", result.getString("stderr")).put("timedOut", result.getBoolean("timedOut")).put("truncated", result.getBoolean("truncated"))
    }
    fun authorizeTerminal() {
        val selected = mode
        check(selected != AndroidMode.OFF) { "ANDROID_ACCESS_DISABLED" }
        if (selected == AndroidMode.ASK || selected == AndroidMode.SMART) awaitApproval("/system/bin/sh", text("交互式 Android 终端：本次会话内的命令会直接执行，关闭终端后授权结束。", "Interactive Android terminal: commands in this session run directly; closing the terminal ends authorization."), selected)
        check(mode == selected) { "ANDROID_MODE_CHANGED" }
    }
    private fun text(zh: String, en: String) = if (context.resources.configuration.locales[0].language == "zh") zh else en
    private fun awaitApproval(command: String, purpose: String, expectedMode: AndroidMode = AndroidMode.SMART) {
        val request = AndroidApproval(command, purpose.take(500))
        synchronized(this) {
            check(mode == expectedMode) { "ANDROID_MODE_CHANGED" }
            approvals += request
        }
        changed()
        try {
            check(request.result.get(request.remainingMillis, TimeUnit.MILLISECONDS)) { "ANDROID_COMMAND_DENIED" }
        } catch (error: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("ANDROID_CONFIRMATION_TIMED_OUT", error)
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        } finally { synchronized(this) { approvals.remove(request) }; changed() }
    }
    fun reply(request: AndroidApproval, allow: Boolean) {
        synchronized(this) {
            if (approvals.remove(request)) {
                if (request.remainingMillis == 0L) request.result.completeExceptionally(IllegalStateException("ANDROID_CONFIRMATION_TIMED_OUT"))
                else request.result.complete(allow)
            }
        }
        changed()
    }
    @Synchronized private fun cancelApprovals(reason: String) {
        approvals.forEach { it.result.completeExceptionally(IllegalStateException(reason)) }
        approvals.clear()
    }
    fun createTerminal(remote: IAndroidShell, client: TerminalSessionClient): TerminalSession {
        check(mode != AndroidMode.OFF && authorized) { "ANDROID_ACCESS_DISABLED" }
        val external = object : TerminalSession.ExternalPty {
            private var pid = 0
            override fun open(processId: IntArray, rows: Int, columns: Int, cellWidth: Int, cellHeight: Int): ParcelFileDescriptor {
                check(mode != AndroidMode.OFF && authorized) { "ANDROID_ACCESS_DISABLED" }
                val opened = remote.openTerminal(rows, columns, cellWidth, cellHeight)
                pid = opened.getInt("pid"); processId[0] = pid
                @Suppress("DEPRECATION")
                return opened.getParcelable<ParcelFileDescriptor>("fd")!!
            }
            override fun resize(rows: Int, columns: Int, cellWidth: Int, cellHeight: Int) {
                try { remote.resizeTerminal(pid, rows, columns, cellWidth, cellHeight) }
                catch (_: android.os.DeadObjectException) { /* The waiter delivers session termination. */ }
            }
            override fun waitFor(): Int = try { remote.waitForTerminal(pid) } catch (_: android.os.RemoteException) { 255 }
            override fun close() { if (pid > 0) try { remote.closeTerminal(pid) } catch (_: android.os.DeadObjectException) { /* Already gone. */ } }
        }
        return TerminalSession(external, 4000, client).also { terminals += it }
    }
    fun forgetTerminal(session: TerminalSession) { terminals -= session }

    @Synchronized fun start(rootfs: File) {
        if (bridge == null) bridge = AndroidMcpServer(this)
        writeConfiguration(rootfs, mode)
    }
    @Synchronized fun setMode(value: AndroidMode, rootfs: File) {
        if (rootfs.isDirectory) {
            if (bridge == null) bridge = AndroidMcpServer(this)
            writeConfiguration(rootfs, value)
        }
        check(prefs.edit().putString("mode", value.name).commit()) { "SETTINGS_WRITE_FAILED" }
        cancelApprovals("ANDROID_MODE_CHANGED")
        if (value == AndroidMode.OFF) disconnect()
        changed()
    }
    private fun writeConfiguration(rootfs: File, value: AndroidMode) {
        val file = File(rootfs, "etc/opencode/opencode.json").apply { parentFile!!.mkdirs() }
        val config = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        val mcp = config.optJSONObject("mcp") ?: JSONObject().also { config.put("mcp", it) }
        val servers = mcp.optJSONObject("servers") ?: JSONObject().also { mcp.put("servers", it) }
        servers.put("android", JSONObject().put("type", "remote").put("url", bridge!!.url)
            .put("oauth", false).put("codemode", false).put("disabled", value == AndroidMode.OFF).put("timeout", JSONObject().put("execution", 240000))
            .put("headers", JSONObject().put("Authorization", "Bearer ${bridge!!.token}")))
        val rules = config.optJSONArray("permissions") ?: JSONArray()
        val retained = JSONArray()
        for (i in 0 until rules.length()) if (rules.getJSONObject(i).optString("action") != "android_exec") retained.put(rules.get(i))
        retained.put(JSONObject().put("action", "android_exec").put("resource", "*").put("effect", value.effect))
        config.put("permissions", retained)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(config.toString(2).toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    fun stop() { disconnect(); synchronized(this) { bridge?.close(); bridge = null } }
    private fun disconnect() {
        cancelApprovals("ANDROID_ACCESS_DISABLED")
        main.post { terminals.forEach { it.finishIfRunning() } }
        synchronized(this) {
            if (connection != null && running) Shizuku.unbindUserService(args, serviceConnection, true)
            connection?.completeExceptionally(IllegalStateException("ANDROID_ACCESS_DISABLED")); connection = null
        }
    }
    companion object {
        @Volatile private var instance: AndroidAccess? = null
        fun get(context: Context): AndroidAccess = instance ?: synchronized(this) { instance ?: AndroidAccess(context.applicationContext).also { instance = it } }
    }
}
