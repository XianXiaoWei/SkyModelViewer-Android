package com.sky.modelviewer.piano;

import com.sky.modelviewer.R;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 自动弹琴主界面
 * - 首次运行从 assets/sheets/dayu.txt 复制默认乐谱
 * - 检查无障碍服务状态、悬浮窗权限
 * - 导入乐谱文件
 * - 启动 PianoService 悬浮窗
 *
 * AIDE 兼容:
 *   - Activity.RESULT_OK 全限定
 *   - View.OnClickListener 全限定
 *   - 无 lambda、无 try-with-resources，泛型用完整类型
 */
public class PianoActivity extends Activity {

    private static final int REQUEST_IMPORT = 1001;
    private static final String PREF_NAME = "piano_prefs";
    private static final String PREF_FIRST_RUN = "first_run_done";

    private Button btnBack;
    private TextView tvStatus;
    private Button btnOpenA11y;
    private TextView tvSheetInfo;
    private Button btnImport;
    private Button btnStart;

    private File sheetsDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_piano);

        btnBack = (Button) findViewById(R.id.btnBack);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        btnOpenA11y = (Button) findViewById(R.id.btnOpenA11y);
        tvSheetInfo = (TextView) findViewById(R.id.tvSheetInfo);
        btnImport = (Button) findViewById(R.id.btnImport);
        btnStart = (Button) findViewById(R.id.btnStart);

        // 准备乐谱目录
        File ext = getExternalFilesDir(null);
        if (ext == null) ext = getFilesDir();
        sheetsDir = new File(ext, "sheets");
        if (!sheetsDir.exists()) {
            sheetsDir.mkdirs();
        }

        // 首次运行从 assets 复制默认乐谱
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_FIRST_RUN, false)) {
            copyDefaultSheet();
            prefs.edit().putBoolean(PREF_FIRST_RUN, true).apply();
        }

        // 返回按钮
        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        // 去开启无障碍/悬浮窗权限
        btnOpenA11y.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(PianoActivity.this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                }
            }
        });

        // 导入乐谱
        btnImport.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openFileChooser();
            }
        });

        // 启动悬浮窗
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startFloatingPanel();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updateSheetInfo();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                importSheet(data.getData());
            }
        }
    }

    // ============ 文件选择 ============

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String[] mimeTypes = new String[]{"text/plain", "application/json"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择乐谱文件"), REQUEST_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void importSheet(Uri uri) {
        InputStream is = null;
        OutputStream os = null;
        String fileName = "imported_" + System.currentTimeMillis() + ".txt";
        String queryName = queryDisplayName(uri);
        if (queryName != null && queryName.length() > 0) {
            fileName = queryName;
        }
        try {
            is = getContentResolver().openInputStream(uri);
            if (is == null) {
                Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show();
                return;
            }
            File target = new File(sheetsDir, fileName);
            os = new FileOutputStream(target);
            byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) > 0) {
                os.write(buffer, 0, n);
            }
            os.flush();
            Toast.makeText(this, "导入成功: " + fileName, Toast.LENGTH_SHORT).show();
            updateSheetInfo();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception e) { /* 忽略 */ }
            }
            if (os != null) {
                try { os.close(); } catch (Exception e) { /* 忽略 */ }
            }
        }
    }

    private String queryDisplayName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        result = cursor.getString(idx);
                    }
                }
            } catch (Exception e) {
                /* 忽略 */
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        } else if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0) {
                    result = path.substring(slash + 1);
                }
            }
        }
        return result;
    }

    // ============ 默认乐谱复制 ============

    private void copyDefaultSheet() {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = getAssets().open("sheets/dayu.txt");
            File target = new File(sheetsDir, "dayu.txt");
            os = new FileOutputStream(target);
            byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) > 0) {
                os.write(buffer, 0, n);
            }
            os.flush();
        } catch (Exception e) {
            Toast.makeText(this, "复制默认乐谱失败: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception e) { /* 忽略 */ }
            }
            if (os != null) {
                try { os.close(); } catch (Exception e) { /* 忽略 */ }
            }
        }
    }

    // ============ 状态更新 ============

    private void updateStatus() {
        boolean a11y = isAccessibilityEnabled();
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        if (a11y && overlay) {
            tvStatus.setText("无障碍服务: 已启用    悬浮窗: 已授权");
            tvStatus.setTextColor(0xFF00B596);
            btnOpenA11y.setVisibility(View.GONE);
        } else {
            String msg = "状态: ";
            if (!a11y) msg += "无障碍未启用 ";
            if (!overlay) msg += "悬浮窗未授权";
            tvStatus.setText(msg.trim());
            tvStatus.setTextColor(0xFFFF5C7A);
            btnOpenA11y.setVisibility(View.VISIBLE);
        }
    }

    private void updateSheetInfo() {
        List<SheetParser.ParsedSheet> list = SheetParser.scanDirectory(sheetsDir);
        int count = list.size();
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(count).append(" 首乐谱");
        if (count > 0) {
            sb.append("\n");
            for (int i = 0; i < list.size(); i++) {
                SheetParser.ParsedSheet s = list.get(i);
                String name = (s.name == null || s.name.length() == 0) ? s.fileName : s.name;
                sb.append("  ").append(i + 1).append(". ").append(name)
                        .append(" (").append(s.keyCount).append("键)\n");
            }
        }
        tvSheetInfo.setText(sb.toString());
    }

    // ============ 启动悬浮窗 ============

    private void startFloatingPanel() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        List<SheetParser.ParsedSheet> list = SheetParser.scanDirectory(sheetsDir);
        if (list.isEmpty()) {
            Toast.makeText(this, "没有可用乐谱，请先导入", Toast.LENGTH_LONG).show();
            return;
        }
        if (!PianoService.isReady()) {
            Toast.makeText(this, "无障碍服务未就绪，请重启服务",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        // 显示面板
        PianoService.showPanel();
        Toast.makeText(this, "悬浮窗已显示", Toast.LENGTH_SHORT).show();
        // 跳转到桌面，方便用户操作光遇游戏
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }

    // ============ 无障碍服务检测 ============

    private boolean isAccessibilityEnabled() {
        String serviceName = PianoService.class.getName();
        ComponentName expected = new ComponentName(this, serviceName);
        String flat = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null || flat.length() == 0) return false;
        String expectedFlat = expected.flattenToString();
        String altFlat = expected.getClassName() + "/" + expected.getPackageName();
        return flat.contains(expectedFlat) || flat.contains(altFlat);
    }
}
