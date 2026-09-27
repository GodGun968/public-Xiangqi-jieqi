package com.sojourners.chess.jieqi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 揭棋走法生成自检
 * <p>
 * 直接运行 main 即可验证走法生成是否正确（perft 比对固定值）。
 * 修改 {@link JieqiPosition} 的走法逻辑后务必跑一遍。
 */
public class JieqiSelfTest {

    /** 参考盘面里暗子底牌的固定分配顺序 */
    private static final char[] SEQ = {'R', 'R', 'N', 'N', 'B', 'B', 'A', 'A', 'C', 'C', 'P', 'P', 'P', 'P', 'P'};

    /**
     * 构造一个固定的参考局面：棋子位置随机性去掉，底牌按 SEQ 顺序写死
     */
    private static JieqiPosition referencePosition() {
        char[][] grid = new char[10][9];
        for (char[] row : grid) {
            Arrays.fill(row, ' ');
        }

        String[] start = {
                "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
                ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABNR"
        };
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char ch = start[r].charAt(c);
                if (ch == '.') {
                    grid[r][c] = ' ';
                } else if (ch != 'K' && ch != 'k') {
                    // 除将/帅外全部变暗子
                    grid[r][c] = Character.isUpperCase(ch) ? JieqiPosition.HIDDEN_RED : JieqiPosition.HIDDEN_BLACK;
                } else {
                    grid[r][c] = ch;
                }
            }
        }

        Map<Integer, Character> truths = new HashMap<>();
        for (char color : new char[]{JieqiPosition.RED, JieqiPosition.BLACK}) {
            int i = 0;
            char mark = color == JieqiPosition.RED ? JieqiPosition.HIDDEN_RED : JieqiPosition.HIDDEN_BLACK;
            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < 9; c++) {
                    if (grid[r][c] == mark) {
                        char pc = SEQ[i++];
                        truths.put(JieqiPosition.key(r, c),
                                color == JieqiPosition.RED ? pc : Character.toLowerCase(pc));
                    }
                }
            }
        }

        return JieqiPosition.fromGrid(grid, JieqiPosition.RED,
                JieqiPosition.newPool(), JieqiPosition.newPool(), truths, 0, 0);
    }

    public static void main(String[] args) {
        int failures = 0;
        JieqiPosition pos = referencePosition();

        failures += check("初始盘面", pos.boardText(),
                "xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/9/XXXXKXXXX");

        List<String> moves = new ArrayList<>(pos.allMoves());
        Collections.sort(moves);
        failures += check("合法着法数", String.valueOf(moves.size()), "46");
        failures += check("着法头部", moves.subList(0, 6).toString(),
                "[a0a1, a0a2, a3a4, b0a2, b0c2, b2a2]");

        failures += check("perft(1)", String.valueOf(pos.perft(1)), "46");
        long t0 = System.currentTimeMillis();
        int p2 = pos.perft(2);
        long ms = System.currentTimeMillis() - t0;
        failures += check("perft(2)", String.valueOf(p2), "2106");

        failures += check("engineFen", pos.engineFen(),
                "xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/9/XXXXKXXXX w "
                        + "R2N2B2A2C2P5r2n2b2a2c2p5 0 1");

        System.out.println(pos.renderAscii());
        System.out.println("perft(2) 用时 " + ms + " ms");
        System.out.println(failures == 0 ? "全部通过 ✔" : "失败项: " + failures + " ✘");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static int check(String name, String actual, String expect) {
        boolean ok = expect.equals(actual);
        System.out.printf("%-12s %s%n  实际: %s%n  期望: %s%n", name, ok ? "PASS" : "FAIL", actual, expect);
        return ok ? 0 : 1;
    }
}
