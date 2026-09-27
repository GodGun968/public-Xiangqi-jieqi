package com.sojourners.chess.jieqi;

import com.sojourners.chess.util.PathUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 揭棋日志
 * <p>
 * 同时输出到控制台和程序目录下的 log/jieqi.log，便于排查识别/连线问题。
 */
public final class JieqiTrace {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Object LOCK = new Object();
    private static volatile File logFile;

    private JieqiTrace() {
    }

    /**
     * 写一行日志
     */
    public static void log(String msg) {
        String stamp = LocalDateTime.now().format(FMT);
        System.out.println("[jieqi] " + msg);
        synchronized (LOCK) {
            try {
                if (logFile == null) {
                    String base = PathUtils.getJarPath();
                    File dir = new File(base == null ? "log" : base + "log");
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    logFile = new File(dir, "jieqi.log");
                }
                try (FileWriter w = new FileWriter(logFile, true)) {
                    w.write("[" + stamp + "] " + msg);
                    w.write(System.lineSeparator());
                }
            } catch (IOException e) {
                System.out.println("[jieqi] 写日志失败: " + e.getMessage());
            }
        }
    }

    /**
     * 把盘面打印成多行文本，便于写日志时看局面
     */
    public static String dump(char[][] grid) {
        StringBuilder sb = new StringBuilder(System.lineSeparator());
        for (char[] row : grid) {
            sb.append("    ");
            for (char ch : row) {
                sb.append(ch == ' ' ? '.' : ch);
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
