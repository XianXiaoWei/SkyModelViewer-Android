package com.sky.modelviewer.badge;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 徽章悬浮窗服务
 *
 * 功能:
 *  - 显示已存储的徽章 (BadgeStore.getAll)
 *  - 每项有"使用"按钮, 调用 BadgeStore.useBadge
 *  - 标题栏可拖动, 有最小化/关闭按钮
 *  - 最小化后显示小图标条, 点击恢复
 *  - 空状态提示
 *
 * UI风格: 与PianoService一致的深色毛玻璃精致风格
 *
 * AIDE兼容: 无lambda, 使用匿名内部类, Java 7语法
 */
public class BadgeFloatingService extends Service {

    private static final String TAG = "BadgeFloatingService";
    private static final String CHANNEL_ID = "badge_floating_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile BadgeFloatingService instance = null;

    // 颜色方案 - 与PianoService一致的深色毛玻璃精致风格
    private static final int C_PANEL   = 0xE8161620;  // 面板背景（深黑紫，91%不透明）
    private static final int C_HEADER  = 0xE66C43FF;  // 标题栏（紫蓝，90%不透明）
    private static final int C_TEXT    = 0xFFFFFFFF;  // 文字主色
    private static final int C_TEXT2   = 0x88FFFFFF;  // 文字次色
    private static final int C_BTN     = 0xFF6C43FF;  // 按钮主色（紫蓝）
    private static final int C_BTN2    = 0x2AFFFFFF;  // 按钮次色（半透明白）
    private static final int C_PLAY    = 0xFF2DD4BF;  // 使用按钮（青绿）
    private static final int C_STOP    = 0xFFFB7185;  // 关闭/危险按钮（玫红）
    private static final int C_ROW     = 0x14FFFFFF;  // 行背景（微白）
    private static final int C_BORDER  = 0x336C43FF;  // 边框（紫蓝色调）
    private static final int C_ACCENT  = 0xFFA78BFA;  // 强调色（浅紫）

    // 悬浮窗尺寸
    private static final int PANEL_WIDTH = 260; // dp

    private WindowManager wm;
    private View mainPanel;
    private View minView;
    private WindowManager.LayoutParams mainParams;
    private WindowManager.LayoutParams minParams;
    private boolean panelShowing = false;
    private boolean minShowing = false;

    // 内容容器
    private LinearLayout badgeListContainer;
    private ScrollView badgeListScroll;
    private TextView tvEmpty;

    // ================================================================
    //  生命周期
    // ================================================================

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Log.d(TAG, "BadgeFloatingService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
        if (!panelShowing && !minShowing) {
            showPanelInternal();
        } else if (panelShowing) {
            refreshBadgeList();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        hidePanelInternal();
        instance = null;
        super.onDestroy();
        Log.d(TAG, "BadgeFloatingService destroyed");
    }

    // ==================== 公开静态方法 ====================

    public static void start(Context context) {
        Intent intent = new Intent(context, BadgeFloatingService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, BadgeFloatingService.class);
        context.stopService(intent);
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void refresh() {
        if (instance != null) {
            instance.refreshBadgeList();
        }
    }

    // ==================== 前台服务通知 ====================

    private android.app.Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        CHANNEL_ID, "徽章悬浮窗", android.app.NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("光遇徽章悬浮窗服务通知");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
        android.app.Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new android.app.Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new android.app.Notification.Builder(this);
        }
        builder.setContentTitle("光遇徽章面板")
                .setContentText("徽章悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setPriority(android.app.Notification.PRIORITY_LOW);
        return builder.build();
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

    private int statusBarHeight() {
        int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (rid > 0) return getResources().getDimensionPixelSize(rid);
        return dp(24);
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
            mainParams.width = dp(PANEL_WIDTH);
            mainParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            mainParams.type = layoutType();
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
            refreshBadgeList();
            Log.d(TAG, "悬浮窗已显示");
        } catch (Exception e) {
            Log.e(TAG, "无法显示悬浮窗", e);
            Toast.makeText(this, "无法显示悬浮窗，请检查权限", Toast.LENGTH_SHORT).show();
            stopSelf();
        }
    }

    private void hidePanelInternal() {
        if (panelShowing) {
            try { wm.removeView(mainPanel); } catch (Exception e) {}
            panelShowing = false;
        }
        if (minShowing) {
            try { wm.removeView(minView); } catch (Exception e) {}
            minShowing = false;
        }
    }

    private void minimizePanel() {
        if (panelShowing) {
            try { wm.removeView(mainPanel); } catch (Exception e) {}
            panelShowing = false;
        }
        if (minShowing) return;

        // 最小化条：徽章图标按钮（点击展开）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(roundRect(C_PANEL, dp(20)));
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        // 徽章图标按钮
        Button btnIcon = new Button(this);
        btnIcon.setText("\u2605");
        btnIcon.setTextColor(C_TEXT);
        btnIcon.setTextSize(16);
        btnIcon.setBackground(roundRect(C_BTN, dp(14)));
        btnIcon.setWidth(dp(36));
        btnIcon.setHeight(dp(36));
        btnIcon.setMinWidth(dp(36));
        btnIcon.setMinHeight(dp(36));
        btnIcon.setPadding(0, 0, 0, 0);
        btnIcon.setAllCaps(false);
        btnIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showPanelInternal();
            }
        });

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        btnIcon.setLayoutParams(iconLp);
        bar.addView(btnIcon);

        // 拖动监听器
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundRectStroke(C_PANEL, dp(14), dp(1), C_BORDER));

        // ===== 标题栏 =====
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(roundRect(C_HEADER, dp(14)));
        header.setPadding(pad, dp(8), pad, dp(8));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("\u5fbd\u7ae0\u9762\u677f");
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvTitle.setLayoutParams(titleLp);

        // 最小化按钮
        Button btnMin = makeBtn("\u2014", C_BTN2, C_TEXT, dp(30));
        btnMin.setWidth(dp(30));
        btnMin.setHeight(dp(30));
        btnMin.setMinWidth(dp(30));
        btnMin.setMinHeight(dp(30));
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        btnMin.setLayoutParams(minLp);

        // 关闭按钮
        Button btnClose = makeBtn("\u2715", 0x33FB7185, C_TEXT, dp(30));
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
            public void onClick(View v) {
                hidePanelInternal();
                stopSelf();
            }
        });

        // ===== 内容区 =====
        badgeListScroll = new ScrollView(this);
        badgeListScroll.setBackgroundColor(0x00000000);
        int maxH = dp(200);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH);
        badgeListScroll.setLayoutParams(scrollLp);

        badgeListContainer = new LinearLayout(this);
        badgeListContainer.setOrientation(LinearLayout.VERTICAL);
        badgeListContainer.setPadding(pad, pad, pad, pad);
        badgeListScroll.addView(badgeListContainer);

        root.addView(badgeListScroll);

        // 空状态提示
        tvEmpty = new TextView(this);
        tvEmpty.setText("\u6682\u65e0\u5df2\u4fdd\u5b58\u7684\u5fbd\u7ae0\n\n\u8bf7\u5728\u5fbd\u7ae0\u52a9\u624b\u4e2d\u6dfb\u52a0\u5fbd\u7ae0");
        tvEmpty.setTextColor(C_TEXT2);
        tvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(dp(16), dp(36), dp(16), dp(36));
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        mainPanel = root;
    }

    // ================================================================
    //  徽章列表刷新
    // ================================================================

    private void refreshBadgeList() {
        if (badgeListContainer == null) return;

        badgeListContainer.removeAllViews();

        List<BadgeStore.StoredBadge> badges = BadgeStore.getAll(this);

        if (badges.isEmpty()) {
            badgeListScroll.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }

        badgeListScroll.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        // 数量提示
        TextView tvCount = new TextView(this);
        tvCount.setText("\u5171 " + badges.size() + " \u4e2a\u5fbd\u7ae0");
        tvCount.setTextColor(C_TEXT2);
        tvCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvCount.setPadding(dp(4), dp(4), dp(4), dp(6));
        badgeListContainer.addView(tvCount);

        // 徽章条目
        for (int i = 0; i < badges.size(); i++) {
            badgeListContainer.addView(buildBadgeItem(badges.get(i)));
        }
    }

    private View buildBadgeItem(final BadgeStore.StoredBadge badge) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(10), dp(8), dp(10), dp(8));
        item.setBackground(roundRect(C_ROW, dp(8)));
        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        itemLp.setMargins(0, 0, 0, dp(4));
        item.setLayoutParams(itemLp);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText(badge.title != null && !badge.title.isEmpty()
                ? badge.title : "(\u672a\u547d\u540d)");
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        item.addView(tvTitle);

        // SK码
        if (badge.skCode != null && !badge.skCode.isEmpty()) {
            TextView tvSk = new TextView(this);
            tvSk.setText("SK: " + badge.skCode);
            tvSk.setTextColor(C_ACCENT);
            tvSk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tvSk.setPadding(0, dp(2), 0, 0);
            tvSk.setSingleLine(true);
            item.addView(tvSk);
        }

        // 渠道
        if (badge.channel != null && !badge.channel.isEmpty()) {
            TextView tvChannel = new TextView(this);
            tvChannel.setText("\u6e20\u9053: " + badge.channel);
            tvChannel.setTextColor(C_TEXT2);
            tvChannel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tvChannel.setPadding(0, dp(2), 0, dp(6));
            tvChannel.setSingleLine(true);
            item.addView(tvChannel);
        }

        // 使用按钮
        Button btnUse = makeBtn("\u4f7f\u7528", C_PLAY, C_TEXT, dp(32));
        btnUse.setWidth(LinearLayout.LayoutParams.MATCH_PARENT);
        btnUse.setTextSize(13);
        LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32));
        btnUse.setLayoutParams(useLp);
        btnUse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean ok = BadgeStore.useBadge(BadgeFloatingService.this,
                        badge.link, badge.channel);
                if (ok) {
                    Toast.makeText(BadgeFloatingService.this,
                            "\u6b63\u5728\u542f\u52a8\u6e38\u620f (" + badge.channel + ")",
                            Toast.LENGTH_SHORT).show();
                    // 保持悬浮窗显示，不关闭也不最小化，避免消失的bug
                    // 用户可手动点击最小化按钮
                } else {
                    Toast.makeText(BadgeFloatingService.this,
                            "\u542f\u52a8\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u6e20\u9053",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        item.addView(btnUse);

        return item;
    }
}
