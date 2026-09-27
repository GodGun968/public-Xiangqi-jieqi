package com.sojourners.chess.linker;

import java.awt.*;
import java.awt.image.BufferedImage;

public interface GraphLinker {

    void start();

    void stop();

    void getTargetWindowId();

    Rectangle getTargetWindowPosition();

    /**
     * 目标窗口标题（揭棋连线自检用：避免连到 TCHESS 自己）
     */
    default String getTargetWindowTitle() {
        return null;
    }

    BufferedImage screenshotByBack(Rectangle windowPos);

    BufferedImage screenshotByFront(Rectangle windowPos);

    void mouseClickByFront(Rectangle windowPos, Point p1, Point p2);

    void mouseClickByBack(Point p1, Point p2);

}
