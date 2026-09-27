package com.sojourners.chess.controller;

import com.sojourners.chess.App;
import com.sojourners.chess.board.ChessBoard;
import com.sojourners.chess.config.Properties;
import com.sojourners.chess.controller.handle.ChessManualCallBack;
import com.sojourners.chess.controller.handle.ChessManualHandle;
import com.sojourners.chess.enginee.Engine;
import com.sojourners.chess.enginee.EngineCallBack;
import com.sojourners.chess.jieqi.JieqiBoardRecognizer;
import com.sojourners.chess.jieqi.JieqiPosition;
import com.sojourners.chess.jieqi.JieqiTrace;
import com.sojourners.chess.linker.*;
import com.sojourners.chess.menu.BoardContextMenu;
import com.sojourners.chess.model.BookData;
import com.sojourners.chess.model.EngineConfig;
import com.sojourners.chess.model.ManualRecord;
import com.sojourners.chess.model.ThinkData;
import com.sojourners.chess.openbook.OpenBookManager;
import com.sojourners.chess.util.*;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.canvas.Canvas;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.*;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Callback;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

import javafx.util.Duration;

public class Controller implements EngineCallBack, LinkerCallBack, ChessManualCallBack {

    @FXML
    private Canvas canvas;

    @FXML
    private BorderPane borderPane;
    @FXML
    private Label infoShowLabel;
    @FXML
    private ToolBar statusToolBar;
    @FXML
    private Label timeShowLabel;
    @FXML
    private SplitPane splitPane;
    @FXML
    private SplitPane splitPane2;

    @FXML
    private ListView<ThinkData> listView;

    @FXML
    private ComboBox<String> engineComboBox;

    /**
     * 对局模式下拉框（象棋 / 揭棋）
     */
    @FXML
    private ComboBox<String> modeComboBox;

    /**
     * 模式下拉框正在被程序同步选择项（避免触发回调）
     */
    private boolean modeComboSyncing;

    @FXML
    private ComboBox<String> linkComboBox;

    @FXML
    private ComboBox<String> hashComboBox;

    @FXML
    private ComboBox<String> threadComboBox;

    @FXML
    private RadioMenuItem menuOfLargeBoard;
    @FXML
    private RadioMenuItem menuOfBigBoard;
    @FXML
    private RadioMenuItem menuOfMiddleBoard;
    @FXML
    private RadioMenuItem menuOfSmallBoard;
    @FXML
    private RadioMenuItem menuOfAutoFitBoard;

    @FXML
    private RadioMenuItem menuOfDefaultBoard;
    @FXML
    private RadioMenuItem menuOfCustomBoard;
    @FXML
    private RadioMenuItem menuOfModernBoard;
    @FXML
    private RadioMenuItem menuOfWoodBoard;
    @FXML
    private RadioMenuItem menuOfDarkBoard;
    @FXML
    private RadioMenuItem menuOfJadeBoard;

    @FXML
    private CheckMenuItem menuOfStepTip;
    @FXML
    private CheckMenuItem menuOfStepSound;
    @FXML
    private CheckMenuItem menuOfLinkBackMode;
    @FXML
    private CheckMenuItem menuOfLinkAnimation;
    @FXML
    private CheckMenuItem menuOfShowStatus;
    @FXML
    private CheckMenuItem menuOfShowNumber;

    @FXML
    private CheckMenuItem menuOfTopWindow;

    /**
     * 对局模式（象棋 / 揭棋）
     */
    @FXML
    private RadioMenuItem menuOfXiangqiMode;
    @FXML
    private RadioMenuItem menuOfJieqiMode;

    /**
     * 引擎输出日志的节流时间戳
     */
    private long lastThinkLog;

    /**
     * 盘面截图日志的节流时间戳
     */
    private long lastSnapshot;

    /**
     * 揭棋引擎名称
     */
    private static final String JIEQI_ENGINE_NAME = "皮卡鱼揭棋";

    /**
     * 切进揭棋模式前的引擎名（切回象棋时恢复）
     */
    private String engineNameBeforeJieqi;

    /**
     * 连线识别到盘面已翻转（黑方在下）
     */
    private boolean jieqiAutoReversed;

    /**
     * 已记入棋谱的揭棋着法（避免重复记录）
     */
    private String lastRecordedJieqiMove;

    /**
     * 上一次自动点击的揭棋着法（用于重试计数）
     */
    private String lastAutoClickedJieqi;

    /**
     * 同一着法已自动点击的次数
     */
    private int jieqiAutoClickTries;

    /**
     * 上一次因着法不合法而重试分析的局面（避免死循环）
     */
    private String lastJieqiRetriedFen;

    /**
     * 揭棋自动走棋的线程数
     */
    private static final int JIEQI_AUTO_THREADS = 6;

    /**
     * 揭棋自动走棋的哈希表大小（MB）
     */
    private static final int JIEQI_AUTO_HASH = 128;

    /**
     * 揭棋自动走棋的单步最长思考时间（毫秒）
     */
    private static final long JIEQI_AUTO_MAX_MS = 2000L;

    /**
     * 上一次给引擎设置的揭棋线程数
     */
    private int lastJieqiThreads = -1;

    /**
     * 上一次给引擎设置的揭棋思考时间
     */
    private long lastJieqiTime = -1L;

    /**
     * 上一次已送引擎分析的揭棋局面
     */
    private String lastJieqiAnalyzedFen;

    private Properties prop;

    private Engine engine;

    private ChessBoard board;

    private AbstractGraphLinker graphLinker;

    @FXML
    private Button analysisButton;
    @FXML
    private Button blackButton;
    @FXML
    private Button redButton;
    @FXML
    private Button reverseButton;
    @FXML
    private Button newButton;
    @FXML
    private Button copyButton;
    @FXML
    private Button pasteButton;
    @FXML
    private Button regretButton;

    @FXML
    private BorderPane charPane;
    private XYChart.Series lineChartSeries;

    @FXML
    private Button immediateButton;
    @FXML
    private Button bookSwitchButton;
    @FXML
    private Button linkButton;
    @FXML
    private Button changeTacticButton;

    @FXML
    private TableView<ManualRecord> recordTable;

    @FXML
    private TableView<BookData> bookTable;

    private SimpleObjectProperty<Boolean> robotRed = new SimpleObjectProperty<>(false);
    private SimpleObjectProperty<Boolean> robotBlack = new SimpleObjectProperty<>(false);
    private SimpleObjectProperty<Boolean> robotAnalysis = new SimpleObjectProperty<>(false);
    private SimpleObjectProperty<Boolean> isReverse = new SimpleObjectProperty<>(false);
    private SimpleObjectProperty<Boolean> linkMode = new SimpleObjectProperty<>(false);
    private SimpleObjectProperty<Boolean> useOpenBook = new SimpleObjectProperty<>(false);

    /**
     * 走棋方
     */
    private boolean redGo;

    /**
     * 正在思考（用于连线判断）
     */
    private volatile boolean isThinking;

    /**
     * 变招列表
     */
    private List<String> tacticList;

    @FXML
    public void newButtonClick(ActionEvent event) {
        if (linkMode.getValue()) {
            stopGraphLink();
        }

        newChessBoard(null);
    }

    /**
     * 工具栏下拉框选择对局模式
     */
    @FXML
    void modeComboBoxChanged(ActionEvent event) {
        if (modeComboSyncing || modeComboBox == null) {
            return;
        }
        boolean wantJieqi = modeComboBox.getSelectionModel().getSelectedIndex() == 1;
        applyGameMode(wantJieqi ? ChessBoard.GameMode.JIEQI : ChessBoard.GameMode.XIANGQI);
    }

    /**
     * 菜单里选择对局模式
     */
    @FXML
    void gameModeSelected(ActionEvent event) {
        RadioMenuItem item = (RadioMenuItem) event.getTarget();
        ChessBoard.GameMode mode = item == menuOfJieqiMode ? ChessBoard.GameMode.JIEQI : ChessBoard.GameMode.XIANGQI;
        applyGameMode(mode);
    }

    /**
     * 切换对局模式：必要时换引擎、开新局、刷新标题
     */
    private void applyGameMode(ChessBoard.GameMode mode) {
        JieqiTrace.log("用户切换模式: 请求=" + mode + " 当前配置=" + prop.getGameMode());
        if (prop.getGameMode() == mode) {
            JieqiTrace.log("模式未变化，忽略");
            return;
        }
        if (mode == ChessBoard.GameMode.JIEQI) {
            if (!JIEQI_ENGINE_NAME.equals(prop.getEngineName())) {
                engineNameBeforeJieqi = prop.getEngineName();
            }
            if (ensureJieqiEngineConfig()) {
                prop.setEngineName(JIEQI_ENGINE_NAME);
                loadEngine(JIEQI_ENGINE_NAME);
                JieqiTrace.log("揭棋模式：已切换到揭棋引擎（" + JIEQI_ENGINE_NAME + "）");
            } else {
                JieqiTrace.log("揭棋模式：没找到揭棋引擎，继续用当前引擎（近似局面）");
            }
            refreshEngineComboBox();
        } else {
            String xiangqiEngine = findXiangqiEngineName();
            if (xiangqiEngine != null && !xiangqiEngine.isBlank()) {
                prop.setEngineName(xiangqiEngine);
                loadEngine(xiangqiEngine);
                JieqiTrace.log("象棋模式：已切换到象棋引擎（" + xiangqiEngine + "）");
                refreshEngineComboBox();
            }
        }

        prop.setGameMode(mode);
        if (mode == ChessBoard.GameMode.JIEQI) {
            menuOfJieqiMode.setSelected(true);
        } else {
            menuOfXiangqiMode.setSelected(true);
        }

        refreshModeButton();
        newChessBoard(null);
        if (mode == ChessBoard.GameMode.JIEQI) {
            infoShowLabel.setText(board.statusText());
            setWindowTitle("　[揭棋]");
        } else {
            setWindowTitle(null);
        }

        JieqiTrace.log("切换完成: 配置=" + prop.getGameMode() + " 棋盘=" + board.getGameMode() + " 暗子数=" + board.hiddenCount());
    }

    /**
     * 设置窗口标题（suffix 为空则用默认标题）
     */
    private void setWindowTitle(String suffix) {
        try {
            if (App.getMainStage() != null) {
                App.getMainStage().setTitle(App.TITLE + (suffix == null ? "" : suffix));
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 找一个非揭棋的引擎名（切回象棋模式用）
     */
    private String findXiangqiEngineName() {
        try {
            if (engineNameBeforeJieqi != null && !engineNameBeforeJieqi.isBlank() && !JIEQI_ENGINE_NAME.equals(engineNameBeforeJieqi)) {
                return engineNameBeforeJieqi;
            }
            for (EngineConfig ec : prop.getEngineConfigList()) {
                if (ec.getName() != null && !JIEQI_ENGINE_NAME.equals(ec.getName())) {
                    return ec.getName();
                }
            }
        } catch (Throwable e) {
            JieqiTrace.log("找象棋引擎失败 " + e);
        }
        return null;
    }

    /**
     * 确保配置里有揭棋引擎；没有就去程序目录找 pikajieqi 自动加上
     */
    private boolean ensureJieqiEngineConfig() {
        try {
            String base = PathUtils.getJarPath();
            File f = new File(base + "engine/pikajieqi-bmi2.exe");
            if (!f.exists()) {
                f = new File(base + "Windows/pikajieqi-bmi2.exe");
            }
            if (!f.exists()) {
                JieqiTrace.log("没找到揭棋引擎：engine/pikajieqi-bmi2.exe 不存在");
                return false;
            }
            String path = f.getAbsolutePath();
            for (EngineConfig ec : prop.getEngineConfigList()) {
                if (JIEQI_ENGINE_NAME.equals(ec.getName())) {
                    if (!path.equals(ec.getPath())) {
                        ec.setPath(path);
                    }
                    return true;
                }
            }
            prop.getEngineConfigList().add(new EngineConfig(JIEQI_ENGINE_NAME, path, "uci", new LinkedHashMap<>()));
            JieqiTrace.log("已自动添加揭棋引擎配置: " + JIEQI_ENGINE_NAME + " -> " + path);
            return true;
        } catch (Throwable e) {
            JieqiTrace.log("添加揭棋引擎配置失败: " + e);
            return false;
        }
    }

    @FXML
    void boardStyleSelected(ActionEvent event) {
        RadioMenuItem item = (RadioMenuItem) event.getTarget();
        if (item.equals(menuOfDefaultBoard)) {
            prop.setBoardStyle(ChessBoard.BoardStyle.DEFAULT);
        } else if (item.equals(menuOfModernBoard)) {
            prop.setBoardStyle(ChessBoard.BoardStyle.MODERN);
        } else if (item.equals(menuOfWoodBoard)) {
            prop.setBoardStyle(ChessBoard.BoardStyle.WOOD);
        } else if (item.equals(menuOfDarkBoard)) {
            prop.setBoardStyle(ChessBoard.BoardStyle.DARK);
        } else if (item.equals(menuOfJadeBoard)) {
            prop.setBoardStyle(ChessBoard.BoardStyle.JADE);
        } else {
            prop.setBoardStyle(ChessBoard.BoardStyle.CUSTOM);
        }
        board.setBoardStyle(prop.getBoardStyle(), this.canvas);
    }

    /**
     * 盘面截图存到 log 目录（排查用）
     */
    private void dumpBoardSnapshot(String tag) {
        try {
            WritableImage img = canvas.snapshot(null, null);
            File dir = new File(PathUtils.getJarPath() + "log");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File f = new File(dir, "board_" + tag + ".png");
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
            JieqiTrace.log("已保存盘面截图 " + f.getName() + " (" + (int) img.getWidth() + "x" + (int) img.getHeight() + ")");
        } catch (Exception e) {
            JieqiTrace.log("盘面截图失败: " + e);
        }
    }

    /**
     * 刷新工具栏的模式下拉框与提示
     */
    private void refreshModeButton() {
        if (modeComboBox == null) {
            return;
        }
        modeComboSyncing = true;
        try {
            if (modeComboBox.getItems().isEmpty()) {
                modeComboBox.getItems().addAll("象棋", "揭棋");
            }
            boolean jieqi = prop.getGameMode() == ChessBoard.GameMode.JIEQI;
            modeComboBox.getSelectionModel().select(jieqi ? 1 : 0);
            modeComboBox.setTooltip(tip(jieqi ? "当前：揭棋（换模式会开新局）" : "当前：象棋"));
        } finally {
            modeComboSyncing = false;
        }
    }

    @FXML
    void boardSizeSelected(ActionEvent event) {
        RadioMenuItem item = (RadioMenuItem) event.getTarget();
        if (item.equals(menuOfLargeBoard)) {
            prop.setBoardSize(ChessBoard.BoardSize.LARGE_BOARD);
        } else if (item.equals(menuOfBigBoard)) {
            prop.setBoardSize(ChessBoard.BoardSize.BIG_BOARD);
        } else if (item.equals(menuOfMiddleBoard)) {
            prop.setBoardSize(ChessBoard.BoardSize.MIDDLE_BOARD);
        } else if (item.equals(menuOfAutoFitBoard)) {
            prop.setBoardSize(ChessBoard.BoardSize.AUTOFIT_BOARD);
        } else {
            prop.setBoardSize(ChessBoard.BoardSize.SMALL_BOARD);
        }
        board.setBoardSize(prop.getBoardSize());
        if (prop.getBoardSize() == ChessBoard.BoardSize.AUTOFIT_BOARD) {
            board.autoFitSize(borderPane.getWidth(), borderPane.getHeight(), splitPane.getDividerPositions()[0]);
        }
    }
    @FXML
    void stepTipChecked(ActionEvent event) {
        CheckMenuItem item = (CheckMenuItem) event.getTarget();
        prop.setStepTip(item.isSelected());
        board.setStepTip(prop.isStepTip());
    }

    @FXML
    void showNumberClick(ActionEvent event) {
        CheckMenuItem item = (CheckMenuItem) event.getTarget();
        prop.setShowNumber(item.isSelected());
        board.setShowNumber(prop.isShowNumber());
    }

    @FXML
    void topWindowClick(ActionEvent event) {
        CheckMenuItem item = (CheckMenuItem) event.getTarget();
        prop.setTopWindow(item.isSelected());
        App.topWindow(prop.isTopWindow());
    }

    @FXML
    void linkBackModeChecked(ActionEvent event) {
        CheckMenuItem item = (CheckMenuItem) event.getTarget();
        if (linkMode.getValue()) {
            stopGraphLink();
        }
        prop.setLinkBackMode(item.isSelected());
    }

    @FXML
    void linkAnimationChecked(ActionEvent event) {
        CheckMenuItem item = (CheckMenuItem) event.getTarget();
        prop.setLinkAnimation(item.isSelected());
    }

    @FXML
    void stepSoundClick(ActionEvent event) {
        CheckMenuItem item = (CheckMenuItem) event.getTarget();
        prop.setStepSound(item.isSelected());
        board.setStepSound(prop.isStepSound());
    }

    @FXML
    void showStatusBarClick(ActionEvent event) {
        CheckMenuItem item = (CheckMenuItem) event.getTarget();
        prop.setLinkShowInfo(item.isSelected());
        statusToolBar.setVisible(item.isSelected());
        board.autoFitSize(borderPane.getWidth(), borderPane.getHeight(), splitPane.getDividerPositions()[0]);
    }

    @FXML
    public void analysisButtonClick(ActionEvent event) {
        if (engine == null) {
            DialogUtils.showWarningDialog("提示", "引擎未加载");
            return;
        }

        robotAnalysis.setValue(!robotAnalysis.getValue());
        if (robotAnalysis.getValue()) {
            robotRed.setValue(false);
            robotBlack.setValue(false);
            engineGo();
        } else {
            engineStop();
        }

        redButton.setDisable(robotAnalysis.getValue());
        blackButton.setDisable(robotAnalysis.getValue());
        immediateButton.setDisable(robotAnalysis.getValue());

        if (linkMode.getValue() && !robotAnalysis.getValue()) {
            stopGraphLink();
        }
    }

    private void engineStop() {
        if (engine != null) {
            engine.stop();
        }
    }

    @FXML
    public void immediateButtonClick(ActionEvent event) {
        if (redGo && robotRed.getValue() || !redGo && robotBlack.getValue()) {
            if (engine != null) {
                engine.moveNow();
            }
        }
    }

    @FXML
    public void changeTacticButtonClick(ActionEvent event) {
        if (robotRed.getValue() && redGo || robotBlack.getValue() && !redGo || robotAnalysis.getValue()) {
            engineStop();
            if (tacticList == null || tacticList.size() <= 1) {
                tacticList = board.getTacticList(redGo);
            }
            if (!listView.getItems().isEmpty()) {
                for (ThinkData td : listView.getItems()) {
                    if (td.getPv() == 1) {
                        tacticList.remove(td.getDetail().get(0));
                        break;
                    }
                }
            }
            engine.setThreadNum(prop.getThreadNum());
            engine.setHashSize(prop.getHashSize());
            engine.setAnalysisModel(robotAnalysis.getValue() ? Engine.AnalysisModel.INFINITE : prop.getAnalysisModel(), prop.getAnalysisValue());
            engine.analysis(chessManualHandle.getFenCode(), chessManualHandle.getMoveList(), tacticList);
        }
    }

    @FXML
    public void blackButtonClick(ActionEvent event) {
        if (engine == null) {
            DialogUtils.showWarningDialog("提示", "引擎未加载");
            return;
        }

        robotBlack.setValue(!robotBlack.getValue());
        if (robotBlack.getValue() && !redGo) {
            engineGo();
        }
        if (!robotBlack.getValue() && !redGo) {
            engineStop();
        }

        if (linkMode.getValue() && !robotBlack.getValue()) {
            stopGraphLink();
        }
    }

    @FXML
    public void engineManageClick(ActionEvent e) {
        App.openEngineDialog();
        // 重新设置引擎列表
        refreshEngineComboBox();
        // 如果引擎被卸载，则关闭
        if (StringUtils.isEmpty(prop.getEngineName())) {
            // 重置按钮
            robotRed.setValue(false);
            robotBlack.setValue(false);
            robotAnalysis.setValue(false);
            // 关闭引擎
            if (engine != null) {
                engine.close();
                engine = null;
            }
        }
    }

    @FXML
    public void redButtonClick(ActionEvent event) {
        if (engine == null) {
            DialogUtils.showWarningDialog("提示", "引擎未加载");
            return;
        }

        robotRed.setValue(!robotRed.getValue());
        if (robotRed.getValue() && redGo) {
            engineGo();
        }
        if (!robotRed.getValue() && redGo) {
            engineStop();
        }

        if (linkMode.getValue() && !robotRed.getValue()) {
            stopGraphLink();
        }
    }

    private void stopGraphLink() {
        graphLinker.stop();

        engineStop();

        redButton.setDisable(false);
        robotRed.setValue(false);

        blackButton.setDisable(false);
        robotBlack.setValue(false);

        analysisButton.setDisable(false);
        robotAnalysis.setValue(false);

        linkMode.setValue(false);
    }

    private void engineGo() {
        if (engine == null) {
            DialogUtils.showWarningDialog("提示", "引擎未加载");
            return;
        }

        if (board.getGameMode() == ChessBoard.GameMode.JIEQI) {
            engineGoJieqi();
            return;
        }

        if (robotRed.getValue() && redGo || robotBlack.getValue() && !redGo) {
            this.isThinking = true;
        } else {
            this.isThinking = false;
        }

        // 重置变招列表
        tacticList = null;

        engine.setThreadNum(prop.getThreadNum());
        engine.setHashSize(prop.getHashSize());
        engine.setAnalysisModel(robotAnalysis.getValue() ? Engine.AnalysisModel.INFINITE : prop.getAnalysisModel(), prop.getAnalysisValue());
        engine.analysis(chessManualHandle.getFenCode(), chessManualHandle.getMoveList(), this.board.getBoard(), redGo);
    }

    /**
     * 揭棋模式送引擎：装了揭棋引擎就是真 FEN，否则退回普通引擎的行为位次近似
     */
    private void engineGoJieqi() {
        String fen = board.jieqiEngineFen();
        boolean realEngine = JIEQI_ENGINE_NAME.equals(prop.getEngineName());
        if (!realEngine) {
            fen = board.jieqiSearchFen();
        }
        if (fen == null) {
            return;
        }

        JieqiTrace.log("揭棋模式：送引擎（" + (realEngine ? "揭棋引擎·真 FEN" : "普通引擎·行为位次近似") + "）fen=" + fen + " 分析模式=" + robotAnalysis.getValue());
        this.isThinking = false;
        tacticList = null;

        boolean autoPlay = !robotAnalysis.getValue();
        int threads = Math.max(prop.getThreadNum(), autoPlay ? JIEQI_AUTO_THREADS : 1);
        int hash = Math.max(prop.getHashSize(), autoPlay ? JIEQI_AUTO_HASH : 16);
        Engine.AnalysisModel model = prop.getAnalysisModel();
        long value = prop.getAnalysisValue();
        if (autoPlay && model == Engine.AnalysisModel.FIXED_TIME && value > JIEQI_AUTO_MAX_MS) {
            value = JIEQI_AUTO_MAX_MS;
        }

        engine.setThreadNum(threads);
        engine.setHashSize(hash);
        engine.setAnalysisModel(model, value);
        if (autoPlay && (threads != lastJieqiThreads || value != lastJieqiTime)) {
            lastJieqiThreads = threads;
            lastJieqiTime = value;
            JieqiTrace.log("揭棋自动走棋：引擎用 " + threads + " 线程 / " + hash + "MB 哈希 / " + value + "ms 一步（设置里是 "
                    + prop.getThreadNum() + " 线程 " + prop.getAnalysisValue() + "ms）");
        }

        engine.analysis(fen, List.of(), null);
    }

    @FXML
    public void canvasClick(MouseEvent event) {

        if (event.getButton() == MouseButton.PRIMARY) {
            String move = board.mouseClick((int) event.getX(), (int) event.getY(),
                    redGo && !robotRed.getValue(), !redGo && !robotBlack.getValue());

            if (move != null) {
                goCallBack(move);
            }

            BoardContextMenu.getInstance().hide();

        } else if (event.getButton() == MouseButton.SECONDARY) {

            BoardContextMenu.getInstance().show(this.canvas, Side.RIGHT, event.getX() - this.canvas.widthProperty().doubleValue(), event.getY());
        }

    }
    private void goCallBack(String move) {
        // 记录棋谱
        List<String> nextList = chessManualHandle.boardMove(move, board.translate(move, true));
        board.setManualList(nextList);
        // 趋势图
        refreshLineChart();
        // 切换行棋方
        redGo = !redGo;
        // 触发引擎走棋
        if (redGo && robotRed.getValue() || !redGo && robotBlack.getValue() || robotAnalysis.getValue()) {
            engineGo();
        } else {
            doOpenBook();
        }
    }

    @Override
    public void refreshLineChart() {
        List<XYChart.Data> oldList = lineChartSeries.getData();
        List<XYChart.Data> newList = chessManualHandle.getScoreList();
        int i = 0;
        while (i < oldList.size() && i < newList.size()) {
            XYChart.Data o = oldList.get(i);
            XYChart.Data n = newList.get(i);
            if (!o.getXValue().equals(n.getXValue()) || !o.getYValue().equals(n.getYValue())) {
                for (int j = oldList.size() - 1; j >= i; j--) {
                    oldList.remove(j);
                }
                break;
            }
            i++;
        }
        if (i < oldList.size()) {
            for (int j = oldList.size() - 1; j >= i; j--) {
                oldList.remove(j);
            }
        } else if (i < newList.size()) {
            oldList.addAll(newList.subList(i, newList.size()));
        }
    }

    private void doOpenBook() {
        if (useOpenBook.getValue()) {
            Thread.startVirtualThread(() -> {
                List<BookData> results = OpenBookManager.getInstance().queryBook(board.getBoard(), redGo, chessManualHandle.getP() / 2 >= Properties.getInstance().getOffManualSteps());
                this.showBookResults(results);
            });
        } else {
            this.bookTable.getItems().clear();
        }
    }

    @FXML
    public void copyButtonClick(ActionEvent e) {
        String fenCode = board.fenCode(redGo);
        ClipboardUtils.setText(fenCode);
    }

    @FXML
    public void pasteButtonClick(ActionEvent e) {
        String fenCode = ClipboardUtils.getText();
        if (StringUtils.isNotEmpty(fenCode) && fenCode.split("/").length == 10) {
            newFromOriginFen(fenCode);
        }
    }

    @FXML
    public void importImageMenuClick(ActionEvent e) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialDirectory(new File(PathUtils.getJarPath()));
        File file = fileChooser.showOpenDialog(App.getMainStage());
        if (file != null) {
            importFromImgFile(file);
        }
    }

    @FXML
    public void exportImageMenuClick(ActionEvent e) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialDirectory(new File(PathUtils.getJarPath()));
        fileChooser.setInitialFileName("tchess_export_" + DateUtils.getDateTimeString(new Date()) + ".png");
        File file = fileChooser.showSaveDialog(App.getMainStage());
        if (file != null) {
            try {
                WritableImage writableImage = new WritableImage((int) this.canvas.getWidth(), (int) this.canvas.getHeight());
                canvas.snapshot(null, writableImage);
                RenderedImage renderedImage = SwingFXUtils.fromFXImage(writableImage, null);
                ImageIO.write(renderedImage, "png", file);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    @FXML
    public void aboutClick(ActionEvent e) {
        DialogUtils.showInfoDialog("关于", "TCHESS"
                + System.lineSeparator() + "Built on : " + App.BUILT_ON
                + System.lineSeparator() + "Author : T"
                + System.lineSeparator() + "Version : " + App.VERSION);
    }

    @FXML
    public void upgradeClick(ActionEvent e) {
        SystemUtils.openBrowser("https://github.com/sojourners/public-Xiangqi/releases");
    }

    @FXML
    public void instructionClick(ActionEvent e) {
        SystemUtils.openBrowser("https://github.com/sojourners/public-Xiangqi/blob/master/MANUAL.md");
    }

    @FXML
    public void homeClick(ActionEvent e) {
        SystemUtils.openBrowser("https://github.com/sojourners/public-Xiangqi");
    }

    @FXML
    void localBookManageButtonClick(ActionEvent e) {
        if (App.openLocalBookDialog()) {
            OpenBookManager.getInstance().setLocalOpenBooks();
        }

    }

    @FXML
    void timeSettingButtonClick(ActionEvent e) {
        App.openTimeSetting();
    }

    @FXML
    void bookSettingButtonClick(ActionEvent e) {
        App.openBookSetting();
    }

    @FXML
    void linkSettingClick(ActionEvent e) {
        App.openLinkSetting();

    }

    @FXML
    public void reverseButtonClick(ActionEvent event) {
        isReverse.setValue(!isReverse.getValue());
        board.reverse(isReverse.getValue());
    }

    @FXML
    void colorSettingClick(ActionEvent e) {
        if (App.openColorSetting()) {
            App.refreshTheme();
            board.refresh();
        }
    }

    @FXML
    private void bookSwitchButtonClick(ActionEvent e) {
        useOpenBook.setValue(!useOpenBook.getValue());
        prop.setBookSwitch(useOpenBook.getValue());

        doOpenBook();
    }

    @FXML
    private void linkButtonClick(ActionEvent e) {
        if (engine == null) {
            DialogUtils.showWarningDialog("提示", "引擎未加载");
            return;
        }

        linkMode.setValue(!linkMode.getValue());
        if (linkMode.getValue()) {
            graphLinker.start();
        } else {
            stopGraphLink();
        }
    }

    private void initLineChart() {
        final NumberAxis xAxis = new NumberAxis();
        final NumberAxis yAxis = new NumberAxis(-1000, 1000, 500);
        xAxis.setTickLabelsVisible(false);
        xAxis.setTickMarkVisible(false);
        xAxis.setMinorTickVisible(false);
        yAxis.setTickMarkVisible(false);
        yAxis.setMinorTickVisible(false);

        LineChart<Number,Number> lineChart = new LineChart<>(xAxis, yAxis);
        lineChart.setMinHeight(100);
        lineChart.setLegendVisible(false);
        lineChart.setCreateSymbols(false);
        lineChart.setVerticalGridLinesVisible(false);
        lineChart.getStylesheets().add(this.getClass().getResource("/style/table.css").toString());

        lineChartSeries = new XYChart.Series();
        lineChart.getData().add(lineChartSeries);

        charPane.setCenter(lineChart);
    }
    public void initialize() {
        // 读取配置
        prop = Properties.getInstance();
        // 思考细节listView
        listView.setCellFactory(new Callback() {
            @Override
            public Object call(Object param) {
                ListCell<ThinkData> cell = new ListCell<ThinkData>() {
                    @Override
                    protected void updateItem(ThinkData item, boolean bln) {
                        super.updateItem(item, bln);
                        if (!bln) {
                            VBox box = new VBox();

                            Label title = new Label();
                            title.setText(item.getTitle());
                            setScoreStyle(title, item.getScore());
                            box.getChildren().add(title);

                            Label body = new Label();
                            body.setText(item.getBody());
                            body.setWrapText(true);
                            body.setMaxWidth(listView.getWidth() / 1.124);//bind(listView.widthProperty().divide(1.124));
                            box.getChildren().add(body);

                            setGraphic(box);
                        }
                    }
                };
                return cell;
            }

        });
        // 按钮
        setButtonTips();
        // 棋盘
        initChessBoard();
        // 库招表
        initBookTable();
        // 引擎view
        initEngineView();
        // 连线器
        initGraphLinker();
        // 按钮监听
        initButtonListener();
        // autofit board size listener
        initAutoFitBoardListener();
        // canvas drag listener
        initCanvasDragListener();
        // line chart
        initLineChart();
        // init chess manual
        chessManualHandle = new ChessManualHandle(chessManualPane, menuOfChessNotation, menuOfShowTactic, notationTree,
                manualTitleLabel, recordTable, subRecordTable, remarkText,
                manualBackButton, manualDeleteButton, manualDownButton, manualFinalButton,
                manualForwardButton, manualFrontButton, manualPlayButton, manualUpButton,
                openManualButton, saveManualButton, manualScoreButton, competitionNameText, competitionCityText, competitionDateText,
                competitionRedText, competitionBlackText, this);

        useOpenBook.setValue(prop.getBookSwitch());
        // 初始化棋局
        newChessBoard(null);
        // 启动检查：当前是象棋模式却挂着揭棋引擎，换回象棋引擎
        if (prop.getGameMode() != ChessBoard.GameMode.JIEQI && JIEQI_ENGINE_NAME.equals(prop.getEngineName())) {
            String xq = findXiangqiEngineName();
            if (xq != null && !xq.isBlank()) {
                JieqiTrace.log("启动检查：当前是象棋模式，引擎从揭棋引擎换回 " + xq);
                prop.setEngineName(xq);
                refreshEngineComboBox();
            }
        }
        // 加载引擎
        loadEngine(prop.getEngineName());
    }

    private void importFromBufferImage(BufferedImage img) {
        char[][] result = graphLinker.findChessBoard(img);
        if (result != null) {
            if (!XiangqiUtils.validateChessBoard(result) && !DialogUtils.showConfirmDialog("提示", "检测到局面不合法，可能会导致引擎退出或者崩溃，是否继续？")) {
                return;
            }
            String fenCode = ChessBoard.fenCode(result, true);
            newFromOriginFen(fenCode);
        }
    }

    private void importFromImgFile(File f) {
        if (f.exists() && PathUtils.isImage(f.getAbsolutePath())) {
            try {
                BufferedImage img = ImageIO.read(f);
                importFromBufferImage(img);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void initCanvasDragListener() {
        this.canvas.setOnDragDropped(event -> {
            File f = event.getDragboard().getFiles().get(0);
            importFromImgFile(f);
        });
        this.canvas.setOnDragOver(event -> {
            event.acceptTransferModes(TransferMode.ANY);
            event.consume();
        });
    }

    private void initAutoFitBoardListener() {
        borderPane.widthProperty().addListener((observableValue, number, t1) -> {
            board.autoFitSize(t1.doubleValue(), borderPane.getHeight(), splitPane.getDividerPositions()[0]);
        });
        borderPane.heightProperty().addListener((observableValue, number, t1) -> {
            board.autoFitSize(borderPane.getWidth(), t1.doubleValue(), splitPane.getDividerPositions()[0]);
        });
        splitPane.getDividers().get(0).positionProperty().addListener((observableValue, number, t1) -> {
            board.autoFitSize(borderPane.getWidth(), borderPane.getHeight(), t1.doubleValue());
        });
    }

    private void initBookTable() {
        TableColumn moveCol = bookTable.getColumns().get(0);
        moveCol.setCellValueFactory(new PropertyValueFactory<BookData, String>("word"));
        TableColumn scoreCol = bookTable.getColumns().get(1);
        scoreCol.setCellValueFactory(new PropertyValueFactory<BookData, Integer>("score"));
        TableColumn winRateCol = bookTable.getColumns().get(2);
        winRateCol.setCellValueFactory(new PropertyValueFactory<BookData, Double>("winRate"));
        TableColumn winNumCol = bookTable.getColumns().get(3);
        winNumCol.setCellValueFactory(new PropertyValueFactory<BookData, Integer>("winNum"));
        TableColumn drawNumCol = bookTable.getColumns().get(4);
        drawNumCol.setCellValueFactory(new PropertyValueFactory<BookData, Integer>("drawNum"));
        TableColumn loseNumCol = bookTable.getColumns().get(5);
        loseNumCol.setCellValueFactory(new PropertyValueFactory<BookData, Integer>("loseNum"));
        TableColumn noteCol = bookTable.getColumns().get(6);
        noteCol.setCellValueFactory(new PropertyValueFactory<BookData, String>("note"));
        TableColumn sourceCol = bookTable.getColumns().get(7);
        sourceCol.setCellValueFactory(new PropertyValueFactory<BookData, String>("source"));
    }

    public void initStage() {
        borderPane.setPrefWidth(prop.getStageWidth());
        borderPane.setPrefHeight(prop.getStageHeight());
        splitPane.setDividerPosition(0, prop.getSplitPos());
        splitPane2.setDividerPosition(0, prop.getSplitPos2());

        // 窗口置顶
        menuOfTopWindow.setSelected(prop.isTopWindow());
        App.topWindow(prop.isTopWindow());
    }

    private void setButtonTips() {
        newButton.setTooltip(new Tooltip("新局面"));
        copyButton.setTooltip(new Tooltip("复制局面"));
        pasteButton.setTooltip(new Tooltip("粘贴局面"));
        regretButton.setTooltip(new Tooltip("悔棋"));
        reverseButton.setTooltip(new Tooltip("翻转"));
        redButton.setTooltip(new Tooltip("引擎执红"));
        blackButton.setTooltip(new Tooltip("引擎执黑"));
        analysisButton.setTooltip(new Tooltip("分析模式"));
        immediateButton.setTooltip(new Tooltip("立即出招"));
        changeTacticButton.setTooltip(new Tooltip("变招"));
        linkButton.setTooltip(new Tooltip("连线"));
        bookSwitchButton.setTooltip(new Tooltip("启用库招"));

    }

    private void initChessBoard() {
        // 棋步提示
        menuOfStepTip.setSelected(prop.isStepTip());
        // 走棋音效
        menuOfStepSound.setSelected(prop.isStepSound());
        // 连线后台模式
        menuOfLinkBackMode.setSelected(prop.isLinkBackMode());
        // 连线动画确认
        menuOfLinkAnimation.setSelected(prop.isLinkAnimation());
        // show number
        menuOfShowNumber.setSelected(prop.isShowNumber());
        // 显示状态栏
        menuOfShowStatus.setSelected(prop.isLinkShowInfo());
        // 棋盘大小
        if (prop.getBoardSize() == ChessBoard.BoardSize.LARGE_BOARD) {
            menuOfLargeBoard.setSelected(true);
        } else if (prop.getBoardSize() == ChessBoard.BoardSize.BIG_BOARD) {
            menuOfBigBoard.setSelected(true);
        } else if (prop.getBoardSize() == ChessBoard.BoardSize.MIDDLE_BOARD) {
            menuOfMiddleBoard.setSelected(true);
        } else if (prop.getBoardSize() == ChessBoard.BoardSize.AUTOFIT_BOARD) {
            menuOfAutoFitBoard.setSelected(true);
        } else {
            menuOfSmallBoard.setSelected(true);
        }
        // 棋盘样式
        if (prop.getBoardStyle() == ChessBoard.BoardStyle.DEFAULT) {
            menuOfDefaultBoard.setSelected(true);
        } else {
            menuOfCustomBoard.setSelected(true);
        }
        // 右键菜单
        initBoardContextMenu();
        // 状态栏
        this.infoShowLabel.prefWidthProperty().bind(statusToolBar.widthProperty().subtract(120));
        this.timeShowLabel.setText(getTimeStrategyString());
        this.statusToolBar.setVisible(prop.isLinkShowInfo());
    }

    private void initBoardContextMenu() {
        BoardContextMenu.getInstance().setOnAction(event -> {
            MenuItem item = (MenuItem) event.getTarget();
            if ("复制局面FEN".equals(item.getText())) {
                copyButtonClick(null);
            } else if ("粘贴局面FEN".equals(item.getText())) {
                pasteButtonClick(null);
            } else if ("交换行棋方".equals(item.getText())) {
                switchPlayer(true);
            } else if ("编辑局面".equals(item.getText())) {
                editChessBoardClick(null);
            } else if ("复制局面图片".equals(item.getText())) {
                copyImageMenuClick(null);
            } else if ("粘贴局面图片".equals(item.getText())) {
                pasteImageMenuClick(null);
            } else if ("复制棋谱".equals(item.getText())) {
                chessManualHandle.copyChessManual();
            } else if ("粘贴棋谱".equals(item.getText())) {
                chessManualHandle.pasteChessManual();
            }
        });
    }

    @FXML
    public void copyImageMenuClick(ActionEvent event) {
        WritableImage writableImage = new WritableImage((int) canvas.getWidth(), (int) canvas.getHeight());
        canvas.snapshot(null, writableImage);
        BufferedImage bi =SwingFXUtils.fromFXImage(writableImage, null);
        ClipboardUtils.setImage(bi);
    }

    @FXML
    public void pasteImageMenuClick(ActionEvent event) {
        Image img = ClipboardUtils.getImage();
        if (img != null) {
            importFromBufferImage((BufferedImage) img);
        }
    }

    @FXML
    public void editChessBoardClick(ActionEvent e) {
        String fenCode = App.openEditChessBoard(board.getBoard(), redGo, isReverse.getValue());
        newFromOriginFen(fenCode);
    }

    /**
     * new from origin fen that maybe reverse, and stop link mode at the same time
     * @param fenCode
     */
    private void newFromOriginFen(String fenCode) {
        if (StringUtils.isNotEmpty(fenCode)) {
            if (linkMode.getValue()) {
                stopGraphLink();
            }

            newChessBoard(fenCode);
            if (XiangqiUtils.isReverse(fenCode)) {
                reverseButtonClick(null);
            }
        }
    }

    private void newChessBoard(String fenCode) {
        newChessBoard(fenCode, false);
    }

    /**
     * 新建局面
     * @param fenCode 传null 新建默认初始局面；传fenCode 则根据fen创建局面
     */
    private void newChessBoard(String fenCode, boolean fromManual) {
        // 重置按钮
        robotRed.setValue(false);
        redButton.setDisable(false);
        robotBlack.setValue(false);
        blackButton.setDisable(false);
        robotAnalysis.setValue(false);
        immediateButton.setDisable(false);
        isReverse.setValue(false);
        // 引擎停止计算
        engineStop();
        // 绘制棋盘
        board = new ChessBoard(this.canvas, prop.getBoardSize(), prop.getBoardStyle(), prop.isStepTip(), prop.isManualTip(),
                engine != null && engine.getMultiPV() > 1, prop.isStepSound(), prop.isShowNumber(), fenCode);
        // 设置局面
        redGo = StringUtils.isEmpty(fenCode) ? true : fenCode.contains("w");
        boolean jieqiMode = prop.getGameMode() == ChessBoard.GameMode.JIEQI;
        if (jieqiMode) {
            // 揭棋：棋盘自造暗子布局，棋谱用标准初始局面
            board.setGameMode(ChessBoard.GameMode.JIEQI);
            char[][] standard = new char[10][9];
            ChessBoard.initChessBoard(standard);
            fenCode = ChessBoard.fenCode(standard, redGo);
        } else {
            fenCode = board.fenCode(redGo);
        }
        JieqiTrace.log("新建棋盘: 配置模式=" + prop.getGameMode() + " 棋盘模式=" + board.getGameMode() + " 红先=" + redGo + " 暗子数=" + board.hiddenCount());
        // 设置棋谱
        if (!fromManual)
            chessManualHandle.newChessManual(fenCode);
        // 重置趋势图
        refreshLineChart();
        // 重置引擎思考输出
        listView.getItems().clear();
        // 重置揭棋的着法追踪状态
        lastRecordedJieqiMove = null;
        lastJieqiAnalyzedFen = null;
        lastJieqiRetriedFen = null;
        lastAutoClickedJieqi = null;
        jieqiAutoClickTries = 0;
        // 清空思考状态信息（揭棋模式显示盘面状态）
        this.infoShowLabel.setText(jieqiMode ? board.statusText() : "");

        // 库招显示
        if (!jieqiMode) {
            doOpenBook();
        }

        System.gc();
    }

    private void initEngineView() {
        // 引擎列表 线程数 哈希表大小
        refreshEngineComboBox();
        for (int i = 1; i <= Runtime.getRuntime().availableProcessors(); i++) {
            threadComboBox.getItems().add(String.valueOf(i));
        }
        hashComboBox.getItems().addAll("16", "32", "64", "128", "256", "512", "1024", "2048", "4096");
        // 加载设置
        threadComboBox.setValue(String.valueOf(prop.getThreadNum()));
        hashComboBox.setValue(String.valueOf(prop.getHashSize()));
    }


    private void initGraphLinker() {
        try {
            this.graphLinker = com.sun.jna.Platform.isWindows() ?
                    new WindowsGraphLinker(this) : (com.sun.jna.Platform.isLinux() ?
                    new LinuxGraphLinker(this) : new MacosGraphLinker(this));
        } catch (Exception e) {
            e.printStackTrace();
        }

        linkComboBox.getItems().addAll("自动走棋", "观战模式");
        linkComboBox.setValue("自动走棋");
    }

    private void refreshEngineComboBox() {
        if (engineComboBox == null) {
            return;
        }
        engineComboBox.getItems().clear();
        for (EngineConfig ec : prop.getEngineConfigList()) {
            engineComboBox.getItems().add(ec.getName());
        }
        engineComboBox.setValue(prop.getEngineName());
    }

    private void initButtonListener() {
        addListener(redButton, robotRed);
        addListener(blackButton, robotBlack);
        addListener(analysisButton, robotAnalysis);
        addListener(reverseButton, isReverse);
        addListener(linkButton, linkMode);
        addListener(bookSwitchButton, useOpenBook);

        threadComboBox.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observableValue, String s, String t1) {
                int num = Integer.parseInt(t1);
                if (num != prop.getThreadNum()) {
                    prop.setThreadNum(num);
                }
            }
        });
        hashComboBox.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observableValue, String s, String t1) {
                int size = Integer.parseInt(t1);
                if (size != prop.getHashSize()) {
                    prop.setHashSize(size);
                }
            }
        });
        engineComboBox.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observableValue, String s, String t1) {
                if (StringUtils.isNotEmpty(t1) && !t1.equals(prop.getEngineName())) {
                    // 保存引擎设置
                    prop.setEngineName(t1);
                    // 重置三个按钮
                    robotRed.setValue(false);
                    redButton.setDisable(false);
                    robotBlack.setValue(false);
                    blackButton.setDisable(false);
                    robotAnalysis.setValue(false);
                    immediateButton.setDisable(false);
                    // 停止连线
                    if (linkMode.getValue()) {
                        stopGraphLink();
                    }
                    // 加载新引擎
                    loadEngine(t1);
                }
            }
        });
        linkComboBox.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observableValue, String s, String t1) {
                setLinkMode(t1);
            }
        });
    }

    private void setLinkMode(String t1) {
        if (linkMode.getValue()) {
            if ("自动走棋".equals(t1)) {
                // 观战模式切换自动走棋，先停止引擎
                engineStop();
                // 走黑棋/红棋
                if (isReverse.getValue()) {
                    blackButton.setDisable(false);
                    robotBlack.setValue(true);

                    redButton.setDisable(true);
                    robotRed.setValue(false);

                    analysisButton.setDisable(true);
                    robotAnalysis.setValue(false);

                    if (!redGo) {
                        engineGo();
                    }
                } else {
                    redButton.setDisable(false);
                    robotRed.setValue(true);

                    blackButton.setDisable(true);
                    robotBlack.setValue(false);

                    analysisButton.setDisable(true);
                    robotAnalysis.setValue(false);

                    if (redGo) {
                        engineGo();
                    }
                }
            } else {
                analysisButton.setDisable(false);
                robotAnalysis.setValue(true);

                blackButton.setDisable(true);
                robotBlack.setValue(false);

                redButton.setDisable(true);
                robotRed.setValue(false);

                immediateButton.setDisable(true);

                engineGo();
            }
        }
    }

    private void addListener(Button button, ObjectProperty property) {
        property.addListener((ChangeListener<Boolean>) (observableValue, aBoolean, t1) -> {
            setButtonSelected(button, t1);
        });
        setButtonSelected(button, Boolean.TRUE.equals(property.getValue()));
    }

    private void setButtonSelected(Button button, boolean selected) {
        String selectedStylesheet = this.getClass().getResource("/style/selected-button.css").toString();
        if (selected) {
            if (!button.getStylesheets().contains(selectedStylesheet)) {
                button.getStylesheets().add(selectedStylesheet);
            }
            if (!button.getStyleClass().contains("selected-state")) {
                button.getStyleClass().add("selected-state");
            }
        } else {
            button.getStylesheets().remove(selectedStylesheet);
            button.getStyleClass().remove("selected-state");
        }
    }

    private void loadEngine(String name) {
        try {
            if (StringUtils.isNotEmpty(name)) {
                for (EngineConfig ec : prop.getEngineConfigList()) {
                    if (name.equals(ec.getName())) {
                        if (engine != null) {
                            engine.close();
                        }
                        engine = new Engine(ec, this);
                        board.showMultiPV(engine.getMultiPV() > 1);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 连线模式下自动点击走棋
     * @param step
     */
    private void trickAutoClick(ChessBoard.Step step) {
        if (step != null) {
            int x1 = step.getStart().getX(), y1 = step.getStart().getY();
            int x2 = step.getEnd().getX(), y2 = step.getEnd().getY();
            if (robotBlack.getValue()) {
                y1 = 9 - y1;
                y2 = 9 - y2;
                x1 = 8 - x1;
                x2 = 8 - x2;
            }
            graphLinker.autoClick(x1, y1, x2, y2);
        }
        this.isThinking = false;
    }

    @Override
    public void bestMove(String first, String second) {
        if (board.getGameMode() == ChessBoard.GameMode.JIEQI) {
            bestMoveJieqi(first, second);
            return;
        }
        if (redGo && robotRed.getValue() || !redGo && robotBlack.getValue()) {
            ChessBoard.Step s = board.stepForBoard(first);

            Platform.runLater(() -> {
                board.move(s.getStart().getX(), s.getStart().getY(), s.getEnd().getX(), s.getEnd().getY());
                board.setTip(second, null, 1);

                goCallBack(first);
            });

            if (linkMode.getValue()) {
                trickAutoClick(s);
            }
        }
    }

    @Override
    public void thinkDetail(ThinkData td) {
        if (board.getGameMode() == ChessBoard.GameMode.JIEQI) {
            handleJieqiThink(td);
            return;
        }
        if (redGo && robotRed.getValue() || !redGo && robotBlack.getValue() || robotAnalysis.getValue()) {
            td.generate(redGo, isReverse.getValue(), board);
            if (td.getValid()) {
                Platform.runLater(() -> {
                    listView.getItems().addFirst(td);
                    if (listView.getItems().size() > 128) {
                        listView.getItems().removeLast();
                    }

                    if (prop.isLinkShowInfo()) {
                        infoShowLabel.setText(td.getTitle() + " | " + td.getBody());
                        setScoreStyle(infoShowLabel, td.getScore());
                        timeShowLabel.setText(getTimeStrategyString());
                    }

                    board.setTip(td.getDetail().get(0), td.getDetail().size() > 1 ? td.getDetail().get(1) : null, td.getPv());

                    if (td.getPv() == 1) {
                        chessManualHandle.setScore(td.getScore(), td.getMate());
                    }
                });
            }
        }
    }

    /**
     * 揭棋模式下引擎给的着法：合法性校验、走子、连线自动点、棋谱
     */
    private void bestMoveJieqi(String first, String second) {
        if (first == null || first.length() < 4 || !board.jieqiMoveLegal(first)) {
            // 引擎给的着法不合法/不完整：同一局面只重试一次分析，避免死循环
            String fenNow = board.jieqiEngineFen();
            if (fenNow != null && !fenNow.equals(lastJieqiRetriedFen)) {
                lastJieqiRetriedFen = fenNow;
                lastJieqiAnalyzedFen = null;
                JieqiTrace.log("揭棋引擎：着法不合法或不完整（" + first + "），这个局面重新分析一次");
            }
            this.isThinking = false;
            return;
        }

        String f = first.substring(0, 4);
        String s = second != null && second.length() >= 4 ? second.substring(0, 4) : null;
        boolean engineRed = robotRed.getValue();
        boolean engineBlack = robotBlack.getValue();
        if (!engineRed && !engineBlack) {
            // 玩家没手选执子方，按盘面方位推断
            engineBlack = jieqiAutoReversed;
            engineRed = !jieqiAutoReversed;
        }
        boolean engineTurn = engineRed && redGo || engineBlack && !redGo;
        JieqiTrace.log("揭棋引擎 bestmove=" + first + " 轮到红=" + redGo + " 自动走子方=" + (engineRed ? "红" : "黑")
                + "（" + (!robotRed.getValue() && !robotBlack.getValue() ? "按玩家方位推断" : "手选") + "） 会走=" + engineTurn
                + " 分析模式=" + robotAnalysis.getValue() + " 连线中=" + linkMode.getValue() + " 观战=" + isWatchMode());

        String cn = board.describeJieqiMove(f);
        Platform.runLater(() -> {
            if (engineTurn && board.playJieqiMove(f)) {
                try {
                    List<String> nextList = chessManualHandle.boardMove(f, cn != null && !cn.isBlank() ? cn : f);
                    board.setManualList(nextList);
                    refreshLineChart();
                } catch (Throwable e) {
                    JieqiTrace.log("揭棋引擎走子后记棋谱失败 " + e);
                }
                if (board.getJieqiPosition() != null) {
                    redGo = board.getJieqiPosition().side == 'w';
                }
                infoShowLabel.setText(board.statusText());
                JieqiTrace.log("揭棋引擎：已在本地自动走子 " + f + "（" + cn + "）");
            } else {
                board.setTip(f, s, 1);
                infoShowLabel.setText("揭棋推荐: " + board.describeJieqiMove(f));
            }
            timeShowLabel.setText(getTimeStrategyString());
        });

        boolean clickAllowed = engineTurn && linkMode.getValue() && !isWatchMode() && !robotAnalysis.getValue();
        if (clickAllowed && f.equals(lastAutoClickedJieqi) && jieqiAutoClickTries >= 3) {
            if (jieqiAutoClickTries == 3) {
                JieqiTrace.log("揭棋连线：同一手 " + f + " 点了 3 次对方棋盘都没走出去，先停手。请看连线窗口是不是还在那张棋盘上、有没有被别的窗口挡住");
                infoShowLabel.setText("点了 3 次没走出去，先停手：检查连线窗口是否被遮挡");
            }
            jieqiAutoClickTries++;
            clickAllowed = false;
        }

        if (clickAllowed) {
            int[] m = JieqiPosition.parseIccs(f);
            if (f.equals(lastAutoClickedJieqi)) {
                jieqiAutoClickTries++;
            } else {
                lastAutoClickedJieqi = f;
                jieqiAutoClickTries = 1;
            }
            lastRecordedJieqiMove = f;
            JieqiTrace.log("揭棋连线：自动走子 " + f + " 从(行" + m[0] + " 列" + m[1] + ") 到(行" + m[2] + " 列" + m[3] + ")");
            try {
                graphLinker.autoClick(m[1], m[0], m[3], m[2]);
            } catch (Exception e) {
                JieqiTrace.log("揭棋连线：自动走子失败 " + e);
            }
        } else if (engineTurn && linkMode.getValue()) {
            JieqiTrace.log("揭棋连线：轮到我们走，但当前是观战/分析模式，只提示不点（" + f + "）");
        }

        this.isThinking = false;
    }

    /**
     * 揭棋模式的引擎思考输出：只提示合法着法，顺带刷新状态栏与窗口标题
     */
    private void handleJieqiThink(ThinkData td) {
        List<String> detail = td.getDetail();
        if (detail == null || detail.isEmpty()) {
            return;
        }
        String first = detail.get(0);
        if (first == null || first.length() < 4) {
            return;
        }
        first = first.substring(0, 4);
        String second = detail.size() > 1 && detail.get(1) != null && detail.get(1).length() >= 4 ? detail.get(1).substring(0, 4) : null;
        boolean legal = board.jieqiMoveLegal(first);
        long now = System.currentTimeMillis();
        if (now - lastThinkLog > 600) {
            lastThinkLog = now;
            JieqiTrace.log("揭棋引擎输出: " + first + " 合法=" + legal + " 深度=" + td.getDepth() + " 分数=" + td.getScore() + " pv=" + td.getPv());
        }

        // 揭棋的着法不是 ICCS 坐标，生成器会抛异常，失败就用原始着法兜底
        try {
            td.generate(redGo, isReverse.getValue(), board);
            String pvText = jieqiPvText(detail);
            if (pvText != null && !pvText.isEmpty()) {
                td.setBody(pvText);
            }
            td.setValid(true);
        } catch (Throwable e) {
            JieqiTrace.log("揭棋着法列表生成失败 " + e);
        }

        if (!legal) {
            return;
        }
        final String tipMove = first;
        Integer score = td.getScore();
        Platform.runLater(() -> {
            try {
                listView.getItems().addFirst(td);
                if (listView.getItems().size() > 128) {
                    listView.getItems().removeLast();
                }
                board.setTip(tipMove, second, 1);
                infoShowLabel.setText("揭棋推荐: " + board.describeJieqiMove(tipMove) + (score == null ? "" : "　分数 " + score));
                timeShowLabel.setText(getTimeStrategyString());
                setWindowTitle("　[揭棋] 推荐 " + board.describeJieqiMove(tipMove));
                if (now - lastSnapshot > 3000) {
                    lastSnapshot = now;
                    dumpBoardSnapshot("jieqi");
                }
            } catch (Exception e) {
                JieqiTrace.log("揭棋提示失败: " + e);
            }
        });
    }

    /**
     * 揭棋变招文本（拿不到就返回空串）
     */
    private String jieqiPvText(List<String> detail) {
        try {
            return board.describeJieqiPv(detail);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 统一的工具提示样式
     */
    private static Tooltip tip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(250));
        t.setHideDelay(Duration.millis(80));
        t.setShowDuration(Duration.seconds(20));
        return t;
    }

    private String getTimeStrategyString() {
        switch (prop.getAnalysisModel()) {
            case Engine.AnalysisModel.FIXED_TIME:
                return "固定时间" + prop.getAnalysisValue() / 1000d + "秒";
            case Engine.AnalysisModel.FIXED_STEPS:
                return "固定深度" + prop.getAnalysisValue() + "层";
            case Engine.AnalysisModel.FIXED_NODES:
                long nodes = prop.getAnalysisValue();
                if (nodes > 1000) {
                    nodes /= 1000;
                    return "固定节点" + nodes + "K个";
                } else {
                    return "固定节点" + nodes + "个";
                }
            default:
                return "";
        }
    }

    private void setScoreStyle(Label label, double score) {
        label.getStyleClass().removeAll("positive-score", "negative-score");
        label.getStyleClass().add(score >= 0 ? "positive-score" : "negative-score");
    }

    @Override
    public void showBookResults(List<BookData> list) {
        this.bookTable.getItems().clear();
        for (BookData bd : list) {
            String move = bd.getMove();
            bd.setWord(board.translate(move, false));
            this.bookTable.getItems().add(bd);
        }
    }

    @FXML
    public void bookTableClick(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
            if (redGo && !robotRed.getValue() || !redGo && !robotBlack.getValue() ||robotAnalysis.getValue()) {
                BookData bd = bookTable.getSelectionModel().getSelectedItem();
                if (bd == null) {
                    return;
                }
                Platform.runLater(() -> {
                    board.move(bd.getMove());
                    goCallBack(bd.getMove());
                });
            }
        }
    }

    @FXML
    public void exit() {
        if (engine != null) {
            engine.close();
        }

        OpenBookManager.getInstance().close();
//        ExecutorsUtils.getInstance().close();

        graphLinker.stop();

        prop.setStageWidth(borderPane.getWidth());
        prop.setStageHeight(borderPane.getHeight());
        prop.setSplitPos(splitPane.getDividerPositions()[0]);
        prop.setSplitPos2(splitPane2.getDividerPositions()[0]);

        prop.save();

        Platform.exit();
    }

    /**
     * 图形连线初始化棋盘
     * @param fenCode
     * @param isReverse
     */
    @Override
    public void linkerInitChessBoard(String fenCode, boolean isReverse) {
        Platform.runLater(() -> {
            newChessBoard(fenCode);
            if (isReverse) {
                reverseButtonClick(null);
            }
            setLinkMode(linkComboBox.getValue());
        });
    }

    @Override
    public char[][] getEngineBoard() {
        return board.getBoard();
    }

    @Override
    public boolean isThinking() {
        return this.isThinking;
    }

    @Override
    public boolean isWatchMode() {
        return "观战模式".equals(linkComboBox.getValue());
    }

    @Override
    public void linkerMove(int x1, int y1, int x2, int y2) {
        Platform.runLater(() -> {
            String move = board.move(x1, y1, x2, y2);
            if (move != null) {
                boolean red = XiangqiUtils.isRed(board.getBoard()[y2][x2]);
                if (isWatchMode() && (!redGo && red || redGo && !red)) {
                    System.out.println(move + "," + red + ", " + redGo);
                    // 连线识别行棋方错误，自动切换行棋方
                    switchPlayer(false);
                } else {
                    goCallBack(move);
                }
            }
        });
    }

    /**
     * 揭棋补点看门狗：连着线、还没走出去的那一手，隔一会儿补点一次
     */
    private void jieqiRetryTick() {
        try {
            if (board == null || graphLinker == null) {
                return;
            }
            if (!linkMode.getValue() || isWatchMode() || robotAnalysis.getValue()) {
                return;
            }
            if (board.getGameMode() != ChessBoard.GameMode.JIEQI) {
                return;
            }
            if (!board.shouldReClickJieqi()) {
                return;
            }
            String pending = board.getPendingJieqiMove();
            if (pending == null || pending.length() < 4) {
                return;
            }
            int[] pm = JieqiPosition.parseIccs(pending);
            JieqiTrace.log("揭棋连线：这一手 " + pending + " 对方棋盘上还没走出去，补点第 " + (board.getPendingJieqiTry() + 1) + " 次（看门狗）");
            board.markJieqiReClicked();
            graphLinker.autoClick(pm[1], pm[0], pm[3], pm[2]);
        } catch (Throwable e) {
            JieqiTrace.log("揭棋补点看门狗异常 " + e);
        }
    }

    /**
     * 盘面上出现的揭棋新着法记进棋谱
     */
    private void recordJieqiMoveIfAny() {
        try {
            String move = board.getLastJieqiMove();
            if (move == null || move.equals(lastRecordedJieqiMove)) {
                return;
            }
            String cn = board.getLastJieqiMoveText();
            if (cn == null || cn.isBlank()) {
                cn = board.describeJieqiMove(move);
            }
            lastRecordedJieqiMove = move;
            lastAutoClickedJieqi = null;
            jieqiAutoClickTries = 0;
            List<String> nextList = chessManualHandle.boardMove(move, cn == null ? move : cn);
            board.setManualList(nextList);
            refreshLineChart();
            if (board.getJieqiPosition() != null) {
                redGo = board.getJieqiPosition().side == 'w';
            }
            JieqiTrace.log("揭棋连线：棋谱记录 " + move + "（" + cn + "）");
        } catch (Throwable e) {
            JieqiTrace.log("揭棋连线：棋谱记录失败 " + e);
        }
    }

    @Override
    public boolean isJieqiMode() {
        return board != null && board.getGameMode() == ChessBoard.GameMode.JIEQI;
    }

    @Override
    public void linkerJieqiBoard(char[][] grid) {
        linkerJieqiBoard(grid, 0L);
    }

    @Override
    public void linkerJieqiBoard(char[][] grid, long capturedAt) {
        // 连线线程回调：盘面同步会画棋盘、趋势图，统一切到 FX 线程执行
        Platform.runLater(() -> {
            try {
                char ourSide = 'w';
                boolean flip = JieqiBoardRecognizer.isFlipped();
                board.applyJieqiGrid(grid, ourSide, capturedAt);
                if (board.wasJieqiRollback()) {
                    // 这一帧是点了没落下去退回的旧画面，不记棋谱
                    JieqiTrace.log("揭棋连线：这一帧是点了没落下去退回的旧画面，不记棋谱");
                } else {
                    recordJieqiMoveIfAny();
                }
                if (flip != jieqiAutoReversed) {
                    jieqiAutoReversed = flip;
                    board.reverse(flip);
                    jieqiRetryTick();
                    JieqiTrace.log("揭棋连线：盘面方向跟随 JJ 调整（" + (flip ? "翻转，黑方在下" : "常规，红方在下") + "）");
                }
                if (board.getJieqiPosition() != null && graphLinker != null && linkMode.getValue() && !isWatchMode() && !robotAnalysis.getValue()) {
                    engineStop();
                    JieqiTrace.log("揭棋连线：已同步盘面 -> " + board.statusText());
                }
                if (board.getJieqiPosition() != null) {
                    redGo = board.getJieqiPosition().side == 'w';
                }
                String fen = board.jieqiEngineFen();
                if (fen != null && !fen.equals(lastJieqiAnalyzedFen)) {
                    boolean ourTurn = board.getJieqiPosition() != null && board.getJieqiPosition().side == ourSide;
                    lastJieqiAnalyzedFen = fen;
                    if (!ourTurn && !robotAnalysis.getValue()) {
                        JieqiTrace.log("揭棋连线：轮到对方走，自动走棋模式不替对方思考（省下一次分析）");
                    } else {
                        JieqiTrace.log("揭棋连线：局面有变化，重新分析 fen=" + fen);
                        engineGo();
                    }
                }
            } catch (Exception e) {
                JieqiTrace.log("揭棋连线：同步失败 " + e);
            }
        });
    }

    /**
     * 连线模块给状态栏的提示
     */
    @Override
    public void jieqiLinkMessage(String message) {
        Platform.runLater(() -> {
            if (infoShowLabel != null) {
                infoShowLabel.setText(message);
            }
        });
    }

    private void switchPlayer(boolean f) {
        engineStop();

        graphLinker.pause();

        boolean tmpRed = robotRed.getValue(), tmpBlack = robotBlack.getValue(), tmpAnalysis = robotAnalysis.getValue(), tmpLink = linkMode.getValue(), tmpReverse = isReverse.getValue();

        String fenCode = board.fenCode(f ? !redGo : redGo);
        newChessBoard(fenCode);

        isReverse.setValue(tmpReverse);
        board.reverse(tmpReverse);
        robotRed.setValue(tmpRed);
        robotBlack.setValue(tmpBlack);
        robotAnalysis.setValue(tmpAnalysis);
        linkMode.setValue(tmpLink);

        graphLinker.resume();
        if (robotRed.getValue() && redGo || robotBlack.getValue() && !redGo || robotAnalysis.getValue()) {
            engineGo();
        }
    }

    // ------------- 棋谱管理 start -----------------
    private ChessManualHandle chessManualHandle;
    @FXML
    private BorderPane chessManualPane;
    @FXML
    private CheckMenuItem menuOfChessNotation;
    @FXML
    private CheckMenuItem menuOfShowTactic;
    @FXML
    private TreeView notationTree;
    @FXML
    private Label manualTitleLabel;
    @FXML
    private ListView subRecordTable;
    @FXML
    private TextArea remarkText;
    @FXML
    private Button manualBackButton;
    @FXML
    private Button manualDeleteButton;
    @FXML
    private Button manualDownButton;
    @FXML
    private Button manualFinalButton;
    @FXML
    private Button manualForwardButton;
    @FXML
    private Button manualFrontButton;
    @FXML
    private Button manualPlayButton;
    @FXML
    private Button manualUpButton;
    @FXML
    private Button openManualButton;
    @FXML
    private Button saveManualButton;
    @FXML
    private Button manualScoreButton;
    @FXML
    private TextField competitionNameText;
    @FXML
    private TextField competitionCityText;
    @FXML
    private TextField competitionDateText;
    @FXML
    private TextField competitionRedText;
    @FXML
    private TextField competitionBlackText;

    @FXML
    void menuOfShowTacticClick(ActionEvent event) {
        CheckMenuItem item = (CheckMenuItem) event.getTarget();
        prop.setManualTip(item.isSelected());
        board.setManualTip(item.isSelected());
    }
    @FXML
    void openChessManualFolder(ActionEvent event) {
        chessManualHandle.openChessNotationFolder(event);
    }
    @FXML
    void deleteButtonClick(ActionEvent event) {
        checkLinkMode();
        chessManualHandle.deleteButtonClick(event);
    }
    @FXML
    void scoreButtonClick(ActionEvent event) {
        if (engine == null) {
            DialogUtils.showWarningDialog("提示", "引擎未加载");
            return;
        }

        checkLinkMode();
        chessManualHandle.scoreButtonClick(event);
    }
    @FXML
    void playButtonClick(ActionEvent event) {
        checkLinkMode();
        chessManualHandle.playButtonClick(event);
    }
    @FXML
    void downwardButtonClick(ActionEvent event) {
        checkLinkMode();
        chessManualHandle.manualButtonClick(8);
    }
    @FXML
    void upwardButtonClick(ActionEvent event) {
        checkLinkMode();
        chessManualHandle.manualButtonClick(7);
    }

    @Override
    public void turnOnAnalysisMode() {
        if (!robotAnalysis.getValue()) {
            analysisButtonClick(null);
        }
    }

    @Override
    public void turnOffAnalysisMode() {
        if (robotAnalysis.getValue()) {
            analysisButtonClick(null);
        }
    }

    @Override
    public void newChessBoardFromManual(String fenCode) {
        newChessBoard(fenCode, true);
    }

    @Override
    public void browseChessRecord(String fenCode, List<String> moveList, boolean redGo, List<String> nextList) {
        checkLinkMode();
        // 棋盘
        board.browseChessRecord(fenCode, moveList);
        board.setManualList(nextList);
        this.redGo = redGo;
        // 趋势图
        refreshLineChart();
        // 引擎走棋
        if (robotRed.getValue() && robotBlack.getValue()) {
            // 如果引擎执红同时执黑，取消状态（否则会有问题）
            robotRed.setValue(false);
            robotBlack.setValue(false);
            engineStop();
        } else if (redGo && robotRed.getValue() || !redGo && robotBlack.getValue() || robotAnalysis.getValue()) {
            // 轮到引擎走棋或者分析模式
            engineGo();
        } else {
            // 其他情况，停止引擎思考
            engineStop();
            // 库招显示
            doOpenBook();
        }
    }

    @Override
    public void setNextList(List<String> nextList) {
        board.setManualList(nextList);
    }

    private void checkLinkMode() {
        if (linkMode.getValue()) {
            stopGraphLink();
        }
    }

    @FXML
    void recordTableClick(MouseEvent event) {
        checkLinkMode();
        chessManualHandle.manualButtonClick(5);
    }

    @FXML
    public void backButtonClick(ActionEvent event) {
        checkLinkMode();
        chessManualHandle.manualButtonClick(2);
    }

    @FXML
    public void regretButtonClick(ActionEvent event) {
        checkLinkMode();
        if (redGo && robotRed.getValue() || !redGo && robotBlack.getValue()) {
            chessManualHandle.manualButtonClick(2);
        } else {
            chessManualHandle.manualButtonClick(6);
        }
    }

    @FXML
    void forwardButtonClick(ActionEvent event) {
        checkLinkMode();
        chessManualHandle.manualButtonClick(3);
    }

    @FXML
    void finalButtonClick(ActionEvent event) {
        checkLinkMode();
        chessManualHandle.manualButtonClick(4);
    }

    @FXML
    void frontButtonClick(ActionEvent event) {
        checkLinkMode();
        chessManualHandle.manualButtonClick(1);
    }

    @FXML
    void openChessManualFile(ActionEvent event) {
        chessManualHandle.openChessManualFile(event);
    }

    @FXML
    void saveAsChessManualFile(ActionEvent event) {
        chessManualHandle.saveAsChessManualFile(event);
    }

    @FXML
    void saveChessManualFile(ActionEvent event) {
        chessManualHandle.saveChessManualFile(event);
    }
    // ------------- 棋谱管理 end -----------------
}
