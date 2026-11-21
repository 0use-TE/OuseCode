package io.github.ouse.ousecode

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import org.json.JSONObject
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class Phase { STOPPED, PREPARING, STARTING, READY, UPDATING, ERROR }
data class RuntimeStatus(val phase: Phase, val message: String, val progress: Int = -1, val version: String = "")

class RuntimeManager private constructor(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(RuntimeStatus) -> Unit>()
    private val logLock = Any()
    private val lines = ArrayDeque<String>()
    private val prefs = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
    val rootfs = File(context.filesDir, "runtime/rootfs")
    val home = File(context.filesDir, "home")
    val engine = File(context.filesDir, "engine")
    private val nativeDir get() = context.applicationInfo.nativeLibraryDir
    private val manifest by lazy { JSONObject(context.assets.open("runtime/manifest.json").bufferedReader().use { it.readText() }) }
    @Volatile var status = RuntimeStatus(Phase.STOPPED, text("尚未启动", "Not running"))
        private set
    @Volatile var baseUrl = ""
        private set
    @Volatile private var server: Process? = null
    @Volatile private var expectedExit = false
    private val commands = java.util.concurrent.ConcurrentHashMap<Process, File>()
    private val terminals = java.util.concurrent.ConcurrentHashMap<TerminalSession, File>()
    private val password: String by lazy {
        prefs.getString("serverPassword", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }
            .also { prefs.edit().putString("serverPassword", it).commit() }
    }
    val busy get() = status.phase in setOf(Phase.PREPARING, Phase.STARTING, Phase.UPDATING)

    fun text(zh: String, en: String): String = if (context.resources.configuration.locales[0].language == "zh") zh else en
    fun terminalDisplayName(): String = prefs.getString("terminalDisplayName", "ouse@OpenCode")!!

    @Synchronized fun setTerminalDisplayName(name: String) {
        // PS1 performs shell expansions. Accept display text, never shell syntax or prompt escapes.
        require(name.matches(Regex("[\\p{L}\\p{N}_.@ -]{1,40}")) && name.isNotBlank()) {
            text("请输入 1–40 个字母、汉字、数字、空格或 _ . @ -", "Use 1–40 letters, numbers, spaces, or _ . @ -")
        }
        if (home.isDirectory) writeTerminalPrompt(name)
        prefs.edit().putString("terminalDisplayName", name).apply()
    }

    @Synchronized private fun writeTerminalPrompt(name: String = terminalDisplayName()) {
        File(home, ".ousecode").mkdirs()
        File(home, ".ousecode/prompt.bash").writeText("PS1='\\[\\e[1;32m\\]$name\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# '\n")
        val bashrc = File(home, ".bashrc")
        val source = "[ ! -f /root/.ousecode/prompt.bash ] || . /root/.ousecode/prompt.bash"
        if (!bashrc.exists() || !bashrc.readText().lineSequence().contains(source)) bashrc.appendText("\n$source\n")
        // Bash login shells need a user profile too. Preserve all existing profiles.
        if (listOf(".bash_profile", ".bash_login", ".profile").none { File(home, it).exists() }) {
            File(home, ".bash_profile").writeText("[ ! -f ~/.bashrc ] || . ~/.bashrc\n")
        }
    }
    fun observe(listener: (RuntimeStatus) -> Unit) { listeners += listener; main.post { listener(status) } }
    fun unobserve(listener: (RuntimeStatus) -> Unit) { listeners -= listener }
    private fun publish(phase: Phase, message: String, progress: Int = -1, version: String = status.version) {
        val next = RuntimeStatus(phase, message, progress, version)
        status = next
        main.post { listeners.forEach { it(next) } }
    }

    fun logs(): String = synchronized(logLock) { lines.joinToString("\n") }
    fun log(raw: String) {
        // OpenCode prints generated passwords and pairing links: never retain them.
        val line = raw.replace(password, "[redacted]")
            .replace(Regex("(?i)(server password|authorization|refresh_token|access_token).*"), "$1 [redacted]")
            .replace(Regex("/auth/connect/[^\\s]+"), "/auth/connect/[redacted]")
        synchronized(logLock) { lines.addLast(line.take(2000)); while (lines.size > 180) lines.removeFirst() }
    }

    @Synchronized fun start() {
        if (busy || status.phase == Phase.READY) return
        publish(Phase.PREPARING, text("准备运行环境", "Preparing your environment"))
        worker.execute { try { install(); launch() } catch (error: Exception) { fail(error) } }
    }

    @Synchronized fun restart() {
        if (busy) return
        publish(Phase.STARTING, text("正在重启", "Restarting"))
        worker.execute { try { stopServer(); install(); launch() } catch (error: Exception) { fail(error) } }
    }

    @Synchronized fun stop() {
        if (busy) return
        publish(Phase.STARTING, text("正在停止", "Stopping"))
        worker.execute {
            try { stopServer(); publish(Phase.STOPPED, text("已停止，项目与会话已保留", "Stopped. Projects and sessions are retained.")) }
            catch (error: Exception) { fail(error) }
        }
    }

    private fun install() {
        home.mkdirs(); engine.mkdirs()
        val expected = manifest.getString("runtimeVersion")
        val marker = File(rootfs, "etc/ousecode/runtime-version")
        if (!marker.isFile || marker.readText().trim() != expected) {
            check(!rootfs.exists()) { text("运行环境版本不同，请先导出项目后重新安装应用", "The runtime version differs. Export your projects before reinstalling.") }
            check(context.filesDir.usableSpace > 1_500_000_000L) { text("请至少预留 1.5 GB 可用空间", "At least 1.5 GB of free space is required") }
            val staging = File(context.filesDir, "runtime/staging")
            if (staging.exists()) check(staging.deleteRecursively()) { "Cannot remove incomplete installation" }
            staging.mkdirs()
            unpack(manifest.getJSONObject("ubuntu"), staging, text("解压 Ubuntu", "Unpacking Ubuntu"))
            check(staging.renameTo(rootfs)) { "Cannot finish Ubuntu installation" }
        }
        if (!File(engine, "opencode").isFile) {
            val staging = File(context.cacheDir, "engine-install")
            if (staging.exists()) check(staging.deleteRecursively())
            staging.mkdirs()
            unpack(manifest.getJSONObject("engine"), staging, text("安装 OpenCode", "Installing OpenCode"))
            val binary = File(staging, "opencode")
            check(binary.isFile && binary.setExecutable(true, true)) { "OpenCode executable missing" }
            check(binary.renameTo(File(engine, "opencode"))) { "Cannot finish OpenCode installation" }
            staging.deleteRecursively()
        }
        File(home, "workspace").mkdirs()
        File(home, ".l2s").mkdirs()
        File(home, ".ousecode").mkdirs()
        File(home, ".ousecode/server-password").apply { writeText(password + "\n"); Os.chmod(path, 0x180) }
        val instructions = File(home, "workspace/AGENTS.md")
        if (!instructions.exists()) instructions.writeText("""
            # OpenCode environment
            This workspace runs inside Ubuntu 24.04 ARM64 under PRoot on Android, without real root.
            Node.js, npm, Git and CA certificates are installed. Use /root/workspace for projects.
            Run individual services directly; systemd, Docker daemon, kernel namespaces and iptables are unavailable.
            /root contains persistent user data. Do not modify the Android app's runtime or credentials.
            Reply in the user's language. Keep explanations concise and focus on outcomes.
        """.trimIndent() + "\n")
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists()) bashrc.writeText("export PATH=/usr/local/bin:/opt/node/bin:\$PATH\nexport LANG=C.UTF-8\n")
        writeTerminalPrompt()
        writeAndroidGroupNames()
        AndroidAccess.get(context).start(rootfs)
        writeDns()
    }

    private fun writeAndroidGroupNames() {
        // PRoot fakes root IDs but retains Android supplementary groups. Give libc
        // names for those IDs so Ubuntu's interactive bash startup can run groups.
        val gids = File("/proc/self/status").readLines().single { it.startsWith("Groups:") }
            .substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt() }.toSet()
        val group = File(rootfs, "etc/group")
        val original = group.readText()
        val entries = original.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split(':') }.toList()
        val existingGids = entries.mapNotNull { it.getOrNull(2)?.toIntOrNull() }.toSet()
        val names = entries.map { it.first() }.toMutableSet()
        val missing = gids - existingGids
        if (missing.isEmpty()) return
        group.appendText(buildString {
            if (original.isNotEmpty() && !original.endsWith('\n')) append('\n')
            for (gid in missing) {
                val base = "android_gid_$gid"
                var name = base
                var suffix = 1
                while (!names.add(name)) name = "${base}_${suffix++}"
                append("$name:!:$gid:\n")
            }
        })
    }

    private fun unpack(info: JSONObject, target: File, message: String) {
        val name = info.getString("file")
        val hash = MessageDigest.getInstance("SHA-256")
        var previous = -1
        context.assets.open("runtime/$name").use { raw ->
            java.security.DigestInputStream(raw, hash).use { input ->
                TarExtractor(target) { done ->
                    val percent = (done * 100 / info.getLong("bytes")).toInt().coerceIn(0, 100)
                    if (percent != previous) { previous = percent; publish(Phase.PREPARING, message, percent) }
                }.extract(input, name)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it) }
        check(actual == info.getString("sha256")) { "Bundled archive checksum mismatch: $name" }
    }

    fun writeDns() {
        if (!rootfs.isDirectory) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val addresses = cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
            .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        val dns = (addresses + listOf("223.5.5.5", "1.1.1.1")).distinct().take(4)
        File(rootfs, "etc/resolv.conf").writeText(dns.joinToString("\n", postfix = "\n") { "nameserver $it" } + "options timeout:3 attempts:2\n")
    }

    private fun launch() {
        expectedExit = false
        publish(Phase.STARTING, text("启动 OpenCode", "Starting OpenCode"))
        val port = prefs.getInt("port", 0).takeIf { it > 0 } ?: ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
            .use { it.localPort }.also { prefs.edit().putInt("port", it).apply() }
        baseUrl = "http://127.0.0.1:$port"
        File(rootfs, "tmp/ousecode-engine.pid").delete()
        val shell = "set -e; IFS= read -r OPENCODE_PASSWORD < /root/.ousecode/server-password; export OPENCODE_PASSWORD; echo \$\$ > /tmp/ousecode-engine.pid; cd /root/workspace; " +
            "opencode service set hostname 127.0.0.1 >/dev/null; opencode service set port $port >/dev/null; opencode service set password \"\$OPENCODE_PASSWORD\" >/dev/null; " +
            "exec /opt/ousecode/opencode serve --service --hostname 127.0.0.1 --port $port"
        val process = spawn(listOf("/bin/sh", "-c", shell), "server")
        server = process
        thread(name = "ousecode-output", isDaemon = true) {
            try { process.inputStream.bufferedReader().useLines { it.forEach(::log) } }
            catch (error: java.io.IOException) { if (!expectedExit) log(error.message ?: "Output pipe closed") }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        var lastError = ""
        while (process.isAlive && System.nanoTime() < deadline) {
            try {
                val info = JSONObject(request("/api/info"))
                val version = info.getString("version")
                publish(Phase.READY, text("本机运行中", "Running on this device"), version = version)
                log("OpenCode $version · Ubuntu ${manifest.getString("ubuntuVersion")} · ARM64")
                thread(name = "ousecode-wait", isDaemon = true) {
                    val exit = process.waitFor()
                    if (server === process && !expectedExit) {
                        log("OpenCode exited: $exit")
                        publish(Phase.ERROR, text("OpenCode 已退出，点击重试", "OpenCode exited. Tap Retry."))
                    }
                }
                return
            } catch (error: Exception) { lastError = error.message ?: error.javaClass.simpleName }
            Thread.sleep(400)
        }
        val code = if (process.isAlive) "timeout" else process.exitValue().toString()
        throw IllegalStateException(text("OpenCode 启动失败", "OpenCode failed to start") + " ($code): $lastError")
    }

    private fun spawn(command: List<String>, label: String): Process = processBuilder(command, label).start()

    private fun processBuilder(command: List<String>, label: String): ProcessBuilder {
        val tmp = File(context.cacheDir, "proot").apply { mkdirs() }
        val pidFile = File(context.cacheDir, "proot-$label.pid").apply { delete() }
        val argv = mutableListOf("$nativeDir/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "-0", "--rootfs=${rootfs.absolutePath}", "--cwd=/root/workspace")
        for (bind in listOf("/dev", "/dev/urandom:/dev/random", "/proc", "/proc/self/fd:/dev/fd", "/proc/self/fd/0:/dev/stdin", "/proc/self/fd/1:/dev/stdout", "/proc/self/fd/2:/dev/stderr", "/sys", "${rootfs.absolutePath}/var/lib/ousecode/empty:/sys/fs/selinux", "${rootfs.absolutePath}/var/lib/ousecode/shm:/dev/shm", "/system", "/apex", "${home.absolutePath}:/root", "${engine.absolutePath}:/opt/ousecode")) {
            argv += "-b"; argv += bind
        }
        argv += listOf("/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash", "PATH=/usr/local/bin:/opt/node/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin", "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp", "TZ=${java.util.TimeZone.getDefault().id}", "NODE_OPTIONS=--dns-result-order=ipv4first", "UV_USE_IO_URING=0", "BUN_CONFIG_NO_CLEAR_TERMINAL=1", "BUN_RUNTIME_TRANSPILER_CACHE_PATH=0")
        argv += command
        val wrapped = listOf("/system/bin/sh", "-c", "pidfile=\$1; shift; echo \$\$ > \"\$pidfile\"; exec \"\$@\"", "ousecode", pidFile.absolutePath) + argv
        val builder = ProcessBuilder(wrapped).redirectErrorStream(true).directory(context.filesDir)
        builder.environment().apply {
            clear()
            put("PATH", "/system/bin:/system/xbin")
            put("PROOT_LOADER", "$nativeDir/libproot-loader.so")
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("PROOT_L2S_DIR", File(home, ".l2s").absolutePath)
            put("LD_LIBRARY_PATH", nativeDir)
            put("HOME", context.filesDir.absolutePath)
        }
        return builder
    }

    /** Called on the main thread; TerminalView starts this process once its size is known. */
    fun createTerminal(client: TerminalSessionClient): TerminalSession {
        check(rootfs.isDirectory && !busy) { text("请先等待 Ubuntu 准备完成", "Wait for Ubuntu to finish preparing") }
        File(home, ".ousecode/terminal.bashrc").writeText(". /root/.bashrc\nPROMPT_COMMAND+=('history -a')\n")
        val label = "terminal-${java.util.UUID.randomUUID()}"
        val builder = processBuilder(listOf("/bin/bash", "--rcfile", "/root/.ousecode/terminal.bashrc", "-i"), label)
        val args = builder.command().toTypedArray()
        return TerminalSession(args[0], builder.directory()!!.absolutePath, args,
            builder.environment().map { "${it.key}=${it.value}" }.toTypedArray(), 4000, client).also {
            terminals[it] = File(context.cacheDir, "proot-$label.pid")
        }
    }

    fun closeTerminal(session: TerminalSession) {
        // PRoot handles SIGQUIT and kills its tracees. SIGKILL can leave guest processes behind.
        val pid = session.pid
        if (pid > 0) {
            try { Os.kill(pid, OsConstants.SIGQUIT) }
            catch (error: android.system.ErrnoException) { if (error.errno != OsConstants.ESRCH) throw error }
        } else if (pid == 0) forgetTerminal(session) // Detached before TerminalView ever created a PTY.
    }

    fun forgetTerminal(session: TerminalSession) { terminals.remove(session)?.delete() }

    private fun stopServer() {
        expectedExit = true
        AndroidAccess.get(context).stop()
        terminals.values.forEach { signalPidFile(it, OsConstants.SIGQUIT) }
        val process = server ?: return
        if (process.isAlive) {
            signalPidFile(File(rootfs, "tmp/ousecode-engine.pid"), OsConstants.SIGTERM)
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                signalPidFile(File(context.cacheDir, "proot-server.pid"), OsConstants.SIGQUIT)
                check(process.waitFor(5, TimeUnit.SECONDS)) { "PRoot did not stop" }
            }
        }
        commands.forEach { (_, pidFile) -> signalPidFile(pidFile, OsConstants.SIGQUIT) }
        server = null
    }

    private fun signalPidFile(file: File, signal: Int) {
        val pid = file.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: return
        if (pid <= 1) return
        try {
            if (Os.stat("/proc/$pid").st_uid == android.os.Process.myUid()) Os.kill(pid, signal)
        } catch (error: android.system.ErrnoException) {
            if (error.errno != OsConstants.ESRCH && error.errno != OsConstants.ENOENT) throw error
        }
    }

    fun request(path: String, authenticated: Boolean = true, method: String = "GET", body: JSONObject? = null): String {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 1500; connection.readTimeout = if (method == "GET") 5000 else 30000
        connection.requestMethod = method
        if (authenticated) connection.setRequestProperty("Authorization", authorization())
        try {
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    fun authorization(): String = "Basic " + Base64.encodeToString("opencode:$password".toByteArray(), Base64.NO_WRAP)
    fun authPassword(): String = password

    fun updateAndroidMode(mode: AndroidMode, done: (String?) -> Unit) {
        worker.execute {
            val error = try {
                AndroidAccess.get(context).setMode(mode, rootfs)
                if (status.phase == Phase.READY) request("/api/location/reload", method = "POST", body = JSONObject())
                null
            } catch (error: Exception) { error.message ?: error.javaClass.simpleName }
            main.post { done(error) }
        }
    }

    /** Pair each new WebView through OpenCode's official one-time sign-in flow. */
    fun pairingUrl(): String {
        val (exit, output) = command("opencode pair --url $baseUrl", 30)
        check(exit == 0) { text("无法连接本机工作区，请重启运行环境", "Cannot pair with the local workspace. Restart the environment.") }
        return Regex(Regex.escape(baseUrl) + "/auth/connect/[^\\s\\u001B]+")
            .find(output)?.value ?: throw IllegalStateException(text("OpenCode 没有返回配对链接", "OpenCode did not return a pairing link"))
    }

    /** Called off the UI thread; commands share the persistent home with OpenCode. */
    fun command(script: String, timeoutSeconds: Long = 120): Pair<Int, String> {
        check(rootfs.isDirectory) { "Ubuntu is not installed" }
        val label = "command-${System.nanoTime()}"
        val process = spawn(listOf("/bin/bash", "-lc", script), label)
        commands[process] = File(context.cacheDir, "proot-$label.pid")
        val output = StringBuilder()
        val reader = thread(isDaemon = true, name = label) {
            process.inputStream.bufferedReader().useLines { stream -> stream.forEach { line ->
                synchronized(output) { output.append(line).append('\n'); if (output.length > 160_000) output.delete(0, 40_000) }
            } }
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                signalPidFile(File(context.cacheDir, "proot-$label.pid"), OsConstants.SIGQUIT)
                process.waitFor(5, TimeUnit.SECONDS)
                throw IllegalStateException(text("命令执行超时", "Command timed out"))
            }
            reader.join(2000)
            return process.exitValue() to synchronized(output) { output.toString().replace(password, "[redacted]") }
        } finally { commands.remove(process); File(context.cacheDir, "proot-$label.pid").delete() }
    }

    @Synchronized fun upgrade() {
        if (busy || status.phase != Phase.READY) return
        publish(Phase.UPDATING, text("更新 OpenCode", "Updating OpenCode"))
        worker.execute {
            try {
                stopServer()
                File(engine, "opencode").copyTo(File(engine, "opencode.previous"), overwrite = true).setExecutable(true, true)
                val result = command("opencode upgrade --method curl", 600)
                log(result.second)
                check(result.first == 0) { text("更新失败，旧版程序仍可保留使用", "Update failed; the previous executable is retained") }
                launch()
            } catch (error: Exception) { fail(error) }
        }
    }

    private fun fail(error: Exception) {
        try { stopServer() } catch (stopError: Exception) { log("Stop: ${stopError.message}") }
        log("${error.javaClass.simpleName}: ${error.message}")
        publish(Phase.ERROR, error.message ?: text("启动失败", "Startup failed"))
    }

    companion object {
        @Volatile private var instance: RuntimeManager? = null
        fun get(context: Context): RuntimeManager = instance ?: synchronized(this) {
            instance ?: RuntimeManager(context.applicationContext).also { instance = it }
        }
    }
}
