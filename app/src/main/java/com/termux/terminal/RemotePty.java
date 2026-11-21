package com.termux.terminal;

/** Native PTY operations shared with the Shizuku process. */
public final class RemotePty {
    public static int open(String shell, String cwd, String[] args, String[] env, int[] pid, int rows, int columns, int cellWidth, int cellHeight) {
        return JNI.createSubprocess(shell, cwd, args, env, pid, rows, columns, cellWidth, cellHeight);
    }
    public static void resize(int fd, int rows, int columns, int cellWidth, int cellHeight) { JNI.setPtyWindowSize(fd, rows, columns, cellWidth, cellHeight); }
    public static int waitFor(int pid) { return JNI.waitFor(pid); }
}
