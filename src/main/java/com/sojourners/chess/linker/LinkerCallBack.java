package com.sojourners.chess.linker;

public interface LinkerCallBack {

    void linkerInitChessBoard(String fenCode, boolean isReverse);

    char[][] getEngineBoard();

    boolean isThinking();

    boolean isWatchMode();

    void linkerMove(int x1, int y1, int x2, int y2);

    /**
     * 是否处于揭棋模式
     */
    default boolean isJieqiMode() {
        return false;
    }

    /**
     * 连线识别出的揭棋盘面（暗子/明子网格）
     */
    default void linkerJieqiBoard(char[][] grid) {
    }

    /**
     * 连线识别出的揭棋盘面（带截图时间戳，用于判定画面是否已更新）
     */
    default void linkerJieqiBoard(char[][] grid, long capturedAt) {
        linkerJieqiBoard(grid);
    }

    /**
     * 揭棋连线过程中给界面的提示
     */
    default void jieqiLinkMessage(String message) {
    }
}
