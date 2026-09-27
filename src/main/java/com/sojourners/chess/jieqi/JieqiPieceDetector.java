package com.sojourners.chess.jieqi;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.sojourners.chess.util.PathUtils;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 揭棋棋子检测 + 分类
 * <p>
 * detector：640x640 输入，YOLO 输出棋子框；
 * classifier：64x64 输入，把棋子框分类成 A/R/N/P/K/C/B。
 */
public class JieqiPieceDetector {

    private static final int SIZE = 640;
    private static final int CLF_SIZE = 64;
    private static final float CONF = 0.3F;
    private static final float NMS_IOU = 0.5F;
    private static final int CHANNEL = SIZE * SIZE;

    /** 分类模型的输出顺序 */
    private static final char[] NAMES = {'A', 'R', 'N', 'P', 'K', 'C', 'B'};

    private static JieqiPieceDetector instance;

    private OrtEnvironment env;
    private OrtSession detector;
    private OrtSession classifier;
    /** 缓存：同一张图不用重复推理 */
    private BufferedImage lastImg;
    private List<Rectangle> lastBoxes;

    private JieqiPieceDetector() {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opt = new OrtSession.SessionOptions();
            opt.setIntraOpNumThreads(2);
            String base = PathUtils.getJarPath() + "model/";
            detector = env.createSession(base + "jieqi_piece_detector.onnx", opt);
            classifier = env.createSession(base + "jieqi_piece_classifier.onnx", opt);
            JieqiTrace.log("揭棋检测器：模型加载成功");
        } catch (Throwable t) {
            JieqiTrace.log("揭棋检测器：模型加载失败 " + t);
        }
    }

    public static synchronized JieqiPieceDetector get() {
        if (instance == null) {
            instance = new JieqiPieceDetector();
        }
        return instance;
    }

    /**
     * 模型是否可用
     */
    public boolean ready() {
        return detector != null;
    }

    /**
     * 检测棋子框
     */
    public List<Rectangle> detect(BufferedImage img) {
        List<Rectangle> list = new ArrayList<>();
        if (img == null) {
            return list;
        }
        // 同一张图直接返回上次结果
        if (img == lastImg && lastBoxes != null) {
            return new ArrayList<>(lastBoxes);
        }
        if (detector == null) {
            return list;
        }

        try {
            int w = img.getWidth();
            int h = img.getHeight();

            BufferedImage resized = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, SIZE, SIZE, null);
            g.dispose();

            float[] data = new float[3 * CHANNEL];
            int[] rgb = resized.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
            for (int i = 0; i < rgb.length; i++) {
                int c = rgb[i];
                data[i] = (c >> 16 & 0xFF) / 255.0F;
                data[CHANNEL + i] = (c >> 8 & 0xFF) / 255.0F;
                data[2 * CHANNEL + i] = (c & 0xFF) / 255.0F;
            }

            long[] shape = {1L, 3L, SIZE, SIZE};
            List<Rectangle> raw = new ArrayList<>();
            OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
            try {
                Map<String, OnnxTensor> feed = new LinkedHashMap<>();
                feed.put("images", tensor);
                float[][][] out = (float[][][]) detector.run(feed).get(0).getValue();
                float[][] boxes = out[0];
                int n = boxes[0].length;

                // 收集置信度足够的候选框
                List<float[]> cand = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    float conf = boxes[4][i];
                    if (conf > CONF) {
                        cand.add(new float[]{boxes[0][i], boxes[1][i], boxes[2][i], boxes[3][i], conf});
                    }
                }
                cand.sort((a, b) -> Float.compare(b[4], a[4]));

                // 按置信度从高到低做 NMS
                for (float[] c : cand) {
                    Rectangle r = new Rectangle(
                            Math.round((c[0] - c[2] / 2.0F) / SIZE * w),
                            Math.round((c[1] - c[3] / 2.0F) / SIZE * h),
                            Math.round(c[2] / SIZE * w),
                            Math.round(c[3] / SIZE * h));
                    boolean dup = false;
                    for (Rectangle k : raw) {
                        if (iou(r, k) > NMS_IOU) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        raw.add(r);
                    }
                }
            } finally {
                tensor.close();
            }

            // 按面积中位数过滤掉明显偏大/偏小的误检
            if (raw.size() >= 5) {
                List<Integer> areas = new ArrayList<>();
                for (Rectangle r : raw) {
                    areas.add(r.width * r.height);
                }
                areas.sort(Integer::compareTo);
                double med = areas.get(areas.size() / 2);
                for (Rectangle r : raw) {
                    double a = (double) r.width * r.height;
                    if (a > 0.4 * med && a < 2.5 * med && r.width > 20 && r.height > 20) {
                        list.add(r);
                    }
                }
            } else {
                list.addAll(raw);
            }
        } catch (Throwable t) {
            JieqiTrace.log("揭棋检测器：推理异常 " + t);
        }

        lastImg = img;
        lastBoxes = new ArrayList<>(list);
        return list;
    }

    /**
     * 把棋子框分类成一个兵种；失败返回 '?'
     */
    public char classify(BufferedImage img, Rectangle box) {
        if (classifier == null) {
            return '?';
        }
        try {
            int bx = Math.max(0, box.x);
            int by = Math.max(0, box.y);
            BufferedImage crop = img.getSubimage(bx, by,
                    Math.min(box.width, img.getWidth() - bx),
                    Math.min(box.height, img.getHeight() - by));

            BufferedImage r64 = new BufferedImage(CLF_SIZE, CLF_SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = r64.createGraphics();
            g.drawImage(crop, 0, 0, CLF_SIZE, CLF_SIZE, null);
            g.dispose();

            int channel = CLF_SIZE * CLF_SIZE;
            float[] data = new float[3 * channel];
            int[] rgb = r64.getRGB(0, 0, CLF_SIZE, CLF_SIZE, null, 0, CLF_SIZE);
            for (int i = 0; i < rgb.length; i++) {
                int c = rgb[i];
                data[i] = (c >> 16 & 0xFF) / 255.0F;
                data[channel + i] = (c >> 8 & 0xFF) / 255.0F;
                data[2 * channel + i] = (c & 0xFF) / 255.0F;
            }

            long[] shape = {1L, 3L, CLF_SIZE, CLF_SIZE};
            OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
            try {
                Map<String, OnnxTensor> feed = new LinkedHashMap<>();
                feed.put("images", tensor);
                float[][] out = (float[][]) classifier.run(feed).get(0).getValue();
                int best = 0;
                for (int i = 1; i < out[0].length; i++) {
                    if (out[0][i] > out[0][best]) {
                        best = i;
                    }
                }
                return NAMES[Math.min(best, NAMES.length - 1)];
            } finally {
                tensor.close();
            }
        } catch (Throwable t) {
            return '?';
        }
    }

    private static double iou(Rectangle a, Rectangle b) {
        int x1 = Math.max(a.x, b.x);
        int y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width);
        int y2 = Math.min(a.y + a.height, b.y + b.height);
        int iw = Math.max(0, x2 - x1);
        int ih = Math.max(0, y2 - y1);
        double inter = (double) iw * ih;
        double uni = (double) a.width * a.height + (double) b.width * b.height - inter;
        return uni <= 0 ? 0 : inter / uni;
    }
}
