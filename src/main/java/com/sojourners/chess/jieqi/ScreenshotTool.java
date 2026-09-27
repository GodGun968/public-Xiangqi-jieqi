package com.sojourners.chess.jieqi;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;

/**
 * 揭棋截图工具
 * <p>
 * 独立运行的后台截图器，用于采集 JJ 象棋的盘面样本（训练/校准用）。
 * 用法：ScreenshotTool [输出目录] [间隔毫秒] [标题关键字] [最多张数]
 * 默认：captures 3000 JJ 600
 * <p>
 * 画面没变化的帧会跳过；同时写 meta.csv 记录每张图的信息。
 */
public final class ScreenshotTool {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HHmmss");

    private ScreenshotTool() {
    }

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "captures";
        int intervalMs = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        String keyword = args.length > 2 ? args[2] : "JJ";
        int maxCount = args.length > 3 ? Integer.parseInt(args[3]) : 600;

        File dir = new File(outDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        JieqiTrace.log("截图工具启动：目录=" + dir.getAbsolutePath() + " 间隔=" + intervalMs
                + "ms 标题关键字=" + keyword + " 最多=" + maxCount + "张");

        HWND hwnd = findWindow(keyword);
        if (hwnd == null) {
            // 关键字没命中就轮流试常见的几个
            for (String kw : new String[]{"JJ", "揭棋", "象棋", "天天", "雷电"}) {
                hwnd = findWindow(kw);
                if (hwnd != null) {
                    keyword = kw;
                    break;
                }
            }
        }
        if (hwnd == null) {
            hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd != null) {
                JieqiTrace.log("按关键字没找到，改用当前前台窗口: " + getTitle(hwnd));
            }
        }

        if (hwnd == null) {
            JieqiTrace.log("没找到标题包含“" + keyword + "”的窗口，先打开 JJ 象棋再启动。");
            System.out.println("没找到窗口，退出。可换关键字，例：... ScreenshotTool captures 3000 揭棋");
            return;
        }

        System.out.println("已找到窗口: " + getTitle(hwnd) + "（后台截图开始，Ctrl+C 或关掉此窗口结束）");
        JieqiTrace.log("目标窗口: " + getTitle(hwnd));

        int count = 0;
        long[] prevSig = null;
        while (count < maxCount) {
            try {
                BufferedImage img = capture(hwnd);
                if (img != null) {
                    long[] sig = signature(img);
                    if (prevSig != null && sameFrame(prevSig, sig)) {
                        JieqiTrace.log("画面没变化，跳过这一帧");
                    } else {
                        prevSig = sig;
                        String name = String.format("jj_%s_%04d.jpg", LocalDateTime.now().format(TS), ++count);
                        File f = new File(dir, name);
                        writeJpeg(img, f);
                        ImageIO.write(img, "png", new File(dir, "latest.png"));
                        appendMeta(dir, name, img.getWidth(), img.getHeight());
                        JieqiTrace.log("已截图 " + name + "（" + img.getWidth() + "x" + img.getHeight() + "）");
                    }
                } else {
                    JieqiTrace.log("截图失败（窗口可能已关闭）");
                }
            } catch (Throwable t) {
                JieqiTrace.log("截图异常: " + t);
            }
            Thread.sleep(intervalMs);
        }

        JieqiTrace.log("截图工具结束，共 " + count + " 张");
        System.out.println("完成，共 " + count + " 张，目录: " + dir.getAbsolutePath());
    }

    /**
     * 按标题关键字找一个可见且有实际尺寸的窗口
     */
    private static HWND findWindow(String keyword) {
        HWND[] result = new HWND[1];
        User32.INSTANCE.EnumWindows((hWnd, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hWnd)) {
                return true;
            }
            String title = getTitle(hWnd);
            if (title != null && !title.isEmpty() && title.contains(keyword)) {
                RECT rect = new RECT();
                User32.INSTANCE.GetWindowRect(hWnd, rect);
                if (rect.toRectangle().width > 200 && rect.toRectangle().height > 200) {
                    result[0] = hWnd;
                    return false;
                }
            }
            return true;
        }, Pointer.NULL);
        return result[0];
    }

    private static String getTitle(HWND hWnd) {
        char[] buf = new char[512];
        User32.INSTANCE.GetWindowText(hWnd, buf, 512);
        return new String(buf).trim();
    }

    /**
     * 用 PrintWindow 做后台截图（窗口被遮挡也能拿到画面）
     */
    private static BufferedImage capture(HWND hWnd) {
        HDC hdcWindow = User32.INSTANCE.GetDC(hWnd);
        if (hdcWindow == null) {
            return null;
        }
        HDC hdcMemDC = GDI32.INSTANCE.CreateCompatibleDC(hdcWindow);
        try {
            RECT bounds = new RECT();
            User32.INSTANCE.GetClientRect(hWnd, bounds);
            int width = bounds.right - bounds.left;
            int height = bounds.bottom - bounds.top;
            if (width <= 0 || height <= 0) {
                return null;
            }

            HBITMAP hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcWindow, width, height);
            HANDLE hOld = GDI32.INSTANCE.SelectObject(hdcMemDC, hBitmap);
            try {
                // PW_RENDERFULLCONTENT = 2，但这里用 3 以兼容更多窗口
                if (!User32.INSTANCE.PrintWindow(hWnd, hdcMemDC, 3)) {
                    return null;
                }
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                BITMAPINFO bmi = new BITMAPINFO();
                bmi.bmiHeader.biWidth = width;
                bmi.bmiHeader.biHeight = -height;
                bmi.bmiHeader.biPlanes = 1;
                bmi.bmiHeader.biBitCount = 32;
                bmi.bmiHeader.biCompression = 0;
                Memory buffer = new Memory((long) width * height * 4L);
                GDI32.INSTANCE.GetDIBits(hdcMemDC, hBitmap, 0, height, buffer, bmi, 0);
                int[] data = buffer.getIntArray(0L, width * height);
                image.setRGB(0, 0, width, height, data, 0, width);
                return image;
            } finally {
                GDI32.INSTANCE.SelectObject(hdcMemDC, hOld);
                GDI32.INSTANCE.DeleteObject(hBitmap);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            GDI32.INSTANCE.DeleteDC(hdcMemDC);
            User32.INSTANCE.ReleaseDC(hWnd, hdcWindow);
        }
    }

    private static void writeJpeg(BufferedImage img, File f) throws Exception {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg");
        if (!it.hasNext()) {
            ImageIO.write(img, "jpg", f);
            return;
        }
        ImageWriter writer = it.next();
        try (ImageOutputStream os = ImageIO.createImageOutputStream(f)) {
            writer.setOutput(os);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.92F);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * 32x32 的灰度指定位图，用来判断两帧是否基本一致
     */
    private static long[] signature(BufferedImage img) {
        long[] sig = new long[32];
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < 32; y++) {
            long bits = 0L;
            for (int x = 0; x < 32; x++) {
                int rgb = img.getRGB(Math.min(w - 1, x * w / 32), Math.min(h - 1, y * h / 32));
                int gray = (rgb >> 16 & 0xFF) * 299 / 1000 + (rgb >> 8 & 0xFF) * 587 / 1000 + (rgb & 0xFF) * 114 / 1000;
                if (gray > 128) {
                    bits |= 1L << x;
                }
            }
            sig[y] = bits;
        }
        return sig;
    }

    private static boolean sameFrame(long[] a, long[] b) {
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff += Long.bitCount(a[i] ^ b[i]);
        }
        return diff <= 8;
    }

    private static synchronized void appendMeta(File dir, String name, int w, int h) {
        File meta = new File(dir, "meta.csv");
        boolean needHeader = !meta.exists();
        try (FileWriter fw = new FileWriter(meta, true)) {
            if (needHeader) {
                fw.write("file,time,width,height" + System.lineSeparator());
            }
            fw.write(name + "," + LocalDateTime.now() + "," + w + "," + h + System.lineSeparator());
        } catch (Exception ignored) {
        }
    }
}
