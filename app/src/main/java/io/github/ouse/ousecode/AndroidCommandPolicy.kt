package io.github.ouse.ousecode

/** Auto-approves a small, known query grammar. Everything else needs an exact-command approval. */
object AndroidCommandPolicy {
    private val word = Regex("[A-Za-z0-9_./:@%+=,-]+")
    private val metadataCommands = setOf("id", "whoami", "uname", "uptime", "free", "ps", "df", "du", "ls", "stat")
    private val properties = setOf("ro.product.model", "ro.product.manufacturer", "ro.product.cpu.abi", "ro.build.version.release", "ro.build.version.sdk", "sys.boot_completed")
    private val procFiles = setOf("/proc/meminfo", "/proc/cpuinfo", "/proc/stat", "/proc/loadavg", "/proc/uptime", "/proc/version", "/proc/diskstats", "/proc/net/tcp", "/proc/net/tcp6", "/proc/net/udp", "/proc/net/udp6")

    fun isQuery(command: String): Boolean {
        // No expansions, quoting, operators, newlines, redirection or executable wrappers.
        // They require confirmation even when one part happens to be a query.
        if (command.any { it != ' ' && it != '\t' && (it.code < 33 || it.code > 126) }) return false
        var tokens = command.trim(' ', '\t').split(Regex("[ \\t]+"))
        if (tokens.any { !word.matches(it) }) return false
        if (tokens[0] in setOf("toybox", "/system/bin/toybox")) tokens = tokens.drop(1)
        if (tokens.isEmpty()) return false
        val executable = tokens[0].removePrefix("/system/bin/")
        if ('/' in executable) return false
        val args = tokens.drop(1)
        return when (executable) {
            in metadataCommands -> true
            "pwd", "date" -> args.isEmpty()
            "getprop" -> args.size == 1 && args[0] in properties
            "cat" -> args.isNotEmpty() && args.all { it in procFiles }
            "dumpsys" -> args.size == 1 && args[0] in setOf("battery", "cpuinfo", "meminfo", "thermalservice", "diskstats")
            "pm" -> args.size >= 2 && args[0] == "list" && args[1] in setOf("packages", "features", "libraries")
            "wm" -> args.size == 1 && args[0] in setOf("size", "density")
            "ip" -> args.size in 1..2 && args[0] in setOf("addr", "address", "link", "route") && (args.size == 1 || args[1] == "show")
            "ss", "netstat" -> args.all { Regex("-[tulnp46a]+").matches(it) }
            else -> false
        }
    }
}
