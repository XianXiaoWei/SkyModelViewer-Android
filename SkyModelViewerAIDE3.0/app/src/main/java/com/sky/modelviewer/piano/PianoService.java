package com.sky.modelviewer.piano;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 光遇自动弹琴服务 - 悬浮窗自动弹琴
 * 只保留15键，半透明深色UI，校准适配刘海屏/挖孔屏
 */
public class PianoService extends AccessibilityService {

    private static final String TAG = "PianoService";
    private static final String PREFS = "piano_prefs";
    private static final String KEY_COORDS = "coords_15";

    // 颜色方案 - 深色毛玻璃精致风格
    private static final int C_PANEL   = 0xE8161620;  // 面板背景（深黑紫，91%不透明）
    private static final int C_HEADER  = 0xE66C43FF;  // 标题栏（紫蓝渐变色，90%不透明）
    private static final int C_TEXT    = 0xFFFFFFFF;  // 文字主色
    private static final int C_TEXT2   = 0x88FFFFFF;  // 文字次色（柔和）
    private static final int C_BTN     = 0xFF6C43FF;  // 按钮主色（紫蓝）
    private static final int C_BTN2    = 0x2AFFFFFF;  // 按钮次色（半透明白）
    private static final int C_PLAY    = 0xFF2DD4BF;  // 播放按钮（青绿，明亮）
    private static final int C_STOP    = 0xFFFB7185;  // 停止按钮（玫红，柔和）
    private static final int C_CALIB   = 0xFFFB923C;  // 校准按钮（暖橙）
    private static final int C_OK      = 0xFF34D399;  // 校准成功（翠绿）
    private static final int C_ROW     = 0x14FFFFFF;  // 乐谱行背景（微白）
    private static final int C_ROW_SEL = 0x406C43FF;  // 选中行（紫蓝高亮）
    private static final int C_BORDER  = 0x336C43FF;  // 边框（紫蓝色调）
    private static final int C_ACCENT  = 0xFFA78BFA;  // 强调色（浅紫，速度文字等）

    private static volatile PianoService instance = null;

    private WindowManager wm;
    private View mainPanel;
    private View minView;
    private WindowManager.LayoutParams mainParams;
    private WindowManager.LayoutParams minParams;
    private boolean panelShowing = false;
    private boolean minShowing = false;

    private FrameLayout contentFrame;
    private View dirView;
    private View playView;
    private boolean inPlayer = false;

    private TextView tvTitle;
    private LinearLayout sheetList;
    private ScrollView sheetScroll;
    private TextView tvEmpty;
    private TextView tvCoordStatus;
    private Button btnCalibrate;

    private TextView tvSheetName;
    private SeekBar seekBar;
    private TextView tvTime;
    private Button btnPlayPause;
    private Button btnStop;
    private Button btnSpeedDn;
    private Button btnSpeedUp;
    private TextView tvSpeed;

    private List<SheetParser.ParsedSheet> sheets = new ArrayList<SheetParser.ParsedSheet>();
    private SheetParser.ParsedSheet currentSheet = null;
    private int currentSheetIndex = 0;

    private volatile boolean playing = false;
    private volatile boolean paused = false;
    private volatile boolean userSeeking = false;
    private volatile int noteIndex = 0;
    private Thread playThread = null;
    private float speed = 1.0f;

    private float[] coords = new float[30]; // 15键 x,y
    private boolean calibrated = false;

    private View calibOverlay;
    private int calibIdx = 0;
    private List<float[]> calibPoints = new ArrayList<float[]>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // ==================== 生命周期 ====================

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (int i = 0; i < coords.length; i++) coords[i] = -1f;
        loadCoords();
        Log.d(TAG, "PianoService connected");
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        stopPlayback();
        hidePanelInternal();
        instance = null;
        super.onDestroy();
    }

    // ==================== 公开静态方法 ====================

    public static boolean isReady() { return instance != null; }

    public static void showPanel() {
        if (instance != null) instance.showPanelInternal();
    }

    public static void hidePanel() {
        if (instance != null) instance.hidePanelInternal();
    }

    public static boolean tap(float x, float y, long duration) {
        if (instance != null) return instance.tapInternal(x, y, duration);
        return false;
    }

    // ==================== 工具方法 ====================

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int layoutType() {
        if (Build.VERSION.SDK_INT >= 26)
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** 获取状态栏高度（刘海屏/挖孔屏安全偏移） */
    private int statusBarHeight() {
        int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (rid > 0) return getResources().getDimensionPixelSize(rid);
        return dp(24);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(0, 0, 0, 0);
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout.LayoutParams lpWeight(int h, float weight) {
        return new LinearLayout.LayoutParams(0, h, weight);
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable roundRectStroke(int color, int radius, int stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(stroke, strokeColor);
        return d;
    }

    private Button makeBtn(String text, int bg, int textColor, int h) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(14);
        b.setBackground(roundRect(bg, dp(8)));
        b.setMinimumHeight(h);
        b.setHeight(h);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setAllCaps(false);
        return b;
    }

    // ==================== 面板显示/隐藏 ====================

    private void showPanelInternal() {
        if (minShowing) {
            try { wm.removeView(minView); } catch (Exception e) {}
            minShowing = false;
        }
        if (panelShowing) return;
        if (mainPanel == null) buildPanel();
        if (mainParams == null) {
            mainParams = new WindowManager.LayoutParams();
            mainParams.width = dp(250);
            mainParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            mainParams.type = layoutType();
            // 关键：用 NOT_TOUCH_MODAL 而非 NOT_FOCUSABLE，这样按钮可以点击
            mainParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            mainParams.format = PixelFormat.TRANSLUCENT;
            mainParams.gravity = Gravity.TOP | Gravity.START;
            mainParams.x = dp(16);
            mainParams.y = statusBarHeight() + dp(8);
        }
        try {
            wm.addView(mainPanel, mainParams);
            panelShowing = true;
            refreshSheets();
            updateCoordStatus();
        } catch (Exception e) {
            Log.e(TAG, "showPanel failed", e);
            Toast.makeText(this, "无法显示悬浮窗，请检查权限", Toast.LENGTH_SHORT).show();
        }
    }

    private void hidePanelInternal() {
        stopPlayback();
        if (calibOverlay != null) {
            try { wm.removeView(calibOverlay); } catch (Exception e) {}
            calibOverlay = null;
        }
        if (panelShowing) {
            try { wm.removeView(mainPanel); } catch (Exception e) {}
            panelShowing = false;
        }
        if (minShowing) {
            try { wm.removeView(minView); } catch (Exception e) {}
            minShowing = false;
        }
        inPlayer = false;
    }

    private void minimizePanel() {
        if (panelShowing) {
            try { wm.removeView(mainPanel); } catch (Exception e) {}
            panelShowing = false;
        }
        if (minShowing) return;

        // 最小化条：音符图标 + 播放/暂停按钮
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(roundRect(C_PANEL, dp(20)));
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        // 音符图标按钮（点击展开面板）
        Button btnIcon = new Button(this);
        btnIcon.setText("\u266A");
        btnIcon.setTextColor(C_TEXT);
        btnIcon.setTextSize(16);
        btnIcon.setBackground(roundRect(C_BTN, dp(14)));
        btnIcon.setWidth(dp(32));
        btnIcon.setHeight(dp(32));
        btnIcon.setMinWidth(dp(32));
        btnIcon.setMinHeight(dp(32));
        btnIcon.setPadding(0, 0, 0, 0);
        btnIcon.setAllCaps(false);

        // 播放/暂停按钮
        final Button btnMiniPlay = new Button(this);
        btnMiniPlay.setText(playing && !paused ? "\u23F8" : "\u25B6");
        btnMiniPlay.setTextColor(C_TEXT);
        btnMiniPlay.setTextSize(14);
        btnMiniPlay.setBackground(roundRect(C_PLAY, dp(14)));
        btnMiniPlay.setWidth(dp(32));
        btnMiniPlay.setHeight(dp(32));
        btnMiniPlay.setMinWidth(dp(32));
        btnMiniPlay.setMinHeight(dp(32));
        btnMiniPlay.setPadding(0, 0, 0, 0);
        btnMiniPlay.setAllCaps(false);

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        iconLp.setMargins(0, 0, dp(4), 0);
        btnIcon.setLayoutParams(iconLp);
        btnMiniPlay.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));

        bar.addView(btnIcon);
        bar.addView(btnMiniPlay);

        // 音符图标点击展开
        btnIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPanelInternal(); }
        });

        // 播放/暂停按钮
        btnMiniPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentSheet == null) {
                    Toast.makeText(PianoService.this, "\u8BF7\u5148\u9009\u62E9\u4E50\u8C31", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!calibrated) {
                    Toast.makeText(PianoService.this, "\u8BF7\u5148\u6821\u51C6", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (playing) {
                    if (paused) {
                        resumePlayback();
                        btnMiniPlay.setText("\u23F8");
                    } else {
                        pausePlayback();
                        btnMiniPlay.setText("\u25B6");
                    }
                } else {
                    startPlayback();
                    btnMiniPlay.setText("\u23F8");
                }
            }
        });

        minParams = new WindowManager.LayoutParams();
        minParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        minParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        minParams.type = layoutType();
        minParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        minParams.format = PixelFormat.TRANSLUCENT;
        minParams.gravity = Gravity.TOP | Gravity.START;
        minParams.x = dp(16);
        minParams.y = statusBarHeight() + dp(8);

        // 统一拖动+点击监听器：每个按钮都能拖动整个条，没移动则触发点击
        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private int ix, iy;
            private float tx, ty;
            private boolean moved;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = minParams.x; iy = minParams.y;
                        tx = e.getRawX(); ty = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - tx) > 8 || Math.abs(e.getRawY() - ty) > 8) moved = true;
                        if (moved) {
                            minParams.x = ix + (int)(e.getRawX() - tx);
                            minParams.y = iy + (int)(e.getRawY() - ty);
                            try { wm.updateViewLayout(minView, minParams); } catch (Exception ex) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) v.performClick();
                        return true;
                }
                return false;
            }
        };
        btnIcon.setOnTouchListener(dragListener);
        btnMiniPlay.setOnTouchListener(dragListener);

        try {
            wm.addView(bar, minParams);
            minView = bar;
            minShowing = true;
        } catch (Exception ex) {
            Log.e(TAG, "minimize failed", ex);
        }
    }

    // ==================== 构建面板 ====================

    private void buildPanel() {
        int pad = dp(8);
        int gap = dp(4);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundRectStroke(C_PANEL, dp(14), dp(1), C_BORDER));

        // ===== 标题栏 =====
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(roundRect(C_HEADER, dp(14)));
        header.setPadding(pad, dp(8), pad, dp(8));

        tvTitle = new TextView(this);
        tvTitle.setText("\u81EA\u52A8\u5F39\u7434");
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTextSize(15);
        tvTitle.setLayoutParams(lpWeight(LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 标题栏最小化按钮：固定正方形，紧挨右边
        Button btnMin = makeBtn("\u2014", C_BTN2, C_TEXT, dp(30));
        btnMin.setWidth(dp(30));
        btnMin.setHeight(dp(30));
        btnMin.setMinWidth(dp(30));
        btnMin.setMinHeight(dp(30));
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        minLp.setMargins(0, 0, 0, 0);
        btnMin.setLayoutParams(minLp);

        // 标题栏关闭按钮：固定正方形，与最小化按钮紧挨
        Button btnClose = makeBtn("\u2715", 0x33FF5C7A, C_TEXT, dp(30));
        btnClose.setWidth(dp(30));
        btnClose.setHeight(dp(30));
        btnClose.setMinWidth(dp(30));
        btnClose.setMinHeight(dp(30));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        closeLp.setMargins(dp(2), 0, 0, 0);
        btnClose.setLayoutParams(closeLp);

        header.addView(tvTitle);
        header.addView(btnMin);
        header.addView(btnClose);
        root.addView(header);

        // 标题栏拖动
        header.setOnTouchListener(new View.OnTouchListener() {
            private int ix, iy;
            private float tx, ty;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    ix = mainParams.x; iy = mainParams.y;
                    tx = e.getRawX(); ty = e.getRawY();
                    return true;
                } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    mainParams.x = ix + (int)(e.getRawX() - tx);
                    mainParams.y = iy + (int)(e.getRawY() - ty);
                    try { wm.updateViewLayout(mainPanel, mainParams); } catch (Exception ex) {}
                    return true;
                }
                return false;
            }
        });

        btnMin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { minimizePanel(); }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hidePanelInternal(); }
        });

        // ===== 内容容器 =====
        contentFrame = new FrameLayout(this);
        contentFrame.setPadding(pad, pad, pad, pad);

        buildDirView();
        buildPlayView();

        contentFrame.addView(dirView);
        contentFrame.addView(playView);
        playView.setVisibility(View.GONE);

        root.addView(contentFrame);
        mainPanel = root;
    }

    // ==================== 目录视图 ====================

    private void buildDirView() {
        LinearLayout dir = new LinearLayout(this);
        dir.setOrientation(LinearLayout.VERTICAL);

        // 乐谱列表
        sheetScroll = new ScrollView(this);
        sheetScroll.setBackgroundColor(0x00000000);
        sheetScroll.setLayoutParams(lp(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));

        sheetList = new LinearLayout(this);
        sheetList.setOrientation(LinearLayout.VERTICAL);
        sheetList.setLayoutParams(lp(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        tvEmpty = new TextView(this);
        tvEmpty.setText("\u6CA1\u6709\u4E50\u8C31\uFF0C\u8BF7\u5BFC\u5165");
        tvEmpty.setTextColor(C_TEXT2);
        tvEmpty.setTextSize(12);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, dp(30), 0, dp(30));
        sheetList.addView(tvEmpty);

        sheetScroll.addView(sheetList);
        dir.addView(sheetScroll);

        // 底部校准区
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(4), 0, 0);

        tvCoordStatus = new TextView(this);
        tvCoordStatus.setText("15\u952E\u672A\u6821\u51C6");
        tvCoordStatus.setTextColor(C_TEXT2);
        tvCoordStatus.setTextSize(11);
        tvCoordStatus.setLayoutParams(lpWeight(
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnCalibrate = makeBtn("\u6821\u51C615\u952E", C_CALIB, C_TEXT, dp(34));

        bottom.addView(tvCoordStatus);
        bottom.addView(btnCalibrate);
        dir.addView(bottom);

        btnCalibrate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startCalibration(); }
        });

        dirView = dir;
    }

    // ==================== 播放视图 ====================

    private void buildPlayView() {
        LinearLayout play = new LinearLayout(this);
        play.setOrientation(LinearLayout.VERTICAL);

        // 返回按钮 + 乐谱名
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, 0, 0, dp(8));

        // 返回按钮：固定正方形
        Button btnBack = makeBtn("\u25C0", C_BTN2, C_TEXT, dp(30));
        btnBack.setWidth(dp(30));
        btnBack.setHeight(dp(30));
        btnBack.setMinWidth(dp(30));
        btnBack.setMinHeight(dp(30));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        backLp.setMargins(0, 0, dp(6), 0);
        btnBack.setLayoutParams(backLp);
        tvSheetName = new TextView(this);
        tvSheetName.setText("");
        tvSheetName.setTextColor(C_TEXT);
        tvSheetName.setTextSize(13);
        tvSheetName.setSingleLine(true);
        tvSheetName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvSheetName.setGravity(Gravity.CENTER);
        tvSheetName.setPadding(dp(8), 0, dp(8), 0);
        tvSheetName.setLayoutParams(lpWeight(
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧占位View，与返回按钮对称，让乐谱名真正居中
        View spacer = new View(this);
        LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        spacerLp.setMargins(dp(6), 0, 0, 0);
        spacer.setLayoutParams(spacerLp);

        topRow.addView(btnBack);
        topRow.addView(tvSheetName);
        topRow.addView(spacer);
        play.addView(topRow);

        // 进度条
        seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(0);
        seekBar.setPadding(0, dp(4), 0, dp(2));
        play.addView(seekBar);

        // 时间
        tvTime = new TextView(this);
        tvTime.setText("00:00 / 00:00");
        tvTime.setTextColor(C_TEXT2);
        tvTime.setTextSize(10);
        tvTime.setGravity(Gravity.CENTER);
        tvTime.setPadding(0, 0, 0, dp(6));
        play.addView(tvTime);

        // 控制按钮行
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER);
        ctrl.setPadding(0, dp(4), 0, dp(4));

        btnSpeedDn = makeBtn("\u2212", C_BTN2, C_TEXT, dp(38));
        btnPlayPause = makeBtn("\u25B6", C_PLAY, C_TEXT, dp(38));
        btnStop = makeBtn("\u25A0", C_STOP, C_TEXT, dp(38));
        btnSpeedUp = makeBtn("+", C_BTN2, C_TEXT, dp(38));

        // 四个控制按钮统一为38x38正方形，间距4dp
        // 每个按钮独立LayoutParams对象，避免addView后被修改
        LinearLayout.LayoutParams ctrlLp1 = lp(dp(38), dp(38));
        ctrlLp1.setMargins(dp(4), 0, dp(4), 0);
        btnSpeedDn.setLayoutParams(ctrlLp1);

        LinearLayout.LayoutParams ctrlLp2 = lp(dp(38), dp(38));
        ctrlLp2.setMargins(dp(4), 0, dp(4), 0);
        btnPlayPause.setLayoutParams(ctrlLp2);

        LinearLayout.LayoutParams ctrlLp3 = lp(dp(38), dp(38));
        ctrlLp3.setMargins(dp(4), 0, dp(4), 0);
        btnStop.setLayoutParams(ctrlLp3);

        LinearLayout.LayoutParams ctrlLp4 = lp(dp(38), dp(38));
        ctrlLp4.setMargins(dp(4), 0, dp(4), 0);
        btnSpeedUp.setLayoutParams(ctrlLp4);

        ctrl.addView(btnSpeedDn);
        ctrl.addView(btnPlayPause);
        ctrl.addView(btnStop);
        ctrl.addView(btnSpeedUp);
        play.addView(ctrl);

        // 速度
        tvSpeed = new TextView(this);
        tvSpeed.setText("\u901F\u5EA6 1.0x");
        tvSpeed.setTextColor(C_ACCENT);
        tvSpeed.setTextSize(11);
        tvSpeed.setGravity(Gravity.CENTER);
        tvSpeed.setPadding(0, dp(2), 0, dp(6));
        play.addView(tvSpeed);

        // 事件
        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { backToDirectory(); }
        });
        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (playing) {
                    if (paused) resumePlayback();
                    else pausePlayback();
                } else {
                    startPlayback();
                }
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopPlayback(); }
        });
        btnSpeedDn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeSpeed(-0.1f); }
        });
        btnSpeedUp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeSpeed(0.1f); }
        });

        // SeekBar拖动
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && currentSheet != null && currentSheet.notes.size() > 0) {
                    long total = currentSheet.notes.get(currentSheet.notes.size() - 1).time
                            - currentSheet.notes.get(0).time;
                    if (total <= 0) total = 1;
                    long cur = (long)(total * progress / 100f);
                    tvTime.setText(formatTime(cur) + " / " + formatTime(total));
                }
            }
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
                paused = true;
            }
            public void onStopTrackingTouch(SeekBar sb) {
                if (currentSheet != null && currentSheet.notes.size() > 0) {
                    int target = (int)((long)sb.getProgress() * currentSheet.notes.size() / 100);
                    if (target >= currentSheet.notes.size()) target = currentSheet.notes.size() - 1;
                    noteIndex = target;
                }
                userSeeking = false;
                if (playing) paused = false;
            }
        });

        playView = play;
    }

    // ==================== 目录/播放切换 ====================

    private void showDirectory() {
        inPlayer = false;
        dirView.setVisibility(View.VISIBLE);
        playView.setVisibility(View.GONE);
        tvTitle.setText("\u81EA\u52A8\u5F39\u7434");
    }

    private void showPlayer() {
        inPlayer = true;
        dirView.setVisibility(View.GONE);
        playView.setVisibility(View.VISIBLE);
        tvTitle.setText("\u64AD\u653E\u4E50\u8C31");
        if (currentSheet != null) {
            tvSheetName.setText(currentSheet.name);
            long total = currentSheet.notes.get(currentSheet.notes.size() - 1).time
                    - currentSheet.notes.get(0).time;
            tvTime.setText("00:00 / " + formatTime(total));
        }
        updateCoordStatus();
    }

    private void backToDirectory() {
        stopPlayback();
        showDirectory();
    }

    // ==================== 乐谱列表 ====================

    private void refreshSheets() {
        File dir = getExternalFilesDir("sheets");
        sheets = SheetParser.scanDirectory(dir);
        sheetList.removeAllViews();
        if (sheets.isEmpty()) {
            sheetList.addView(tvEmpty);
            return;
        }
        for (int i = 0; i < sheets.size(); i++) {
            final int idx = i;
            final SheetParser.ParsedSheet s = sheets.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(roundRect(C_ROW, dp(8)));
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            LinearLayout.LayoutParams rp = lp(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, 0, 0, dp(4));
            row.setLayoutParams(rp);

            TextView name = new TextView(this);
            name.setText(s.name);
            name.setTextColor(C_TEXT);
            name.setTextSize(13);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setLayoutParams(lpWeight(LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView info = new TextView(this);
            info.setText(s.notes.size() + "\u97F3\u7B26");
            info.setTextColor(C_TEXT2);
            info.setTextSize(10);

            row.addView(name);
            row.addView(info);

            // 在row的info后面添加删除按钮（仅非自带乐谱）
            // 自带乐谱文件名为"dayu.txt"，不可删除；导入乐谱可删除
            boolean isBuiltin = "dayu.txt".equals(s.fileName);
            if (!isBuiltin) {
                Button btnDel = new Button(this);
                btnDel.setText("\u2715");
                btnDel.setTextColor(C_STOP);
                btnDel.setTextSize(12);
                btnDel.setBackground(roundRect(0x33FF5C7A, dp(6)));
                btnDel.setWidth(dp(28));
                btnDel.setHeight(dp(28));
                btnDel.setMinWidth(dp(28));
                btnDel.setMinHeight(dp(28));
                btnDel.setPadding(0, 0, 0, 0);
                btnDel.setAllCaps(false);
                LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(dp(28), dp(28));
                delLp.setMargins(dp(8), 0, 0, 0);
                btnDel.setLayoutParams(delLp);

                btnDel.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        // 删除乐谱文件
                        File delDir = getExternalFilesDir("sheets");
                        File f = new File(delDir, s.fileName);
                        if (f.exists()) f.delete();
                        refreshSheets();
                        Toast.makeText(PianoService.this, "\u5DF2\u5220\u9664", Toast.LENGTH_SHORT).show();
                    }
                });
                row.addView(btnDel);
            }

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    currentSheetIndex = idx;
                    currentSheet = s;
                    showPlayer();
                }
            });

            sheetList.addView(row);
        }
    }

    // ==================== 校准 ====================

    private void startCalibration() {
        if (calibOverlay != null) return;
        stopPlayback();
        calibIdx = 0;
        calibPoints.clear();

        // 自定义View画编号标记
        View v = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                // 半透明背景
                Paint bg = new Paint();
                bg.setColor(0x88000000);
                canvas.drawPaint(bg);

                // 已点击的绿色圆+编号
                Paint circle = new Paint();
                circle.setAntiAlias(true);
                circle.setStyle(Paint.Style.FILL);
                Paint stroke = new Paint();
                stroke.setAntiAlias(true);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(3);
                stroke.setColor(0xFFFFFFFF);
                Paint num = new Paint();
                num.setAntiAlias(true);
                num.setColor(0xFFFFFFFF);
                num.setTextSize(dp(28));
                num.setTextAlign(Paint.Align.CENTER);

                for (int i = 0; i < calibPoints.size(); i++) {
                    float[] pt = calibPoints.get(i);
                    circle.setColor(0x804CAF50);
                    canvas.drawCircle(pt[0], pt[1], dp(24), circle);
                    stroke.setColor(0xFFFFFFFF);
                    canvas.drawCircle(pt[0], pt[1], dp(24), stroke);
                    num.setColor(0xFFFFFFFF);
                    String t = String.valueOf(i + 1);
                    canvas.drawText(t, pt[0], pt[1] + dp(10), num);
                }
                // 提示文字
                Paint tip = new Paint();
                tip.setAntiAlias(true);
                tip.setColor(0xFFFFFFFF);
                tip.setTextSize(dp(18));
                tip.setTextAlign(Paint.Align.CENTER);
                String msg = "\u8BF7\u70B9\u51FB\u7B2C " + (calibIdx + 1) + " \u4E2A\u952E ("
                        + (calibIdx + 1) + "/15)\n\u4ECE\u5DE6\u5230\u53F3\u4F9D\u6B21\u70B9\u51FB\u7434\u952E";
                int y = statusBarHeight() + dp(40);
                for (String line : msg.split("\n")) {
                    canvas.drawText(line, getWidth() / 2, y, tip);
                    y += dp(26);
                }

                // 右上角取消按钮
                int btnW = dp(80);
                int btnH = dp(36);
                int btnX = getWidth() - btnW - dp(16);
                int btnY = statusBarHeight() + dp(10);
                Paint cancelBg = new Paint();
                cancelBg.setAntiAlias(true);
                cancelBg.setColor(0xCCFB7185);
                canvas.drawRoundRect(btnX, btnY, btnX + btnW, btnY + btnH, dp(8), dp(8), cancelBg);
                Paint cancelText = new Paint();
                cancelText.setAntiAlias(true);
                cancelText.setColor(0xFFFFFFFF);
                cancelText.setTextSize(dp(14));
                cancelText.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("\u53D6\u6D88\u6821\u51C6", btnX + btnW / 2, btnY + btnH / 2 + dp(5), cancelText);
            }
        };

        WindowManager.LayoutParams p = new WindowManager.LayoutParams();
        p.width = WindowManager.LayoutParams.MATCH_PARENT;
        p.height = WindowManager.LayoutParams.MATCH_PARENT;
        p.type = layoutType();
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        p.format = PixelFormat.TRANSLUCENT;
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 0;
        p.y = 0;

        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    float x = e.getX();
                    float y = e.getY();
                    // 检测是否点击了取消按钮
                    int btnW = dp(80);
                    int btnH = dp(36);
                    int btnX = v.getWidth() - btnW - dp(16);
                    int btnY = statusBarHeight() + dp(10);
                    if (x >= btnX && x <= btnX + btnW && y >= btnY && y <= btnY + btnH) {
                        // 取消校准
                        try { wm.removeView(calibOverlay); } catch (Exception ex) {}
                        calibOverlay = null;
                        calibPoints.clear();
                        calibIdx = 0;
                        Toast.makeText(PianoService.this, "\u5DF2\u53D6\u6D88\u6821\u51C6", Toast.LENGTH_SHORT).show();
                        showPanelInternal();
                        return true;
                    }
                    // overlay全屏MATCH_PARENT覆盖，用getX/getY即可与dispatchGesture坐标系一致
                    // 避免getRawY在刘海屏/挖孔屏上包含状态栏偏移导致点击偏移
                    calibPoints.add(new float[]{x, y});
                    coords[calibIdx * 2] = x;
                    coords[calibIdx * 2 + 1] = y;
                    calibIdx++;
                    v.invalidate();
                    if (calibIdx >= 15) {
                        calibrated = true;
                        saveCoords();
                        try { wm.removeView(calibOverlay); } catch (Exception ex) {}
                        calibOverlay = null;
                        Toast.makeText(PianoService.this, "\u6821\u51C6\u5B8C\u6210", Toast.LENGTH_SHORT).show();
                        updateCoordStatus();
                        showPanelInternal();
                    }
                    return true;
                }
                return false;
            }
        });

        try {
            wm.addView(v, p);
            calibOverlay = v;
            Toast.makeText(this, "\u8BF7\u4F9D\u6B21\u70B9\u51FB15\u4E2A\u7434\u952E", Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {
            Log.e(TAG, "calibration failed", ex);
        }
    }

    // ==================== 坐标持久化 ====================

    private void loadCoords() {
        String s = prefs.getString(KEY_COORDS, null);
        if (s == null) return;
        String[] parts = s.split(",");
        if (parts.length != 30) return;
        try {
            for (int i = 0; i < 30; i++) coords[i] = Float.parseFloat(parts[i]);
            calibrated = true;
        } catch (Exception e) { calibrated = false; }
    }

    private void saveCoords() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            if (i > 0) sb.append(",");
            sb.append(coords[i]);
        }
        prefs.edit().putString(KEY_COORDS, sb.toString()).apply();
    }

    private void updateCoordStatus() {
        String ok = "15\u952E\u5DF2\u6821\u51C6";
        String no = "15\u952E\u672A\u6821\u51C6";
        if (tvCoordStatus != null) {
            tvCoordStatus.setText(calibrated ? ok : no);
            tvCoordStatus.setTextColor(calibrated ? C_OK : C_TEXT2);
        }
    }

    // ==================== 播放控制 ====================

    private void startPlayback() {
        if (currentSheet == null || currentSheet.notes.isEmpty()) {
            Toast.makeText(this, "\u6CA1\u6709\u4E50\u8C31", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!calibrated) {
            Toast.makeText(this, "\u8BF7\u5148\u6821\u51C6", Toast.LENGTH_SHORT).show();
            return;
        }
        playing = true;
        paused = false;
        btnPlayPause.setText("\u23F8");
        final SheetParser.ParsedSheet sheet = currentSheet;
        playThread = new Thread(new Runnable() {
            public void run() { playSheet(sheet); }
        });
        playThread.start();
    }

    private void pausePlayback() {
        paused = true;
        handler.post(new Runnable() {
            public void run() { btnPlayPause.setText("\u25B6"); }
        });
    }

    private void resumePlayback() {
        paused = false;
        handler.post(new Runnable() {
            public void run() { btnPlayPause.setText("\u23F8"); }
        });
    }

    private void stopPlayback() {
        playing = false;
        paused = false;
        noteIndex = 0;
        if (playThread != null) {
            try { playThread.interrupt(); } catch (Exception e) {}
            playThread = null;
        }
        handler.post(new Runnable() {
            public void run() {
                if (btnPlayPause != null) btnPlayPause.setText("\u25B6");
                if (seekBar != null) seekBar.setProgress(0);
                if (tvTime != null && currentSheet != null && currentSheet.notes.size() > 0) {
                    long total = currentSheet.notes.get(currentSheet.notes.size() - 1).time
                            - currentSheet.notes.get(0).time;
                    tvTime.setText("00:00 / " + formatTime(total));
                }
            }
        });
    }

    private void playSheet(SheetParser.ParsedSheet sheet) {
        long firstTime = sheet.notes.get(0).time;
        long totalTime = sheet.notes.get(sheet.notes.size() - 1).time - firstTime;
        if (totalTime <= 0) totalTime = 1;

        for (int i = noteIndex; i < sheet.notes.size() && playing; i++) {
            while (paused && playing) {
                try { Thread.sleep(50); } catch (InterruptedException e) { playing = false; break; }
            }
            if (!playing) break;

            noteIndex = i;
            SheetParser.ParsedNote note = sheet.notes.get(i);

            // 发送点击
            for (int k = 0; k < note.keys.size(); k++) {
                int key = note.keys.get(k);
                if (key >= 0 && key < 15) {
                    tap(coords[key * 2], coords[key * 2 + 1], 30);
                }
            }

            // 更新进度
            final int fi = i;
            final long curT = note.time - firstTime;
            final long totT = totalTime;
            if (!userSeeking) {
                handler.post(new Runnable() {
                    public void run() {
                        if (seekBar != null) {
                            int pct = totT > 0 ? (int)(curT * 100 / totT) : 0;
                            seekBar.setProgress(pct);
                        }
                        if (tvTime != null) {
                            tvTime.setText(formatTime(curT) + " / " + formatTime(totT));
                        }
                    }
                });
            }

            // 等待到下一个音符
            if (i + 1 < sheet.notes.size() && playing) {
                long interval = sheet.notes.get(i + 1).time - note.time;
                long sleep = (long)(interval / speed);
                if (sleep > 0) {
                    try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
                }
            }
        }

        playing = false;
        handler.post(new Runnable() {
            public void run() {
                if (btnPlayPause != null) btnPlayPause.setText("\u25B6");
                if (seekBar != null) seekBar.setProgress(100);
            }
        });
    }

    private void changeSpeed(float delta) {
        speed += delta;
        if (speed < 0.1f) speed = 0.1f;
        if (speed > 5.0f) speed = 5.0f;
        if (tvSpeed != null) {
            tvSpeed.setText("\u901F\u5EA6 " + String.format("%.1fx", speed));
        }
    }

    private String formatTime(long ms) {
        int sec = (int)(ms / 1000);
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    // ==================== 手势 ====================

    private boolean tapInternal(final float x, final float y, final long duration) {
        handler.post(new Runnable() {
            public void run() { dispatchGestureInternal(x, y, duration); }
        });
        return true;
    }

    private void dispatchGestureInternal(float x, float y, long duration) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0L, duration);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(stroke);
            dispatchGesture(b.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {}
                @Override
                public void onCancelled(GestureDescription g) {}
            }, handler);
        } catch (Exception e) {
            Log.e(TAG, "dispatchGesture failed", e);
        }
    }
}
