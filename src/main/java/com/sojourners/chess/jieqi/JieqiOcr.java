package com.sojourners.chess.jieqi;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.sojourners.chess.util.PathUtils;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 揭棋 OCR（PPOCRv6 检测 + 识别）
 * <p>
 * 用于识别盘面上的"车马炮..."等文字，辅助判断棋子和行棋方。
 * 模型放在 model/ppocrv6/{det,rec}.onnx，字典内嵌在 rec 模型的 metadata 里。
 */
public class JieqiOcr {

    /** 是否强制用 OCR 结果覆盖识别结果（默认不覆盖，只作辅助） */
    public static final boolean OVERRIDE = false;

    private static final int DET_NET = 128;
    private static final int REC_H = 48;
    private static final int REC_MAX_W = 320;
    /** det 输出的置信度阈值 */
    private static final float DET_CONF = 0.3F;

    private static JieqiOcr instance;

    private OrtEnvironment env;
    private OrtSession det;
    private OrtSession rec;
    private String[] dict;

    private JieqiOcr() {
        try {
            String base = PathUtils.getJarPath() + "model/ppocrv6/";
            File detFile = new File(base + "det.onnx");
            File recFile = new File(base + "rec.onnx");
            if (!detFile.exists() || !recFile.exists()) {
                JieqiTrace.log("揭棋OCR：没找到 model/ppocrv6 下的模型，跳过 OCR");
                return;
            }

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opt = new OrtSession.SessionOptions();
            opt.setIntraOpNumThreads(2);
            det = env.createSession(detFile.getAbsolutePath(), opt);
            rec = env.createSession(recFile.getAbsolutePath(), opt);

            String dictText = rec.getMetadata().getCustomMetadata().get("character");
            if (dictText == null || dictText.isEmpty()) {
                JieqiTrace.log("揭棋OCR：rec 模型里没有字典，跳过 OCR");
                det = null;
                rec = null;
                return;
            }
            dict = dictText.split("\n");
            JieqiTrace.log("揭棋OCR：模型加载成功（字典 " + dict.length + " 条）");
        } catch (Throwable t) {
            JieqiTrace.log("揭棋OCR：加载失败 " + t);
            det = null;
            rec = null;
        }
    }

    public static synchronized JieqiOcr get() {
        if (instance == null) {
            instance = new JieqiOcr();
        }
        return instance;
    }

    public boolean ready() {
        return det != null && rec != null;
    }

    /**
     * 识别一个棋子方框里的文字，失败返回 null
     */
    public String recognizeChar(BufferedImage img, Rectangle box) {
        if (!ready() || img == null || box == null) {
            return null;
        }
        try {
            // 取一个以方框中心为准的正方形区域
            int side = Math.min(box.width, box.height);
            if (side < 12) {
                return null;
            }
            int x = Math.max(0, box.x + box.width / 2 - side / 2);
            int y = Math.max(0, box.y + box.height / 2 - side / 2);
            int w = Math.min(side, img.getWidth() - x);
            int h = Math.min(side, img.getHeight() - y);
            if (w < 12 || h < 12) {
                return null;
            }

            BufferedImage cell = img.getSubimage(x, y, w, h);
            // 优先用 det 模型裁出文字区域，失败再退回亮/彩色阈值法
            BufferedImage text = detCrop(cell);
            if (text == null) {
                text = tightGlyph(cell);
            }
            return text == null ? null : recText(text);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用 det 模型找文字区域并裁剪出来
     */
    private BufferedImage detCrop(BufferedImage cell) {
        OnnxTensor t = null;
        try {
            BufferedImage small = new BufferedImage(DET_NET, DET_NET, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.drawImage(cell, 0, 0, DET_NET, DET_NET, null);
            g.dispose();

            float[] data = toTensor(small, DET_NET, DET_NET);
            long[] shape = {1L, 3L, DET_NET, DET_NET};
            t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);

            Map<String, OnnxTensor> feed = new LinkedHashMap<>();
            feed.put("x", t);
            float[][][][] out = (float[][][][]) det.run(feed).get(0).getValue();
            float[][] map = out[0][0];
            int mapH = map.length;
            int mapW = map[0].length;

            // 把概率图的包围盒映射回原图坐标
            int minX = cell.getWidth();
            int minY = cell.getHeight();
            int maxX = -1;
            int maxY = -1;
            for (int yy = 0; yy < mapH; yy++) {
                for (int xx = 0; xx < mapW; xx++) {
                    if (map[yy][xx] > DET_CONF) {
                        int sx = (int) ((double) xx / mapW * cell.getWidth());
                        int sy = (int) ((double) yy / mapH * cell.getHeight());
                        minX = Math.min(minX, sx);
                        minY = Math.min(minY, sy);
                        maxX = Math.max(maxX, sx);
                        maxY = Math.max(maxY, sy);
                    }
                }
            }
            if (maxX <= minX || maxY <= minY) {
                return null;
            }

            int padX = Math.max(2, (maxX - minX) / 6);
            int padY = Math.max(2, (maxY - minY) / 6);
            int x1 = Math.max(0, minX - padX);
            int y1 = Math.max(0, minY - padY);
            int x2 = Math.min(cell.getWidth(), maxX + padX);
            int y2 = Math.min(cell.getHeight(), maxY + padY);
            return cell.getSubimage(x1, y1, x2 - x1, y2 - y1);
        } catch (Throwable t2) {
            return null;
        } finally {
            if (t != null) {
                try {
                    t.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 不用 det 模型时的兜底裁剪：
     * 取棋盘格中央区域里"明显比背景暗"或"明显偏红"的像素，求包围盒。
     */
    private BufferedImage tightGlyph(BufferedImage cell) {
        int w = cell.getWidth();
        int h = cell.getHeight();
        double[] lum = new double[w * h];
        double[] sorted = new double[w * h];

        int k = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = cell.getRGB(x, y);
                lum[k] = 0.299 * (p >> 16 & 0xFF) + 0.587 * (p >> 8 & 0xFF) + 0.114 * (p & 0xFF);
                sorted[k] = lum[k];
                k++;
            }
        }
        Arrays.sort(sorted);
        // 取 85% 分位作为背景亮度参考
        double bg = sorted[(int) (sorted.length * 0.85)];

        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        k = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = cell.getRGB(x, y);
                int r = p >> 16 & 0xFF;
                int gg = p >> 8 & 0xFF;
                int b = p & 0xFF;
                boolean red = r > 110 && r - gg > 45 && r - b > 45;
                // 只看中心区域（半径平方 0.16），排除棋盘线上的干扰
                double dx = ((double) x - w / 2.0) / w;
                double dy = ((double) y - h / 2.0) / h;
                if (dx * dx + dy * dy < 0.16 && (lum[k] < bg - 45.0 || red)) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
                k++;
            }
        }

        if (maxX <= minX || maxY <= minY) {
            return null;
        }
        int pad = Math.max(2, (maxX - minX) / 8);
        int x1 = Math.max(0, minX - pad);
        int y1 = Math.max(0, minY - pad);
        int x2 = Math.min(w, maxX + pad);
        int y2 = Math.min(h, maxY + pad);
        return cell.getSubimage(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * 识别文字（CTC 解码：去掉 blank 和连续重复字符）
     */
    private String recText(BufferedImage text) throws Exception {
        int w = Math.max(1, (int) Math.round((double) text.getWidth() / text.getHeight() * REC_H));
        w = Math.min(w, REC_MAX_W);

        BufferedImage r = new BufferedImage(w, REC_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = r.createGraphics();
        g.drawImage(text, 0, 0, w, REC_H, null);
        g.dispose();

        float[] data = toTensor(r, w, REC_H);
        long[] shape = {1L, 3L, REC_H, (long) w};
        OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
        try {
            Map<String, OnnxTensor> feed = new LinkedHashMap<>();
            feed.put("x", t);
            float[][][] out = (float[][][]) rec.run(feed).get(0).getValue();

            StringBuilder sb = new StringBuilder();
            int prev = -1;
            for (float[] row : out[0]) {
                int best = 0;
                for (int i = 1; i < row.length; i++) {
                    if (row[i] > row[best]) {
                        best = i;
                    }
                }
                // 0 是 blank；重复的字符只保留一个
                if (best != 0 && best != prev && best < dict.length) {
                    sb.append(dict[best]);
                }
                prev = best;
            }
            return sb.toString();
        } finally {
            t.close();
        }
    }

    /**
     * 归一化到 [-1, 1]，CHW 排布
     */
    private static float[] toTensor(BufferedImage img, int w, int h) {
        float[] data = new float[3 * h * w];
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            data[i] = ((p >> 16 & 0xFF) / 255.0F - 0.5F) / 0.5F;
            data[h * w + i] = ((p >> 8 & 0xFF) / 255.0F - 0.5F) / 0.5F;
            data[2 * h * w + i] = ((p & 0xFF) / 255.0F - 0.5F) / 0.5F;
        }
        return data;
    }
}
