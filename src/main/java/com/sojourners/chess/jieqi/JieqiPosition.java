package com.sojourners.chess.jieqi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

/**
 * 揭棋局面
 * <p>
 * 揭棋（暗棋）与普通象棋的区别：
 * <ul>
 *     <li>除将/帅外的棋子初始都是暗子，用 'X'（红）/'x'（黑）表示；</li>
 *     <li>暗子第一次移动时翻开，翻开的兵种从"棋子池"里随机（或按识别结果）确定；</li>
 *     <li>吃掉对方暗子时，需要从对方的棋子池里扣除一个对应兵种。</li>
 * </ul>
 * 盘面坐标：[行][列]，行 0 在上（黑方底线），行 9 在下（红方底线）。
 */
public class JieqiPosition {

    public static final int ROWS = 10;
    public static final int COLS = 9;

    /** 红方 */
    public static final char RED = 'w';
    /** 黑方 */
    public static final char BLACK = 'b';

    /** 红方暗子 */
    public static final char HIDDEN_RED = 'X';
    /** 黑方暗子 */
    public static final char HIDDEN_BLACK = 'x';

    /** 揭棋的初始棋位（和象棋一样，只是除将/帅外的字都变成暗子） */
    private static final String[] START_ROWS = {
            "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
            ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABNR"
    };

    /** 初始有子的位置 -> 该位置本来是什么子（用于推断被吃掉的暗子底牌） */
    private static final Map<Integer, Character> POWS = new HashMap<>();

    /** 每种棋子的数量（单方） */
    private static final Map<Character, Integer> INIT_POOL = new HashMap<>();

    /** 棋子池的输出顺序（红方用大写、黑方用小写） */
    private static final char[] POOL_ORDER = {'R', 'N', 'B', 'A', 'C', 'P'};

    /** 吃暗子时扣除棋子池的优先级：先扣小兵，最后扣车 */
    private static final char[] CAPTURE_ORDER = {'P', 'A', 'B', 'N', 'C', 'R'};

    /** 盘面 */
    public char[][] grid = new char[ROWS][COLS];
    /** 轮到哪一方走棋：RED / BLACK */
    public char side = RED;
    /** 红方剩余棋子池 */
    public Map<Character, Integer> poolRed = newPool();
    /** 黑方剩余棋子池 */
    public Map<Character, Integer> poolBlack = newPool();
    /** 已走步数 */
    public int ply = 0;
    /** 距离上次吃子的步数（用于和棋判定） */
    public int noCapture = 0;
    /** 是否终局 */
    public boolean over = false;
    /** 胜方（RED / BLACK），和棋为 0 */
    public char winner = 0;
    /** 暗子位置的底牌 */
    private Map<Integer, Character> truth = new HashMap<>();

    /**
     * 是否暗子
     */
    public static boolean isHiddenPiece(char ch) {
        return ch == HIDDEN_RED || ch == HIDDEN_BLACK;
    }

    /**
     * 把 (行, 列) 映射成 key
     */
    public static int key(int r, int c) {
        return r * 9 + c;
    }

    /**
     * 新建一个满编的棋子池
     */
    public static Map<Character, Integer> newPool() {
        return new HashMap<>(INIT_POOL);
    }

    /**
     * 随机开一个揭棋局面
     *
     * @param seed 随机种子，便于复现
     */
    public JieqiPosition(long seed) {
        Random rng = new Random(seed);

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                grid[r][c] = '.';
            }
        }

        for (Entry<Integer, Character> e : POWS.entrySet()) {
            grid[e.getKey() / 9][e.getKey() % 9] = e.getValue();
        }

        // 逐方洗牌：把该方的非将棋子随机分配给该方的暗子位置
        for (char color : new char[]{RED, BLACK}) {
            List<int[]> cells = new ArrayList<>();
            List<Character> chars = new ArrayList<>();

            for (Entry<Integer, Character> e : POWS.entrySet()) {
                int r = e.getKey() / 9;
                int c = e.getKey() % 9;
                char ch = e.getValue();
                if (colorOf(ch) == color && Character.toLowerCase(ch) != 'k') {
                    cells.add(new int[]{r, c});
                }
            }

            for (char pc : POOL_ORDER) {
                for (int i = 0; i < INIT_POOL.get(pc); i++) {
                    chars.add(pc);
                }
            }

            Collections.shuffle(chars, rng);

            for (int i = 0; i < cells.size(); i++) {
                int[] rc = cells.get(i);
                char trueCh = chars.get(i);
                if (color == BLACK) {
                    trueCh = Character.toLowerCase(trueCh);
                }
                grid[rc[0]][rc[1]] = isHiddenPiece(trueCh) ? trueCh
                        : (Character.isLowerCase(trueCh) ? HIDDEN_BLACK : HIDDEN_RED);
                truth.put(key(rc[0], rc[1]), trueCh);
            }
        }

        side = RED;
    }

    private JieqiPosition() {
    }

    /**
     * 从指定的盘面构造局面（识别结果导入时使用）
     */
    public static JieqiPosition fromGrid(char[][] rows, char side,
                                         Map<Character, Integer> poolRed,
                                         Map<Character, Integer> poolBlack,
                                         Map<Integer, Character> truths,
                                         int noCapture, int ply) {
        JieqiPosition p = new JieqiPosition();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                p.grid[r][c] = r < rows.length && c < rows[r].length ? rows[r][c] : ' ';
            }
        }
        p.side = side;
        p.poolRed = poolRed != null ? new HashMap<>(poolRed) : newPool();
        p.poolBlack = poolBlack != null ? new HashMap<>(poolBlack) : newPool();
        p.noCapture = noCapture;
        p.ply = ply;
        if (truths != null) {
            p.truth.putAll(truths);
        }
        // 没用显式底牌的地方，用初始棋位推断（暗子的底牌一定来自该位置原来的兵种）
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (isHiddenPiece(p.grid[r][c]) && !p.truth.containsKey(key(r, c))) {
                    Character beh = POWS.get(key(r, c));
                    if (beh != null && "RNBACP".indexOf(Character.toUpperCase(beh)) >= 0) {
                        p.truth.put(key(r, c), beh);
                    }
                }
            }
        }
        return p;
    }

    public JieqiPosition copy() {
        JieqiPosition p = new JieqiPosition();
        for (int r = 0; r < ROWS; r++) {
            System.arraycopy(grid[r], 0, p.grid[r], 0, COLS);
        }
        p.side = side;
        p.poolRed = new HashMap<>(poolRed);
        p.poolBlack = new HashMap<>(poolBlack);
        p.ply = ply;
        p.noCapture = noCapture;
        p.over = over;
        p.winner = winner;
        p.truth = new HashMap<>(truth);
        return p;
    }

    /**
     * 棋子属于哪一方
     */
    public static char colorOf(char ch) {
        return Character.isLowerCase(ch) ? BLACK : RED;
    }

    /**
     * 该位置的"明面兵种"：明子直接取，暗子按底牌推断
     */
    public char effectiveType(int r, int c) {
        char ch = grid[r][c];
        if (ch == ' ' || ch == '.') {
            return ' ';
        }
        if (!isHiddenPiece(ch)) {
            return Character.toUpperCase(ch);
        }
        Character beh = POWS.get(key(r, c));
        return beh == null ? ' ' : Character.toUpperCase(beh);
    }

    /**
     * 棋位上原本的兵种（静态，只跟位置有关）
     */
    public static char positionPiece(int r, int c) {
        Character beh = POWS.get(key(r, c));
        return beh == null ? '\0' : Character.toUpperCase(beh);
    }

    /**
     * 找到某一方的将/帅，找不到返回 null
     */
    public int[] findKing(char color) {
        char target = color == RED ? 'K' : 'k';
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (grid[r][c] == target) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    /**
     * 双方将帅是否照面（中间无子）
     */
    public boolean kingsFacing() {
        List<int[]> kings = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (Character.toLowerCase(grid[r][c]) == 'k') {
                    kings.add(new int[]{r, c});
                }
            }
        }
        if (kings.size() < 2) {
            return false;
        }
        int[] a = kings.get(0);
        int[] b = kings.get(1);
        if (a[1] != b[1]) {
            return false;
        }
        int lo = Math.min(a[0], b[0]);
        int hi = Math.max(a[0], b[0]);
        for (int r = lo + 1; r < hi; r++) {
            if (grid[r][a[1]] != ' ' && grid[r][a[1]] != '.') {
                return false;
            }
        }
        return true;
    }

    private boolean occupied(int r, int c) {
        return grid[r][c] != ' ' && grid[r][c] != '.';
    }

    /**
     * (tr, tc) 是否被 color 方的棋子攻击
     */
    public boolean attackedBy(int tr, int tc, char color) {
        // 车 / 将 / 炮：四个方向直线扫描
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int r = tr + d[0];
            int c = tc + d[1];
            int blockers = 0;
            while (r >= 0 && r < ROWS && c >= 0 && c < COLS) {
                char ch = grid[r][c];
                if (occupied(r, c)) {
                    if (blockers == 0) {
                        // 第一个子：车或将（将的贴身攻击按一格处理，这里方向相邻判断交给下面的将逻辑）
                        char et = effectiveType(r, c);
                        if (colorOf(ch) == color && (et == 'R' || et == 'K')) {
                            return true;
                        }
                        blockers = 1;
                    } else if (blockers == 1) {
                        // 隔着正好一个子：炮
                        if (colorOf(ch) == color && effectiveType(r, c) == 'C') {
                            return true;
                        }
                        break;
                    }
                    r += d[0];
                    c += d[1];
                    continue;
                }
                r += d[0];
                c += d[1];
            }
        }

        // 马
        int[][] horse = {{2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {1, -2}, {-1, 2}, {-1, -2}};
        for (int[] d : horse) {
            int r = tr + d[0];
            int c = tc + d[1];
            if (r < 0 || r >= ROWS || c < 0 || c >= COLS) {
                continue;
            }
            char ch = grid[r][c];
            if (!occupied(r, c) || colorOf(ch) != color || effectiveType(r, c) != 'N') {
                continue;
            }
            // 马腿
            int lr = Math.abs(d[0]) == 2 ? r - d[0] / 2 : tr;
            int lc = Math.abs(d[1]) == 2 ? c - d[1] / 2 : tc;
            if (!occupied(lr, lc)) {
                return true;
            }
        }

        // 象 / 相（塞象眼）
        int[][] elephant = {{2, 2}, {2, -2}, {-2, 2}, {-2, -2}};
        for (int[] d : elephant) {
            int r = tr + d[0];
            int c = tc + d[1];
            if (r < 0 || r >= ROWS || c < 0 || c >= COLS) {
                continue;
            }
            char ch = grid[r][c];
            if (occupied(r, c) && colorOf(ch) == color && effectiveType(r, c) == 'B'
                    && !occupied(tr + d[0] / 2, tc + d[1] / 2)) {
                return true;
            }
        }

        // 士 / 仕
        int[][] advisor = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : advisor) {
            int r = tr + d[0];
            int c = tc + d[1];
            if (r < 0 || r >= ROWS || c < 0 || c >= COLS) {
                continue;
            }
            char ch = grid[r][c];
            if (occupied(r, c) && colorOf(ch) == color && effectiveType(r, c) == 'A') {
                return true;
            }
        }

        // 兵 / 卒：正前方永远能攻，过河后左右也能攻
        int back = color == RED ? 1 : -1;
        int[][] pawn = {{back, 0}, {0, 1}, {0, -1}};
        for (int[] d : pawn) {
            int r = tr + d[0];
            int c = tc + d[1];
            if (r < 0 || r >= ROWS || c < 0 || c >= COLS) {
                continue;
            }
            char ch = grid[r][c];
            if (!occupied(r, c) || colorOf(ch) != color || effectiveType(r, c) != 'P') {
                continue;
            }
            if (d[1] != 0) {
                // 只有过河后才能横着吃
                boolean crossed = color == RED ? r >= 5 : r <= 4;
                if (!crossed) {
                    continue;
                }
            }
            return true;
        }

        return false;
    }

    /**
     * 目标点是否可以落子（空，或对方棋子）
     */
    private boolean passable(int tr, int tc, char mine) {
        return !occupied(tr, tc) || colorOf(grid[tr][tc]) != mine;
    }

    /**
     * 某个棋子的所有合法目标点（已过滤掉导致自己被将军的走法）
     */
    public List<int[]> legalTargets(int fr, int fc) {
        List<int[]> safe = new ArrayList<>();
        if (!occupied(fr, fc) || colorOf(grid[fr][fc]) != side) {
            return safe;
        }

        char mine = side;
        // 暗子按底牌推断走法
        char et = effectiveType(fr, fc);
        List<int[]> raw = new ArrayList<>();
        switch (et) {
            case 'A': {
                for (int[] d : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                    int tr = fr + d[0];
                    int tc = fc + d[1];
                    if (tr >= 0 && tr < ROWS && tc >= 0 && tc < COLS && passable(tr, tc, mine)) {
                        raw.add(new int[]{tr, tc});
                    }
                }
                break;
            }
            case 'B': {
                for (int[] d : new int[][]{{2, 2}, {2, -2}, {-2, 2}, {-2, -2}}) {
                    int tr = fr + d[0];
                    int tc = fc + d[1];
                    if (tr >= 0 && tr < ROWS && tc >= 0 && tc < COLS
                            && !occupied(fr + d[0] / 2, fc + d[1] / 2) && passable(tr, tc, mine)) {
                        raw.add(new int[]{tr, tc});
                    }
                }
                break;
            }
            case 'C': {
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int r = fr + d[0];
                    int c = fc + d[1];
                    boolean jumped = false;
                    while (r >= 0 && r < ROWS && c >= 0 && c < COLS) {
                        if (!occupied(r, c)) {
                            if (!jumped) {
                                raw.add(new int[]{r, c});
                            }
                        } else {
                            if (jumped) {
                                if (passable(r, c, mine)) {
                                    raw.add(new int[]{r, c});
                                }
                                break;
                            }
                            jumped = true;
                        }
                        r += d[0];
                        c += d[1];
                    }
                }
                break;
            }
            case 'K': {
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int tr = fr + d[0];
                    int tc = fc + d[1];
                    boolean palace = tc >= 3 && tc <= 5 && (mine == RED ? tr >= 7 && tr <= 9 : tr >= 0 && tr <= 2);
                    if (palace && passable(tr, tc, mine)) {
                        raw.add(new int[]{tr, tc});
                    }
                }
                break;
            }
            case 'N': {
                for (int[] d : new int[][]{{2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {1, -2}, {-1, 2}, {-1, -2}}) {
                    int tr = fr + d[0];
                    int tc = fc + d[1];
                    if (tr < 0 || tr >= ROWS || tc < 0 || tc >= COLS) {
                        continue;
                    }
                    int lr = Math.abs(d[0]) == 2 ? fr + d[0] / 2 : fr;
                    int lc = Math.abs(d[1]) == 2 ? fc + d[1] / 2 : fc;
                    if (!occupied(lr, lc) && passable(tr, tc, mine)) {
                        raw.add(new int[]{tr, tc});
                    }
                }
                break;
            }
            case 'P': {
                int fwd = mine == RED ? -1 : 1;
                if (fr + fwd >= 0 && fr + fwd < ROWS && passable(fr + fwd, fc, mine)) {
                    raw.add(new int[]{fr + fwd, fc});
                }
                boolean crossed = mine == RED ? fr <= 4 : fr >= 5;
                if (crossed) {
                    for (int dc : new int[]{1, -1}) {
                        int tc = fc + dc;
                        if (tc >= 0 && tc < COLS && passable(fr, tc, mine)) {
                            raw.add(new int[]{fr, tc});
                        }
                    }
                }
                break;
            }
            case 'R': {
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int r = fr + d[0];
                    int c = fc + d[1];
                    while (r >= 0 && r < ROWS && c >= 0 && c < COLS) {
                        if (occupied(r, c)) {
                            if (passable(r, c, mine)) {
                                raw.add(new int[]{r, c});
                            }
                            break;
                        }
                        raw.add(new int[]{r, c});
                        r += d[0];
                        c += d[1];
                    }
                }
                break;
            }
            default:
                break;
        }

        // 试走一遍，剔除会让己方被将军的走法
        char opp = mine == RED ? BLACK : RED;
        for (int[] t : raw) {
            char cap = grid[t[0]][t[1]];
            grid[t[0]][t[1]] = grid[fr][fc];
            grid[fr][fc] = ' ';
            int[] kp = findKing(mine);
            boolean bad = kp == null || kingsFacing() || attackedBy(kp[0], kp[1], opp);
            grid[fr][fc] = grid[t[0]][t[1]];
            grid[t[0]][t[1]] = cap;
            if (!bad) {
                safe.add(t);
            }
        }
        return safe;
    }

    /**
     * 把内部的 (行, 列) 转成 ICCS 着法文本，例如 a0a1
     */
    public static String iccsOf(int fr, int fc, int tr, int tc) {
        return "" + (char) ('a' + fc) + (9 - fr) + (char) ('a' + tc) + (9 - tr);
    }

    /**
     * 解析 ICCS 着法文本
     *
     * @return {起始行, 起始列, 目标行, 目标列}
     */
    public static int[] parseIccs(String mv) {
        return new int[]{
                9 - (mv.charAt(1) - '0'), mv.charAt(0) - 'a',
                9 - (mv.charAt(3) - '0'), mv.charAt(2) - 'a'
        };
    }

    public List<String> allMoves() {
        return allMoves(side);
    }

    /**
     * 某一方的全部合法着法
     */
    public List<String> allMoves(char color) {
        List<String> moves = new ArrayList<>();
        char saved = side;
        side = color;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (occupied(r, c) && colorOf(grid[r][c]) == color) {
                    for (int[] t : legalTargets(r, c)) {
                        moves.add(iccsOf(r, c, t[0], t[1]));
                    }
                }
            }
        }
        side = saved;
        return moves;
    }

    /**
     * 某一方是否被将军
     */
    public boolean inCheck(char color) {
        int[] kp = findKing(color);
        return kp != null && attackedBy(kp[0], kp[1], color == RED ? BLACK : RED);
    }

    public MoveEvent makeMove(String mv4) {
        return makeMove(mv4, null);
    }

    /**
     * 走一步棋
     *
     * @param revealChar 暗子翻开的兵种；为 null 时按底牌翻开
     */
    public MoveEvent makeMove(String mv4, Character revealChar) {
        int[] m = parseIccs(mv4);
        int fr = m[0];
        int fc = m[1];
        int tr = m[2];
        int tc = m[3];
        if (!allMoves().contains(mv4)) {
            throw new IllegalArgumentException("非法走法: " + mv4);
        }

        MoveEvent ev = new MoveEvent();
        ev.move = mv4;
        char mover = grid[fr][fc];
        char mcolor = colorOf(mover);
        char cap = grid[tr][tc];

        if (occupied(tr, tc)) {
            ev.captured = cap;
            noCapture = 0;
            // 吃掉的是暗子：从对方棋子池里扣掉一个（按优先级扣）
            if (isHiddenPiece(cap)) {
                Map<Character, Integer> pl = cap == HIDDEN_BLACK ? poolBlack : poolRed;
                int max = 0;
                for (char pc : CAPTURE_ORDER) {
                    max = Math.max(max, pl.getOrDefault(pc, 0));
                }
                for (char pc : CAPTURE_ORDER) {
                    if (max > 0 && pl.getOrDefault(pc, 0) == max) {
                        pl.put(pc, max - 1);
                        break;
                    }
                }
            }
        } else {
            noCapture++;
        }

        if (!isHiddenPiece(mover)) {
            grid[tr][tc] = mover;
        } else {
            // 暗子翻开
            char up;
            if (revealChar != null) {
                up = Character.toUpperCase(revealChar);
            } else {
                Character t = truth.get(key(fr, fc));
                if (t == null) {
                    Character beh = POWS.get(key(fr, fc));
                    t = beh == null ? 'P' : beh;
                }
                up = Character.toUpperCase(t);
            }
            char trueCh = mcolor == RED ? up : Character.toLowerCase(up);
            truth.remove(key(fr, fc));
            Map<Character, Integer> pl = mcolor == RED ? poolRed : poolBlack;
            if (pl.getOrDefault(up, 0) > 0) {
                pl.put(up, pl.get(up) - 1);
            }
            grid[tr][tc] = trueCh;
            ev.flip = trueCh;
        }

        grid[fr][fc] = ' ';
        side = mcolor == RED ? BLACK : RED;
        ply++;

        int[] kp = findKing(side);
        ev.check = kp != null && attackedBy(kp[0], kp[1], mcolor);
        if (Character.toLowerCase(cap) == 'k') {
            over = true;
            winner = mcolor;
        } else if (allMoves().isEmpty()) {
            over = true;
            winner = mcolor;
        } else if (noCapture >= 80) {
            over = true;
            winner = 0;
        }
        ev.gameOver = over;
        ev.winner = winner;
        return ev;
    }

    /**
     * 盘面文本（揭棋格式，暗子用 X/x）
     */
    public String boardText() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < ROWS; r++) {
            int empty = 0;
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < COLS; c++) {
                char ch = grid[r][c];
                if (ch != ' ' && ch != '.') {
                    if (empty > 0) {
                        row.append(empty);
                        empty = 0;
                    }
                    row.append(ch);
                } else {
                    empty++;
                }
            }
            if (empty > 0) {
                row.append(empty);
            }
            if (r > 0) {
                sb.append('/');
            }
            sb.append(row);
        }
        return sb.toString();
    }

    /**
     * 给引擎的 FEN（揭棋扩展格式：盘面、行棋方、双方棋子池、无吃子步数、回合数）
     */
    public String engineFen() {
        StringBuilder pool = new StringBuilder();
        for (char pc : POOL_ORDER) {
            pool.append(pc).append(poolRed.getOrDefault(pc, 0));
        }
        for (char pc : POOL_ORDER) {
            pool.append(Character.toLowerCase(pc)).append(poolBlack.getOrDefault(pc, 0));
        }
        return boardText() + " " + (side == RED ? 'w' : 'b') + " " + pool
                + " " + noCapture + " " + (ply / 2 + 1);
    }

    /**
     * 解析盘面文本（揭棋格式）
     */
    public static char[][] parseBoardText(String text) {
        char[][] g = new char[ROWS][COLS];
        for (char[] row : g) {
            Arrays.fill(row, ' ');
        }
        String[] rows = text.split("/");
        for (int r = 0; r < Math.min(rows.length, ROWS); r++) {
            int c = 0;
            for (char ch : rows[r].toCharArray()) {
                if (Character.isDigit(ch)) {
                    c += ch - '0';
                } else if (ch == '.') {
                    c++;
                } else if (c < COLS) {
                    g[r][c++] = ch;
                }
            }
        }
        return g;
    }

    /**
     * perft：统计 depth 层内的着法总数，用于自检走法生成是否正确
     */
    public int perft(int depth) {
        if (depth == 0) {
            return 1;
        }
        int total = 0;
        for (String mv : new ArrayList<>(allMoves())) {
            JieqiPosition child = copy();
            child.makeMove(mv);
            total += child.perft(depth - 1);
        }
        return total;
    }

    /**
     * 打印成可读的 ASCII 盘面
     */
    public String renderAscii() {
        StringBuilder sb = new StringBuilder("   a b c d e f g h i\n");
        for (int r = 0; r < ROWS; r++) {
            sb.append(9 - r).append("  ");
            for (int c = 0; c < COLS; c++) {
                char ch = grid[r][c];
                String s;
                if (ch == ' ' || ch == '.') {
                    s = "・";
                } else if (!isHiddenPiece(ch)) {
                    s = String.valueOf(ch);
                } else {
                    s = ch == HIDDEN_RED ? "红" : "黑";
                }
                sb.append(s).append(' ');
            }
            sb.append('\n');
        }
        sb.append(side == RED ? "红方行棋" : "黑方行棋");
        return sb.toString();
    }

    static {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                char ch = START_ROWS[r].charAt(c);
                if (ch != '.') {
                    POWS.put(key(r, c), ch);
                }
            }
        }
        INIT_POOL.put('R', 2);
        INIT_POOL.put('N', 2);
        INIT_POOL.put('B', 2);
        INIT_POOL.put('A', 2);
        INIT_POOL.put('C', 2);
        INIT_POOL.put('P', 5);
    }

    /**
     * 一步棋产生的事件
     */
    public static class MoveEvent {
        /** 着法文本 */
        public String move;
        /** 被吃的子（暗子为 'X'/'x'） */
        public Character captured;
        /** 本次翻开的兵种，没翻开为 null */
        public Character flip;
        /** 是否将军 */
        public boolean check;
        /** 是否终局 */
        public boolean gameOver;
        /** 胜方 */
        public char winner;
    }
}
