package com.sky.modelviewer.badge;

import com.sky.modelviewer.R;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 光遇徽章助手 Activity
 *
 * 功能:
 *  - 两个Tab: 我的徽章 / 徽章图鉴
 *  - NFC标签读取 (NfcHelper前台调度)
 *  - 手动输入徽章链接/SK码
 *  - 徽章图鉴 (BadgeData.BADGES 列表)
 *  - 渠道选择 (10个游戏渠道, 水平滚动)
 *  - 使用徽章 (启动游戏)
 *  - 删除徽章 (长按卡片)
 *  - 悬浮窗开关 (BadgeFloatingService)
 *
 * UI风格:
 *  - 与主页一致的浅色卡片风格 (背景 #F0F2F5, 卡片白色圆角)
 *  - 主色 #5B6EF5 (蓝紫色)
 *  - 所有对话框使用布局内显眼全宽Button (不使用AlertDialog默认按钮)
 *  - 纯代码构建UI, 无XML布局依赖
 *
 * AIDE兼容: 无lambda, 使用匿名内部类, Java 7语法
 */
public class BadgeHelperActivity extends Activity {

    private static final String TAG = "BadgeHelperActivity";

    // ================================================================
    //  颜色方案 (与主页配色一致)
    // ================================================================

    private static final int C_PRIMARY      = 0xFF5B6EF5; // 主色 蓝紫
    private static final int C_PRIMARY_DARK = 0xFF4A5CE0; // 主色按下
    private static final int C_PRIMARY_TINT= 0xFFEEF0FE; // 主色浅底
    private static final int C_BG           = 0xFFF0F2F5; // 浅灰背景
    private static final int C_CARD         = 0xFFFFFFFF; // 白色卡片
    private static final int C_TEXT         = 0xFF1A1D26; // 主文字
    private static final int C_TEXT_2       = 0xFF4A5061; // 次要文字
    private static final int C_TEXT_HINT    = 0xFF8891A5; // 灰色提示
    private static final int C_DIVIDER      = 0xFFE2E8F0; // 分割线
    private static final int C_BORDER       = 0xFFE8EBF0; // 边框
    private static final int C_BTN_SECOND   = 0xFFF3F5F9; // 次按钮背景
    private static final int C_SEARCH_BG    = 0xFFF3F5F9; // 搜索/输入框背景
    private static final int C_RED          = 0xFFFF5C7A; // 危险/删除
    private static final int C_GREEN        = 0xFF00C8A8; // 成功
    private static final int C_WHITE        = 0xFFFFFFFF;

    // 图鉴分类配色
    private static final int CAT_SEASON_BG  = 0xFFEEF0FE;
    private static final int CAT_SEASON_TX  = 0xFF5B6EF5;
    private static final int CAT_ANCESTOR_BG= 0xFFE6FAF5;
    private static final int CAT_ANCESTOR_TX= 0xFF00B596;
    private static final int CAT_EVENT_BG   = 0xFFFFF3EA;
    private static final int CAT_EVENT_TX   = 0xFFE6782C;
    private static final int CAT_LIMIT_BG   = 0xFFFFEEF1;
    private static final int CAT_LIMIT_TX   = 0xFFFF5C7A;

    // Tab
    private static final int TAB_MY   = 0;
    private static final int TAB_WIKI = 1;

    // ================================================================
    //  字段
    // ================================================================

    private NfcHelper nfcHelper;

    // 顶部栏
    private Button btnFloating;

    // Tab
    private int currentTab = TAB_MY;
    private TextView tvTabMy;
    private View indicatorMy;
    private TextView tvTabWiki;
    private View indicatorWiki;

    // 控制面板 (仅"我的徽章"Tab显示)
    private View controlPanel;
    private LinearLayout channelRow;
    private String selectedChannel = BadgeData.DEFAULT_CHANNEL;

    // 计数
    private TextView tvCount;

    // 内容区
    private FrameLayout contentFrame;
    private ScrollView scrollMyBadges;
    private LinearLayout layoutMyBadges;
    private ScrollView scrollWiki;
    private LinearLayout layoutWiki;

    // NFC等待对话框
    private Dialog nfcWaitDialog;

    // ================================================================
    //  生命周期
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化NFC
        nfcHelper = new NfcHelper();
        nfcHelper.init(this);

        // 构建UI
        setContentView(buildRootView());

        // 渲染初始数据
        refreshMyBadges();
        buildWikiList();

        // 检查NFC状态
        updateNfcStatus();

        // 处理可能的启动Intent (从NFC标签启动)
        handleNfcIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcHelper != null) {
            nfcHelper.enableForegroundDispatch(this);
        }
        refreshMyBadges();
        updateNfcStatus();
        // 更新悬浮窗按钮文字
        if (btnFloating != null) {
            btnFloating.setText(BadgeFloatingService.isRunning() ? "关闭悬浮窗" : "悬浮窗");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcHelper != null) {
            nfcHelper.disableForegroundDispatch(this);
        }
        if (nfcWaitDialog != null && nfcWaitDialog.isShowing()) {
            nfcWaitDialog.dismiss();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNfcIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                BadgeFloatingService.start(this);
                btnFloating.setText("关闭悬浮窗");
                Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void toggleFloatingWindow() {
        if (BadgeFloatingService.isRunning()) {
            BadgeFloatingService.stop(this);
            btnFloating.setText("悬浮窗");
            Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 100);
            } else {
                try {
                    BadgeFloatingService.start(this);
                    btnFloating.setText("关闭悬浮窗");
                    Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "悬浮窗启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    // ================================================================
    //  UI构建 - 根布局
    // ================================================================

    private View buildRootView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶部栏 ----
        root.addView(buildTopBar());

        // ---- Tab栏 ----
        root.addView(buildTabBar());

        // ---- 控制面板 (渠道 + 操作按钮 + 提示) ----
        controlPanel = buildControlPanel();
        root.addView(controlPanel);

        // ---- 计数文字 ----
        tvCount = new TextView(this);
        tvCount.setTextColor(C_TEXT_HINT);
        tvCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvCount.setPadding(dp(16), dp(8), dp(16), dp(4));
        root.addView(tvCount);

        // ---- 内容区 (FrameLayout 容纳两个Tab的列表) ----
        contentFrame = new FrameLayout(this);

        // 我的徽章列表
        scrollMyBadges = new ScrollView(this);
        scrollMyBadges.setFillViewport(true);
        layoutMyBadges = new LinearLayout(this);
        layoutMyBadges.setOrientation(LinearLayout.VERTICAL);
        layoutMyBadges.setPadding(dp(12), dp(4), dp(12), dp(16));
        scrollMyBadges.addView(layoutMyBadges);

        // 徽章图鉴列表
        scrollWiki = new ScrollView(this);
        scrollWiki.setFillViewport(true);
        layoutWiki = new LinearLayout(this);
        layoutWiki.setOrientation(LinearLayout.VERTICAL);
        layoutWiki.setPadding(dp(12), dp(4), dp(12), dp(16));
        scrollWiki.addView(layoutWiki);
        scrollWiki.setVisibility(View.GONE);

        contentFrame.addView(scrollMyBadges, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        contentFrame.addView(scrollWiki, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        contentFrame.setLayoutParams(contentLp);
        root.addView(contentFrame);

        // 初始选中"我的徽章"
        applyTabState();

        return root;
    }

    // ================================================================
    //  UI构建 - 顶部栏
    // ================================================================

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(C_WHITE);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bar.setLayoutParams(barLp);

        // 返回按钮
        Button btnBack = new Button(this);
        btnBack.setText("\u2190");
        btnBack.setTextColor(C_TEXT);
        btnBack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        btnBack.setBackgroundColor(Color.TRANSPARENT);
        btnBack.setPadding(dp(12), dp(8), dp(12), dp(8));
        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(btnBack);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("徽章助手");
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(dp(8), 0, 0, 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvTitle.setLayoutParams(titleLp);
        bar.addView(tvTitle);

        // 悬浮窗按钮
        btnFloating = new Button(this);
        btnFloating.setText("悬浮窗");
        btnFloating.setTextColor(C_PRIMARY);
        btnFloating.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnFloating.setBackgroundDrawable(roundRectStroke(C_BTN_SECOND, 8, C_BORDER, 1));
        btnFloating.setPadding(dp(14), dp(8), dp(14), dp(8));
        btnFloating.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleFloatingWindow();
            }
        });
        bar.addView(btnFloating);

        // 用纵向布局包裹顶部栏 + 底部分割线
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(bar);
        wrap.addView(makeDivider());
        return wrap;
    }

    // ================================================================
    //  UI构建 - Tab栏
    // ================================================================

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(C_WHITE);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        // 我的徽章
        bar.addView(buildTabItem("我的徽章", true, new View.OnClickListener() {
            public void onClick(View v) {
                switchTab(TAB_MY);
            }
        }));

        // 徽章图鉴
        bar.addView(buildTabItem("徽章图鉴", false, new View.OnClickListener() {
            public void onClick(View v) {
                switchTab(TAB_WIKI);
            }
        }));

        // 底部细分割线
        View line = new View(this);
        line.setBackgroundColor(C_DIVIDER);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        line.setLayoutParams(lineLp);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(bar);
        wrap.addView(line);
        return wrap;
    }

    private View buildTabItem(String text, boolean isFirst, View.OnClickListener listener) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER_HORIZONTAL);
        tab.setPadding(0, dp(12), 0, 0);
        tab.setOnClickListener(listener);

        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tabLp.setMargins(isFirst ? 0 : dp(4), 0, isFirst ? dp(4) : 0, 0);
        tab.setLayoutParams(tabLp);

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, 0, 0, dp(10));
        tab.addView(label);

        View indicator = new View(this);
        LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        indicator.setLayoutParams(indLp);
        tab.addView(indicator);

        if (isFirst) {
            tvTabMy = label;
            indicatorMy = indicator;
        } else {
            tvTabWiki = label;
            indicatorWiki = indicator;
        }
        return tab;
    }

    private void switchTab(int tab) {
        if (currentTab == tab) return;
        currentTab = tab;
        applyTabState();
    }

    private void applyTabState() {
        boolean my = (currentTab == TAB_MY);
        if (tvTabMy != null) {
            tvTabMy.setTextColor(my ? C_PRIMARY : C_TEXT_HINT);
            tvTabMy.setTypeface(my ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            indicatorMy.setBackgroundColor(my ? C_PRIMARY : Color.TRANSPARENT);
        }
        if (tvTabWiki != null) {
            tvTabWiki.setTextColor(!my ? C_PRIMARY : C_TEXT_HINT);
            tvTabWiki.setTypeface(!my ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            indicatorWiki.setBackgroundColor(!my ? C_PRIMARY : Color.TRANSPARENT);
        }
        controlPanel.setVisibility(my ? View.VISIBLE : View.GONE);
        scrollMyBadges.setVisibility(my ? View.VISIBLE : View.GONE);
        scrollWiki.setVisibility(my ? View.GONE : View.VISIBLE);
        updateCountText();
    }

    private void updateCountText() {
        if (tvCount == null) return;
        if (currentTab == TAB_MY) {
            int n = BadgeStore.count(this);
            tvCount.setText("\u5df2\u5b58\u50a8 " + n + " \u4e2a\u5fbd\u7ae0");
        } else {
            tvCount.setText("\u5171 " + BadgeData.BADGES.length + " \u6b3e\u5fbd\u7ae0");
        }
    }

    // ================================================================
    //  UI构建 - 控制面板 (渠道选择 + 操作按钮 + 提示)
    // ================================================================

    private View buildControlPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(C_BG);

        // ---- 渠道选择 (水平滚动) ----
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setBackgroundColor(C_WHITE);
        hsv.setPadding(dp(12), dp(8), dp(12), dp(8));
        channelRow = new LinearLayout(this);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        buildChannelButtons();
        hsv.addView(channelRow);
        panel.addView(hsv);

        // 渠道与操作按钮之间的分割线
        panel.addView(makeDivider());

        // ---- 操作按钮行 ----
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setBackgroundColor(C_WHITE);
        actionRow.setPadding(dp(12), dp(10), dp(12), dp(10));

        // 读取NFC徽章
        Button btnNfc = new Button(this);
        btnNfc.setText("\u8bfb\u53d6NFC\u5fbd\u7ae0");
        applyPrimaryStyle(btnNfc);
        btnNfc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showNfcReadDialog();
            }
        });
        LinearLayout.LayoutParams lpNfc = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnNfc.setLayoutParams(lpNfc);
        actionRow.addView(btnNfc);

        panel.addView(actionRow);

        // ---- 提示文字 ----
        TextView tvHint = new TextView(this);
        tvHint.setText("\u5c06STAR\u5fbd\u7ae0\u8d34\u8fd1\u624b\u673aNFC\u611f\u5e94\u533a\u5373\u53ef\u81ea\u52a8\u8bfb\u53d6");
        tvHint.setTextColor(C_TEXT_HINT);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvHint.setPadding(dp(16), dp(10), dp(16), dp(12));
        panel.addView(tvHint);

        return panel;
    }

    private void buildChannelButtons() {
        channelRow.removeAllViews();
        for (int i = 0; i < BadgeData.GAME_CHANNELS.length; i++) {
            final String channelName = BadgeData.GAME_CHANNELS[i][0];
            Button btn = new Button(this);
            btn.setText(channelName);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setPadding(dp(14), dp(6), dp(14), dp(6));
            boolean selected = channelName.equals(selectedChannel);
            if (selected) {
                applyPrimaryStyle(btn);
            } else {
                applySecondaryStyle(btn);
            }
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectChannel(channelName);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            btn.setLayoutParams(lp);
            channelRow.addView(btn);
        }
    }

    private void selectChannel(String channelName) {
        selectedChannel = channelName;
        // 刷新渠道按钮高亮
        for (int i = 0; i < channelRow.getChildCount(); i++) {
            View child = channelRow.getChildAt(i);
            if (child instanceof Button) {
                Button b = (Button) child;
                if (b.getText().toString().equals(channelName)) {
                    applyPrimaryStyle(b);
                } else {
                    applySecondaryStyle(b);
                }
            }
        }
        Toast.makeText(this, "\u6e20\u9053: " + channelName, Toast.LENGTH_SHORT).show();
    }

    // ================================================================
    //  我的徽章列表
    // ================================================================

    private void refreshMyBadges() {
        if (layoutMyBadges == null) return;
        layoutMyBadges.removeAllViews();

        List<BadgeStore.StoredBadge> badges = BadgeStore.getAll(this);
        updateCountText();

        if (badges.isEmpty()) {
            // 空状态
            TextView empty = new TextView(this);
            empty.setText("\u8fd8\u6ca1\u6709\u4fdd\u5b58\u7684\u5fbd\u7ae0\n\u70b9\u51fb\u4e0a\u65b9\u6309\u94ae\u6dfb\u52a0");
            empty.setTextColor(C_TEXT_HINT);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(32), dp(72), dp(32), dp(72));
            layoutMyBadges.addView(empty);
            return;
        }

        for (int i = 0; i < badges.size(); i++) {
            layoutMyBadges.addView(buildStoredBadgeItem(badges.get(i)));
        }
    }

    private View buildStoredBadgeItem(final BadgeStore.StoredBadge badge) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundDrawable(cardDrawable());
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardLp);

        // 长按删除
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showDeleteConfirmDialog(badge);
                return true;
            }
        });

        // ---- 左侧文字区 ----
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMargins(0, 0, dp(12), 0);
        textCol.setLayoutParams(textLp);

        // 徽章名称 (粗体)
        TextView tvTitle = new TextView(this);
        tvTitle.setText(badge.title != null && !badge.title.isEmpty()
                ? badge.title : "(\u672a\u547d\u540d)");
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(tvTitle);

        // SK码 (等宽字体, 蓝紫色)
        if (badge.skCode != null && !badge.skCode.isEmpty()) {
            TextView tvSk = new TextView(this);
            tvSk.setText(badge.skCode);
            tvSk.setTextColor(C_PRIMARY);
            tvSk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvSk.setTypeface(Typeface.MONOSPACE);
            tvSk.setPadding(0, dp(3), 0, 0);
            tvSk.setSingleLine(true);
            textCol.addView(tvSk);
        }

        // 效果描述 / 备注 (灰色小字)
        String desc = null;
        if (badge.remark != null && !badge.remark.isEmpty()) {
            desc = badge.remark;
        } else if (badge.channel != null && !badge.channel.isEmpty()) {
            desc = "\u6e20\u9053: " + badge.channel;
        }
        if (desc != null) {
            TextView tvDesc = new TextView(this);
            tvDesc.setText(desc);
            tvDesc.setTextColor(C_TEXT_HINT);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvDesc.setPadding(0, dp(3), 0, 0);
            tvDesc.setSingleLine(true);
            tvDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(tvDesc);
        }

        // 时间
        if (badge.timestamp > 0) {
            TextView tvTime = new TextView(this);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            tvTime.setText(sdf.format(new Date(badge.timestamp)));
            tvTime.setTextColor(C_TEXT_HINT);
            tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvTime.setPadding(0, dp(3), 0, 0);
            textCol.addView(tvTime);
        }

        card.addView(textCol);

        // ---- 右侧"使用"按钮 ----
        Button btnUse = new Button(this);
        btnUse.setText("\u4f7f\u7528");
        applyPrimaryStyle(btnUse);
        btnUse.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnUse.setPadding(dp(20), dp(8), dp(20), dp(8));
        // 覆盖为自适应宽度 (applyPrimaryStyle默认全宽, 此处需内联小按钮)
        btnUse.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        btnUse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                useStoredBadge(badge);
            }
        });
        card.addView(btnUse);

        return card;
    }

    private void useStoredBadge(final BadgeStore.StoredBadge badge) {
        boolean ok = BadgeStore.useBadge(this, badge.link, selectedChannel);
        if (ok) {
            // 更新徽章记录的渠道
            badge.channel = selectedChannel;
            BadgeStore.update(this, badge);
            Toast.makeText(this,
                    "\u6b63\u5728\u542f\u52a8\u6e38\u620f (" + selectedChannel + ")",
                    Toast.LENGTH_SHORT).show();
            refreshMyBadges();
        }
    }

    // ================================================================
    //  徽章图鉴列表
    // ================================================================

    private void buildWikiList() {
        if (layoutWiki == null) return;
        layoutWiki.removeAllViews();

        // 按分类分组显示
        String[] categories = {"\u5f81\u7ae0", "\u94a5\u5319\u6263", "\u6bdb\u7ed2\u73a9\u5177", "\u5176\u4ed6"};
        for (int c = 0; c < categories.length; c++) {
            String category = categories[c];

            // 分类标题
            boolean hasItems = false;
            for (int i = 0; i < BadgeData.BADGES.length; i++) {
                if (BadgeData.BADGES[i].category.equals(category)) {
                    hasItems = true;
                    break;
                }
            }
            if (!hasItems) continue;

            TextView tvCat = new TextView(this);
            tvCat.setText(category);
            tvCat.setTextColor(C_TEXT);
            tvCat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvCat.setTypeface(Typeface.DEFAULT_BOLD);
            tvCat.setPadding(dp(4), dp(8), dp(4), dp(6));
            layoutWiki.addView(tvCat);

            for (int i = 0; i < BadgeData.BADGES.length; i++) {
                BadgeData.Badge b = BadgeData.BADGES[i];
                if (!b.category.equals(category)) continue;
                layoutWiki.addView(buildWikiBadgeItem(b));
            }
        }
    }

    private View buildWikiBadgeItem(BadgeData.Badge badge) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundDrawable(cardDrawable());
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(cardLp);

        // ---- 左侧文字区 ----
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMargins(0, 0, dp(12), 0);
        textCol.setLayoutParams(textLp);

        // 名称 (粗体)
        TextView tvName = new TextView(this);
        tvName.setText(badge.name);
        tvName.setTextColor(C_TEXT);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(tvName);

        // ID (等宽, 蓝紫色)
        TextView tvId = new TextView(this);
        tvId.setText(badge.id);
        tvId.setTextColor(C_PRIMARY);
        tvId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvId.setTypeface(Typeface.MONOSPACE);
        tvId.setPadding(0, dp(2), 0, 0);
        textCol.addView(tvId);

        // 描述 (灰色小字)
        TextView tvDesc = new TextView(this);
        tvDesc.setText(badge.description);
        tvDesc.setTextColor(C_TEXT_HINT);
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvDesc.setPadding(0, dp(3), 0, 0);
        tvDesc.setSingleLine(true);
        tvDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(tvDesc);

        card.addView(textCol);

        // ---- 右侧分类标签 ----
        TextView tvTag = new TextView(this);
        tvTag.setText(badge.category);
        tvTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvTag.setPadding(dp(10), dp(4), dp(10), dp(4));
        tvTag.setBackgroundDrawable(categoryTagDrawable(badge.category));
        tvTag.setTextColor(categoryTagTextColor(badge.category));
        card.addView(tvTag);

        return card;
    }

    private GradientDrawable categoryTagDrawable(String category) {
        int bg;
        if ("\u5b63\u8282".equals(category)) {
            bg = CAT_SEASON_BG;
        } else if ("\u5148\u7956".equals(category)) {
            bg = CAT_ANCESTOR_BG;
        } else if ("\u6d3b\u52a8".equals(category)) {
            bg = CAT_EVENT_BG;
        } else {
            bg = CAT_LIMIT_BG;
        }
        return roundRect(bg, 8);
    }

    private int categoryTagTextColor(String category) {
        if ("\u5b63\u8282".equals(category)) {
            return CAT_SEASON_TX;
        } else if ("\u5148\u7956".equals(category)) {
            return CAT_ANCESTOR_TX;
        } else if ("\u6d3b\u52a8".equals(category)) {
            return CAT_EVENT_TX;
        } else {
            return CAT_LIMIT_TX;
        }
    }

    // ================================================================
    //  NFC处理
    // ================================================================

    private void updateNfcStatus() {
        // NFC状态通过读取按钮交互反馈, 这里仅做静默检查
    }

    private void handleNfcIntent(Intent intent) {
        if (intent == null) return;
        String data = NfcHelper.parseNdefFromIntent(intent);
        if (data == null) return;

        // 关闭NFC等待对话框
        if (nfcWaitDialog != null && nfcWaitDialog.isShowing()) {
            nfcWaitDialog.dismiss();
        }

        Log.d(TAG, "NFC\u8bfb\u53d6\u5230\u6570\u636e: " + data);
        Toast.makeText(this, "NFC\u8bfb\u53d6\u6210\u529f", Toast.LENGTH_SHORT).show();

        // 显示NFC读取结果对话框
        showResultDialog(data, "NFC\u8bfb\u53d6\u7ed3\u679c");
    }

    // ================================================================
    //  对话框: NFC读取 (等待扫描)
    // ================================================================

    private void showNfcReadDialog() {
        if (!nfcHelper.isNfcSupported()) {
            Toast.makeText(this, "\u8bbe\u5907\u4e0d\u652f\u6301NFC", Toast.LENGTH_LONG).show();
            return;
        }
        if (!nfcHelper.isNfcEnabled()) {
            Toast.makeText(this, "\u8bf7\u5148\u5728\u8bbe\u7f6e\u4e2d\u5f00\u542fNFC", Toast.LENGTH_LONG).show();
            // 跳转NFC设置
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_NFC_SETTINGS));
            } catch (Exception e) {
                Log.e(TAG, "\u65e0\u6cd5\u6253\u5f00NFC\u8bbe\u7f6e", e);
            }
            return;
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        setupDialogWindow(dialog);

        LinearLayout layout = newDialogCard();

        TextView tvTitle = createDialogTitle("NFC\u8bfb\u53d6");
        layout.addView(tvTitle);

        TextView tvHint = new TextView(this);
        tvHint.setText("\u8bf7\u5c06NFC\u6807\u7b7e\u9760\u8fd1\u624b\u673a\u80cc\u9762...\n\n\u7b49\u5f85\u8bfb\u53d6\u4e2d...");
        tvHint.setTextColor(C_TEXT_2);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvHint.setPadding(0, dp(16), 0, dp(24));
        tvHint.setGravity(Gravity.CENTER);
        layout.addView(tvHint);

        Button btnCancel = new Button(this);
        btnCancel.setText("\u53d6\u6d88");
        applySecondaryStyle(btnCancel);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        layout.addView(btnCancel);

        dialog.setContentView(layout);
        nfcWaitDialog = dialog;
        dialog.show();
    }

    // ================================================================
    //  对话框: 徽章识别结果 (NFC / 手动输入共用)
    // ================================================================

    private void showResultDialog(final String data, String title) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        setupDialogWindow(dialog);

        LinearLayout layout = newDialogCard();

        TextView tvTitle = createDialogTitle(title);
        layout.addView(tvTitle);

        // 显示原始数据
        TextView tvData = new TextView(this);
        tvData.setText("\u539f\u59cb\u6570\u636e:\n" + data);
        tvData.setTextColor(C_TEXT_2);
        tvData.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvData.setTypeface(Typeface.MONOSPACE);
        tvData.setBackgroundDrawable(roundRect(C_SEARCH_BG, 8));
        tvData.setPadding(dp(12), dp(10), dp(12), dp(10));
        layout.addView(tvData);

        // 提取SK码
        String skCode = SkExtractor.getSkFromLink(data);
        final String finalSkCode = skCode;

        TextView tvSk = new TextView(this);
        if (skCode != null) {
            tvSk.setText("SK\u7801: " + skCode);
            tvSk.setTextColor(C_GREEN);
        } else {
            tvSk.setText("\u672a\u80fd\u63d0\u53d6SK\u7801\n(\u53ef\u624b\u52a8\u8f93\u5165\u4fdd\u5b58)");
            tvSk.setTextColor(C_RED);
        }
        tvSk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvSk.setTypeface(Typeface.DEFAULT_BOLD);
        tvSk.setPadding(0, dp(14), 0, dp(8));
        layout.addView(tvSk);

        // 检测预设徽章
        BadgeData.PresetBadge preset = BadgeData.detectByUrl(data);
        if (preset != null) {
            TextView tvPreset = new TextView(this);
            tvPreset.setText("\u5339\u914d\u9884\u8bbe: " + preset.name + "\n" + preset.description);
            tvPreset.setTextColor(C_PRIMARY);
            tvPreset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvPreset.setBackgroundDrawable(roundRect(C_PRIMARY_TINT, 8));
            tvPreset.setPadding(dp(12), dp(10), dp(12), dp(10));
            layout.addView(tvPreset);
        }

        // 保存按钮 (主色全宽)
        Button btnSave = new Button(this);
        btnSave.setText("\u4fdd\u5b58\u5230\u6211\u7684\u5fbd\u7ae0");
        applyPrimaryStyle(btnSave);
        final String defaultTitle = preset != null ? preset.name : (skCode != null ? skCode : "NFC\u5fbd\u7ae0");
        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
                showSaveBadgeDialog(data, finalSkCode, defaultTitle);
            }
        });
        layout.addView(withMarginTop(btnSave, dp(16)));

        // 直接使用按钮 (次色全宽)
        Button btnUse = new Button(this);
        btnUse.setText("\u76f4\u63a5\u4f7f\u7528");
        applySecondaryStyle(btnUse);
        btnUse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
                useBadgeLink(data, finalSkCode);
            }
        });
        layout.addView(withMarginTop(btnUse, dp(10)));

        // 关闭按钮 (灰色全宽)
        Button btnClose = new Button(this);
        btnClose.setText("\u5173\u95ed");
        applySecondaryStyle(btnClose);
        btnClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        layout.addView(withMarginTop(btnClose, dp(10)));

        dialog.setContentView(layout);
        dialog.show();
    }

    private void useBadgeLink(String link, String skCode) {
        boolean ok = BadgeStore.useBadge(this, link, selectedChannel);
        if (ok) {
            Toast.makeText(this,
                    "\u6b63\u5728\u542f\u52a8\u6e38\u620f (" + selectedChannel + ")",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================
    //  对话框: 保存徽章 (标题、备注、渠道)
    // ================================================================

    private void showSaveBadgeDialog(final String link, final String skCode, String defaultTitle) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setupDialogWindow(dialog);

        LinearLayout layout = newDialogCard();

        TextView tvTitle = createDialogTitle("\u4fdd\u5b58\u5fbd\u7ae0");
        layout.addView(tvTitle);

        // 显示SK码
        if (skCode != null) {
            TextView tvSk = new TextView(this);
            tvSk.setText("SK\u7801: " + skCode);
            tvSk.setTextColor(C_PRIMARY);
            tvSk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvSk.setTypeface(Typeface.MONOSPACE);
            tvSk.setBackgroundDrawable(roundRect(C_PRIMARY_TINT, 8));
            tvSk.setPadding(dp(12), dp(8), dp(12), dp(8));
            layout.addView(tvSk);
        }

        // 标题
        TextView tvTitleLabel = createInputLabel("\u6807\u9898:");
        layout.addView(withMarginTop(tvTitleLabel, dp(8)));

        final EditText etTitle = new EditText(this);
        etTitle.setText(defaultTitle);
        etTitle.setSingleLine(true);
        etTitle.setTextColor(C_TEXT);
        etTitle.setHintTextColor(C_TEXT_HINT);
        etTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        etTitle.setBackgroundDrawable(roundRectStroke(C_SEARCH_BG, 8, C_BORDER, 1));
        etTitle.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(etTitle);

        // 备注
        TextView tvRemarkLabel = createInputLabel("\u5907\u6ce8 (\u53ef\u9009):");
        layout.addView(withMarginTop(tvRemarkLabel, dp(12)));

        final EditText etRemark = new EditText(this);
        etRemark.setHint("\u5907\u6ce8\u4fe1\u606f");
        etRemark.setSingleLine(true);
        etRemark.setTextColor(C_TEXT);
        etRemark.setHintTextColor(C_TEXT_HINT);
        etRemark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        etRemark.setBackgroundDrawable(roundRectStroke(C_SEARCH_BG, 8, C_BORDER, 1));
        etRemark.setPadding(dp(12), dp(12), dp(12), dp(12));
        layout.addView(etRemark);

        // 渠道
        TextView tvChannelLabel = createInputLabel("\u6e38\u620f\u6e20\u9053:");
        layout.addView(withMarginTop(tvChannelLabel, dp(12)));

        final EditText etChannel = new EditText(this);
        etChannel.setText(selectedChannel);
        etChannel.setSingleLine(true);
        etChannel.setFocusable(false);
        etChannel.setTextColor(C_TEXT);
        etChannel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        etChannel.setBackgroundDrawable(roundRectStroke(C_SEARCH_BG, 8, C_BORDER, 1));
        etChannel.setPadding(dp(12), dp(12), dp(12), dp(12));
        etChannel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showChannelPickerDialog(etChannel);
            }
        });
        layout.addView(etChannel);

        // 保存按钮 (主色 #5B6EF5 全宽)
        Button btnSave = new Button(this);
        btnSave.setText("\u4fdd\u5b58");
        applyPrimaryStyle(btnSave);
        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String title = etTitle.getText().toString().trim();
                if (title.isEmpty()) {
                    title = skCode != null ? skCode : "\u5fbd\u7ae0";
                }
                String remark = etRemark.getText().toString().trim();
                String channel = etChannel.getText().toString().trim();

                BadgeStore.add(BadgeHelperActivity.this, title, link, skCode, remark, channel);
                Toast.makeText(BadgeHelperActivity.this, "\u4fdd\u5b58\u6210\u529f", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                refreshMyBadges();
                BadgeFloatingService.refresh();
            }
        });
        layout.addView(withMarginTop(btnSave, dp(18)));

        // 取消按钮 (次色全宽)
        Button btnCancel = new Button(this);
        btnCancel.setText("\u53d6\u6d88");
        applySecondaryStyle(btnCancel);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        layout.addView(withMarginTop(btnCancel, dp(10)));

        dialog.setContentView(layout);
        dialog.show();
    }

    // ================================================================
    //  对话框: 渠道选择器 (用于保存对话框的EditText)
    // ================================================================

    private void showChannelPickerDialog(final EditText etChannel) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setupDialogWindow(dialog);

        LinearLayout layout = newDialogCard();

        TextView tvTitle = createDialogTitle("\u9009\u62e9\u6e20\u9053");
        layout.addView(tvTitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        channelList.setPadding(0, dp(8), 0, 0);

        for (int i = 0; i < BadgeData.GAME_CHANNELS.length; i++) {
            final String channelName = BadgeData.GAME_CHANNELS[i][0];

            Button btnChannel = new Button(this);
            btnChannel.setText(channelName);
            // 高亮当前选中渠道
            if (channelName.equals(selectedChannel)
                    || channelName.equals(etChannel.getText().toString())) {
                applyPrimaryStyle(btnChannel);
            } else {
                applySecondaryStyle(btnChannel);
            }
            btnChannel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    etChannel.setText(channelName);
                    selectedChannel = channelName;
                    buildChannelButtons();
                    dialog.dismiss();
                }
            });
            channelList.addView(withMarginBottom(btnChannel, dp(8)));
        }

        scroll.addView(channelList);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.setMargins(0, dp(8), 0, 0);
        scroll.setLayoutParams(scrollLp);
        layout.addView(scroll);

        // 取消按钮 (次色全宽)
        Button btnCancel = new Button(this);
        btnCancel.setText("\u53d6\u6d88");
        applySecondaryStyle(btnCancel);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        layout.addView(withMarginTop(btnCancel, dp(12)));

        dialog.setContentView(layout);

        // 设置对话框高度
        WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.65);
        dialog.getWindow().setAttributes(lp);

        dialog.show();
    }

    // ================================================================
    //  对话框: 删除确认
    // ================================================================

    private void showDeleteConfirmDialog(final BadgeStore.StoredBadge badge) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setupDialogWindow(dialog);

        LinearLayout layout = newDialogCard();

        TextView tvTitle = createDialogTitle("\u786e\u8ba4\u5220\u9664");
        layout.addView(tvTitle);

        TextView tvMsg = new TextView(this);
        tvMsg.setText("\u786e\u5b9a\u8981\u5220\u9664\u5fbd\u7ae0 \""
                + (badge.title != null ? badge.title : "") + "\" \u5417?");
        tvMsg.setTextColor(C_TEXT_2);
        tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvMsg.setPadding(0, dp(12), 0, dp(20));
        layout.addView(tvMsg);

        // 确认删除按钮 (红色 #FF5C7A 全宽)
        Button btnDelete = new Button(this);
        btnDelete.setText("\u786e\u8ba4\u5220\u9664");
        applyDangerStyle(btnDelete);
        btnDelete.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                BadgeStore.delete(BadgeHelperActivity.this, badge.id);
                Toast.makeText(BadgeHelperActivity.this, "\u5df2\u5220\u9664", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                refreshMyBadges();
                BadgeFloatingService.refresh();
            }
        });
        layout.addView(btnDelete);

        // 取消按钮 (次色全宽)
        Button btnCancel = new Button(this);
        btnCancel.setText("\u53d6\u6d88");
        applySecondaryStyle(btnCancel);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        layout.addView(withMarginTop(btnCancel, dp(10)));

        dialog.setContentView(layout);
        dialog.show();
    }

    // ================================================================
    //  UI辅助方法 - 样式
    // ================================================================

    /** 主色按钮样式: #5B6EF5 背景, 白字, 圆角 */
    private void applyPrimaryStyle(Button b) {
        b.setTextColor(C_WHITE);
        b.setBackgroundDrawable(roundRect(C_PRIMARY, 10));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** 次按钮样式: #F3F5F9 背景, 灰字, 圆角, 边框 */
    private void applySecondaryStyle(Button b) {
        b.setTextColor(C_TEXT_2);
        b.setBackgroundDrawable(roundRectStroke(C_BTN_SECOND, 10, C_BORDER, 1));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** 危险按钮样式: #FF5C7A 背景, 白字, 圆角 */
    private void applyDangerStyle(Button b) {
        b.setTextColor(C_WHITE);
        b.setBackgroundDrawable(roundRect(C_RED, 10));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** 卡片背景: 白色, 14dp圆角, 浅边框 */
    private GradientDrawable cardDrawable() {
        return roundRectStroke(C_CARD, 14, C_BORDER, 1);
    }

    /** 纯色圆角 drawable */
    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** 圆角带边框 drawable */
    private GradientDrawable roundRectStroke(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = roundRect(color, radiusDp);
        d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    /** 对话框标题 */
    private TextView createDialogTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(12));
        return tv;
    }

    /** 输入框标签 */
    private TextView createInputLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_TEXT_2);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, dp(2), 0, dp(6));
        return tv;
    }

    /** 分割线 */
    private View makeDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(C_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divider.setLayoutParams(lp);
        return divider;
    }

    /** 给View添加顶部外边距 (返回同一View, 便于链式添加) */
    private View withMarginTop(View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topDp, 0, 0);
        v.setLayoutParams(lp);
        return v;
    }

    /** 给View添加底部外边距 */
    private View withMarginBottom(View v, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, bottomDp);
        v.setLayoutParams(lp);
        return v;
    }

    // ================================================================
    //  UI辅助方法 - 对话框容器
    // ================================================================

    /** 创建对话框内容卡片容器 (白色圆角, 内边距) */
    private LinearLayout newDialogCard() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundDrawable(cardDrawable());
        layout.setPadding(dp(22), dp(22), dp(22), dp(22));
        return layout;
    }

    /** 设置对话框窗口透明背景 (让卡片圆角生效) */
    private void setupDialogWindow(Dialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** dp转px */
    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
