package io.github.ouse.ousecode;
import android.os.Bundle;
interface IAndroidShell {
    void destroy() = 16777114;
    Bundle execute(String command, int timeoutSeconds) = 1;
    Bundle openTerminal(int rows, int columns, int cellWidth, int cellHeight) = 2;
    void resizeTerminal(int pid, int rows, int columns, int cellWidth, int cellHeight) = 3;
    int waitForTerminal(int pid) = 4;
    void closeTerminal(int pid) = 5;
}
