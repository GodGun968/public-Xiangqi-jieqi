package com.sojourners.chess.jieqi;

import com.sojourners.chess.util.PathUtils;
import com.sojourners.chess.yolo.OnnxModel;
import com.sojourners.chess.yolo.VinYolo5Model;
import com.sojourners.chess.yolo.Yolo11Model;
import com.sojourners.chess.yolo.Yolo5Model;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import javax.imageio.ImageIO;

/**
 * 揭棋盘面识别
 * <p>
 * 从一张窗口截图里认出 10x9 的揭棋局面，流程大致是：
 * <ol>
 *     <li>先用 YOLO 模型找到棋盘框（detectBoard）；</li>
 *     <li>再按棋子框拟合出棋盘网格（fitGrid / fitGridByPieces），格距和相位用投票法确定；</li>
 *     <li>对每个格子判断是空、暗子还是明子：暗子看纹理方差，明子交给分类器/OCR 认字；</li>
 *     <li>根据上下方的将帅颜色判断棋盘朝向，必要时整体旋转 180 度。</li>
 * </ol>
 * 因为屏幕上的棋盘可能翻转（用户执黑时上方是红方），朝向判定会连续几帧一致后锁存，避免抖动。
 * 识别过程与中间量都会写到 log/jieqi.log，便于排查。
 */
public final class JieqiBoardRecognizer {
    public static final double PADDING = 0.8;
    private static final char[] TEMPLATE_CHARS = new char[]{'r', 'n', 'b', 'a', 'k', 'c', 'p', 'R', 'N', 'B', 'A', 'K', 'C', 'P'};
    private static final String[] TEMPLATE_FILES = new String[]{
        "black_r.jpg",
        "black_n.jpg",
        "black_b.jpg",
        "black_a.jpg",
        "black_k.jpg",
        "black_c.jpg",
        "black_p.jpg",
        "red_R.jpg",
        "red_N.jpg",
        "red_B.jpg",
        "red_A.jpg",
        "red_K.jpg",
        "red_C.jpg",
        "red_P.jpg"
    };
    private static final int TPL_SIZE = 48;
    private static final double REVEALED_STD = 50.0;
    private static Map<Character, float[]> templates;
    private static volatile Rectangle lastBoardPos;
    private static volatile Rectangle lastBoardRect;
    private static volatile int lastKingEvidence;
    private static volatile char[][] lastScreenGrid;
    private static volatile boolean flipped;
    private static volatile boolean flippedKnown;
    private static final int FLIP_LATCH_STREAK = 3;
    private static volatile boolean flipTentative;
    private static volatile boolean flipTentativeKnown;
    private static volatile int flipStreak;
    private static volatile boolean flipLatched;
    private static volatile long lastOcrLog;
    private static volatile JieqiBoardRecognizer.Grid lastFitted;
    private static volatile int lastFittedW;
    private static volatile int lastFittedH;
    private static volatile JieqiBoardRecognizer.Calibrated calibrated;
    private static OnnxModel cachedAi;
    private static OnnxModel cachedVin;

    private JieqiBoardRecognizer() {
    }

    private static synchronized void loadTemplates() throws Exception {
        if (templates == null) {
            templates = new LinkedHashMap<>();

            for (int i = 0; i < TEMPLATE_CHARS.length; i++) {
                try (InputStream in = JieqiBoardRecognizer.class.getResourceAsStream("/jieqi/jj/" + TEMPLATE_FILES[i])) {
                    if (in != null) {
                        BufferedImage img = ImageIO.read(in);
                        if (img != null) {
                            templates.put(TEMPLATE_CHARS[i], grayNcc(resize(img, 48, 48)));
                        }
                    }
                }
            }
        }
    }

    /**
     * 识别盘面（不带模型时的简化入口）
     */
    public static char[][] recognize(BufferedImage img, Rectangle board) {
        return recognize(img, board, null, null);
    }

    public static char[][] recognize(
        BufferedImage img, Rectangle boardPos, OnnxModel aiModel, OnnxModel vinModel, List<JieqiBoardRecognizer.CellFeature> features
    ) {
        char[][] pieces = new char[10][9];

        for (char[] row : pieces) {
            Arrays.fill(row, ' ');
        }

        boolean yoloOk = false;

        try {
            if (aiModel != null && aiModel.findChessBoard(img, pieces)) {
                yoloOk = true;
            }
        } catch (Exception e) {
            JieqiTrace.log("揭棋识别：主模型异常 " + e);
        }

        if (!yoloOk) {
            char[][] vinBoard = new char[10][9];

            for (char[] row : vinBoard) {
                Arrays.fill(row, ' ');
            }

            try {
                if (vinModel != null) {
                    vinModel.findChessBoard(img, vinBoard);
                }
            } catch (Exception e) {
                JieqiTrace.log("揭棋识别：Vin 模型异常 " + e);
            }

            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < 9; c++) {
                    if (vinBoard[r][c] != ' ') {
                        pieces[r][c] = vinBoard[r][c];
                    }
                }
            }
        }

        int named = 0;

        for (char[] row : pieces) {
            for (char ch : row) {
                if (ch != ' ') {
                    named++;
                }
            }
        }

        JieqiTrace.log("揭棋识别：YOLO 明子数=" + named + "（主模型命中=" + yoloOk + "）");
        char[][] grid = new char[10][9];
        JieqiBoardRecognizer.Grid fitted = fitGrid(img, boardPos);
        if (fitted != null) {
            lastFitted = fitted;
            lastFittedW = img.getWidth();
            lastFittedH = img.getHeight();
        }

        double pw = fitted != null ? fitted.dx : (double)boardPos.width / 9.6;
        double ph = fitted != null ? fitted.dy : (double)boardPos.height / 10.6;
        double boxW = Math.max(8.0, pw * 0.62);
        double boxH = Math.max(8.0, ph * 0.62);
        double[] lum = new double[90];
        double[] std = new double[90];
        List<BufferedImage> cells = new ArrayList<>(90);

        for (int r = 0; r < 10; r++) {
            for (int cx = 0; cx < 9; cx++) {
                BufferedImage cell;
                if (fitted != null) {
                    int cxx = (int)Math.round(fitted.x0 + (double)cx * fitted.dx);
                    int cy = (int)Math.round(fitted.y0 + (double)r * fitted.dy);
                    cell = cellAt(img, cxx, cy, boxW, boxH);
                } else {
                    cell = cellImage(img, boardPos, pw, ph, boxW, boxH, r, cx);
                }

                cells.add(cell);
                double[] mean = meanColor(cell);
                lum[r * 9 + cx] = (mean[0] + mean[1] + mean[2]) / 3.0;
                std[r * 9 + cx] = grayStd(cell);
            }
        }

        double[] sorted = (double[])lum.clone();
        Arrays.sort(sorted);
        double bgLum = sorted[45];
        double[] sortedStd = (double[])std.clone();
        Arrays.sort(sortedStd);
        double bgStd = sortedStd[45];
        boolean[] hasBox = new boolean[90];
        double[] boxStd = new double[90];
        Rectangle[] cellBox = new Rectangle[90];
        if (fitted != null && JieqiPieceDetector.get().ready()) {
            try {
                for (Rectangle r : JieqiPieceDetector.get().detect(img)) {
                    int cc = (int)Math.round(((double)r.x + (double)r.width / 2.0 - fitted.x0) / fitted.dx);
                    int rr = (int)Math.round(((double)r.y + (double)r.height / 2.0 - fitted.y0) / fitted.dy);
                    if (rr >= 0 && rr <= 9 && cc >= 0 && cc <= 8) {
                        int half = Math.max(4, Math.min(r.width, r.height) / 3);
                        int bx = r.x + r.width / 2;
                        int by = r.y + r.height / 2;
                        int x1 = Math.max(0, bx - half);
                        int y1 = Math.max(0, by - half);
                        int x2 = Math.min(img.getWidth(), bx + half);
                        int y2 = Math.min(img.getHeight(), by + half);
                        if (x2 - x1 >= 6 && y2 - y1 >= 6) {
                            hasBox[rr * 9 + cc] = true;
                            boxStd[rr * 9 + cc] = grayStd(img.getSubimage(x1, y1, x2 - x1, y2 - y1));
                            cellBox[rr * 9 + cc] = r;
                        }
                    }
                }
            } catch (Throwable e) {
                JieqiTrace.log("揭棋识别：检测器框映射失败 " + e);
            }
        }

        int boxCount = countTrue(hasBox);
        updateOrientation(img, cellBox);
        boolean[] occupied = new boolean[90];

        for (int i = 0; i < 90; i++) {
            occupied[i] = hasBox[i] || Math.abs(lum[i] - bgLum) >= 45.0;
        }

        boolean[] revealedByBox = new boolean[90];

        for (int i = 0; i < 90; i++) {
            revealedByBox[i] = hasBox[i] && boxStd[i] >= 50.0;
        }

        double occMedian = bgStd;
        int occN = 0;
        double[] occStds = new double[90];

        for (int i = 0; i < 90; i++) {
            if (occupied[i]) {
                occStds[occN++] = std[i];
            }
        }

        if (occN > 0) {
            double[] copy = Arrays.copyOf(occStds, occN);
            Arrays.sort(copy);
            occMedian = copy[occN / 2];
        }

        double revealedThreshold = Math.max(occMedian * 1.25, occMedian + 12.0);
        char[] hiddenChar = new char[90];
        Arrays.fill(hiddenChar, ' ');
        boolean[] revealed = new boolean[90];

        for (int ix = 0; ix < 90; ix++) {
            if (occupied[ix]) {
                int rx = ix / 9;
                int cx = ix % 9;
                boolean kingSpot = (rx == 0 || rx == 9) && cx == 4;
                boolean revealedHere = hasBox[ix] ? revealedByBox[ix] : std[ix] >= Math.max(revealedThreshold, 50.0);
                if (!kingSpot && !revealedHere) {
                    hiddenChar[ix] = hiddenColorAt(rx, cx);
                } else {
                    revealed[ix] = true;
                }
            }
        }

        int hiddenCount = countTrue(occupied) - countTrue(revealed);
        JieqiTrace.log(
            "揭棋识别：占用格="
                + countTrue(occupied)
                + " 暗子="
                + hiddenCount
                + " 明子="
                + countTrue(revealed)
                + " YOLO候选="
                + named
                + " bgLum="
                + Math.round(bgLum)
                + " bgStd="
                + Math.round(bgStd)
                + " 暗子std中位="
                + Math.round(occMedian)
                + " 明子阈值="
                + Math.round(revealedThreshold)
                + (flippedKnown ? (flipped ? " 朝向=翻转(上方红方)" : " 朝向=正常(上方黑方)") : " 朝向=未知")
        );

        for (int rx = 0; rx < 10; rx++) {
            for (int cx = 0; cx < 9; cx++) {
                int ixx = rx * 9 + cx;
                BufferedImage cell = cells.get(rx * 9 + cx);
                double[] mean = meanColor(cell);
                char decision;
                if (!occupied[ixx]) {
                    decision = '.';
                } else if (hiddenChar[ixx] != ' ') {
                    decision = hiddenChar[ixx];
                } else {
                    decision = revealedDecision(img, cellBox[ixx], pieces[rx][cx], rx, cx);
                }

                grid[rx][cx] = decision;
                if (features != null) {
                    JieqiBoardRecognizer.CellFeature f = new JieqiBoardRecognizer.CellFeature();
                    f.row = rx;
                    f.col = cx;
                    f.meanR = mean[0];
                    f.meanG = mean[1];
                    f.meanB = mean[2];
                    f.grayStd = grayStd(cell);
                    f.bgDist = Math.abs(lum[ixx] - bgLum);
                    f.centerCorner = centerVsCorner(cell);
                    f.decision = decision;
                    features.add(f);
                }
            }
        }

        char[][] screenGrid = new char[10][9];

        for (int rx = 0; rx < 10; rx++) {
            System.arraycopy(grid[rx], 0, screenGrid[rx], 0, 9);
        }

        lastScreenGrid = screenGrid;
        if (flippedKnown && flipped) {
            rotate180(grid);
        }

        sanitizeKings(grid);
        return grid;
    }

    private static char revealedDecision(BufferedImage img, Rectangle box, char yoloChar, int r, int c) {
        char type = ' ';
        if (yoloChar != ' ') {
            type = Character.toUpperCase(yoloChar);
        }

        Boolean red = null;
        if (box != null) {
            if (type == ' ') {
                char t = JieqiPieceDetector.get().classify(img, box);
                if (t != '?' && t != ' ') {
                    type = t;
                }
            }

            red = pieceColorRed(img, box);
            if (type != ' ') {
                String ocr = JieqiOcr.get().recognizeChar(img, box);
                char ocrPiece = ocrToPiece(ocr);
                if (ocrPiece != ' ' && ocrPiece != type) {
                    JieqiTrace.log("揭棋OCR：格(" + r + "," + c + ") 分类器=" + type + " 认字=" + ocr);
                } else if (ocr != null && !ocr.isEmpty() && System.currentTimeMillis() - lastOcrLog > 5000L) {
                    lastOcrLog = System.currentTimeMillis();
                    JieqiTrace.log("揭棋OCR：格(" + r + "," + c + ") 分类器=" + type + " 认字=“" + ocr + "”（没对上棋子名）");
                }
            }
        }

        if ((r == 0 || r == 9) && c == 4) {
            type = 'K';
            red = r == 0 ? flipped : !flipped;
        }

        if (type == ' ') {
            return hiddenColorAt(r, c);
        } else {
            if (red == null) {
                red = homeSideIsRed(r);
            }

            return red ? type : Character.toLowerCase(type);
        }
    }

    private static char ocrToPiece(String s) {
        if (s == null) {
            return ' ';
        } else {
            for (int i = 0; i < s.length(); i++) {
                switch (s.charAt(i)) {
                    case '仕':
                    case '士':
                        return 'A';
                    case '兵':
                    case '卒':
                        return 'P';
                    case '包':
                    case '炮':
                    case '砲':
                        return 'C';
                    case '将':
                    case '將':
                    case '帅':
                    case '帥':
                        return 'K';
                    case '相':
                    case '象':
                        return 'B';
                    case '車':
                    case '车':
                        return 'R';
                    case '馬':
                    case '马':
                        return 'N';
                }
            }

            return ' ';
        }
    }

    private static boolean homeSideIsRed(int r) {
        return flipped ? r <= 4 : r >= 5;
    }

    private static char hiddenColorAt(int r, int c) {
        return (char)(homeSideIsRed(r) ? 'X' : 'x');
    }

    private static void rotate180(char[][] g) {
        char[][] out = new char[10][9];

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                out[9 - r][8 - c] = g[r][c];
            }
        }

        for (int r = 0; r < 10; r++) {
            System.arraycopy(out[r], 0, g[r], 0, 9);
        }
    }

    private static void updateOrientation(BufferedImage img, Rectangle[] cellBox) {
        Boolean top = null;
        if (cellBox[4] != null) {
            top = pieceColorRed(img, cellBox[4]);
        }

        if (top == null) {
            top = palaceColor(img, cellBox, 0);
        }

        if (top != null) {
            applyOrientationCandidate(top);
        } else {
            Boolean bottom = null;
            if (cellBox[85] != null) {
                bottom = pieceColorRed(img, cellBox[85]);
            }

            if (bottom == null) {
                bottom = palaceColor(img, cellBox, 7);
            }

            if (bottom != null) {
                applyOrientationCandidate(!bottom);
            }
        }
    }

    private static void applyOrientationCandidate(boolean v) {
        if (!flipLatched) {
            if (flipTentativeKnown && flipTentative == v) {
                flipStreak++;
            } else {
                flipTentative = v;
                flipTentativeKnown = true;
                flipStreak = 1;
            }

            if (!flippedKnown || v != flipped) {
                JieqiTrace.log("揭棋识别：棋盘朝向判定为 " + (v ? "翻转（上方是红方，用户在执黑）" : "正常（上方是黑方）"));
            }

            flipped = v;
            flippedKnown = true;
            if (flipStreak >= 3) {
                flipLatched = true;
                JieqiTrace.log("揭棋识别：棋盘朝向锁存为 " + (v ? "翻转（上方是红方，用户在执黑）" : "正常（上方是黑方）") + "（连续 " + flipStreak + " 帧一致，后面不再跟着抖动改）");
            }
        }
    }

    private static Boolean palaceColor(BufferedImage img, Rectangle[] cellBox, int firstRow) {
        for (int r = firstRow; r < firstRow + 3; r++) {
            for (int c = 3; c <= 5; c++) {
                if (cellBox[r * 9 + c] != null) {
                    Boolean red = pieceColorRed(img, cellBox[r * 9 + c]);
                    if (red != null) {
                        return red;
                    }
                }
            }
        }

        return null;
    }

    private static Boolean pieceColorRed(BufferedImage img, Rectangle box) {
        int half = Math.max(4, Math.min(box.width, box.height) / 4);
        int cx = box.x + box.width / 2;
        int cy = box.y + box.height / 2;
        int x1 = Math.max(0, cx - half);
        int y1 = Math.max(0, cy - half);
        int x2 = Math.min(img.getWidth(), cx + half);
        int y2 = Math.min(img.getHeight(), cy + half);
        int red = 0;
        int black = 0;

        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                int p = img.getRGB(x, y);
                int rr = p >> 16 & 0xFF;
                int gg = p >> 8 & 0xFF;
                int bb = p & 0xFF;
                if (rr > 120 && rr - gg > 60 && rr - bb > 60) {
                    red++;
                } else if (rr < 95 && gg < 95 && bb < 95) {
                    black++;
                }
            }
        }

        if (red + black < 60) {
            return null;
        } else if (red > black * 3) {
            return Boolean.TRUE;
        } else {
            return black > red * 3 ? Boolean.FALSE : null;
        }
    }

    private static void sanitizeKings(char[][] g) {
        int extra = demoteExtraKings(g, 'k', 0) + demoteExtraKings(g, 'K', 9);
        if (extra > 0) {
            JieqiTrace.log("揭棋识别：多余的将/帅降级为士 " + extra + " 个（否则 FEN 非法、引擎不出着法）");
        }
    }

    private static int demoteExtraKings(char[][] g, char king, int homeRow) {
        List<int[]> spots = new ArrayList<>();

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                if (g[r][c] == king) {
                    spots.add(new int[]{r, c});
                }
            }
        }

        if (spots.size() <= 1) {
            return 0;
        } else {
            int keep = 0;
            int best = Integer.MAX_VALUE;

            for (int i = 0; i < spots.size(); i++) {
                int[] s = spots.get(i);
                int d = Math.abs(s[0] - homeRow) * 3 + Math.abs(s[1] - 4);
                if (d < best) {
                    best = d;
                    keep = i;
                }
            }

            int n = 0;

            for (int ix = 0; ix < spots.size(); ix++) {
                if (ix != keep) {
                    int[] s = spots.get(ix);
                    g[s[0]][s[1]] = (char)(Character.isUpperCase(king) ? 65 : 97);
                    n++;
                }
            }

            return n;
        }
    }

    private static float[] signature(BufferedImage img) {
        float[] v = new float[img.getWidth() * img.getHeight()];
        double mean = 0.0;
        int i = 0;

        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                float gray = (float)(0.299 * (double)(rgb >> 16 & 0xFF) + 0.587 * (double)(rgb >> 8 & 0xFF) + 0.114 * (double)(rgb & 0xFF));
                v[i++] = gray;
                mean += (double)gray;
            }
        }

        mean /= (double)v.length;

        for (int k = 0; k < v.length; k++) {
            v[k] = (float)((double)v[k] - mean);
        }

        return v;
    }

    private static int countTrue(boolean[] arr) {
        int n = 0;

        for (boolean b : arr) {
            if (b) {
                n++;
            }
        }

        return n;
    }

    /**
     * 上一帧网格拟合时的将帅证据数（0~2），越大越可信
     */
    public static int getLastKingEvidence() {
        return lastKingEvidence;
    }

    /**
     * 棋盘是否已判定为翻转（上方是红方）
     */
    public static boolean isFlipped() {
        return flippedKnown && flipped;
    }

    /**
     * 重置朝向判定（换棋盘/重连时调用）
     */
    public static void resetFlipLatch() {
        flipLatched = false;
        flipTentativeKnown = false;
        flipStreak = 0;
        flippedKnown = false;
        flipped = false;
    }

    /**
     * 上一帧拟合出的网格，可能为 null
     */
    public static JieqiBoardRecognizer.Grid getLastGrid() {
        return lastFitted;
    }

    /**
     * 手动校准网格：设定后同一尺寸的截图直接用这个网格，不再自动拟合
     */
    public static void setCalibratedGrid(double x0, double y0, double dx, double dy, int imgW, int imgH) {
        JieqiBoardRecognizer.Calibrated c = new JieqiBoardRecognizer.Calibrated();
        JieqiBoardRecognizer.Grid g = new JieqiBoardRecognizer.Grid();
        g.x0 = x0;
        g.y0 = y0;
        g.dx = dx;
        g.dy = dy;
        c.grid = g;
        c.w = imgW;
        c.h = imgH;
        calibrated = c;
        JieqiTrace.log("揭棋识别：已应用手动校准网格 " + g + "（窗口 " + imgW + "x" + imgH + "）");
    }

    /**
     * 是否已手动校准
     */
    public static boolean hasCalibration() {
        return calibrated != null;
    }

    public static JieqiBoardRecognizer.Grid fitGrid(BufferedImage img, Rectangle board) {
        if (calibrated != null && calibrated.w == img.getWidth() && calibrated.h == img.getHeight()) {
            return calibrated.grid;
        } else {
            if (board != null && board.width > 0) {
                lastBoardRect = board;
            }

            JieqiBoardRecognizer.Grid byPieces = fitGridByPieces(img, board);
            if (byPieces != null) {
                return byPieces;
            } else {
                JieqiBoardRecognizer.Grid prev = lastFitted;
                if (prev != null && prev.dx > 0.0 && lastFittedW == img.getWidth() && lastFittedH == img.getHeight() && gridStillValid(img, prev)) {
                    JieqiTrace.log("揭棋识别：本帧棋子太少，沿用上一帧网格 " + prev);
                    return prev;
                } else {
                    BufferedImage crop = img;
                    int W = img.getWidth();
                    int H = img.getHeight();
                    if (W >= 200 && H >= 200) {
                        double[] gx = new double[W];
                        double[] gy = new double[H];

                        for (int y = 0; y < H; y++) {
                            for (int x = 1; x < W; x++) {
                                gx[x] += (double)Math.abs(gray(crop, x, y) - gray(crop, x - 1, y));
                            }
                        }

                        for (int x = 0; x < W; x++) {
                            for (int y = 1; y < H; y++) {
                                gy[y] += (double)Math.abs(gray(crop, x, y) - gray(crop, x, y - 1));
                            }
                        }

                        double dx = bestPeriod(gx, 55.0, 135.0);
                        double dy = bestPeriod(gy, 55.0, 135.0);
                        if (!(dx <= 0.0) && !(dy <= 0.0)) {
                            JieqiBoardRecognizer.Grid grid = new JieqiBoardRecognizer.Grid();
                            grid.dx = dx;
                            grid.dy = dy;
                            double bestScore = -1.0;

                            for (double ox = 0.0; ox < dx; ox += dx / 10.0) {
                                for (double oy = 0.0; oy < dy; oy += dy / 10.0) {
                                    double s = contentScore(crop, ox, oy, dx, dy);
                                    if (s > bestScore) {
                                        bestScore = s;
                                        grid.x0 = ox;
                                        grid.y0 = oy;
                                    }
                                }
                            }

                            double rx = grid.x0;
                            double ry = grid.y0;
                            double rxScore = bestScore;

                            for (double ox = grid.x0 - dx / 10.0; ox <= grid.x0 + dx / 10.0; ox += dx / 40.0) {
                                for (double oyx = grid.y0 - dy / 10.0; oyx <= grid.y0 + dy / 10.0; oyx += dy / 40.0) {
                                    double s = contentScore(crop, ox, oyx, dx, dy);
                                    if (s > rxScore) {
                                        rxScore = s;
                                        rx = ox;
                                        ry = oyx;
                                    }
                                }
                            }

                            grid.x0 = rx;
                            grid.y0 = ry;
                            int globalKings = kingEvidence(img, grid.x0, grid.y0, grid.dx, grid.dy, JieqiPieceDetector.get().detect(img));
                            if (globalKings < 2) {
                                if (prev != null && lastFittedW == img.getWidth() && lastFittedH == img.getHeight() && gridStillValid(img, prev)) {
                                    JieqiTrace.log("揭棋识别：全局拟合没过将帅校验（将帅=" + globalKings + "），沿用上一帧网格 " + prev);
                                    return prev;
                                }

                                JieqiTrace.log("揭棋识别：全局拟合没过将帅校验（将帅=" + globalKings + "），本帧结果不可靠");
                            }

                            if (prev != null
                                && lastFittedW == img.getWidth()
                                && lastFittedH == img.getHeight()
                                && gridStillValid(img, prev)
                                && alignedCount(img, prev) >= alignedCount(img, grid)) {
                                JieqiTrace.log("揭棋识别：全局拟合不如上一帧网格，沿用 " + prev);
                                return prev;
                            } else {
                                JieqiTrace.log("揭棋识别：网格拟合 " + grid + " 分数=" + String.format("%.0f", rxScore));
                                return grid;
                            }
                        } else {
                            return null;
                        }
                    } else {
                        return null;
                    }
                }
            }
        }
    }

    private static boolean gridStillValid(BufferedImage img, JieqiBoardRecognizer.Grid g) {
        int total = 0;
        int ok = 0;
        List<Double> res = new ArrayList<>();

        for (Rectangle r : JieqiPieceDetector.get().detect(img)) {
            double x = (double)r.x + (double)r.width / 2.0;
            double y = (double)r.y + (double)r.height / 2.0;
            double kx = (double)Math.round((x - g.x0) / g.dx);
            double ky = (double)Math.round((y - g.y0) / g.dy);
            if (!(kx < 0.0) && !(kx > 8.0) && !(ky < 0.0) && !(ky > 9.0)) {
                total++;
                double ex = Math.abs(x - (g.x0 + kx * g.dx));
                double ey = Math.abs(y - (g.y0 + ky * g.dy));
                res.add(Math.max(ex, ey));
                if (ex < g.dx * 0.25 && ey < g.dy * 0.25) {
                    ok++;
                }
            }
        }

        if (total >= 4 && !((double)ok < Math.max(4.0, (double)total * 0.7))) {
            res.sort(Double::compareTo);
            return res.get(res.size() / 2) < Math.max(g.dx, g.dy) * 0.2;
        } else {
            return false;
        }
    }

    private static int alignedCount(BufferedImage img, JieqiBoardRecognizer.Grid g) {
        int n = 0;

        for (Rectangle r : JieqiPieceDetector.get().detect(img)) {
            double x = (double)r.x + (double)r.width / 2.0;
            double y = (double)r.y + (double)r.height / 2.0;
            double kx = (double)Math.round((x - g.x0) / g.dx);
            double ky = (double)Math.round((y - g.y0) / g.dy);
            if (!(kx < 0.0)
                && !(kx > 8.0)
                && !(ky < 0.0)
                && !(ky > 9.0)
                && Math.abs(x - (g.x0 + kx * g.dx)) < g.dx * 0.25
                && Math.abs(y - (g.y0 + ky * g.dy)) < g.dy * 0.25) {
                n++;
            }
        }

        return n;
    }

    private static int gray(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        return (int)(0.299 * (double)(rgb >> 16 & 0xFF) + 0.587 * (double)(rgb >> 8 & 0xFF) + 0.114 * (double)(rgb & 0xFF));
    }

    public static JieqiBoardRecognizer.Grid fitGridByPieces(BufferedImage img, Rectangle board) {
        try {
            if (JieqiPieceDetector.get().ready()) {
                List<Rectangle> boxes = JieqiPieceDetector.get().detect(img);
                JieqiBoardRecognizer.Grid g = gridFromBoxes(boxes, img, "jieqi_ai检测器");
                if (g != null) {
                    return g;
                }
            }

            if (cachedAi == null) {
                try {
                    cachedAi = new Yolo11Model();
                } catch (Throwable e) {
                }
            }

            if (cachedVin == null) {
                try {
                    cachedVin = new VinYolo5Model();
                } catch (Throwable e) {
                }
            }

            List<Rectangle> boxes = new ArrayList<>();
            if (cachedAi instanceof Yolo5Model) {
                boxes.addAll(((Yolo5Model)cachedAi).detectPieceBoxes(img));
            }

            if (cachedVin instanceof Yolo5Model) {
                boxes.addAll(((Yolo5Model)cachedVin).detectPieceBoxes(img));
            }

            return boxes.size() < 10 ? null : gridFromBoxes(boxes, img, "本机YOLO");
        } catch (Throwable e) {
            JieqiTrace.log("揭棋识别：按棋子拟合失败 " + e);
            return null;
        }
    }

    private static JieqiBoardRecognizer.Grid gridFromBoxes(List<Rectangle> boxes, BufferedImage img, String tag) {
        if (boxes != null && boxes.size() >= 8) {
            try {
                double[] med = new double[2];
                List<Rectangle> keep = sizeFilter(boxes, med);
                if (keep.size() < 8) {
                    return null;
                } else {
                    double[] cx = new double[keep.size()];
                    double[] cy = new double[keep.size()];

                    for (int i = 0; i < keep.size(); i++) {
                        cx[i] = (double)keep.get(i).x + (double)keep.get(i).width / 2.0;
                        cy[i] = (double)keep.get(i).y + (double)keep.get(i).height / 2.0;
                    }

                    double dx = periodByVotes(cx, med[0] * 0.85, med[0] * 1.45, med[0] * 1.12);
                    double dy = periodByVotes(cy, med[1] * 0.75, med[1] * 1.25, med[1] * 0.98);
                    if (!(dx < 25.0) && !(dx > 200.0) && !(dy < 25.0) && !(dy > 200.0)) {
                        double[] prior = priorBoardCenter(img);
                        double priorW = prior != null && prior.length > 2 ? prior[2] : 0.0;
                        if (priorW >= 4.0 && prior != null) {
                            JieqiBoardRecognizer.Grid pg = new JieqiBoardRecognizer.Grid();
                            pg.dx = dx;
                            pg.dy = dy;
                            pg.x0 = prior[0] - 4.0 * dx;
                            pg.y0 = prior[1] - 4.5 * dy;
                            double[] ax = refineAxisFree(cx, pg.x0, pg.dx, 9);
                            double[] ay = refineAxisFree(cy, pg.y0, pg.dy, 10);
                            pg.x0 = ax[0];
                            pg.dx = ax[1];
                            pg.y0 = ay[0];
                            pg.dy = ay[1];
                            double bx = gridResidual(cx, pg.x0, pg.dx, 9);
                            double by = gridResidual(cy, pg.y0, pg.dy, 10);
                            int bk = kingEvidence(img, pg.x0, pg.y0, pg.dx, pg.dy, keep);
                            if (bx >= 0.0 && by >= 0.0 && bx < 8.0 && by < 8.0) {
                                lastKingEvidence = bk;
                                JieqiTrace.log(
                                    "揭棋识别："
                                        + tag
                                        + " 棋盘框定网格 "
                                        + pg
                                        + "（框 "
                                        + keep.size()
                                        + "/"
                                        + boxes.size()
                                        + " 残差="
                                        + fmt(bx)
                                        + "/"
                                        + fmt(by)
                                        + " 将帅="
                                        + bk
                                        + " 先验中心="
                                        + fmt(prior[0])
                                        + ","
                                        + fmt(prior[1])
                                        + "）"
                                );
                                return pg;
                            }

                            JieqiTrace.log("揭棋识别：" + tag + " 棋盘框定网格残差偏大（" + fmt(bx) + "/" + fmt(by) + "），改用周期投票定相位");
                        }

                        double px = phaseByVotes(cx, dx);
                        double py = phaseByVotes(cy, dy);
                        int n = keep.size();
                        int[] qx = new int[n];
                        int[] qy = new int[n];

                        for (int i = 0; i < n; i++) {
                            qx[i] = (int)Math.round((cx[i] - px) / dx);
                            qy[i] = (int)Math.round((cy[i] - py) / dy);
                        }

                        int minIX = Integer.MAX_VALUE;
                        int maxIX = Integer.MIN_VALUE;
                        int minIY = Integer.MAX_VALUE;
                        int maxIY = Integer.MIN_VALUE;

                        for (int i = 0; i < n; i++) {
                            minIX = Math.min(minIX, qx[i]);
                            maxIX = Math.max(maxIX, qx[i]);
                            minIY = Math.min(minIY, qy[i]);
                            maxIY = Math.max(maxIY, qy[i]);
                        }

                        double pIx = Double.NaN;
                        double pIy = Double.NaN;
                        if (prior != null) {
                            pIx = (prior[0] - px) / dx - 4.0;
                            pIy = (prior[1] - py) / dy - 4.5;
                        }

                        int bestX0 = minIX;
                        int bestY0 = minIY;
                        double bestScore = -Double.MAX_VALUE;

                        for (int x0 = minIX - 8; x0 <= maxIX; x0++) {
                            double sx = 0.0;
                            if (!Double.isNaN(pIx)) {
                                sx = priorW * Math.max(0.0, 1.0 - Math.abs((double)x0 - pIx) / 2.0);
                            }

                            for (int y0 = minIY - 9; y0 <= maxIY; y0++) {
                                int cnt = 0;

                                for (int i = 0; i < n; i++) {
                                    int c = qx[i] - x0;
                                    int r = qy[i] - y0;
                                    if (c >= 0 && c < 9 && r >= 0 && r < 10) {
                                        cnt++;
                                    }
                                }

                                double score = (double)cnt + sx;
                                if (!Double.isNaN(pIy)) {
                                    score += priorW * Math.max(0.0, 1.0 - Math.abs((double)y0 - pIy) / 2.0);
                                }

                                if (score > bestScore) {
                                    bestScore = score;
                                    bestX0 = x0;
                                    bestY0 = y0;
                                }
                            }
                        }

                        JieqiBoardRecognizer.Grid g = new JieqiBoardRecognizer.Grid();
                        g.dx = dx;
                        g.dy = dy;
                        g.x0 = px + (double)bestX0 * dx;
                        g.y0 = py + (double)bestY0 * dy;
                        int bestKing = kingEvidence(img, g.x0, g.y0, dx, dy, keep);

                        for (int ox = -1; ox <= 1; ox++) {
                            for (int oy = -1; oy <= 1; oy++) {
                                if (ox != 0 || oy != 0) {
                                    double tx0 = px + (double)(bestX0 + ox) * dx;
                                    double ty0 = py + (double)(bestY0 + oy) * dy;
                                    int k = kingEvidence(img, tx0, ty0, dx, dy, keep);
                                    if (k > bestKing) {
                                        bestKing = k;
                                        g.x0 = tx0;
                                        g.y0 = ty0;
                                    }
                                }
                            }
                        }

                        double[] rx = refineAxisFree(cx, g.x0, g.dx, 9);
                        double[] ry = refineAxisFree(cy, g.y0, g.dy, 10);
                        g.x0 = rx[0];
                        g.dx = rx[1];
                        g.y0 = ry[0];
                        g.dy = ry[1];
                        double ex = gridResidual(cx, g.x0, g.dx, 9);
                        double ey = gridResidual(cy, g.y0, g.dy, 10);
                        if (lastFitted != null && (ex > 8.0 || ey > 8.0)) {
                            double lx = gridResidual(cx, lastFitted.x0, lastFitted.dx, 9);
                            double ly = gridResidual(cy, lastFitted.y0, lastFitted.dy, 10);
                            if (lx >= 0.0 && ly >= 0.0 && lx < 6.0 && ly < 6.0) {
                                JieqiTrace.log("揭棋识别：" + tag + " 本帧拟合不佳（残差 " + fmt(ex) + "/" + fmt(ey) + "），沿用上一帧网格 " + lastFitted);
                                return lastFitted;
                            }
                        }

                        lastKingEvidence = bestKing;
                        JieqiTrace.log(
                            "揭棋识别："
                                + tag
                                + " 周期投票网格 "
                                + g
                                + "（框 "
                                + keep.size()
                                + "/"
                                + boxes.size()
                                + " 得分="
                                + Math.round(bestScore)
                                + " 残差="
                                + fmt(ex)
                                + "/"
                                + fmt(ey)
                                + " 将帅="
                                + bestKing
                                + " 先验中心="
                                + (prior == null ? "无" : fmt(prior[0]) + "," + fmt(prior[1]))
                                + "）"
                        );
                        return g;
                    } else {
                        JieqiTrace.log("揭棋识别：" + tag + " 格距投票失败 dx=" + fmt(dx) + " dy=" + fmt(dy));
                        return null;
                    }
                }
            } catch (Throwable e) {
                JieqiTrace.log("揭棋识别：" + tag + " 拟合异常 " + e);
                return null;
            }
        } else {
            return null;
        }
    }

    private static int kingEvidence(BufferedImage img, double x0, double y0, double dx, double dy, List<Rectangle> boxes) {
        int n = 0;
        if (revealedInPalace(img, x0, y0, dx, dy, boxes, 0)) {
            n++;
        }

        if (revealedInPalace(img, x0, y0, dx, dy, boxes, 7)) {
            n++;
        }

        return n;
    }

    private static boolean revealedInPalace(BufferedImage img, double x0, double y0, double dx, double dy, List<Rectangle> boxes, int firstRow) {
        for (int r = firstRow; r < firstRow + 3; r++) {
            for (int c = 3; c <= 5; c++) {
                if (revealedPieceAt(img, x0 + (double)c * dx, y0 + (double)r * dy, dx, dy, boxes)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean revealedPieceAt(BufferedImage img, double cx, double cy, double dx, double dy, List<Rectangle> boxes) {
        for (Rectangle r : boxes) {
            double bx = (double)r.x + (double)r.width / 2.0;
            double by = (double)r.y + (double)r.height / 2.0;
            if (!(Math.abs(bx - cx) > dx * 0.35) && !(Math.abs(by - cy) > dy * 0.35)) {
                int half = Math.max(4, Math.min(r.width, r.height) / 3);
                int x1 = (int)Math.round(bx) - half;
                int y1 = (int)Math.round(by) - half;
                int x2 = x1 + half * 2;
                int y2 = y1 + half * 2;
                if (x1 >= 0 && y1 >= 0 && x2 < img.getWidth() && y2 < img.getHeight()) {
                    return grayStd(img.getSubimage(x1, y1, x2 - x1, y2 - y1)) >= 45.0;
                }
            }
        }

        return false;
    }

    private static double[] refineAxisFree(double[] v, double origin, double period, int count) {
        double sxx = 0.0;
        double sx = 0.0;
        double sy = 0.0;
        double sxy = 0.0;
        int n = 0;

        for (double x : v) {
            double k = (double)Math.round((x - origin) / period);
            if (!(k < 0.0) && !(k > (double)(count - 1))) {
                double e = Math.abs(x - (origin + k * period));
                if (!(e > period * 0.3)) {
                    sx += k;
                    sy += x;
                    sxx += k * k;
                    sxy += k * x;
                    n++;
                }
            }
        }

        if (n < 5) {
            return new double[]{origin, period};
        } else {
            double den = (double)n * sxx - sx * sx;
            if (Math.abs(den) < 1.0E-6) {
                return new double[]{origin, period};
            } else {
                double slope = ((double)n * sxy - sx * sy) / den;
                double intercept = (sy - slope * sx) / (double)n;
                return !(slope < period * 0.85) && !(slope > period * 1.15) ? new double[]{intercept, slope} : new double[]{origin, period};
            }
        }
    }

    private static double gridResidual(double[] v, double origin, double period, int count) {
        List<Double> res = new ArrayList<>();

        for (double x : v) {
            double k = (double)Math.round((x - origin) / period);
            if (!(k < 0.0) && !(k > (double)(count - 1))) {
                res.add(Math.abs(x - (origin + k * period)));
            }
        }

        if (res.isEmpty()) {
            return -1.0;
        } else {
            res.sort(Double::compareTo);
            return res.get(res.size() / 2);
        }
    }

    private static double[] refineAxis(int[] q, double[] v, double origin, double period, int idx0, int count) {
        double sxx = 0.0;
        double sx = 0.0;
        double sy = 0.0;
        double sxy = 0.0;
        int n = 0;

        for (int i = 0; i < v.length; i++) {
            int k = q[i] - idx0;
            if (k >= 0 && k < count && !(Math.abs(v[i] - (origin + (double)k * period)) > period * 0.3)) {
                sx += (double)k;
                sy += v[i];
                sxx += (double)k * (double)k;
                sxy += (double)k * v[i];
                n++;
            }
        }

        if (n < 5) {
            return new double[]{origin, period};
        } else {
            double den = (double)n * sxx - sx * sx;
            if (Math.abs(den) < 1.0E-6) {
                return new double[]{origin, period};
            } else {
                double slope = ((double)n * sxy - sx * sy) / den;
                double intercept = (sy - slope * sx) / (double)n;
                return !(slope < period * 0.85) && !(slope > period * 1.15) ? new double[]{intercept, slope} : new double[]{origin, period};
            }
        }
    }

    private static double residualMedian(int[] q, double[] v, double origin, double period, int idx0, int count) {
        List<Double> res = new ArrayList<>();

        for (int i = 0; i < v.length; i++) {
            int k = q[i] - idx0;
            if (k >= 0 && k < count) {
                res.add(Math.abs(v[i] - (origin + (double)k * period)));
            }
        }

        if (res.isEmpty()) {
            return -1.0;
        } else {
            res.sort(Double::compareTo);
            return res.get(res.size() / 2);
        }
    }

    private static List<Rectangle> sizeFilter(List<Rectangle> boxes, double[] med) {
        List<Rectangle> cur = new ArrayList<>(boxes);

        for (int pass = 0; pass < 2 && cur.size() >= 8; pass++) {
            List<Integer> ws = new ArrayList<>();
            List<Integer> hs = new ArrayList<>();

            for (Rectangle r : cur) {
                ws.add(r.width);
                hs.add(r.height);
            }

            ws.sort(Integer::compareTo);
            hs.sort(Integer::compareTo);
            int mw = ws.get(ws.size() / 2);
            int mh = hs.get(hs.size() / 2);
            med[0] = (double)mw;
            med[1] = (double)mh;
            List<Rectangle> next = new ArrayList<>();

            for (Rectangle r : cur) {
                if ((double)r.width >= (double)mw * 0.72
                    && (double)r.width <= (double)mw * 1.32
                    && (double)r.height >= (double)mh * 0.72
                    && (double)r.height <= (double)mh * 1.32) {
                    next.add(r);
                }
            }

            if (next.size() >= 8) {
                cur = next;
            }
        }

        return cur;
    }

    private static double periodByVotes(double[] v, double lo, double hi, double prior) {
        if (v.length < 4) {
            return -1.0;
        } else {
            double best = -1.0;
            double bestScore = -Double.MAX_VALUE;

            for (double t = Math.max(20.0, lo); t <= Math.min(220.0, hi); t += 0.25) {
                int cnt = 0;

                for (int i = 0; i < v.length; i++) {
                    for (int j = i + 1; j < v.length; j++) {
                        double d = Math.abs(v[i] - v[j]);
                        double k = (double)Math.round(d / t);
                        if (k >= 1.0 && Math.abs(d - k * t) < t * 0.12) {
                            cnt++;
                        }
                    }
                }

                double score = (double)cnt * 100.0 - Math.abs(t - prior);
                if (score > bestScore) {
                    bestScore = score;
                    best = t;
                }
            }

            return best;
        }
    }

    private static double phaseByVotes(double[] v, double t) {
        double best = 0.0;
        int bestCnt = -1;

        for (double p = 0.0; p < t; p += 0.5) {
            int cnt = 0;

            for (double x : v) {
                double f = ((x - p) % t + t) % t;
                if (Math.min(f, t - f) < t * 0.22) {
                    cnt++;
                }
            }

            if (cnt > bestCnt) {
                bestCnt = cnt;
                best = p;
            }
        }

        return best;
    }

    private static double[] priorBoardCenter(BufferedImage img) {
        Rectangle b = lastBoardRect;
        if (b != null
            && (double)b.width > (double)img.getWidth() * 0.45
            && (double)b.height > (double)img.getHeight() * 0.4
            && (double)b.height < (double)img.getHeight() * 0.9) {
            return new double[]{(double)b.x + (double)b.width / 2.0, (double)b.y + (double)b.height / 2.0, 5.0};
        } else {
            double[] f = frameLineCenter(img);
            if (f != null) {
                return f;
            } else {
                return b != null && b.width > 40 && b.height > 40 && (double)b.height < (double)img.getHeight() * 0.9
                    ? new double[]{(double)b.x + (double)b.width / 2.0, (double)b.y + (double)b.height / 2.0, 3.0}
                    : new double[]{(double)img.getWidth() / 2.0, (double)img.getHeight() / 2.0, 2.5};
            }
        }
    }

    private static double[] frameLineCenter(BufferedImage img) {
        double[] h = frameLines(img, true);
        double[] v = frameLines(img, false);
        if (h == null && v == null) {
            return null;
        } else {
            double cy = h == null ? (double)img.getHeight() / 2.0 : (h[0] + h[1]) / 2.0;
            double cx = v == null ? (double)img.getWidth() / 2.0 : (v[0] + v[1]) / 2.0;
            return new double[]{cx, cy, 4.0};
        }
    }

    private static double[] frameLines(BufferedImage img, boolean horizontal) {
        try {
            int W = img.getWidth();
            int H = img.getHeight();
            int n = horizontal ? H : W;
            int m = horizontal ? W : H;
            double[] score = new double[n];

            for (int a = 3; a < n; a += 2) {
                int cnt = 0;

                for (int b = 0; b < m; b += 2) {
                    int g1 = horizontal ? gray(img, b, a) : gray(img, a, b);
                    int g2 = horizontal ? gray(img, b, a - 3) : gray(img, a - 3, b);
                    if (Math.abs(g1 - g2) > 35) {
                        cnt++;
                    }
                }

                score[a] = (double)cnt;
            }

            List<Integer> idx = new ArrayList<>();

            for (int i = 0; i < n; i++) {
                idx.add(i);
            }

            idx.sort((a, bx) -> Double.compare(score[bx], score[a]));
            List<Integer> peaks = new ArrayList<>();

            for (int i : idx) {
                if (score[i] < (double)m * 0.35) {
                    break;
                }

                boolean far = true;

                for (int p : peaks) {
                    if (Math.abs(p - i) < 25) {
                        far = false;
                        break;
                    }
                }

                if (far) {
                    peaks.add(i);
                }

                if (peaks.size() >= 60) {
                    break;
                }
            }

            if (peaks.size() < 2) {
                return null;
            } else {
                peaks.sort(Integer::compareTo);
                return new double[]{(double)peaks.get(0).intValue(), (double)peaks.get(peaks.size() - 1).intValue()};
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }

    private static List<Rectangle> filterToBand(List<Rectangle> boxes, double dy) {
        List<Rectangle> out = new ArrayList<>();
        if (!boxes.isEmpty() && !(dy <= 0.0)) {
            double minY = Double.MAX_VALUE;
            double maxY = -1.0;

            for (Rectangle r : boxes) {
                double y = (double)r.y + (double)r.height / 2.0;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }

            double bandH = 9.0 * dy;
            if (maxY - minY <= bandH) {
                return boxes;
            } else {
                double bestY = minY;
                int bestCnt = -1;

                for (double y0 = minY; y0 <= maxY - bandH; y0 += 4.0) {
                    int cnt = 0;

                    for (Rectangle r : boxes) {
                        double y = (double)r.y + (double)r.height / 2.0;
                        if (y >= y0 - dy * 0.45 && y <= y0 + bandH + dy * 0.45) {
                            cnt++;
                        }
                    }

                    if (cnt > bestCnt) {
                        bestCnt = cnt;
                        bestY = y0;
                    }
                }

                for (Rectangle rx : boxes) {
                    double y = (double)rx.y + (double)rx.height / 2.0;
                    if (y >= bestY - dy * 0.45 && y <= bestY + bandH + dy * 0.45) {
                        out.add(rx);
                    }
                }

                JieqiTrace.log("揭棋识别：棋盘带筛选 y=" + Math.round(bestY) + "~" + Math.round(bestY + bandH) + "，框数 " + boxes.size() + " -> " + out.size());
                return out.size() >= 8 ? out : boxes;
            }
        } else {
            return boxes;
        }
    }

    private static JieqiBoardRecognizer.Grid anchorByBand(List<Rectangle> boxes, BufferedImage img, double dx, double dy, String tag) {
        try {
            if (boxes.size() < 10) {
                return null;
            } else {
                double minY = Double.MAX_VALUE;
                double maxY = -1.0;

                for (Rectangle r : boxes) {
                    double y = (double)r.y + (double)r.height / 2.0;
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }

                double top = minY;
                double bestScore = -1.0;

                for (double y0 = minY; y0 <= Math.max(minY, maxY - 9.0 * dy); y0 += 4.0) {
                    int aligned = 0;
                    Set<Integer> rows = new HashSet<>();

                    for (Rectangle r : boxes) {
                        double k = ((double)r.y + (double)r.height / 2.0 - y0) / dy;
                        int rr = (int)Math.round(k);
                        if (rr >= 0 && rr <= 9 && Math.abs(k - (double)rr) < 0.3) {
                            aligned++;
                            rows.add(rr);
                        }
                    }

                    double score = (double)aligned + (double)rows.size() * 1.5;
                    if (score > bestScore) {
                        bestScore = score;
                        top = y0;
                    }
                }

                double bottom = top + 9.0 * dy;
                double bestGap = -1.0;
                double[] up = null;
                double[] low = null;

                for (Rectangle a : boxes) {
                    double ax = (double)a.x + (double)a.width / 2.0;
                    double ay = (double)a.y + (double)a.height / 2.0;
                    if (!(ay < top - dy * 0.4) && !(ay > bottom + dy * 0.4)) {
                        for (Rectangle b : boxes) {
                            double bx = (double)b.x + (double)b.width / 2.0;
                            double by = (double)b.y + (double)b.height / 2.0;
                            if (!(by < top - dy * 0.4) && !(by > bottom + dy * 0.4) && !(by - ay < bestGap) && !(Math.abs(ax - bx) > dx * 0.35)) {
                                double gap = by - ay;
                                if (gap >= dy * 7.5 && gap <= dy * 10.5 && gap > bestGap) {
                                    bestGap = gap;
                                    up = new double[]{ax, ay};
                                    low = new double[]{bx, by};
                                }
                            }
                        }
                    }
                }

                if (up != null && low != null) {
                    JieqiBoardRecognizer.Grid g = new JieqiBoardRecognizer.Grid();
                    g.dx = dx;
                    g.dy = bestGap / 9.0;
                    g.x0 = (up[0] + low[0]) / 2.0 - 4.0 * dx;
                    g.y0 = up[1];
                    JieqiTrace.log(
                        "揭棋识别："
                            + tag
                            + " 棋盘带锚定网格 "
                            + g
                            + "（带 y="
                            + Math.round(top)
                            + "~"
                            + Math.round(bottom)
                            + " 将帅 y="
                            + Math.round(up[1])
                            + "/"
                            + Math.round(low[1])
                            + "）"
                    );
                    return g;
                } else {
                    return null;
                }
            }
        } catch (Throwable e) {
            JieqiTrace.log("揭棋识别：" + tag + " 棋盘带锚定异常 " + e);
            return null;
        }
    }

    private static double clusterGap(double[] vals, double tol) {
        if (vals.length < 4) {
            return -1.0;
        } else {
            double[] v = (double[])vals.clone();
            Arrays.sort(v);
            List<Double> centers = new ArrayList<>();
            double sum = v[0];
            int n = 1;

            for (int i = 1; i < v.length; i++) {
                if (v[i] - v[i - 1] <= tol) {
                    sum += v[i];
                    n++;
                } else {
                    centers.add(sum / (double)n);
                    sum = v[i];
                    n = 1;
                }
            }

            centers.add(sum / (double)n);
            if (centers.size() < 3) {
                return -1.0;
            } else {
                List<Double> diffs = new ArrayList<>();

                for (int ix = 1; ix < centers.size(); ix++) {
                    double d = centers.get(ix) - centers.get(ix - 1);
                    if (d > 30.0) {
                        diffs.add(d);
                    }
                }

                if (diffs.isEmpty()) {
                    return -1.0;
                } else {
                    double min = diffs.stream().min(Double::compareTo).orElse(0.0);
                    List<Double> sub = new ArrayList<>();

                    for (double d : diffs) {
                        if (d >= min * 0.75 && d <= min * 1.4) {
                            sub.add(d);
                        }
                    }

                    sub.sort(Double::compareTo);
                    return sub.get(sub.size() / 2);
                }
            }
        }
    }

    private static double groupInnerGap(double[] main, double[] group, double tol) {
        int n = main.length;
        if (n < 4) {
            return -1.0;
        } else {
            Integer[] order = new Integer[n];

            for (int i = 0; i < n; i++) {
                order[i] = i;
            }

            Arrays.sort(order, (a, b) -> Double.compare(group[a], group[b]));
            List<Double> gaps = new ArrayList<>();
            int i = 0;

            while (i < n) {
                int j = i;

                List<Double> vals;
                for (vals = new ArrayList<>(); j < n && (j == i || group[order[j]] - group[order[j - 1]] <= tol); j++) {
                    vals.add(main[order[j]]);
                }

                if (vals.size() >= 2) {
                    vals.sort(Double::compareTo);

                    for (int k = 1; k < vals.size(); k++) {
                        double d = vals.get(k) - vals.get(k - 1);
                        if (d >= 50.0 && d <= 140.0) {
                            gaps.add(d);
                        }
                    }
                }

                i = j;
            }

            if (gaps.size() < 3) {
                return -1.0;
            } else {
                gaps.sort(Double::compareTo);
                return gaps.get(gaps.size() / 2);
            }
        }
    }

    private static double withinRowGap(double[] cx, double[] cy, double dy) {
        return groupedGap(cx, cy, dy, true);
    }

    private static double withinColumnGap(double[] cx, double[] cy, double dx) {
        return groupedGap(cy, cx, dx, false);
    }

    private static double groupedGap(double[] main, double[] group, double tol, boolean unusedFlag) {
        List<Double> gaps = new ArrayList<>();
        int n = main.length;

        for (int i = 0; i < n; i++) {
            double best = Double.MAX_VALUE;

            for (int j = 0; j < n; j++) {
                if (i != j && !(Math.abs(group[j] - group[i]) > tol * 0.4)) {
                    double d = Math.abs(main[j] - main[i]);
                    if (d > 8.0 && d < best) {
                        best = d;
                    }
                }
            }

            if (best < Double.MAX_VALUE) {
                gaps.add(best);
            }
        }

        if (gaps.size() < 4) {
            return -1.0;
        } else {
            List<Double> sub = new ArrayList<>();

            for (double d : gaps) {
                if (d >= 55.0 && d <= 130.0) {
                    sub.add(d);
                }
            }

            if (sub.size() < 4) {
                return -1.0;
            } else {
                sub.sort(Double::compareTo);
                return sub.get(sub.size() / 2);
            }
        }
    }

    private static JieqiBoardRecognizer.Grid anchorByKings(List<Rectangle> boxes, BufferedImage img, double dx, double dy, String tag) {
        try {
            if (boxes.size() < 8) {
                return null;
            } else {
                List<double[]> cand = new ArrayList<>();

                for (Rectangle r : boxes) {
                    int cx = r.x + r.width / 2;
                    int cy = r.y + r.height / 2;
                    int half = Math.max(4, Math.min(r.width, r.height) / 3);
                    if (cx - half >= 0 && cy - half >= 0 && cx + half < img.getWidth() && cy + half < img.getHeight()) {
                        BufferedImage cell = img.getSubimage(cx - half, cy - half, half * 2, half * 2);
                        cand.add(new double[]{(double)cx, (double)cy, grayStd(cell)});
                    }
                }

                cand.sort((ax, bx) -> Double.compare(bx[2], ax[2]));
                int top = Math.min(cand.size(), 8);
                double bestGap = -1.0;
                double[] upper = null;
                double[] lower = null;

                for (int i = 0; i < top; i++) {
                    for (int j = i + 1; j < top; j++) {
                        double[] a = cand.get(i);
                        double[] b = cand.get(j);
                        if (!(Math.abs(a[0] - b[0]) > dx * 0.35)) {
                            double gap = Math.abs(a[1] - b[1]);
                            if (gap > bestGap && gap >= dy * 7.5 && gap <= dy * 10.5) {
                                bestGap = gap;
                                upper = a[1] < b[1] ? a : b;
                                lower = a[1] < b[1] ? b : a;
                            }
                        }
                    }
                }

                if (upper != null && lower != null) {
                    JieqiBoardRecognizer.Grid g = new JieqiBoardRecognizer.Grid();
                    g.dx = dx;
                    g.dy = bestGap / 9.0;
                    g.x0 = (upper[0] + lower[0]) / 2.0 - 4.0 * dx;
                    g.y0 = upper[1];
                    JieqiTrace.log(
                        "揭棋识别："
                            + tag
                            + " 用双将帅锚定网格 "
                            + g
                            + "（将 y="
                            + Math.round(upper[1])
                            + " 帅 y="
                            + Math.round(lower[1])
                            + " 对比度 "
                            + Math.round(upper[2])
                            + "/"
                            + Math.round(lower[2])
                            + "）"
                    );
                    return g;
                } else {
                    return null;
                }
            }
        } catch (Throwable e) {
            JieqiTrace.log("揭棋识别：" + tag + " 将帅锚定异常 " + e);
            return null;
        }
    }

    private static double medianNeighborGap(double[] vals) {
        if (vals.length < 2) {
            return -1.0;
        } else {
            double[] v = (double[])vals.clone();
            Arrays.sort(v);
            List<Double> gaps = new ArrayList<>();

            for (int i = 1; i < v.length; i++) {
                double d = v[i] - v[i - 1];
                if (d > 8.0) {
                    gaps.add(d);
                }
            }

            if (gaps.size() < 3) {
                return -1.0;
            } else {
                List<Double> sub = new ArrayList<>();

                for (double d : gaps) {
                    if (d >= 55.0 && d <= 130.0) {
                        sub.add(d);
                    }
                }

                if (sub.isEmpty()) {
                    return -1.0;
                } else {
                    sub.sort(Double::compareTo);
                    return sub.get(sub.size() / 2);
                }
            }
        }
    }

    private static double bestPhase(double[] vals, double T) {
        double best = 0.0;
        int bestCount = -1;

        for (int k = 0; k < 40; k++) {
            double ph = T * (double)k / 40.0;
            int cnt = 0;

            for (double v : vals) {
                double d = ((v - ph) % T + T) % T;
                d = Math.min(d, T - d);
                if (d < T * 0.2) {
                    cnt++;
                }
            }

            if (cnt > bestCount) {
                bestCount = cnt;
                best = ph;
            }
        }

        return best;
    }

    private static int[] indices(double[] vals, double T, double phase) {
        int[] idx = new int[vals.length];

        for (int i = 0; i < vals.length; i++) {
            idx[i] = (int)Math.round((vals[i] - phase) / T);
        }

        return idx;
    }

    private static int bestWindow(int[] idx, int width) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int i : idx) {
            min = Math.min(min, i);
            max = Math.max(max, i);
        }

        int bestStart = min;
        int bestCount = -1;

        for (int s = min - 2; s <= max + 1; s++) {
            int cnt = 0;

            for (int i : idx) {
                if (i >= s && i < s + width) {
                    cnt++;
                }
            }

            if (cnt > bestCount) {
                bestCount = cnt;
                bestStart = s;
            }
        }

        return bestStart;
    }

    private static int grayUnused(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        return (int)(0.299 * (double)(rgb >> 16 & 0xFF) + 0.587 * (double)(rgb >> 8 & 0xFF) + 0.114 * (double)(rgb & 0xFF));
    }

    private static double bestPeriod(double[] profile, double lo, double hi) {
        double bestT = 0.0;
        double bestScore = -1.0;

        for (double T = lo; T <= hi; T++) {
            double sigma = T * 0.15;
            double best = -1.0;

            for (double ph = 0.0; ph < T; ph += 2.0) {
                double s = 0.0;

                for (int i = 0; i < profile.length; i++) {
                    double d = (((double)i - ph) % T + T) % T - T / 2.0;
                    s += profile[i] * Math.exp(-(d * d) / (2.0 * sigma * sigma));
                }

                if (s > best) {
                    best = s;
                }
            }

            if (best > bestScore) {
                bestScore = best;
                bestT = T;
            }
        }

        return bestT;
    }

    private static double contentScore(BufferedImage crop, double ox, double oy, double dx, double dy) {
        double sum = 0.0;
        int n = 0;
        int outside = 0;

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                int cx = (int)Math.round(ox + (double)c * dx);
                int cy = (int)Math.round(oy + (double)r * dy);
                int half = (int)Math.max(6.0, Math.min(dx, dy) * 0.31);
                if (cx - half >= 0 && cy - half >= 0 && cx + half < crop.getWidth() && cy + half < crop.getHeight()) {
                    BufferedImage cell = crop.getSubimage(cx - half, cy - half, half * 2, half * 2);
                    sum += centerVsCorner(cell);
                    n++;
                } else {
                    outside++;
                }
            }
        }

        return outside <= 0 && n != 0 ? sum / (double)n : -1.0;
    }

    /**
     * 找棋盘框：主模型优先，其次 Vin 模型，都没有就用上一帧或按经验比例估算
     */
    public static Rectangle detectBoard(BufferedImage img, OnnxModel ai, OnnxModel vin) {
        try {
            if (ai != null) {
                Rectangle r = ai.findBoardPosition(img);
                if (r != null && r.width > 100 && r.height > 100) {
                    lastBoardPos = r;
                    JieqiTrace.log("揭棋连线：YOLO(主) 找到棋盘框 " + fmt(r));
                    return r;
                }
            }
        } catch (Exception e) {
            JieqiTrace.log("揭棋连线：主模型找棋盘异常 " + e);
        }

        try {
            if (vin != null) {
                Rectangle r = vin.findBoardPosition(img);
                if (r != null && r.width > 100 && r.height > 100) {
                    lastBoardPos = r;
                    JieqiTrace.log("揭棋连线：YOLO(Vin) 找到棋盘框 " + fmt(r));
                    return r;
                }
            }
        } catch (Exception e) {
            JieqiTrace.log("揭棋连线：Vin 模型找棋盘异常 " + e);
        }

        if (lastBoardPos != null && lastBoardPos.width > 100) {
            return lastBoardPos;
        } else {
            Rectangle r = new Rectangle(
                (int)((double)img.getWidth() * 0.02),
                (int)((double)img.getHeight() * 0.18),
                (int)((double)img.getWidth() * 0.96),
                (int)((double)img.getHeight() * 0.6)
            );
            JieqiTrace.log("揭棋连线：模型没找到棋盘，按经验比例估算 " + fmt(r));
            return r;
        }
    }

    private static String fmt(Rectangle r) {
        return r.x + "," + r.y + "," + r.width + "," + r.height;
    }

    /**
     * 把当前帧的全图/棋盘/叠加图/格子特征导出到 log/jieqi_debug，用于排查识别问题
     */
    public static void debugDump(BufferedImage img, Rectangle board, int frame) {
        try {
            File dir = new File(PathUtils.getJarPath() + "log/jieqi_debug");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            ImageIO.write(img, "png", new File(dir, "full_" + frame + ".png"));
            Rectangle b = clamp(board, img);
            ImageIO.write(img.getSubimage(b.x, b.y, b.width, b.height), "png", new File(dir, "board_" + frame + ".png"));
            drawOverlay(img, dir, frame);
            List<JieqiBoardRecognizer.CellFeature> feats = new ArrayList<>();
            recognize(img, board, null, null, feats);

            try (PrintWriter w = new PrintWriter(new FileWriter(new File(dir, "cells_" + frame + ".csv")))) {
                w.println("row,col,meanR,meanG,meanB,grayStd,bgDist,centerCorner,bestTemplate,bestScore,decision");

                for (JieqiBoardRecognizer.CellFeature f : feats) {
                    w.printf(
                        "%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%s,%.3f,%s%n",
                        f.row,
                        f.col,
                        f.meanR,
                        f.meanG,
                        f.meanB,
                        f.grayStd,
                        f.bgDist,
                        f.centerCorner,
                        f.bestTemplate,
                        f.bestScore,
                        f.decision
                    );
                }
            }

            JieqiTrace.log("揭棋连线：已导出调试帧 " + frame + " 到 log/jieqi_debug/");
            trimDebugFrames(dir, 24);
        } catch (Exception e) {
            JieqiTrace.log("揭棋连线：调试导出失败 " + e);
        }
    }

    private static void trimDebugFrames(File dir, int keep) {
        try {
            File[] files = dir.listFiles((d, n) -> n.startsWith("full_") && n.endsWith(".png"));
            if (files == null || files.length <= keep) {
                return;
            }

            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            int drop = files.length - keep;

            for (int i = 0; i < drop; i++) {
                String name = files[i].getName();
                String idx = name.substring("full_".length(), name.length() - ".png".length());

                for (String prefix : new String[]{"full_", "board_", "cells_", "overlay_"}) {
                    File f = new File(dir, prefix + idx + (prefix.equals("cells_") ? ".csv" : ".png"));
                    if (f.exists()) {
                        f.delete();
                    }
                }
            }
        } catch (Throwable e) {
        }
    }

    private static void drawOverlay(BufferedImage img, File dir, int frame) {
        JieqiBoardRecognizer.Grid g = lastFitted;
        char[][] grid = lastScreenGrid;
        if (g != null && grid != null && !(g.dx <= 0.0)) {
            try {
                BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), 1);
                Graphics2D gc = copy.createGraphics();
                gc.drawImage(img, 0, 0, null);
                gc.setFont(new Font("Dialog", 1, 26));

                for (int r = 0; r < 10; r++) {
                    for (int c = 0; c < 9; c++) {
                        int cx = (int)Math.round(g.x0 + (double)c * g.dx);
                        int cy = (int)Math.round(g.y0 + (double)r * g.dy);
                        gc.setColor(new Color(255, 60, 60, 220));
                        gc.setStroke(new BasicStroke(2.0F));
                        gc.drawRect(cx - 14, cy - 14, 28, 28);
                        char ch = grid[r][c];
                        String s = String.valueOf(ch == ' ' ? '.' : ch);
                        gc.setColor(new Color(0, 0, 0, 200));
                        gc.drawString(s, cx - 8, cy + 10);
                        gc.setColor(new Color(255, 255, 0, 230));
                        gc.drawString(s, cx - 9, cy + 9);
                    }
                }

                gc.dispose();
                ImageIO.write(copy, "png", new File(dir, "overlay_" + frame + ".png"));
            } catch (Throwable e) {
                JieqiTrace.log("揭棋连线：叠加图导出失败 " + e);
            }
        }
    }

    private static BufferedImage cellImage(BufferedImage img, Rectangle boardPos, double pw, double ph, double boxW, double boxH, int r, int c) {
        int cx = (int)Math.round((double)boardPos.x + (0.8 + (double)c) * pw);
        int cy = (int)Math.round((double)boardPos.y + (0.8 + (double)r) * ph);
        return cellAt(img, cx, cy, boxW, boxH);
    }

    private static BufferedImage cellAt(BufferedImage img, int cx, int cy, double boxW, double boxH) {
        Rectangle inner = clamp(
            new Rectangle((int)Math.round((double)cx - boxW / 2.0), (int)Math.round((double)cy - boxH / 2.0), (int)Math.round(boxW), (int)Math.round(boxH)), img
        );
        return img.getSubimage(inner.x, inner.y, inner.width, inner.height);
    }

    public static char[][] recognize(BufferedImage img, Rectangle board, List<JieqiBoardRecognizer.CellFeature> features, File dumpDir) {
        try {
            loadTemplates();
        } catch (Exception e) {
            JieqiTrace.log("模板加载失败: " + e);
        }

        char[][] grid = new char[10][9];
        double pw = (double)board.width / 9.6;
        double ph = (double)board.height / 10.6;
        double boxW = Math.max(8.0, pw * 0.62);
        double boxH = Math.max(8.0, ph * 0.62);
        if (dumpDir != null && !dumpDir.exists()) {
            dumpDir.mkdirs();
        }

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                int cx = (int)Math.round((double)board.x + (0.8 + (double)c) * pw);
                int cy = (int)Math.round((double)board.y + (0.8 + (double)r) * ph);
                Rectangle inner = clamp(
                    new Rectangle((int)Math.round((double)cx - boxW / 2.0), (int)Math.round((double)cy - boxH / 2.0), (int)Math.round(boxW), (int)Math.round(boxH)),
                    img
                );
                BufferedImage cell = img.getSubimage(inner.x, inner.y, inner.width, inner.height);
                JieqiBoardRecognizer.CellFeature f = new JieqiBoardRecognizer.CellFeature();
                f.row = r;
                f.col = c;
                double[] mean = meanColor(cell);
                f.meanR = mean[0];
                f.meanG = mean[1];
                f.meanB = mean[2];
                f.grayStd = grayStd(cell);
                f.bgDist = colorDistance(mean, backgroundColor(img, cx, cy, pw, ph));
                float[] cellNcc = grayNcc(resize(cell, 48, 48));

                for (Entry<Character, float[]> e : templates.entrySet()) {
                    double score = ncc(cellNcc, e.getValue());
                    if (score > f.bestScore) {
                        f.bestScore = score;
                        f.bestTemplate = e.getKey();
                    }
                }

                f.decision = decide(f);
                grid[r][c] = f.decision;
                if (features != null) {
                    features.add(f);
                }

                if (dumpDir != null) {
                    try {
                        ImageIO.write(
                            cell, "png", new File(dumpDir, String.format("cell_%d_%d_%s.png", r, c, f.decision == '.' ? "empty" : String.valueOf(f.decision)))
                        );
                    } catch (Exception e) {
                    }
                }
            }
        }

        return grid;
    }

    private static char decide(JieqiBoardRecognizer.CellFeature f) {
        if (f.bgDist < 20.0) {
            return '.';
        } else if (f.bestScore >= 0.55) {
            return f.bestTemplate;
        } else {
            return (char)(f.meanR - f.meanB > 25.0 ? 'X' : 'x');
        }
    }

    private static Rectangle clamp(Rectangle rect, BufferedImage img) {
        int x = Math.min(Math.max(0, rect.x), img.getWidth() - 1);
        int y = Math.min(Math.max(0, rect.y), img.getHeight() - 1);
        int w = Math.max(1, Math.min(rect.width, img.getWidth() - x));
        int h = Math.max(1, Math.min(rect.height, img.getHeight() - y));
        return new Rectangle(x, y, w, h);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, 1);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static double[] meanColor(BufferedImage img) {
        double r = 0.0;
        double g = 0.0;
        double b = 0.0;
        int n = 0;

        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                r += (double)(rgb >> 16 & 0xFF);
                g += (double)(rgb >> 8 & 0xFF);
                b += (double)(rgb & 0xFF);
                n++;
            }
        }

        return new double[]{r / (double)n, g / (double)n, b / (double)n};
    }

    private static double colorDistance(double[] a, double[] b) {
        double dr = a[0] - b[0];
        double dg = a[1] - b[1];
        double db = a[2] - b[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static double centerVsCorner(BufferedImage cell) {
        int w = cell.getWidth();
        int h = cell.getHeight();
        double[] center = meanColor(
            sub(cell, (int)((double)w * 0.35), (int)((double)h * 0.35), Math.max(1, (int)((double)w * 0.3)), Math.max(1, (int)((double)h * 0.3)))
        );
        double r = 0.0;
        double g = 0.0;
        double b = 0.0;
        int n = 0;
        int cw = Math.max(1, (int)((double)w * 0.2));
        int ch = Math.max(1, (int)((double)h * 0.2));
        int[][] pos = new int[][]{{0, 0}, {w - cw, 0}, {0, h - ch}, {w - cw, h - ch}};

        for (int[] p : pos) {
            double[] m = meanColor(sub(cell, p[0], p[1], cw, ch));
            r += m[0];
            g += m[1];
            b += m[2];
            n++;
        }

        return colorDistance(center, new double[]{r / (double)n, g / (double)n, b / (double)n});
    }

    private static BufferedImage sub(BufferedImage img, int x, int y, int w, int h) {
        x = Math.max(0, Math.min(x, img.getWidth() - 1));
        y = Math.max(0, Math.min(y, img.getHeight() - 1));
        w = Math.max(1, Math.min(w, img.getWidth() - x));
        h = Math.max(1, Math.min(h, img.getHeight() - y));
        return img.getSubimage(x, y, w, h);
    }

    private static double grayStd(BufferedImage img) {
        double sum = 0.0;
        double sum2 = 0.0;
        int n = 0;

        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                double gray = 0.299 * (double)(rgb >> 16 & 0xFF) + 0.587 * (double)(rgb >> 8 & 0xFF) + 0.114 * (double)(rgb & 0xFF);
                sum += gray;
                sum2 += gray * gray;
                n++;
            }
        }

        double mean = sum / (double)n;
        return Math.sqrt(Math.max(0.0, sum2 / (double)n - mean * mean));
    }

    private static float[] grayNcc(BufferedImage img) {
        float[] v = new float[img.getWidth() * img.getHeight()];
        double mean = 0.0;
        int i = 0;

        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                float gray = (float)(0.299 * (double)(rgb >> 16 & 0xFF) + 0.587 * (double)(rgb >> 8 & 0xFF) + 0.114 * (double)(rgb & 0xFF));
                v[i++] = gray;
                mean += (double)gray;
            }
        }

        mean /= (double)v.length;
        double norm = 0.0;

        for (int k = 0; k < v.length; k++) {
            v[k] = (float)((double)v[k] - mean);
            norm += (double)(v[k] * v[k]);
        }

        norm = Math.sqrt(norm);
        if (norm < 1.0E-6) {
            return v;
        } else {
            for (int k = 0; k < v.length; k++) {
                v[k] = (float)((double)v[k] / norm);
            }

            return v;
        }
    }

    private static double ncc(float[] a, float[] b) {
        double s = 0.0;

        for (int i = 0; i < a.length && i < b.length; i++) {
            s += (double)(a[i] * b[i]);
        }

        return s;
    }

    private static double[] backgroundColor(BufferedImage img, int cx, int cy, double pw, double ph) {
        int dx = (int)(pw * 0.46);
        int dy = (int)(ph * 0.46);
        double r = 0.0;
        double g = 0.0;
        double b = 0.0;
        int n = 0;
        int[][] offsets = new int[][]{{-dx, -dy}, {dx, -dy}, {-dx, dy}, {dx, dy}};

        for (int[] off : offsets) {
            int x = cx + off[0];
            int y = cy + off[1];
            if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) {
                int rgb = img.getRGB(x, y);
                r += (double)(rgb >> 16 & 0xFF);
                g += (double)(rgb >> 8 & 0xFF);
                b += (double)(rgb & 0xFF);
                n++;
            }
        }

        return n == 0 ? new double[]{0.0, 0.0, 0.0} : new double[]{r / (double)n, g / (double)n, b / (double)n};
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: JieqiBoardRecognizer <图片> [x,y,w,h] [输出目录]");
        } else {
            BufferedImage img = ImageIO.read(new File(args[0]));
            if (img == null) {
                System.out.println("图片读取失败: " + args[0]);
            } else {
                Rectangle board = null;
                if (args.length >= 2 && args[1].contains(",")) {
                    String[] p = args[1].split(",");
                    board = new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                }

                File outDir = args.length >= 3 ? new File(args[2]) : new File("log/jieqi_cells");
                if (!outDir.exists()) {
                    outDir.mkdirs();
                }

                List<JieqiBoardRecognizer.CellFeature> features = new ArrayList<>();
                OnnxModel ai = null;
                OnnxModel vin = null;

                try {
                    ai = new Yolo11Model();
                    JieqiTrace.log("揭棋识别：主模型加载成功");
                } catch (Throwable e) {
                    JieqiTrace.log("揭棋识别：主模型加载失败 " + e);
                }

                try {
                    vin = new VinYolo5Model();
                    JieqiTrace.log("揭棋识别：Vin 模型加载成功");
                } catch (Throwable e) {
                    JieqiTrace.log("揭棋识别：Vin 模型加载失败 " + e);
                }

                if (board == null) {
                    try {
                        if (ai != null) {
                            board = ai.findBoardPosition(img);
                        }

                        if (board == null && vin != null) {
                            board = vin.findBoardPosition(img);
                        }
                    } catch (Throwable e) {
                        JieqiTrace.log("自动检测棋盘异常: " + e);
                    }

                    if (board != null) {
                        JieqiTrace.log("自动检测棋盘框: " + board.x + "," + board.y + "," + board.width + "," + board.height);
                    } else {
                        board = new Rectangle(0, 0, img.getWidth(), img.getHeight());
                        JieqiTrace.log("未检测到棋盘框，改用整图: " + board.width + "x" + board.height);
                    }
                }

                char[][] grid = ai == null && vin == null ? recognize(img, board, features, outDir) : recognize(img, board, ai, vin, features);
                System.out.println("棋盘框: " + board.x + "," + board.y + "," + board.width + "," + board.height);

                for (char[] row : grid) {
                    StringBuilder sb = new StringBuilder("  ");

                    for (char ch : row) {
                        sb.append(ch == ' ' ? '.' : ch);
                    }

                    System.out.println(sb);
                }

                File csv = new File(outDir, "cells.csv");

                try (PrintWriter w = new PrintWriter(new FileWriter(csv))) {
                    w.println("row,col,meanR,meanG,meanB,grayStd,bgDist,centerCorner,bestTemplate,bestScore,decision");

                    for (JieqiBoardRecognizer.CellFeature f : features) {
                        w.printf(
                            "%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%s,%.3f,%s%n",
                            f.row,
                            f.col,
                            f.meanR,
                            f.meanG,
                            f.meanB,
                            f.grayStd,
                            f.bgDist,
                            f.centerCorner,
                            f.bestTemplate,
                            f.bestScore,
                            f.decision
                        );
                    }
                }

                System.out.println("特征 CSV: " + csv.getAbsolutePath());
            }
        }
    }

    private static class Calibrated {
        JieqiBoardRecognizer.Grid grid;
        int w;
        int h;
    }

    public static class CellFeature {
        public int row;
        public int col;
        public double meanR;
        public double meanG;
        public double meanB;
        public double grayStd;
        public double bgDist;
        public double centerCorner;
        public char bestTemplate = ' ';
        public double bestScore;
        public char decision = ' ';
    }

    public static class Grid {
        public double x0;
        public double y0;
        public double dx;
        public double dy;

        @Override
        public String toString() {
            return String.format("x0=%.1f y0=%.1f dx=%.1f dy=%.1f", x0, y0, dx, dy);
        }
    }
}
