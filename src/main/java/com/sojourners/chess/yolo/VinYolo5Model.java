package com.sojourners.chess.yolo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * VinXiangQi 的 YOLOv5 模型
 * <p>
 * 输出格式同 {@link Yolo5Model}（每组 5+类别 个值，含 obj 置信度），
 * 但输入像 {@link Yolo11Model} 一样缩放后居中贴到 640x640、四周填灰，
 * 识别出的坐标要减掉留白（padX / padY）再换算回原图。
 * 揭棋模式使用这个模型。
 */
public class VinYolo5Model extends Yolo5Model {

    float BOARD_CONFIDENCE = 0.5f;
    float PIECE_CONFIDENCE = 0.5f;

    /** 贴到 640x640 画布上的左右/上下留白 */
    private float padX;
    private float padY;

    @Override
    public String getModelPath() {
        return "model/yolo5-vin.onnx";
    }

    @Override
    protected float getBoardConfidence() {
        return BOARD_CONFIDENCE;
    }

    @Override
    protected float getPieceConfidence() {
        return PIECE_CONFIDENCE;
    }

    @Override
    protected float getRetryPieceConfidence() {
        return 0.4f;
    }

    @Override
    protected float getRecoveryPieceConfidence() {
        return 0.35f;
    }

    @Override
    float[][][] processInput(BufferedImage image, float rate) {
        int destW = Math.round(image.getWidth() * rate);
        int destH = Math.round(image.getHeight() * rate);
        // 居中放置，记录留白用于把输出坐标换算回原图
        padX = (640 - destW) / 2.0f;
        padY = (640 - destH) / 2.0f;
        int leftMargin = (int) padX;
        int topMargin = (int) padY;

        BufferedImage resizedImage = new BufferedImage(destW, destH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resizedImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(image, 0, 0, destW, destH, null);
        g2d.dispose();

        float[][][] arr = new float[3][640][640];
        for (int i = 0; i < 640; i++) {
            for (int j = 0; j < 640; j++) {
                if (i >= topMargin && j >= leftMargin && i < topMargin + destH && j < leftMargin + destW) {
                    int rgb = resizedImage.getRGB(j - leftMargin, i - topMargin);
                    Color color = new Color(rgb, true);
                    arr[0][i][j] = color.getRed() / 255.0f;
                    arr[1][i][j] = color.getGreen() / 255.0f;
                    arr[2][i][j] = color.getBlue() / 255.0f;
                } else {
                    // 灰色填充：114/255
                    arr[0][i][j] = 0.44705883f;
                    arr[1][i][j] = 0.44705883f;
                    arr[2][i][j] = 0.44705883f;
                }
            }
        }
        return arr;
    }

    @Override
    List<Yolo5Model.DetectResult> processOutput(float[] output, BufferedImage img, float rate,
                                                float boardConf, float pieceConf) {
        List<Yolo5Model.DetectResult> list = new ArrayList<>();
        int sizeClasses = labels.length;
        int stride = 5 + sizeClasses;
        int size = output.length / stride;

        for (int i = 0; i < size; i++) {
            int indexBase = i * stride;

            float maxClass = 0.0f;
            int maxIndex = 0;
            for (int c = 0; c < sizeClasses; c++) {
                if (output[indexBase + c + 5] > maxClass) {
                    maxClass = output[indexBase + c + 5];
                    maxIndex = c;
                }
            }

            float score = maxClass * output[indexBase + 4];
            // 棋盘框和棋子用不同阈值
            float threshold = labels[maxIndex] == '0' ? boardConf : pieceConf;
            if (score > threshold) {
                float xPos = output[indexBase];
                float yPos = output[indexBase + 1];
                float w = output[indexBase + 2];
                float h = output[indexBase + 3];
                // 减掉居中留白再换算回原图坐标
                Yolo5Model.Rectangle rect = new Yolo5Model.Rectangle(
                        (xPos - padX) / rate, (yPos - padY) / rate, w / rate, h / rate);
                list.add(new Yolo5Model.DetectResult(labels[maxIndex], rect, score));
            }
        }
        return nms(list);
    }
}
