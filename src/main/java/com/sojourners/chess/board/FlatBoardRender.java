package com.sojourners.chess.board;

import com.sojourners.chess.jieqi.JieqiPosition;
import com.sojourners.chess.util.XiangqiUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * 扁平风格棋盘渲染
 * <p>
 * 相比默认的木纹图棋盘，这里全部用矢量绘制：圆角底板、细线棋格、圆片棋子。
 * 一共四套配色（现代木色 / 经典木纹 / 墨玉 / 青瓷），由 {@link Palette} 描述。
 * 揭棋的暗子画成"两圈圆片"，不显示字。
 */
public class FlatBoardRender extends BaseBoardRender {

    /** 现代木色 */
    public static final Palette MODERN = new Palette("现代木色",
            Color.web("#F5F7F4"), Color.web("#E9DCBF"), Color.web("#C6AF86"), Color.web("#A5836A"),
            Color.web("#FFF9EC"), Color.web("#C0392B"), Color.web("#374B46"),
            Color.web("#7B5B3A"), Color.web("#F4EDDC"), 0.1);

    /** 经典木纹 */
    public static final Palette WOOD = new Palette("经典木纹",
            Color.web("#F3E9D6"), Color.web("#D9B77C"), Color.web("#AE8A52"), Color.web("#8A6A42"),
            Color.web("#FBF4E4"), Color.web("#A81C0D"), Color.web("#20352E"),
            Color.web("#6E4E2A"), Color.web("#F7F0DE"), 0.14);

    /** 墨玉（深色） */
    public static final Palette DARK = new Palette("墨玉",
            Color.web("#17181A"), Color.web("#262A2E"), Color.web("#3A4045"), Color.web("#5E6A73"),
            Color.web("#EDEAE0"), Color.web("#E0553F"), Color.web("#7FC4D8"),
            Color.web("#3A4045"), Color.web("#E7E3D8"), 0.28);

    /** 青瓷（默认） */
    public static final Palette JADE = new Palette("青瓷",
            Color.web("#F6FBF9"), Color.web("#DCEBE3"), Color.web("#A9C7B8"), Color.web("#7FA593"),
            Color.web("#FFFFFF"), Color.web("#C0392B"), Color.web("#2F6E5A"),
            Color.web("#7FA593"), Color.web("#FFFFFF"), 0.08);

    private final Palette p;
    private Font font;
    private int fontSize;

    public FlatBoardRender(Canvas canvas, Palette palette) {
        super(canvas);
        this.p = palette;
    }

    @Override
    public Color getBackgroundColor() {
        return p.board;
    }

    @Override
    public void drawBackgroundImage(double width, double height) {
        // 外层底色 + 圆角棋盘板 + 描边
        gc.setFill(p.back);
        gc.fillRect(0, 0, width, height);

        double inset = Math.max(2, Math.round(width * 0.014));
        double arc = inset * 1.8;
        gc.setFill(p.board);
        gc.fillRoundRect(inset, inset, width - 2 * inset, height - 2 * inset, arc, arc);

        gc.setStroke(p.edge);
        gc.setLineWidth(Math.max(1, inset * 0.4));
        gc.strokeRoundRect(inset, inset, width - 2 * inset, height - 2 * inset, arc, arc);
    }

    @Override
    public void drawBoardLine(int pos, int padding, int piece, boolean isReverse, ChessBoard.BoardSize style) {
        gc.setStroke(p.line);
        gc.setLineWidth(Math.max(1, piece / 44.0));
        // 外框（9 列宽 = 8 格，10 行高 = 9 格）
        gc.strokeRect(pos, pos, piece * 8, piece * 9);

        // 横向 9 条线
        for (int i = 1; i < 9; i++) {
            gc.strokeLine(pos, pos + piece * i, pos + piece * 8, pos + piece * i);
        }

        // 纵向线：河界处断开
        for (int i = 1; i < 8; i++) {
            gc.strokeLine(pos + piece * i, pos, pos + piece * i, pos + piece * 4);
            gc.strokeLine(pos + piece * i, pos + piece * 5, pos + piece * i, pos + piece * 9);
        }

        // 九宫斜线
        gc.strokeLine(pos + piece * 3, pos, pos + piece * 5, pos + piece * 2);
        gc.strokeLine(pos + piece * 3, pos + piece * 2, pos + piece * 5, pos);
        gc.strokeLine(pos + piece * 3, pos + piece * 9, pos + piece * 5, pos + piece * 7);
        gc.strokeLine(pos + piece * 3, pos + piece * 7, pos + piece * 5, pos + piece * 9);

        // 兵/炮位的星标
        for (int i = 0; i < 9; i += 2) {
            markStar(pos + piece * i, pos + piece * 3, piece, i != 0, i != 8);
            markStar(pos + piece * i, pos + piece * 6, piece, i != 0, i != 8);
        }
        for (int i = 1; i < 9; i += 6) {
            markStar(pos + piece * i, pos + piece * 2, piece, true, true);
            markStar(pos + piece * i, pos + piece * 7, piece, true, true);
        }
    }

    /**
     * 画一个"十"字角标（两侧可单独控制，边线上的只画内侧）
     */
    private void markStar(double x, double y, int piece, boolean left, boolean right) {
        double off = piece / 16.0;
        double len = piece / 6.0;
        gc.setLineWidth(Math.max(1, piece / 44.0));

        if (left) {
            gc.strokePolyline(new double[]{x - off - len, x - off, x - off},
                    new double[]{y - off, y - off, y - off - len}, 3);
            gc.strokePolyline(new double[]{x - off - len, x - off, x - off},
                    new double[]{y + off, y + off, y + off + len}, 3);
        }
        if (right) {
            gc.strokePolyline(new double[]{x + off + len, x + off, x + off},
                    new double[]{y - off, y - off, y - off - len}, 3);
            gc.strokePolyline(new double[]{x + off + len, x + off, x + off},
                    new double[]{y + off, y + off, y + off + len}, 3);
        }
    }

    @Override
    public void drawCenterText(int pos, int piece, ChessBoard.BoardSize style) {
        double size = piece / 2.5;
        gc.setFont(Font.font(size));
        gc.setFill(p.line);
        gc.setGlobalAlpha(0.85);
        double baseY = pos + 4.5 * piece + size / 3.6;
        gc.fillText("楚", pos + 2 * piece - size, baseY);
        gc.fillText("河", pos + 3 * piece - size, baseY);
        gc.fillText("汉", pos + 5 * piece, baseY);
        gc.fillText("界", pos + 6 * piece, baseY);
        gc.setGlobalAlpha(1.0);
    }

    @Override
    protected void drawHiddenPiece(double cx, double cy, double r, boolean isRed) {
        // 暗子：外圈 + 内面 + 细圆环，不显示字
        gc.setFill(p.hiddenRim);
        gc.fillOval(cx - r, cy - r, 2 * r, 2 * r);

        double inner = r * 0.87;
        gc.setFill(p.hiddenFace);
        gc.fillOval(cx - inner, cy - inner, 2 * inner, 2 * inner);

        gc.setStroke(p.edge);
        gc.setGlobalAlpha(0.6);
        gc.setLineWidth(Math.max(1, r / 20.0));
        double ring = r * 0.72;
        gc.strokeOval(cx - ring, cy - ring, 2 * ring, 2 * ring);
        gc.setGlobalAlpha(1.0);
    }

    @Override
    public void drawPieces(int pos, int piece, char[][] board, boolean isReverse, ChessBoard.BoardSize style) {
        if (font == null || fontSize != piece / 2) {
            fontSize = piece / 2;
            font = Font.loadFont(getClass().getResourceAsStream("/font/chessman.ttf"), fontSize);
        }

        double r = (piece - piece / 10) / 2.0;
        for (int i = 0; i < board.length; i++) {
            for (int j = 0; j < board[0].length; j++) {
                char pc = board[i][j];
                double x = pos + piece * getReverseX(j, isReverse);
                double y = pos + piece * getReverseY(i, isReverse);

                if (JieqiPosition.isHiddenPiece(pc)) {
                    drawHiddenPiece(x, y, r, pc == 'X');
                    continue;
                }

                String word = XiangqiUtils.map.get(pc);
                if (word == null) {
                    continue;
                }
                Color c = XiangqiUtils.isRed(pc) ? p.red : p.black;
                // 阴影
                if (p.shadow > 0) {
                    gc.setFill(Color.rgb(0, 0, 0, p.shadow));
                    gc.fillOval(x - r + 1.5, y - r + 2.5, 2 * r, 2 * r);
                }
                // 棋子圆片
                gc.setFill(p.disc);
                gc.fillOval(x - r, y - r, 2 * r, 2 * r);
                gc.setStroke(c);
                gc.setLineWidth(Math.max(1.2, r * 0.15));
                gc.strokeOval(x - r, y - r, 2 * r, 2 * r);
                // 棋子字
                gc.setFill(c);
                gc.setFont(font);
                gc.fillText(word, x - fontSize / 2.0, y + fontSize / 2.0 - fontSize / 5.5);
            }
        }
    }

    /**
     * 一套扁平棋盘的配色
     */
    public static final class Palette {
        /** 配色名称（菜单里显示） */
        public final String name;
        /** 窗口底色 */
        public final Color back;
        /** 棋盘板底色 */
        public final Color board;
        /** 棋盘描边 */
        public final Color edge;
        /** 棋格线 / 河界字 */
        public final Color line;
        /** 棋子圆片底色 */
        public final Color disc;
        /** 红方字色 */
        public final Color red;
        /** 黑方字色 */
        public final Color black;
        /** 暗子外圈 */
        public final Color hiddenRim;
        /** 暗子内面 */
        public final Color hiddenFace;
        /** 棋子投影透明度，0 表示不画阴影 */
        public final double shadow;

        public Palette(String name, Color back, Color board, Color edge, Color line, Color disc,
                       Color red, Color black, Color hiddenRim, Color hiddenFace, double shadow) {
            this.name = name;
            this.back = back;
            this.board = board;
            this.edge = edge;
            this.line = line;
            this.disc = disc;
            this.red = red;
            this.black = black;
            this.hiddenRim = hiddenRim;
            this.hiddenFace = hiddenFace;
            this.shadow = shadow;
        }
    }
}
