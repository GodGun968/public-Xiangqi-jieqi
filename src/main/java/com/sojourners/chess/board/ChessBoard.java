package com.sojourners.chess.board;

import com.sojourners.chess.config.Properties;
import com.sojourners.chess.jieqi.JieqiPosition;
import com.sojourners.chess.jieqi.JieqiTrace;
import com.sojourners.chess.media.SoundPlayer;
import com.sojourners.chess.util.PathUtils;
import com.sojourners.chess.util.StringUtils;
import com.sojourners.chess.util.XiangqiUtils;
import javafx.scene.canvas.Canvas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 棋盘
 */
public class ChessBoard {

    private static BaseBoardRender boardRender;

    private static volatile char[][] board = new char[10][9];

    private static char[][] copyBoard = new char[10][9];

    private BoardSize boardSize;

    private boolean stepTip;

    private boolean showNumber;

    private boolean stepSound;

    private boolean manualTip;
    private List<Step> manualList = new ArrayList<>();

    private static SoundPlayer sound;

    static {
        sound = new SoundPlayer(PathUtils.getJarPath() + "sound/click.wav",
                PathUtils.getJarPath() + "sound/move.wav",
                PathUtils.getJarPath() + "sound/capture.wav",
                PathUtils.getJarPath() + "sound/check.wav",
                PathUtils.getJarPath() + "sound/win.wav");
    }

    private Point remark;

    private Step prevStep;

    private boolean showMultiPV;

    private List<MoveTip> moveTips = new ArrayList<>();

    private boolean isReverse;

    /** 对局模式：象棋 / 揭棋 */
    private GameMode gameMode = GameMode.XIANGQI;

    /** 揭棋局面（象棋模式下为 null） */
    private JieqiPosition jieqi;

    /** 揭棋消息（状态栏显示） */
    private String jieqiMessage = "";

    /** 已经走出、等待对方棋盘出现的一手（ICCS），null 表示没有待确认的手 */
    private String pendingJieqiMove;
    /** 上一步走的子（用于回放确认） */
    private char pendingJieqiPiece;
    private long pendingJieqiAt;
    private long pendingJieqiClickAt;
    private int pendingJieqiTry;
    /** 这一手超过 5 秒没在对方棋盘上出现，判定为没走成、需要重新分析 */
    private boolean pendingJieqiRollback;
    /** 对方截图里的局面仍是走之前的样子 */
    private boolean pendingJieqiStillBefore;
    /** 对方截图的时间（帧时间），用于确认点击后是否有新帧 */
    private long pendingJieqiFrameAt;

    /** 从对方截图识别出的最近一手（ICCS） */
    private String lastJieqiMove;
    /** 最近一手的文字描述 */
    private String lastJieqiMoveText;

    public static class Point {
        int x;
        int y;
        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
        }
    }
    public static class Step {
        Point start;
        Point end;
        public Step(Point start, Point end) {
            this.start = start;
            this.end = end;
        }

        public Point getStart() {
            return start;
        }

        public void setStart(Point start) {
            this.start = start;
        }

        public Point getEnd() {
            return end;
        }

        public void setEnd(Point end) {
            this.end = end;
        }
    }

    public class MoveTip {
        Step first;
        Step second;

        public MoveTip(Step first, Step second) {
            this.first = first;
            this.second = second;
        }

        public Step getFirst() {
            return first;
        }

        public void setFirst(Step first) {
            this.first = first;
        }

        public Step getSecond() {
            return second;
        }

        public void setSecond(Step second) {
            this.second = second;
        }
    }

    public enum BoardSize {
        LARGE_BOARD,
        BIG_BOARD,
        MIDDLE_BOARD,
        SMALL_BOARD,
        AUTOFIT_BOARD
    }
    public enum BoardStyle {
        DEFAULT,
        CUSTOM,
        MODERN,
        WOOD,
        DARK,
        JADE;
    }

    /** 对局模式 */
    public enum GameMode {
        /** 象棋 */
        XIANGQI,
        /** 揭棋 */
        JIEQI
    }

    public ChessBoard(Canvas canvas, BoardSize bs, BoardStyle style, boolean stepTip, boolean manualTip,
                      boolean showMultiPV, boolean stepSound, boolean showNumber, String fenCode) {
        if (this.boardRender == null) {
            this.boardRender = createRender(style, canvas);
        }

        this.stepTip = stepTip;
        this.manualTip = manualTip;
        this.stepSound = stepSound;
        this.showNumber = showNumber;
        this.showMultiPV = showMultiPV;
        // 设置局面
        setNewBoard(fenCode);
        // 设置棋盘大小
        this.boardSize = bs;
        // 默认不翻转
        isReverse = false;

        this.paint();
    }

    public static void initChessBoard(char[][] board) {
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 9; j++) {
                if (i == 0 && (j == 0 || j == 8)) {
                    board[i][j] = 'r';
                } else if (i == 0 && (j == 1 || j == 7)) {
                    board[i][j] = 'n';
                } else if (i == 0 && (j == 2 || j == 6)) {
                    board[i][j] = 'b';
                } else if (i == 0 && (j == 3 || j == 5)) {
                    board[i][j] = 'a';
                } else if (i == 0 && j == 4) {
                    board[i][j] = 'k';
                } else if (i == 2 && (j == 1 || j == 7)) {
                    board[i][j] = 'c';
                } else if (i == 3 && (j == 0 || j == 2 || j == 4 || j == 6 || j == 8)) {
                    board[i][j] = 'p';
                } else if (i == 9 && (j == 0 || j == 8)) {
                    board[i][j] = 'R';
                } else if (i == 9 && (j == 1 || j == 7)) {
                    board[i][j] = 'N';
                } else if (i == 9 && (j == 2 || j == 6)) {
                    board[i][j] = 'B';
                } else if (i == 9 && (j == 3 || j == 5)) {
                    board[i][j] = 'A';
                } else if (i == 9 && j == 4) {
                    board[i][j] = 'K';
                } else if (i == 7 && (j == 1 || j == 7)) {
                    board[i][j] = 'C';
                } else if (i == 6 && (j == 0 || j == 2 || j == 4 || j == 6 || j == 8)) {
                    board[i][j] = 'P';
                } else {
                    board[i][j] = ' ';
                }
            }
        }
    }

    public void showMultiPV(boolean showMultiPV) {
        this.showMultiPV = showMultiPV;
    }

    private void setNewBoard(String fenCode) {
        if (StringUtils.isEmpty(fenCode)) {
            initChessBoard(board);
        } else {
            setBoard(fenCode);
        }
    }

    public void setBoardStyle(BoardStyle style, Canvas canvas) {
        boardRender = createRender(style, canvas);
        this.paint();
    }

    /**
     * 按棋盘样式创建渲染器，四种新风格都是扁平渲染器换配色
     */
    private static BaseBoardRender createRender(BoardStyle style, Canvas canvas) {
        switch (style) {
            case CUSTOM: {
                return new CustomBoardRender(canvas);
            }
            case MODERN: {
                return new FlatBoardRender(canvas, FlatBoardRender.MODERN);
            }
            case WOOD: {
                return new FlatBoardRender(canvas, FlatBoardRender.WOOD);
            }
            case DARK: {
                return new FlatBoardRender(canvas, FlatBoardRender.DARK);
            }
            case JADE: {
                return new FlatBoardRender(canvas, FlatBoardRender.JADE);
            }
            default: {
                return new DefaultBoardRender(canvas);
            }
        }
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    /**
     * 切换对局模式。切到揭棋会新开一局；切回象棋恢复初始局面。
     */
    public void setGameMode(GameMode mode) {
        gameMode = mode == null ? GameMode.XIANGQI : mode;
        JieqiTrace.log("模式切换 -> " + gameMode);

        remark = null;
        prevStep = null;
        if (gameMode == GameMode.JIEQI) {
            newJieqiGame(System.nanoTime());
        } else {
            jieqi = null;
            jieqiMessage = "";
            initChessBoard(board);
            paint();
        }
    }

    /**
     * 新开一局揭棋（seed 用于复现）
     */
    public void newJieqiGame(long seed) {
        jieqi = new JieqiPosition(seed);
        jieqiMessage = "";
        remark = null;
        prevStep = null;
        syncJieqiBoard();
        paint();
        JieqiTrace.log("揭棋新局 seed=" + seed + " 暗子数=" + hiddenCount() + JieqiTrace.dump(board));
    }

    /**
     * 盘面上还有多少个暗子
     */
    public int hiddenCount() {
        int n = 0;
        for (char[] row : board) {
            for (char ch : row) {
                if (JieqiPosition.isHiddenPiece(ch)) {
                    n++;
                }
            }
        }
        return n;
    }

    public JieqiPosition getJieqiPosition() {
        return jieqi;
    }

    public void applyJieqiGrid(char[][] grid) {
        applyJieqiGrid(grid, '\0', 0L);
    }

    public void applyJieqiGrid(char[][] grid, char ourSide) {
        applyJieqiGrid(grid, ourSide, 0L);
    }

    /**
     * 用连线截到的棋盘校准揭棋局面：
     * 先吸收"我们已走、对方棋盘还没显示"的那一手，再按盘面差分推断对方走了什么，
     * 更新行棋方，最后重算棋子池。
     *
     * @param ourSide   我们的颜色（w/b），用连线时才知道；0 表示未知
     * @param capturedAt 这张截图的采集时间，用于确认点击有没有生效
     */
    public void applyJieqiGrid(char[][] grid, char ourSide, long capturedAt) {
        if (jieqi == null) {
            jieqi = new JieqiPosition(System.nanoTime());
        }

        char[][] frame = new char[10][9];
        for (int r = 0; r < 10; r++) {
            System.arraycopy(grid[r], 0, frame[r], 0, 9);
        }

        pendingJieqiRollback = false;
        absorbPendingInto(frame, ourSide, capturedAt);

        char[][] before = new char[10][9];
        for (int r = 0; r < 10; r++) {
            System.arraycopy(jieqi.grid[r], 0, before[r], 0, 9);
        }

        char mover = detectMover(jieqi.grid, frame, ourSide);
        lastJieqiMove = detectMove(before, frame, mover);
        lastJieqiMoveText = lastJieqiMove == null ? null : describeOnBoard(before, lastJieqiMove);
        if (mover != 0) {
            // 对方的这一手走完后，轮到另一方
            char nowSide = (char) (mover == 'w' ? 'b' : 'w');
            if (nowSide != jieqi.side) {
                JieqiTrace.log("揭棋连线：行棋方改为 " + (nowSide == 'w' ? "红方" : "黑方")
                        + "（上一手是" + (mover == 'w' ? "红方" : "黑方") + "走的）");
            }
            jieqi.side = nowSide;
        }

        if (pendingJieqiRollback && ourSide != 0) {
            if (jieqi.side != ourSide) {
                JieqiTrace.log("揭棋连线：上一手我们点的没落到对方棋盘上，行棋方仍算 "
                        + (ourSide == 'w' ? "红方" : "黑方") + "（我们），重新分析");
            }
            jieqi.side = ourSide;
        }

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char ch = frame[r][c];
                jieqi.grid[r][c] = ch != ' ' && ch != '.' ? ch : '.';
            }
        }

        // 重算棋子池：满池减去盘面上已经翻明的子
        jieqi.poolRed = JieqiPosition.newPool();
        jieqi.poolBlack = JieqiPosition.newPool();
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char ch = jieqi.grid[r][c];
                if (ch != '.' && !JieqiPosition.isHiddenPiece(ch)) {
                    char up = Character.toUpperCase(ch);
                    Map<Character, Integer> pool = Character.isLowerCase(ch) ? jieqi.poolBlack : jieqi.poolRed;
                    if (pool.containsKey(up)) {
                        pool.put(up, Math.max(0, pool.get(up) - 1));
                    }
                }
            }
        }

        remark = null;
        syncJieqiBoard();
        paint();
    }

    public String getPendingJieqiMove() {
        return pendingJieqiMove;
    }

    /**
     * 是否没有待确认的走子（pendingJieqiMove 为空）
     */
    public boolean isPendingJieqiShown() {
        return pendingJieqiMove == null;
    }

    /**
     * 点下去超过 1.5 秒、对方棋盘还是走之前的样子（且已确认读过新帧）时，
     * 说明点击可能没生效，需要重新点一次（最多 3 次）
     */
    public boolean shouldReClickJieqi() {
        return pendingJieqiMove != null
                && pendingJieqiTry < 3
                && System.currentTimeMillis() - pendingJieqiClickAt > 1500L
                && pendingJieqiStillBefore
                && pendingJieqiFrameAt >= pendingJieqiClickAt + 700L;
    }

    public int getPendingJieqiTry() {
        return pendingJieqiTry;
    }

    public void markJieqiReClicked() {
        pendingJieqiTry++;
        pendingJieqiClickAt = System.currentTimeMillis();
    }

    public void clearPendingJieqiMove() {
        pendingJieqiMove = null;
        pendingJieqiPiece = 0;
        pendingJieqiTry = 0;
    }

    public boolean wasJieqiRollback() {
        return pendingJieqiRollback;
    }

    /**
     * 把我们已走、对方棋盘还没显示的那一手补回帧里。
     * 超过了 5 秒还不出现，就判定没走成，标记回滚让外面重新分析。
     */
    private void absorbPendingInto(char[][] grid, char ourSide, long capturedAt) {
        if (pendingJieqiMove == null || jieqi == null) {
            return;
        }
        if (System.currentTimeMillis() - pendingJieqiAt > 5000L) {
            JieqiTrace.log("揭棋连线：这一手 " + pendingJieqiMove
                    + " 5 秒没在对方棋盘上出现（点击没落下去，或者对方棋盘不认这一步），仍然算我们走，重新分析换一步走");
            pendingJieqiRollback = true;
            pendingJieqiStillBefore = false;
            clearPendingJieqiMove();
        } else {
            if (capturedAt > 0L) {
                pendingJieqiFrameAt = capturedAt;
            }
            if (!replayPendingIfMissing(grid, pendingJieqiMove, pendingJieqiPiece)) {
                pendingJieqiStillBefore = false;
                clearPendingJieqiMove();
            } else {
                pendingJieqiStillBefore = true;
            }
        }
    }

    /**
     * 若帧里起点还有我们的子（说明那一手还没显示出来），就把 pendingMove 补进帧。
     *
     * @return true 表示"对方棋盘仍是走之前的样子"、已把我们的子补上；false 表示帧里已有结果或无法补
     */
    static boolean replayPendingIfMissing(char[][] frame, String pendingMove, char pendingPiece) {
        if (frame == null || pendingMove == null || pendingMove.length() < 4 || !occupiedChar(pendingPiece)) {
            return false;
        }

        int[] m;
        try {
            m = JieqiPosition.parseIccs(pendingMove);
        } catch (Exception e) {
            return false;
        }
        if (m == null || m.length < 4) {
            return false;
        }

        int fr = m[0];
        int fc = m[1];
        int tr = m[2];
        int tc = m[3];
        if (fr < 0 || fr > 9 || fc < 0 || fc > 8 || tr < 0 || tr > 9 || tc < 0 || tc > 8) {
            return false;
        }

        char atFrom = frame[fr][fc];
        boolean stillBefore = occupiedChar(atFrom) && JieqiPosition.colorOf(atFrom) == JieqiPosition.colorOf(pendingPiece);
        if (!stillBefore) {
            return false;
        }
        frame[fr][fc] = '.';
        frame[tr][tc] = pendingPiece;
        return true;
    }

    private static char detectMover(char[][] prev, char[][] now) {
        return detectMover(prev, now, '\0');
    }

    /**
     * 对比前后两帧判断哪一方走了棋：
     * 统计"少了子"的格子，1~2 个且同一颜色就是走子方；
     * 两边都少了子（比如吃过子）时，按 ourSide 推断是对方动的（推测法，尽可能合理）。
     */
    private static char detectMover(char[][] prev, char[][] now, char ourSide) {
        if (prev == null || now == null) {
            return '\0';
        }

        int sources = 0;
        char mover = 0;
        boolean bothSides = false;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char a = prev[r][c];
                char b = now[r][c];
                boolean hadA = a != 0 && a != ' ' && a != '.';
                boolean hasB = b != 0 && b != ' ' && b != '.';
                if (hadA && !hasB) {
                    char color = JieqiPosition.colorOf(a);
                    if (mover != 0 && mover != color) {
                        bothSides = true;
                    }
                    mover = color;
                    sources++;
                }
            }
        }

        if (sources == 0 || sources > 2) {
            return '\0';
        }
        if (!bothSides) {
            return mover;
        }
        // 两边都动过：应当是对手刚好在我们之后走了一步
        if (ourSide == 'w') {
            return 'b';
        }
        return ourSide == 'b' ? 'w' : '\0';
    }

    public String getLastJieqiMove() {
        return lastJieqiMove;
    }

    public String getLastJieqiMoveText() {
        return lastJieqiMoveText;
    }

    /**
     * 把含暗子的盘面转成"能正常翻译"的盘面：暗子按其初始方位推断底牌
     */
    private char[][] effectiveBoard(char[][] g) {
        char[][] copy = new char[10][9];
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char ch = g[r][c];
                if (ch != ' ' && ch != '.') {
                    char t = JieqiPosition.isHiddenPiece(ch) ? JieqiPosition.positionPiece(r, c) : Character.toUpperCase(ch);
                    if (t == 0) {
                        t = ' ';
                    }
                    copy[r][c] = JieqiPosition.colorOf(ch) == 'w' ? Character.toUpperCase(t) : Character.toLowerCase(t);
                } else {
                    copy[r][c] = ' ';
                }
            }
        }
        return copy;
    }

    private String describeOnBoard(char[][] g, String iccs) {
        if (iccs == null || iccs.length() < 4) {
            return "";
        }
        char[][] eff = effectiveBoard(g);
        StringBuilder sb = new StringBuilder();
        try {
            XiangqiUtils.translate(eff, sb, iccs.substring(0, 4), false);
        } catch (Throwable ignored) {
            return iccs;
        }
        return fixNullName(sb.toString(), eff, iccs);
    }

    /**
     * 揭棋的走法文字里会出现 null（牌没翻出来、查不到名字）。
     * 用棋盘上该子（或"暗子"）的名字把 null 换掉。
     */
    private static String fixNullName(String text, char[][] eff, String iccs) {
        if (text == null || !text.contains("null")) {
            return text;
        }

        char piece = ' ';
        if (iccs != null && iccs.length() >= 4) {
            int fromI = 9 - (iccs.charAt(1) - '0');
            int fromJ = iccs.charAt(0) - 'a';
            if (fromI >= 0 && fromI < 10 && fromJ >= 0 && fromJ < 9) {
                piece = eff[fromI][fromJ];
            }
        }

        String name = XiangqiUtils.map.get(piece);
        if (name == null || name.isBlank()) {
            name = "暗子";
        }
        return text.replace("null", name);
    }

    /**
     * 把一连串着法翻译成文字（用于思考细节的 PV 显示），暗子按推断的底牌翻译
     */
    public String describeJieqiPv(List<String> moves) {
        if (jieqi == null || moves == null || moves.isEmpty()) {
            return "";
        }

        char[][] eff = effectiveBoard(jieqi.grid);
        StringBuilder sb = new StringBuilder();
        for (String mv : moves) {
            if (mv == null || mv.length() < 4) {
                continue;
            }
            String m4 = mv.substring(0, 4);
            try {
                StringBuilder one = new StringBuilder();
                XiangqiUtils.translate(eff, one, m4, false);
                sb.append(fixNullName(one.toString(), eff, m4));
            } catch (Throwable ignored) {
                sb.append(m4);
            }
            sb.append("  ");

            int fromI = 9 - Integer.parseInt(String.valueOf(m4.charAt(1)));
            int fromJ = m4.charAt(0) - 'a';
            int toI = 9 - Integer.parseInt(String.valueOf(m4.charAt(3)));
            int toJ = m4.charAt(2) - 'a';
            if (fromI >= 0 && fromI < 10 && toI >= 0 && toI < 10 && fromJ >= 0 && fromJ < 9 && toJ >= 0 && toJ < 9) {
                eff[toI][toJ] = eff[fromI][fromJ];
                eff[fromI][fromJ] = ' ';
            }
        }

        if (sb.length() >= 2) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }

    /**
     * 从两帧差分推断走子方走的哪一手（起点是唯一少子的格子）
     */
    private String detectMove(char[][] before, char[][] now, char moverColor) {
        if (before == null || now == null || moverColor == 0) {
            return null;
        }

        int fromR = -1;
        int fromC = -1;
        int sources = 0;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                if (occupiedChar(before[r][c]) && !occupiedChar(now[r][c])) {
                    sources++;
                    fromR = r;
                    fromC = c;
                }
            }
        }
        if (sources != 1) {
            return null;
        }

        List<int[]> cand = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            for (int cx = 0; cx < 9; cx++) {
                if ((r != fromR || cx != fromC) && before[r][cx] != now[r][cx]
                        && occupiedChar(now[r][cx]) && JieqiPosition.colorOf(now[r][cx]) == moverColor) {
                    cand.add(new int[]{r, cx});
                }
            }
        }
        if (cand.isEmpty()) {
            return null;
        }

        // 有多个候选终点时，用合法着法过滤
        int[] to = cand.get(0);
        if (cand.size() > 1) {
            try {
                for (int[] t : jieqi.legalTargets(fromR, fromC)) {
                    for (int[] u : cand) {
                        if (t[0] == u[0] && t[1] == u[1]) {
                            to = u;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return JieqiPosition.iccsOf(fromR, fromC, to[0], to[1]);
    }

    private static boolean occupiedChar(char ch) {
        return ch != 0 && ch != ' ' && ch != '.';
    }

    /**
     * 把揭棋局面同步到绘制用的棋盘数组
     */
    private void syncJieqiBoard() {
        if (jieqi == null) {
            return;
        }
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char ch = jieqi.grid[r][c];
                board[r][c] = ch == '.' ? ' ' : ch;
            }
        }
    }

    /**
     * 揭棋模式下的鼠标点击（选中 / 走子）
     */
    private String jieqiClick(int x, int y) {
        if (jieqi == null) {
            return null;
        }

        int padding = boardRender.getPadding(this.boardSize);
        int piece = boardRender.getPieceSize(this.boardSize);
        int i = (x - padding) / piece;
        int j = (y - padding) / piece;
        int c = boardRender.getReverseX(i, this.isReverse);
        int r = boardRender.getReverseY(j, this.isReverse);
        if (r < 0 || r > 9 || c < 0 || c > 8) {
            return null;
        }

        JieqiTrace.log("点击像素(" + x + "," + y + ") -> 行" + r + " 列" + c
                + " 该格=" + displayName(jieqi.grid[r][c])
                + " 已选=" + (remark == null ? "无" : "行" + remark.y + " 列" + remark.x));

        if (jieqi.over) {
            jieqiMessage = jieqi.winner == 0 ? "对局已结束（和棋）" : (jieqi.winner == 'w' ? "对局已结束，红胜" : "对局已结束，黑胜");
            JieqiTrace.log("对局已结束，忽略点击");
            paint();
            return null;
        }

        char clicked = jieqi.grid[r][c];
        boolean ownPiece = clicked != ' ' && clicked != '.' && JieqiPosition.colorOf(clicked) == jieqi.side;
        if (remark == null) {
            if (ownPiece) {
                if (stepSound) {
                    sound.pick();
                }
                remark = new Point(c, r);
                jieqiMessage = "";
                JieqiTrace.log("选中 行" + r + " 列" + c + "（" + displayName(clicked) + "）");
            } else {
                jieqiMessage = jieqi.side == 'w' ? "该红方走棋" : "该黑方走棋";
                JieqiTrace.log("点击空位/对方子，未选中");
            }
            paint();
            return null;
        }

        int fr = remark.y;
        int fc = remark.x;
        if (fr == r && fc == c) {
            remark = null;
            paint();
            return null;
        }
        if (ownPiece) {
            // 改选自己的另一枚子
            if (stepSound) {
                sound.pick();
            }
            remark = new Point(c, r);
            paint();
            return null;
        }

        boolean legal = false;
        for (int[] t : jieqi.legalTargets(fr, fc)) {
            if (t[0] == r && t[1] == c) {
                legal = true;
                break;
            }
        }
        if (!legal) {
            jieqiMessage = "该子不能走到这里";
            JieqiTrace.log("非法目标 行" + r + " 列" + c + "（选中 行" + fr + " 列" + fc + "）");
            paint();
            return null;
        }

        try {
            boolean hidden = JieqiPosition.isHiddenPiece(jieqi.grid[fr][fc]);
            boolean captured = jieqi.grid[r][c] != ' ' && jieqi.grid[r][c] != '.';
            char moverBefore = jieqi.grid[fr][fc];
            JieqiPosition.MoveEvent ev = jieqi.makeMove(JieqiPosition.iccsOf(fr, fc, r, c));
            remark = null;
            syncJieqiBoard();
            clearTip();

            StringBuilder msg = new StringBuilder();
            if (ev.flip != null) {
                msg.append("翻开 ").append(displayName(ev.flip));
            } else if (hidden) {
                msg.append("暗子移动");
            }
            if (captured) {
                if (msg.length() > 0) {
                    msg.append("，");
                }
                msg.append("吃子");
            }
            if (ev.check) {
                if (msg.length() > 0) {
                    msg.append("，");
                }
                msg.append("将军");
            }
            if (ev.gameOver) {
                if (msg.length() > 0) {
                    msg.append("，");
                }
                msg.append(ev.winner == 0 ? "判和" : (ev.winner == 'w' ? "红胜" : "黑胜"));
            }
            jieqiMessage = msg.toString();

            JieqiTrace.log("走出 " + JieqiPosition.iccsOf(fr, fc, r, c)
                    + " 行" + fr + "列" + fc
                    + "(" + displayName(moverBefore) + ")"
                    + " -> 行" + r + "列" + c
                    + " 翻开=" + (ev.flip == null ? "无" : displayName(ev.flip))
                    + " 吃子=" + captured
                    + " 将军=" + ev.check
                    + " 结束=" + ev.gameOver
                    + " 轮到=" + (jieqi.side == 'w' ? "红" : "黑")
                    + " 暗子池红" + poolSum(jieqi.poolRed) + "/黑" + poolSum(jieqi.poolBlack)
                    + JieqiTrace.dump(board));

            if (stepSound) {
                if (ev.gameOver) {
                    sound.over();
                } else if (ev.check) {
                    sound.check();
                } else if (captured) {
                    sound.eat();
                } else {
                    sound.move();
                }
            }
        } catch (IllegalArgumentException e) {
            jieqiMessage = "非法走法";
            JieqiTrace.log("引擎层拒绝走法: " + e.getMessage());
        }

        paint();
        return null;
    }

    /**
     * 引擎分析后自动走出的一手（揭棋）
     *
     * @return 是否走成功
     */
    public boolean playJieqiMove(String iccs) {
        if (jieqi == null || iccs == null || iccs.length() < 4) {
            return false;
        }
        String mv = iccs.substring(0, 4);

        int[] m;
        try {
            m = JieqiPosition.parseIccs(mv);
        } catch (Exception e) {
            return false;
        }
        if (m == null || m.length < 4) {
            return false;
        }

        int fr = m[0];
        int fc = m[1];
        int tr = m[2];
        int tc = m[3];
        if (fr < 0 || fr > 9 || fc < 0 || fc > 8 || tr < 0 || tr > 9 || tc < 0 || tc > 8) {
            return false;
        }
        if (jieqi.over) {
            return false;
        }

        boolean legal = false;
        for (int[] t : jieqi.legalTargets(fr, fc)) {
            if (t[0] == tr && t[1] == tc) {
                legal = true;
                break;
            }
        }
        if (!legal) {
            return false;
        }

        try {
            boolean hidden = JieqiPosition.isHiddenPiece(jieqi.grid[fr][fc]);
            boolean captured = jieqi.grid[tr][tc] != ' ' && jieqi.grid[tr][tc] != '.';
            char moverBefore = jieqi.grid[fr][fc];
            JieqiPosition.MoveEvent ev = jieqi.makeMove(mv);
            remark = null;
            syncJieqiBoard();
            clearTip();

            // 记录这一手，等对方棋盘出现后再确认
            pendingJieqiMove = mv;
            pendingJieqiPiece = jieqi.grid[tr][tc];
            pendingJieqiAt = System.currentTimeMillis();
            pendingJieqiClickAt = pendingJieqiAt;
            pendingJieqiFrameAt = pendingJieqiAt;
            pendingJieqiStillBefore = false;
            pendingJieqiTry = 0;

            StringBuilder msg = new StringBuilder();
            if (ev.flip != null) {
                msg.append("翻开 ").append(displayName(ev.flip));
            } else if (hidden) {
                msg.append("暗子移动");
            }
            if (captured) {
                if (msg.length() > 0) {
                    msg.append("，");
                }
                msg.append("吃子");
            }
            if (ev.check) {
                if (msg.length() > 0) {
                    msg.append("，");
                }
                msg.append("将军");
            }
            if (ev.gameOver) {
                if (msg.length() > 0) {
                    msg.append("，");
                }
                msg.append(ev.winner == 0 ? "判和" : (ev.winner == 'w' ? "红胜" : "黑胜"));
            }
            jieqiMessage = msg.toString();

            JieqiTrace.log("引擎自动走子 " + mv
                    + " 行" + fr + "列" + fc
                    + "(" + displayName(moverBefore) + ")"
                    + " -> 行" + tr + "列" + tc
                    + " 翻开=" + (ev.flip == null ? "无" : displayName(ev.flip))
                    + " 吃子=" + captured
                    + " 将军=" + ev.check
                    + " 结束=" + ev.gameOver
                    + " 轮到=" + (jieqi.side == 'w' ? "红" : "黑"));

            if (stepSound) {
                if (ev.gameOver) {
                    sound.over();
                } else if (ev.check) {
                    sound.check();
                } else if (captured) {
                    sound.eat();
                } else {
                    sound.move();
                }
            }
        } catch (IllegalArgumentException e) {
            jieqiMessage = "非法走法";
            JieqiTrace.log("引擎自动走子被拒: " + e.getMessage());
            paint();
            return false;
        }

        paint();
        return true;
    }

    /**
     * 给引擎用的盘面：暗子按初始方位推断成普通棋子
     */
    public char[][] jieqiSearchBoard() {
        char[][] copy = new char[10][9];
        if (jieqi == null) {
            return copy;
        }
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char ch = jieqi.grid[r][c];
                if (ch != ' ' && ch != '.') {
                    char t = jieqi.effectiveType(r, c);
                    copy[r][c] = JieqiPosition.colorOf(ch) == 'w' ? Character.toUpperCase(t) : Character.toLowerCase(t);
                } else {
                    copy[r][c] = ' ';
                }
            }
        }
        return copy;
    }

    public String jieqiSearchFen() {
        return jieqi == null ? null : fenCode(jieqiSearchBoard(), jieqi.side == 'w');
    }

    public String jieqiEngineFen() {
        return jieqi == null ? null : jieqi.engineFen();
    }

    public boolean jieqiMoveLegal(String iccs) {
        if (jieqi == null || iccs == null || iccs.length() < 4) {
            return false;
        }
        int[] m = JieqiPosition.parseIccs(iccs.substring(0, 4));
        for (int[] t : jieqi.legalTargets(m[0], m[1])) {
            if (t[0] == m[2] && t[1] == m[3]) {
                return true;
            }
        }
        return false;
    }

    public String describeJieqiMove(String iccs) {
        if (iccs == null || iccs.length() < 4) {
            return "";
        }
        char[][] eff = jieqiSearchBoard();
        StringBuilder sb = new StringBuilder();
        try {
            XiangqiUtils.translate(eff, sb, iccs.substring(0, 4), false);
        } catch (Exception e) {
            return iccs;
        }
        return fixNullName(sb.toString(), eff, iccs);
    }

    /**
     * 棋子的显示名（暗子显示为红暗/黑暗）
     */
    public static String displayName(char ch) {
        if (JieqiPosition.isHiddenPiece(ch)) {
            return ch == 'X' ? "红暗" : "黑暗";
        }
        String word = XiangqiUtils.map.get(ch);
        return word == null ? String.valueOf(ch) : word;
    }

    private static int poolSum(Map<Character, Integer> pool) {
        if (pool == null) {
            return 0;
        }
        int sum = 0;
        for (int v : pool.values()) {
            sum += v;
        }
        return sum;
    }

    /**
     * 状态栏文字（揭棋模式显示走棋方和暗子池）
     */
    public String statusText() {
        if (gameMode == GameMode.JIEQI && jieqi != null) {
            StringBuilder sb = new StringBuilder("[揭棋] ");
            sb.append(jieqi.side == 'w' ? "红方走棋" : "黑方走棋");
            sb.append("　暗子池 红").append(poolSum(jieqi.poolRed)).append(" / 黑").append(poolSum(jieqi.poolBlack));
            if (jieqiMessage != null && !jieqiMessage.isEmpty()) {
                sb.append("　").append(jieqiMessage);
            }
            return sb.toString();
        }
        return "";
    }

    public String mouseClick(int x, int y, boolean canRedGo, boolean canBlackGo) {
        if (gameMode == GameMode.JIEQI) {
            return jieqiClick(x, y);
        }
        int padding = boardRender.getPadding(this.boardSize);
        int piece = boardRender.getPieceSize(this.boardSize);
        int i = (x - padding) / piece;
        int j = (y - padding) / piece;
        i = boardRender.getReverseX(i, isReverse);
        j = boardRender.getReverseY(j, isReverse);

        if (i < 0 || i > 8 || j < 0 || j > 9) {
            return null;
        }

        if (remark != null) {
            boolean isRed = XiangqiUtils.isRed(board[remark.y][remark.x]);
            if (isRed && !canRedGo || !isRed && !canBlackGo) {
                return null;
            } else if (board[j][i] != ' ' && XiangqiUtils.isRed(board[j][i]) == isRed) {
                if (stepSound) sound.pick();
                remark = new Point(i, j);
                paint();
                return null;
            } else if (!XiangqiUtils.canGo(board, remark.y, remark.x, j, i)) {
                return null;
            } else {
                return move(remark.x, remark.y, i, j);
            }
        } else {
            if (board[j][i] != ' ') {
                boolean isRed = XiangqiUtils.isRed(board[j][i]);
                if (!(isRed && !canRedGo || !isRed && !canBlackGo)) {
                    if (stepSound) sound.pick();
                    remark = new Point(i, j);
                    paint();
                }
            }
            return null;
        }

    }

    private void setBoard(String fenCode) {
        XiangqiUtils.fenToBoard(this.board, fenCode);
    }

    public String fenCode(boolean redGo) {
        return fenCode(this.board, redGo);
    }

    public static String fenCode(char[][] board, Boolean redGo) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < board.length; i++) {
            int count = 0;
            for (int j = 0; j < board[0].length; j++) {
                if (board[i][j] != ' ') {
                    if (count != 0) {
                        sb.append(count);
                        count = 0;
                    }
                    sb.append(board[i][j]);
                } else {
                    count++;
                }
            }
            if (count != 0) {
                sb.append(count);
            }
            if (i != board.length - 1) {
                sb.append("/");
            }
        }
        if (redGo != null) {
            if (redGo) {
                sb.append(" w - - 0 1");
            } else {
                sb.append(" b - - 0 1");
            }
        }
        return sb.toString();
    }

    /**
     * 浏览棋谱
     * @param fenCode
     * @param moveList
     */
    public void browseChessRecord(String fenCode, List<String> moveList) {
        setBoard(fenCode);
        if (moveList == null || moveList.isEmpty()) {
            // 开始局面
            prevStep = null;
            moveTips.clear();
            remark = null;
            manualList.clear();
            paint();
        } else {
            for (int i = 0; i < moveList.size() - 1; i++) {
                Step s = stepForBoard(moveList.get(i));
                board[s.getEnd().y][s.getEnd().x] = board[s.getStart().y][s.getStart().x];
                board[s.getStart().y][s.getStart().x] = ' ';
            }
            Step s = stepForBoard(moveList.get(moveList.size() - 1));
            move(s.getStart().x, s.getStart().y, s.getEnd().x, s.getEnd().y);
        }
    }

    /**
     * 清除棋步提示
     */
    public void clearTip() {
        moveTips.clear();
        paint();
    }

    public void setTip(String firstMove, String secondMove, int pv) {
        if (pv < moveTips.size()) {
            moveTips.clear();
        }
        if (pv > moveTips.size()) {
            moveTips.add(new MoveTip(stepForBoard(firstMove), stepForBoard(secondMove)));
        } else {
            moveTips.set(pv - 1, new MoveTip(stepForBoard(firstMove), stepForBoard(secondMove)));
        }
        if (stepTip) {
            paint();
        }
    }

    public void setManualList(List<String> list) {
        manualList.clear();
        for (String move : list) {
            manualList.add(stepForBoard(move));
        }
        if (manualTip)
            paint();
    }

    public Step stepForBoard(String step) {
        if (step == null) {
            return null;
        }
        char c = step.charAt(0);
        int x1 = c - 'a';
        c = step.charAt(1);
        int y1 = 9 - Integer.parseInt(String.valueOf(c));
        c = step.charAt(2);
        int x2 = c - 'a';
        c = step.charAt(3);
        int y2 = 9 - Integer.parseInt(String.valueOf(c));
        return new Step(new Point(x1, y1), new Point(x2, y2));
    }

    public Step move(String step) {
        if (step == null || step.length() != 4) {
            return null;
        }
        Step s = stepForBoard(step);
        move(s.getStart().x, s.getStart().y, s.getEnd().x, s.getEnd().y);
        return s;
    }

    public String move(int x1, int y1, int x2, int y2) {
        char tmp = board[y2][x2];
        boolean isRed = XiangqiUtils.isRed(board[y1][x1]);
        board[y2][x2] = board[y1][x1];
        board[y1][x1] = ' ';
        if (XiangqiUtils.isJiang(board, isRed)) {
            // 不可送将
            if (stepSound) {
                sound.check();
            }
            board[y1][x1] = board[y2][x2];
            board[y2][x2] = tmp;
            return null;
        }
        if (stepSound) {
            if (XiangqiUtils.isSha(board, !isRed)) {
                // 绝杀
                sound.over();
            } else if (XiangqiUtils.isJiang(board, !isRed)) {
                // 将军
                sound.check();
            } else {
                // 是否吃子
                if (tmp == ' ') {
                    sound.move();
                } else {
                    sound.eat();
                }
            }
        }

        prevStep = new Step(new Point(x1, y1), new Point(x2, y2));
        moveTips.clear();
        remark = null;
        manualList.clear();
        paint();
        return stepForEngine(x1, y1, x2, y2);
    }

    public List<String> getTacticList(boolean redGo) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < board.length; i++) {
            for (int j = 0; j < board[0].length; j++) {
                if (board[i][j] != ' ' && XiangqiUtils.isRed(board[i][j]) == redGo) {
                    for (int i2 = 0; i2 < board.length; i2++) {
                        for (int j2 = 0; j2 < board[0].length; j2++) {
                            if ((i != i2 || j != j2) && XiangqiUtils.canGo(board, i, j, i2, j2)) {
                                list.add(stepForEngine(j, i, j2, i2));
                            }
                        }
                    }
                }
            }
        }
        return list;
    }

    public static String stepForEngine(int x1, int y1, int x2, int y2) {
        StringBuffer sb = new StringBuffer();
        sb.append((char)('a' + x1));
        sb.append(9 - y1);
        sb.append((char)('a' + x2));
        sb.append(9 - y2);
        return sb.toString();
    }

    private void paint() {
        this.boardRender.paint(boardSize, this.board, prevStep, remark, stepTip,
                showMultiPV, moveTips, isReverse, showNumber, manualTip, manualList);
    }

    public void refresh() {
        paint();
    }

    /**
     * 设置翻转
     * @param isReverse
     */
    public void reverse(boolean isReverse) {
        if (this.isReverse != isReverse) {
            this.isReverse = isReverse;
            paint();
        }
    }

    /**
     * 设置棋盘样式
     * @param bs
     */
    public void setBoardSize(BoardSize bs) {
        this.boardSize = bs;
        paint();
    }

    /**
     * 设置棋步提示
     * @param f
     */
    public void setStepTip(boolean f) {
        this.stepTip = f;
        paint();
    }

    public void setManualTip(boolean f) {
        this.manualTip = f;
        paint();
    }

    public void setShowNumber(boolean showNumber) {
        this.showNumber = showNumber;
        paint();
    }

    /**
     * 设置走棋音效
     * @param f
     */
    public void setStepSound(boolean f) {
        this.stepSound = f;
    }

    /**
     * 翻译着法(记录棋谱)
     * @param move
     * @return
     */
    public String translate(String move, boolean hasGo) {
        StringBuilder sb = new StringBuilder();
        XiangqiUtils.translate(this.board, sb, move, hasGo);
        return sb.toString();
    }

    /**
     * 翻译引擎着法(思考细节)
     * @param moveList
     * @return
     */
    public String translate(List<String> moveList) {
        for (int i = 0; i < board.length; i++) {
            System.arraycopy(board[i], 0, copyBoard[i], 0, copyBoard[i].length);
        }
        StringBuilder sb = new StringBuilder();
        for (String move : moveList) {
            char a = move.charAt(0), b = move.charAt(1), c = move.charAt(2), d = move.charAt(3);
            int fromJ = a - 'a', toJ = c - 'a';
            int fromI = 9 - Integer.parseInt(String.valueOf(b)), toI = 9 - Integer.parseInt(String.valueOf(d));
            XiangqiUtils.translate(copyBoard, sb, move, false);
            sb.append("  ");
            copyBoard[toI][toJ] = copyBoard[fromI][fromJ];
            copyBoard[fromI][fromJ] = ' ';
        }
        sb.delete(sb.length() - 2, sb.length());
        return sb.toString();
    }

    public char[][] getBoard() {
        return this.board;
    }

    public void autoFitSize(double width, double height, double position) {
        if (boardSize == BoardSize.AUTOFIT_BOARD) {
            if (Properties.getInstance().isShowChessNotation()) {
                width = width - 256;
            }
            position = Math.abs(position);
            width = width * position;
            height = height - 56;
            if (Properties.getInstance().isLinkShowInfo()) {
                height = height - 27;
            }
            int pieceSize;
            if (width / height > 1120 / 1240d) {
                pieceSize = (int) (height / (10 + 1/3d));
            } else {
                pieceSize = (int) (width / (9 + 1/3d));
            }
            if (pieceSize < 42) {
                pieceSize = 42;
            }
            boardRender.setAutoPieceSize(pieceSize);

            paint();
        }
    }
}
