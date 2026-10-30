package com.highlightrecorder.monitor;

// 仅固定的只读诊断,不提供任意 shell 命令入口。
interface IFpsShell {
    void destroy() = 16777114;
    String[] focus() = 1;
    String[] layers() = 2;
    String[] latency(String layer) = 3;
}
