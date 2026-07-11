package com.sky.modelviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.sky.modelviewer.export.GlbExporter;
import com.sky.modelviewer.model.MeshCatalogEntry;
import com.sky.modelviewer.model.MeshData;
import com.sky.modelviewer.model.ScanResult;
import com.sky.modelviewer.model.MaterialInfo;
import com.sky.modelviewer.parsing.TgcMeshReader;
import com.sky.modelviewer.parsing.LevelFileParser;
import com.sky.modelviewer.render.KtxTextureLoader;
import com.sky.modelviewer.render.MeshSurfaceView;
import com.sky.modelviewer.render.MeshRenderer;
import com.sky.modelviewer.scanner.SkyAssetScanner;
import com.sky.modelviewer.scanner.SkyResourceResolver;
import com.sky.modelviewer.ui.MeshListAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "SkyModelViewer";
    private static final int REQUEST_OPEN_APK = 1001;
    private static final int REQUEST_CREATE_GLB = 1002;
    private static final int REQUEST_BATCH_DIR = 1003;
    private static final int REQUEST_STORAGE = 1004;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MeshListAdapter meshListAdapter;
    private TextView statusText;
    private TextView sourcePathText;
    private TextView meshCountText;
    private EditText searchBox;
    private ListView meshTree;
    private TextView selectionTitle;
    private TextView selectionMeta;
    private TextView detailsBox;
    private MeshSurfaceView meshViewport;
    private RadioGroup viewModeGroup;
    private Button btnExport;
    private Button btnBatchExport;
    private Button btnMoveUp;
    private Button btnMoveDown;
    private FrameLayout joystickArea;
    private View joystickThumb;

    // Joystick state
    private float joystickDX = 0f, joystickDY = 0f;
    private boolean joystickActive = false;
    private float joystickCenterX = 0f, joystickCenterY = 0f;
    private final float joystickMaxRadius = 50f;
    private java.util.concurrent.ScheduledExecutorService movementScheduler;
    private volatile boolean moveUpHeld = false;
    private volatile boolean moveDownHeld = false;

    private String currentApkPath = null;
    private MeshData currentMeshData = null;
    private float currentScale = 1f;
    private MeshCatalogEntry currentMeshEntry = null;
    private volatile int currentLoadId = 0; // Incremented each time a new item is clicked to cancel stale operations

    private void updateJoystick(float touchX, float touchY, View v) {
        float dx = touchX - joystickCenterX;
        float dy = touchY - joystickCenterY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist > joystickMaxRadius) {
            dx = dx / dist * joystickMaxRadius;
            dy = dy / dist * joystickMaxRadius;
        }
        joystickDX = dx / joystickMaxRadius; // -1..1
        joystickDY = dy / joystickMaxRadius; // -1..1
        joystickThumb.setTranslationX(dx);
        joystickThumb.setTranslationY(dy);
    }

    private void debugLog(String msg) {
        Log.i(TAG, msg);
        try {
            File dir = new File("/storage/emulated/0/Documents");
            if (!dir.exists()) dir.mkdirs();
            File logFile;
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            if (dir.exists() && dir.canWrite()) {
                logFile = new File(dir, "sky_debug.txt");
            } else {
                logFile = new File(getCacheDir(), "sky_debug.txt");
            }
            java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
            fw.append("[" + timestamp + "] " + msg + "\n");
            fw.close();
        } catch (Exception e) {
            // ignore
        }
    }

    private void writeCrashToFile(Throwable t) {
        try {
            File dir = new File("/storage/emulated/0/Documents");
            if (!dir.exists()) dir.mkdirs();
            File cacheDir = (dir.exists() && dir.canWrite()) ? dir : getCacheDir();
            if (!cacheDir.exists()) cacheDir.mkdirs();

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File logFile = new File(cacheDir, "sky_crash_" + timestamp + ".txt");

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("Sky Model Viewer - Crash Report");
            pw.println("Time: " + new Date());
            pw.println("Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            pw.println("Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            pw.println();
            pw.println("Stack Trace:");
            t.printStackTrace(pw);
            pw.flush();
            java.io.FileWriter fw = new java.io.FileWriter(logFile);
            fw.write(sw.toString());
            fw.close();
        } catch (Exception e) {
            try {
                File logFile = new File(getCacheDir(), "sky_crash.txt");
                java.io.FileWriter fw = new java.io.FileWriter(logFile);
                fw.write("Original crash: " + t.getMessage() + "\n\n");
                StringWriter sw2 = new StringWriter();
                t.printStackTrace(new PrintWriter(sw2));
                fw.write(sw2.toString());
                fw.write("\n\nLog write error: " + e.getMessage());
                fw.close();
            } catch (Exception e2) {
                // give up
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        debugLog("=== onCreate started ===");

        // Install crash handler FIRST
        try {
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    debugLog("UNCAUGHT EXCEPTION on " + thread.getName() + ": " + throwable.getMessage());
                    writeCrashToFile(throwable);
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            });
            debugLog("Crash handler installed");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install crash handler", t);
        }

        // Check dependency integrity
        try {
            debugLog("Checking lz4-java...");
            Class.forName("net.jpountz.lz4.LZ4Factory");
            debugLog("lz4-java OK");
        } catch (Throwable e) {
            debugLog("DEPENDENCY CHECK FAILED: " + e.getClass().getName() + ": " + e.getMessage());
            writeCrashToFile(e);
        }

        try {
            requestStoragePermission();
            debugLog("Storage permission requested");

            setContentView(R.layout.activity_main);
            debugLog("Layout inflated");

            statusText = (TextView) findViewById(R.id.statusText);
            sourcePathText = (TextView) findViewById(R.id.sourcePathText);
            meshCountText = (TextView) findViewById(R.id.meshCountText);
            searchBox = (EditText) findViewById(R.id.searchBox);
            meshTree = (ListView) findViewById(R.id.meshTree);
            selectionTitle = (TextView) findViewById(R.id.selectionTitle);
            selectionMeta = (TextView) findViewById(R.id.selectionMeta);
            detailsBox = (TextView) findViewById(R.id.detailsBox);
            meshViewport = (MeshSurfaceView) findViewById(R.id.meshViewport);
            viewModeGroup = (RadioGroup) findViewById(R.id.viewModeGroup);
            btnExport = (Button) findViewById(R.id.btnExport);
            btnBatchExport = (Button) findViewById(R.id.btnBatchExport);
            debugLog("Views bound");

            meshListAdapter = new MeshListAdapter();
            meshListAdapter.setOnMeshClickListener(new MeshListAdapter.OnMeshClickListener() {
                @Override
                public void onMeshClick(MeshCatalogEntry entry) {
                    loadMesh(entry);
                }
            });
            meshTree.setAdapter(meshListAdapter);
            detailsBox.setMovementMethod(new ScrollingMovementMethod());
            debugLog("Adapter set");

            findViewById(R.id.btnChooseApk).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQUEST_OPEN_APK);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "错误: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });

            findViewById(R.id.btnChooseInstalled).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showInstalledAppDialog();
                }
            });

            viewModeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    MeshRenderer.ViewMode mode;
                    if (checkedId == R.id.btnSolid) mode = MeshRenderer.ViewMode.SOLID;
                    else if (checkedId == R.id.btnWire) mode = MeshRenderer.ViewMode.WIRE;
                    else mode = MeshRenderer.ViewMode.TEXTURE;
                    meshViewport.getMeshRenderer().setViewMode(mode);
                    meshViewport.requestRender();
                }
            });

            btnExport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exportCurrentMesh();
                }
            });

            btnBatchExport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!meshListAdapter.getVisibleEntries().isEmpty()) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                            startActivityForResult(intent, REQUEST_BATCH_DIR);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "错误: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }
            });

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    meshListAdapter.filter(s != null ? s.toString() : "");
                }
            });

            debugLog("onCreate completed successfully");
            statusText.setText("就绪");

            // ===== Setup joystick and movement controls =====
            btnMoveUp = (Button) findViewById(R.id.btnMoveUp);
            btnMoveDown = (Button) findViewById(R.id.btnMoveDown);
            joystickArea = (FrameLayout) findViewById(R.id.joystickArea);
            joystickThumb = findViewById(R.id.joystickThumb);

            // Up button: hold to move up
            btnMoveUp.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            moveUpHeld = true;
                            v.setPressed(true);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            moveUpHeld = false;
                            v.setPressed(false);
                            return true;
                    }
                    return false;
                }
            });

            // Down button: hold to move down
            btnMoveDown.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            moveDownHeld = true;
                            v.setPressed(true);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            moveDownHeld = false;
                            v.setPressed(false);
                            return true;
                    }
                    return false;
                }
            });

            // Joystick: drag to move horizontally
            joystickArea.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            joystickActive = true;
                            joystickCenterX = v.getWidth() / 2f;
                            joystickCenterY = v.getHeight() / 2f;
                            updateJoystick(event.getX(), event.getY(), v);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (joystickActive) {
                                updateJoystick(event.getX(), event.getY(), v);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            joystickActive = false;
                            joystickDX = 0;
                            joystickDY = 0;
                            joystickThumb.setTranslationX(0);
                            joystickThumb.setTranslationY(0);
                            return true;
                    }
                    return false;
                }
            });

            // Start movement loop (30fps)
            movementScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            movementScheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    boolean needMove = joystickActive && (Math.abs(joystickDX) > 0.01f || Math.abs(joystickDY) > 0.01f);
                    boolean needVert = moveUpHeld || moveDownHeld;
                    if (!needMove && !needVert) return;

                    final float jx = joystickDX;
                    final float jy = joystickDY;
                    final boolean up = moveUpHeld;
                    final boolean down = moveDownHeld;
                    final boolean doMove = needMove;

                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            MeshRenderer r = meshViewport.getMeshRenderer();
                            if (doMove) {
                                float speed = (float) (r.getCameraDistance() * 0.05);
                                r.moveHorizontal(jx, -jy, speed);
                            }
                            if (up) {
                                float speed = (float) (r.getCameraDistance() * 0.05);
                                r.moveVertical(1f, speed);
                            }
                            if (down) {
                                float speed = (float) (r.getCameraDistance() * 0.05);
                                r.moveVertical(-1f, speed);
                            }
                            meshViewport.requestRender();
                        }
                    });
                }
            }, 0, 33, java.util.concurrent.TimeUnit.MILLISECONDS);

            String savedPath = AppSettings.loadSourcePath(this);
            if (savedPath != null && !savedPath.isEmpty() && new File(savedPath).exists()) {
                loadApkSource(savedPath);
            }
        } catch (Throwable t) {
            debugLog("FATAL: onCreate failed: " + t.getMessage());
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            debugLog("Stack: " + sw.toString());
            writeCrashToFile(t);
            Toast.makeText(this, "严重错误: " + t.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (movementScheduler != null) {
            movementScheduler.shutdownNow();
            movementScheduler = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_OPEN_APK) {
            final Uri uri = data.getData();
            if (uri == null) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final String path = copyUriToTempFile(uri);
                        if (path != null) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    loadApkSource(path);
                                }
                            });
                        }
                    } catch (Exception e) {
                        debugLog("copyUriToTempFile error: " + e.getMessage());
                    }
                }
            }).start();
        } else if (requestCode == REQUEST_CREATE_GLB) {
            Uri uri = data.getData();
            if (uri == null) return;
            startGlbExport(uri);
        } else if (requestCode == REQUEST_BATCH_DIR) {
            Uri uri = data.getData();
            if (uri == null) return;
            List<MeshCatalogEntry> meshes = meshListAdapter.getVisibleEntries();
            String apkPath = currentApkPath;
            if (apkPath == null) return;
            batchExportMeshes(meshes, apkPath, uri);
        }
    }

    private void requestStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                String perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
                if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{perm}, REQUEST_STORAGE);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void showInstalledAppDialog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<SkyAssetScanner.AppEntry> apps = SkyAssetScanner.findSkyApps(MainActivity.this);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (apps.isEmpty()) {
                                Toast.makeText(MainActivity.this, "未找到包含sky的已安装应用", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            String[] labels = new String[apps.size()];
                            for (int i = 0; i < apps.size(); i++) {
                                labels[i] = apps.get(i).label + " (" + apps.get(i).packageName + ")";
                            }
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择应用")
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        SkyAssetScanner.AppEntry app = apps.get(which);
                                        loadApkSource(app.apkPath);
                                    }
                                })
                                .show();
                        }
                    });
                } catch (final Exception e) {
                    debugLog("findSkyApps error: " + e.getMessage());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "错误: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void loadApkSource(final String apkPath) {
        statusText.setText("正在索引资源...");
        detailsBox.setText("正在索引资源...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    debugLog("Scanning APK: " + apkPath);
                    final ScanResult scanResult = SkyAssetScanner.scanApk(apkPath);
                    debugLog("Scan complete: " + scanResult.meshes.size() + " meshes");

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            currentApkPath = apkPath;
                            meshListAdapter.setEntries(scanResult.meshes);
                            sourcePathText.setText(new File(apkPath).getName());
                            statusText.setText("已索引 " + scanResult.meshes.size() + " 个模型");
                            meshCountText.setText(scanResult.meshes.size() + " 个模型");
                            detailsBox.setText("索引完成\nAPK: " + new File(apkPath).getName() + "\n模型数: " + scanResult.meshes.size() + "\n");
                            btnBatchExport.setEnabled(!scanResult.meshes.isEmpty());
                            AppSettings.saveSource(MainActivity.this, apkPath, "apk");
                        }
                    });
                } catch (final Exception e) {
                    debugLog("Scan failed: " + e.getMessage());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("索引失败");
                            detailsBox.setText(e.toString());
                            Toast.makeText(MainActivity.this, "索引失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void loadMesh(final MeshCatalogEntry entry) {
        final String apkPath = currentApkPath;
        if (apkPath == null) return;
        final int loadId = ++currentLoadId;
        statusText.setText("正在加载 " + entry.name);
        selectionTitle.setText(entry.name);
        selectionMeta.setText(entry.relativePath);
        detailsBox.setText("正在加载 " + entry.name + "...");

        // Check if this is a level file
        if ("level".equals(entry.fileType)) {
            loadLevelFile(entry, apkPath, loadId);
            return;
        }

        // Check if this is a KTX texture
        if ("ktx".equals(entry.fileType)) {
            loadKtxTexture(entry, apkPath, loadId);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    debugLog("Loading mesh: " + entry.fullPath);
                    String meshEntryPath = entry.fullPath;

                    byte[] raw = SkyAssetScanner.readApkEntry(apkPath, meshEntryPath);
                    if (raw == null) throw new RuntimeException("Cannot read mesh entry: " + meshEntryPath);
                    debugLog("Mesh raw data read: " + raw.length + " bytes");

                    final MeshData meshData = TgcMeshReader.readMesh(raw, meshEntryPath);
                    debugLog("Mesh parsed: " + meshData.vertices.size() + " vertices, " + meshData.indices.size() + " faces");

                    final MaterialInfo material = SkyResourceResolver.resolveMaterial(apkPath, meshEntryPath);
                    Float scaleVal = SkyResourceResolver.resolveScale(apkPath, meshEntryPath);
                    final float scale = scaleVal != null ? scaleVal : 1f;

                    currentMeshData = meshData;
                    currentScale = scale;
                    currentMeshEntry = entry;

                    final byte[] textureBytes;
                    try {
                        String meshNameForTexture = SkyResourceResolver.stripMeshVariantSuffix(
                            nameWithoutExtension(new File(meshEntryPath).getName())
                        );
                        String texturePath = SkyResourceResolver.findTextureFile(
                            apkPath,
                            new String[]{material != null ? material.diffuseTex : null,
                                         material != null ? material.diffuse2Tex : null,
                                         meshNameForTexture}
                        );
                        textureBytes = texturePath != null ? SkyAssetScanner.readApkEntry(apkPath, texturePath) : null;
                    } catch (Exception e) {
                        debugLog("Texture resolution failed: " + e.getMessage());
                        textureBytes = null;
                    }

                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            if (loadId != currentLoadId) return; // Cancelled by newer click
                            try {
                                MeshRenderer renderer = meshViewport.getMeshRenderer();
                                renderer.setMesh(meshData, scale);

                                // Load texture with fallback
                                if (textureBytes != null) {
                                    int texId = 0;
                                    try {
                                        texId = KtxTextureLoader.loadTexture(textureBytes);
                                        if (texId == 0) {
                                            texId = KtxTextureLoader.loadStandardImage(textureBytes);
                                        }
                                    } catch (Exception texEx) {
                                        debugLog("Texture load error: " + texEx.getMessage());
                                    }
                                    renderer.setTexture(texId);
                                } else {
                                    renderer.setTexture(0);
                                }
                            } catch (Exception e) {
                                debugLog("GL render error: " + e.getMessage());
                            } finally {
                                // ALWAYS request render, even on error
                                meshViewport.requestRender();
                            }
                        }
                    });

                    final int skeletonCount = meshData.embeddedSkeleton != null ? meshData.embeddedSkeleton.size() : 0;
                    final String materialSummary = material == null ? "<none>"
                        : material.source + " :: " + material.name + " | shader=" + material.shader + " | diff=" + material.diffuseTex;

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("已加载 " + meshData.name);
                            selectionTitle.setText(meshData.name);
                            selectionMeta.setText(entry.relativePath);
                            detailsBox.setText(
                                "Name: " + meshData.name + "\n" +
                                "Path: " + meshData.sourcePath + "\n" +
                                "Category: " + entry.category + "\n" +
                                "Version: 0x" + Integer.toHexString(meshData.version) + "\n" +
                                "Animated: " + meshData.isAnimated + "\n" +
                                "Vertices: " + meshData.vertices.size() + "\n" +
                                "Faces: " + meshData.indices.size() + "\n" +
                                "UV0: " + meshData.uv0.size() + "\n" +
                                "Packed Vertex Attrs: " + meshData.packedVertexAttrs.size() + "\n" +
                                "Weighted Vertices: " + meshData.boneWeights.size() + "\n" +
                                "Embedded Skeleton Bones: " + skeletonCount + "\n" +
                                "Scale: " + scale + "\n" +
                                "Material: " + materialSummary + "\n"
                            );
                            btnExport.setEnabled(true);
                        }
                    });
                } catch (final Exception e) {
                    debugLog("Mesh load failed: " + e.getMessage());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("加载失败");
                            detailsBox.setText(e.toString());
                        }
                    });
                }
            }
        }).start();
    }

    private void loadLevelFile(final MeshCatalogEntry entry, final String apkPath, final int loadId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    debugLog("Loading level file: " + entry.fullPath);
                    final byte[] raw = SkyAssetScanner.readApkEntry(apkPath, entry.fullPath);
                    if (raw == null) throw new RuntimeException("Cannot read level file: " + entry.fullPath);
                    debugLog("Level file read: " + raw.length + " bytes");

                    // Parse TGCL format
                    final com.sky.modelviewer.parsing.LevelFileParser.LevelParseResult parseResult =
                        com.sky.modelviewer.parsing.LevelFileParser.parse(raw);
                    debugLog("Level parsed: " + parseResult.meshEntries.size() + " mesh entries, " +
                             parseResult.allNodeNames.size() + " total nodes");

                    // Find and load all referenced meshes
                    final List<String> loadedMeshes = new ArrayList<String>();
                    final List<String> failedMeshes = new ArrayList<String>();
                    final int[] meshStats = {0, 0}; // [loaded, failed]

                    // Build mesh path map once (much faster than per-mesh APK scanning)
                    java.util.HashMap<String, String> meshPathMap = new java.util.HashMap<String, String>();
                    try {
                        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath);
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> zipEntries = zf.entries();
                        while (zipEntries.hasMoreElements()) {
                            java.util.zip.ZipEntry ze = zipEntries.nextElement();
                            String name = ze.getName();
                            if (name.toLowerCase().endsWith(".mesh")) {
                                int lastSlash = name.lastIndexOf('/');
                                String meshNameOnly = (lastSlash >= 0 ? name.substring(lastSlash + 1) : name);
                                meshNameOnly = meshNameOnly.substring(0, meshNameOnly.length() - 5);
                                meshPathMap.put(meshNameOnly.toLowerCase(), name);
                            }
                        }
                        zf.close();
                    } catch (Exception e) {
                        debugLog("Failed to build mesh path map: " + e.getMessage());
                    }
                    debugLog("Built mesh path map: " + meshPathMap.size() + " entries");

                    // Clear previous mesh instances on GL thread
                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            if (loadId != currentLoadId) return;
                            meshViewport.getMeshRenderer().clearMeshInstances();
                            meshViewport.requestRender();
                        }
                    });

                    // Step 1: Load .meshes files FIRST (terrain geometry)
                    List<String> meshesFiles = SkyAssetScanner.findMeshesFilesInLevel(apkPath, entry.fullPath);
                    debugLog("Found " + meshesFiles.size() + " .meshes files in level dir: " + entry.fullPath);
                    
                    // Fallback: if not found, try searching entire APK for BstBaked.meshes
                    if (meshesFiles.isEmpty()) {
                        debugLog("BstBaked.meshes not found in level dir, searching entire APK...");
                        try {
                            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath);
                            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
                            while (entries.hasMoreElements()) {
                                java.util.zip.ZipEntry ze = entries.nextElement();
                                if (ze.getName().endsWith("BstBaked.meshes")) {
                                    meshesFiles.add(ze.getName());
                                    debugLog("  Found BstBaked.meshes at: " + ze.getName());
                                }
                            }
                            zf.close();
                        } catch (Exception e) {
                            debugLog("APK search error: " + e.getMessage());
                        }
                    }
                    
                    for (String mf : meshesFiles) {
                        debugLog("  .meshes file: " + mf);
                    }
                    
                    // Load all .meshes files and compute global bounding box
                    final List<com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData> terrainDataList =
                        new ArrayList<com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData>();
                    float gMinX = Float.MAX_VALUE, gMinY = Float.MAX_VALUE, gMinZ = Float.MAX_VALUE;
                    float gMaxX = -Float.MAX_VALUE, gMaxY = -Float.MAX_VALUE, gMaxZ = -Float.MAX_VALUE;
                    
                    for (final String meshesPath : meshesFiles) {
                        if (loadId != currentLoadId) {
                            debugLog("Level loading cancelled during terrain loading");
                            return;
                        }
                        try {
                            byte[] meshesRaw = SkyAssetScanner.readApkEntry(apkPath, meshesPath);
                            if (meshesRaw == null) {
                                debugLog("  readApkEntry returned null for: " + meshesPath);
                                continue;
                            }
                            debugLog("  Raw size: " + meshesRaw.length + " bytes, magic: " +
                                String.format("%02X %02X %02X %02X",
                                    meshesRaw[0] & 0xFF, meshesRaw[1] & 0xFF,
                                    meshesRaw[2] & 0xFF, meshesRaw[3] & 0xFF));
                            // Dump first 32 bytes of header for debugging
                            StringBuilder hexDump = new StringBuilder("  Header hex: ");
                            for (int hi = 0; hi < Math.min(32, meshesRaw.length); hi++) {
                                hexDump.append(String.format("%02X ", meshesRaw[hi] & 0xFF));
                            }
                            debugLog(hexDump.toString());
                            // Dump TOC entries
                            if (meshesRaw.length >= 12) {
                                int tocCount = (meshesRaw[8] & 0xFF) | ((meshesRaw[9] & 0xFF) << 8) |
                                    ((meshesRaw[10] & 0xFF) << 16) | ((meshesRaw[11] & 0xFF) << 24);
                                debugLog("  TOC count: " + tocCount);
                                for (int ti = 0; ti < tocCount && ti < 8; ti++) {
                                    int eo = 12 + ti * 12;
                                    if (eo + 12 <= meshesRaw.length) {
                                        String tType = "" + (char)(meshesRaw[eo] & 0xFF) + (char)(meshesRaw[eo+1] & 0xFF) +
                                            (char)(meshesRaw[eo+2] & 0xFF) + (char)(meshesRaw[eo+3] & 0xFF);
                                        int tOff = (meshesRaw[eo+4] & 0xFF) | ((meshesRaw[eo+5] & 0xFF) << 8) |
                                            ((meshesRaw[eo+6] & 0xFF) << 16) | ((meshesRaw[eo+7] & 0xFF) << 24);
                                        int tLen = (meshesRaw[eo+8] & 0xFF) | ((meshesRaw[eo+9] & 0xFF) << 8) |
                                            ((meshesRaw[eo+10] & 0xFF) << 16) | ((meshesRaw[eo+11] & 0xFF) << 24);
                                        debugLog("  TOC[" + ti + "]: type='" + tType + "' offset=" + tOff + " length=" + tLen);
                                    }
                                }
                            }
                            com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData md =
                                com.sky.modelviewer.parsing.LevelMeshesReader.parse(meshesRaw);
                            if (md == null) {
                                debugLog("  LevelMeshesReader.parse returned null for: " + meshesPath);
                                // Dump GEO0 segment data if we can find it
                                if (meshesRaw.length >= 12) {
                                    int tocCount2 = (meshesRaw[8] & 0xFF) | ((meshesRaw[9] & 0xFF) << 8) |
                                        ((meshesRaw[10] & 0xFF) << 16) | ((meshesRaw[11] & 0xFF) << 24);
                                    for (int ti = 0; ti < tocCount2 && ti < 8; ti++) {
                                        int eo = 12 + ti * 12;
                                        if (eo + 12 <= meshesRaw.length) {
                                            String tType = "" + (char)(meshesRaw[eo] & 0xFF) + (char)(meshesRaw[eo+1] & 0xFF) +
                                                (char)(meshesRaw[eo+2] & 0xFF) + (char)(meshesRaw[eo+3] & 0xFF);
                                            int tOff = (meshesRaw[eo+4] & 0xFF) | ((meshesRaw[eo+5] & 0xFF) << 8) |
                                                ((meshesRaw[eo+6] & 0xFF) << 16) | ((meshesRaw[eo+7] & 0xFF) << 24);
                                            int tLen = (meshesRaw[eo+8] & 0xFF) | ((meshesRaw[eo+9] & 0xFF) << 8) |
                                                ((meshesRaw[eo+10] & 0xFF) << 16) | ((meshesRaw[eo+11] & 0xFF) << 24);
                                            if (tType.equals("GEO0") && tOff < meshesRaw.length) {
                                                StringBuilder geoDump = new StringBuilder("  GEO0 first 40 bytes: ");
                                                for (int gi = 0; gi < Math.min(40, tLen) && tOff + gi < meshesRaw.length; gi++) {
                                                    geoDump.append(String.format("%02X ", meshesRaw[tOff + gi] & 0xFF));
                                                }
                                                debugLog(geoDump.toString());
                                                // Parse 5 u32 counts manually
                                                if (tOff + 20 <= meshesRaw.length) {
                                                    int ic = (meshesRaw[tOff] & 0xFF) | ((meshesRaw[tOff+1] & 0xFF) << 8) | ((meshesRaw[tOff+2] & 0xFF) << 16) | ((meshesRaw[tOff+3] & 0xFF) << 24);
                                                    int vc = (meshesRaw[tOff+4] & 0xFF) | ((meshesRaw[tOff+5] & 0xFF) << 8) | ((meshesRaw[tOff+6] & 0xFF) << 16) | ((meshesRaw[tOff+7] & 0xFF) << 24);
                                                    int cc = (meshesRaw[tOff+8] & 0xFF) | ((meshesRaw[tOff+9] & 0xFF) << 8) | ((meshesRaw[tOff+10] & 0xFF) << 16) | ((meshesRaw[tOff+11] & 0xFF) << 24);
                                                    int ccc = (meshesRaw[tOff+12] & 0xFF) | ((meshesRaw[tOff+13] & 0xFF) << 8) | ((meshesRaw[tOff+14] & 0xFF) << 16) | ((meshesRaw[tOff+15] & 0xFF) << 24);
                                                    int scc = (meshesRaw[tOff+16] & 0xFF) | ((meshesRaw[tOff+17] & 0xFF) << 8) | ((meshesRaw[tOff+18] & 0xFF) << 16) | ((meshesRaw[tOff+19] & 0xFF) << 24);
                                                    debugLog("  GEO0 counts: idxCount=" + ic + " vtxCount=" + vc + " chunkCount=" + cc + " cloudChunkCount=" + ccc + " subchunkCount=" + scc);
                                                }
                                            }
                                        }
                                    }
                                }
                                continue;
                            }
                            if (md.positions == null || md.positions.length == 0) {
                                debugLog("  No positions in: " + meshesPath);
                                continue;
                            }
                            if (md.indices == null || md.indices.length == 0) {
                                debugLog("  No indices in: " + meshesPath + " (vertexCount=" + md.vertexCount + ") — generating fallback");
                                // Generate fallback indices: simple triangle list (0,1,2, 3,4,5, ...)
                                int triCount = md.vertexCount / 3;
                                md.indices = new int[md.vertexCount];
                                for (int fi = 0; fi < md.vertexCount; fi++) {
                                    md.indices[fi] = fi;
                                }
                            }
                            debugLog("  Loaded: " + meshesPath + " - " + md.vertexCount + " verts, " + md.indices.length + " indices, " + md.info);
                            // Log first few vertex positions for debugging
                            if (md.vertexCount > 0) {
                                debugLog("  First vertex: [" + md.positions[0] + ", " + md.positions[1] + ", " + md.positions[2] + "]");
                                debugLog("  Last vertex: [" + md.positions[(md.vertexCount-1)*3] + ", " + md.positions[(md.vertexCount-1)*3+1] + ", " + md.positions[(md.vertexCount-1)*3+2] + "]");
                                if (md.indices.length >= 3) {
                                    debugLog("  First 3 indices: [" + md.indices[0] + ", " + md.indices[1] + ", " + md.indices[2] + "]");
                                }
                            }
                            // Export terrain as OBJ for debugging
                            try {
                                File objFile = new File(getCacheDir(), "terrain_debug.obj");
                                java.io.FileWriter fw = new java.io.FileWriter(objFile);
                                for (int vi = 0; vi < md.vertexCount; vi++) {
                                    fw.write("v " + md.positions[vi*3] + " " + md.positions[vi*3+1] + " " + md.positions[vi*3+2] + "\n");
                                }
                                for (int ii2 = 0; ii2 + 2 < md.indices.length; ii2 += 3) {
                                    fw.write("f " + (md.indices[ii2]+1) + " " + (md.indices[ii2+1]+1) + " " + (md.indices[ii2+2]+1) + "\n");
                                }
                                fw.close();
                                debugLog("  Exported terrain OBJ to: " + objFile.getAbsolutePath());
                            } catch (Exception e) {
                                debugLog("  OBJ export error: " + e.getMessage());
                            }
                            terrainDataList.add(md);
                            // Accumulate global bounding box
                            if (md.boundsMin != null && md.boundsMax != null) {
                                if (md.boundsMin[0] < gMinX) gMinX = md.boundsMin[0];
                                if (md.boundsMin[1] < gMinY) gMinY = md.boundsMin[1];
                                if (md.boundsMin[2] < gMinZ) gMinZ = md.boundsMin[2];
                                if (md.boundsMax[0] > gMaxX) gMaxX = md.boundsMax[0];
                                if (md.boundsMax[1] > gMaxY) gMaxY = md.boundsMax[1];
                                if (md.boundsMax[2] > gMaxZ) gMaxZ = md.boundsMax[2];
                            }
                        } catch (Exception e) {
                            debugLog("Failed to load .meshes " + meshesPath + ": " + e.getMessage());
                        }
                    }
                    
                    // Compute global center and scale
                    float cx, cy, cz, levelScale;
                    if (terrainDataList.isEmpty()) {
                        cx = 0; cy = 0; cz = 0; levelScale = 1f;
                    } else {
                        cx = (gMinX + gMaxX) * 0.5f;
                        cy = (gMinY + gMaxY) * 0.5f;
                        cz = (gMinZ + gMaxZ) * 0.5f;
                        float gSize = Math.max(Math.max(gMaxX - gMinX, gMaxY - gMinY), gMaxZ - gMinZ);
                        levelScale = (gSize > 1000f) ? 1000f / gSize : 1.0f;
                    }
                    final float[] levelCenter = new float[]{cx, cy, cz};
                    final float finalLevelScale = levelScale;
                    debugLog("Global bounds: [" + gMinX + "," + gMinY + "," + gMinZ + "] to [" + gMaxX + "," + gMaxY + "," + gMaxZ + "], center: [" + cx + "," + cy + "," + cz + "], scale: " + levelScale);
                    
                    // Apply centering to all terrain data and upload to GL
                    int terrainLoaded = 0;
                    for (final com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData md : terrainDataList) {
                        if (loadId != currentLoadId) return;
                        
                        // Center and scale positions
                        final float[] terrainPos = md.positions;
                        // Validate positions before centering
                        int nanCount = 0;
                        for (int i = 0; i < md.vertexCount; i++) {
                            float px = terrainPos[i * 3];
                            float py = terrainPos[i * 3 + 1];
                            float pz = terrainPos[i * 3 + 2];
                            if (Float.isNaN(px) || Float.isNaN(py) || Float.isNaN(pz) ||
                                Float.isInfinite(px) || Float.isInfinite(py) || Float.isInfinite(pz)) {
                                nanCount++;
                                if (nanCount <= 3) {
                                    debugLog("  NaN/Inf vertex " + i + ": [" + px + "," + py + "," + pz + "]");
                                }
                            }
                        }
                        if (nanCount > 0) {
                            debugLog("  WARNING: " + nanCount + " NaN/Inf vertices in terrain!");
                        }
                        // Apply centering
                        for (int i = 0; i < md.vertexCount; i++) {
                            terrainPos[i * 3]     = (terrainPos[i * 3]     - cx) * levelScale;
                            terrainPos[i * 3 + 1] = (terrainPos[i * 3 + 1] - cy) * levelScale;
                            terrainPos[i * 3 + 2] = (terrainPos[i * 3 + 2] - cz) * levelScale;
                        }
                        // Log centered bounds
                        float cMinX = Float.MAX_VALUE, cMinY = Float.MAX_VALUE, cMinZ = Float.MAX_VALUE;
                        float cMaxX = -Float.MAX_VALUE, cMaxY = -Float.MAX_VALUE, cMaxZ = -Float.MAX_VALUE;
                        for (int i = 0; i < md.vertexCount; i++) {
                            float px = terrainPos[i * 3], py = terrainPos[i * 3 + 1], pz = terrainPos[i * 3 + 2];
                            if (Float.isNaN(px)) continue;
                            cMinX = Math.min(cMinX, px); cMaxX = Math.max(cMaxX, px);
                            cMinY = Math.min(cMinY, py); cMaxY = Math.max(cMaxY, py);
                            cMinZ = Math.min(cMinZ, pz); cMaxZ = Math.max(cMaxZ, pz);
                        }
                        debugLog("  Centered terrain bounds: [" + cMinX + "," + cMinY + "," + cMinZ + "] to [" + cMaxX + "," + cMaxY + "," + cMaxZ + "]");
                        debugLog("  Camera: target=(0,0,0) distance=20 near=0.1 far=200");
                        android.util.Log.e("SkyViewer", "Terrain centered bounds: [" + cMinX + "," + cMinY + "," + cMinZ + "] to [" + cMaxX + "," + cMaxY + "," + cMaxZ + "] indices=" + md.indices.length);
                        
                        final float[] terrainUVs = new float[md.vertexCount * 2];
                        final int[] terrainIdxInt = md.indices;
                        final short[] terrainIdxShort = null;
                        final float[] terrainNorm = md.normals;
                        final float[] terrainColors = md.colors;
                        final String terrainName = "terrain_" + terrainLoaded;
                        meshViewport.queueEvent(new Runnable() {
                            @Override
                            public void run() {
                                if (loadId != currentLoadId) return;
                                try {
                                    meshViewport.getMeshRenderer().addMeshInstance(
                                        terrainPos, terrainNorm, terrainColors, terrainUVs,
                                        terrainIdxShort, terrainIdxInt,
                                        0, null, terrainName, true);
                                } catch (Exception e) {
                                    debugLog("Terrain GL error: " + e.getMessage());
                                } finally {
                                    meshViewport.requestRender();
                                }
                            }
                        });
                        terrainLoaded++;
                    }
                    debugLog("Terrain meshes loaded: " + terrainLoaded);
                    final int terrainCount = terrainLoaded;

                    // Frame camera NOW so terrain is visible immediately
                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            if (loadId != currentLoadId) return;
                            meshViewport.getMeshRenderer().frameCamera();
                            meshViewport.requestRender();
                        }
                    });
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("关卡: 已加载 " + terrainCount + " 个地形, 正在加载模型...");
                        }
                    });

                    // Step 2: Load all bin meshes WITHOUT textures (geometry only, fast)
                    final int maxMeshes = Math.min(parseResult.meshEntries.size(), 200);
                    final List<String[]> texLoadQueue = new ArrayList<String[]>(); // [meshName, diffuse1Tex, diffuse2Tex, meshPath]
                    final java.util.Set<String> texQueuedNames = new java.util.HashSet<String>(); // dedup by meshName
                    for (int mi = 0; mi < maxMeshes; mi++) {
                        if (loadId != currentLoadId) {
                            debugLog("Level loading cancelled at mesh " + mi);
                            return;
                        }

                        final com.sky.modelviewer.parsing.LevelFileParser.LevelMeshEntry meshEntry =
                            parseResult.meshEntries.get(mi);
                        final int meshIdx = mi;

                        try {
                            String meshName = meshEntry.meshName;
                            String meshNameStripped = SkyResourceResolver.stripMeshVariantSuffix(meshName);
                            String meshPath = null;

                            meshPath = meshPathMap.get(meshName.toLowerCase());
                            if (meshPath == null && !meshNameStripped.equals(meshName)) {
                                meshPath = meshPathMap.get(meshNameStripped.toLowerCase());
                            }
                            if (meshPath == null) {
                                meshPath = findMeshInApk(apkPath, meshName);
                            }

                            if (meshPath == null) {
                                if (meshIdx < 20) debugLog("Mesh not found: " + meshName);
                                failedMeshes.add(meshName);
                                meshStats[1]++;
                                continue;
                            }

                            final byte[] meshRaw = SkyAssetScanner.readApkEntry(apkPath, meshPath);
                            if (meshRaw == null) {
                                failedMeshes.add(meshName);
                                meshStats[1]++;
                                continue;
                            }

                            final MeshData meshData = TgcMeshReader.readMesh(meshRaw, meshPath);
                            if (meshData.vertices.isEmpty() || meshData.indices.isEmpty()) {
                                failedMeshes.add(meshName);
                                meshStats[1]++;
                                continue;
                            }

                            // Build vertex data - game world space
                            final int vc = meshData.vertices.size();
                            final float[] positions = new float[vc * 3];
                            final List<float[]> normals = computeSmoothNormalsStatic(meshData);
                            int pi = 0;
                            for (float[] v : meshData.vertices) {
                                positions[pi++] = v[0];
                                positions[pi++] = v[1];
                                positions[pi++] = v[2];
                            }
                            final float[] normalArr = new float[vc * 3];
                            int ni = 0;
                            for (float[] n : normals) {
                                normalArr[ni++] = n[0];
                                normalArr[ni++] = n[1];
                                normalArr[ni++] = n[2];
                            }
                            final float[] uvs = new float[vc * 2];
                            int ui = 0;
                            for (float[] uv : meshData.uv0) {
                                uvs[ui++] = uv[0];
                                uvs[ui++] = uv[1];
                            }
                            while (ui < vc * 2) uvs[ui++] = 0f;

                            final int ic = meshData.indices.size() * 3;
                            final boolean useShort = vc <= 65535;
                            final short[] idxShort = useShort ? new short[ic] : null;
                            final int[] idxInt = !useShort ? new int[ic] : null;
                            int ii = 0;
                            for (int[] tri : meshData.indices) {
                                if (useShort) {
                                    idxShort[ii++] = (short) tri[0];
                                    idxShort[ii++] = (short) tri[1];
                                    idxShort[ii++] = (short) tri[2];
                                } else {
                                    idxInt[ii++] = tri[0];
                                    idxInt[ii++] = tri[1];
                                    idxInt[ii++] = tri[2];
                                }
                            }

                            // Apply level centering and scaling to transform matrix
                            final float[] transform;
                            if (meshEntry.transformMatrix != null) {
                                float[] adjusted = new float[16];
                                System.arraycopy(meshEntry.transformMatrix, 0, adjusted, 0, 16);
                                for (int t = 0; t < 12; t++) {
                                    adjusted[t] *= finalLevelScale;
                                }
                                adjusted[12] = (adjusted[12] - levelCenter[0]) * finalLevelScale;
                                adjusted[13] = (adjusted[13] - levelCenter[1]) * finalLevelScale;
                                adjusted[14] = (adjusted[14] - levelCenter[2]) * finalLevelScale;
                                adjusted[3] = 0; adjusted[7] = 0; adjusted[11] = 0; adjusted[15] = 1;
                                transform = adjusted;
                            } else {
                                transform = null;
                            }
                            final String meshNameFinal = meshName;

                            // Upload geometry WITHOUT texture (texId=0)
                            meshViewport.queueEvent(new Runnable() {
                                @Override
                                public void run() {
                                    if (loadId != currentLoadId) return;
                                    try {
                                        meshViewport.getMeshRenderer().addMeshInstance(
                                            positions, normalArr, uvs, idxShort, idxInt,
                                            0, transform, meshNameFinal
                                        );
                                    } catch (Exception e) {
                                        debugLog("GL error for " + meshNameFinal + ": " + e.getMessage());
                                    } finally {
                                        meshViewport.requestRender();
                                    }
                                }
                            });

                            // Queue texture for async loading (dedup by meshName)
                            if (!texQueuedNames.contains(meshNameFinal)) {
                                texQueuedNames.add(meshNameFinal);
                                texLoadQueue.add(new String[]{meshNameFinal,
                                    meshEntry.diffuse1Tex != null ? meshEntry.diffuse1Tex : "",
                                    meshEntry.diffuse2Tex != null ? meshEntry.diffuse2Tex : "",
                                    meshPath});
                            }

                            loadedMeshes.add(meshName);
                            meshStats[0]++;

                            if (meshIdx % 20 == 0) {
                                final int progress = meshIdx + 1;
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        statusText.setText("正在加载模型: " + progress + "/" + maxMeshes);
                                    }
                                });
                            }

                        } catch (Exception e) {
                            debugLog("Failed to load mesh " + meshEntry.meshName + ": " + e.getMessage());
                            failedMeshes.add(meshEntry.meshName);
                            meshStats[1]++;
                        }
                    }

                    // Frame camera again after all bin meshes loaded
                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            if (loadId != currentLoadId) return;
                            meshViewport.getMeshRenderer().frameCamera();
                            meshViewport.requestRender();
                        }
                    });

                    // Step 3: Load textures in parallel (background thread)
                    final String apkPathFinal = apkPath;
                    final int loadIdFinal = loadId;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            debugLog("Starting async texture loading: " + texLoadQueue.size() + " textures");
                            int texLoaded = 0;
                            for (final String[] texInfo : texLoadQueue) {
                                if (loadIdFinal != currentLoadId) return;

                                final String meshName = texInfo[0];
                                final String diffuse1 = texInfo[1];
                                final String diffuse2 = texInfo[2];
                                final String meshPath = texInfo[3];

                                byte[] textureBytes = null;
                                try {
                                    // Try diffuse1Tex from shaderParams
                                    if (diffuse1 != null && !diffuse1.isEmpty()) {
                                        String path = SkyAssetScanner.findTextureEntry(apkPathFinal, diffuse1);
                                        if (path != null) {
                                            textureBytes = SkyAssetScanner.readApkEntry(apkPathFinal, path);
                                        }
                                    }
                                    // Try diffuse2Tex
                                    if (textureBytes == null && diffuse2 != null && !diffuse2.isEmpty()) {
                                        String path = SkyAssetScanner.findTextureEntry(apkPathFinal, diffuse2);
                                        if (path != null) {
                                            textureBytes = SkyAssetScanner.readApkEntry(apkPathFinal, path);
                                        }
                                    }
                                    // Fallback to material/mesh name
                                    if (textureBytes == null) {
                                        String meshNameForTex = SkyResourceResolver.stripMeshVariantSuffix(
                                            nameWithoutExtension(new File(meshPath).getName())
                                        );
                                        com.sky.modelviewer.model.MaterialInfo mat =
                                            SkyResourceResolver.resolveMaterial(apkPathFinal, meshPath);
                                        String texturePath = SkyResourceResolver.findTextureFile(
                                            apkPathFinal,
                                            new String[]{mat != null ? mat.diffuseTex : null,
                                                         mat != null ? mat.diffuse2Tex : null,
                                                         meshNameForTex}
                                        );
                                        if (texturePath != null) {
                                            textureBytes = SkyAssetScanner.readApkEntry(apkPathFinal, texturePath);
                                        }
                                    }
                                } catch (Exception e) {
                                    debugLog("Texture resolve error for " + meshName + ": " + e.getMessage());
                                }

                                if (textureBytes != null) {
                                    final byte[] finalTexBytes = textureBytes;
                                    meshViewport.queueEvent(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (loadIdFinal != currentLoadId) return;
                                            try {
                                                int texId = KtxTextureLoader.loadTexture(finalTexBytes);
                                                if (texId == 0) {
                                                    texId = KtxTextureLoader.loadStandardImage(finalTexBytes);
                                                }
                                                if (texId != 0) {
                                                    meshViewport.getMeshRenderer().updateMeshTexture(meshName, texId);
                                                    meshViewport.requestRender();
                                                }
                                            } catch (Exception e) {
                                                debugLog("Texture GL error for " + meshName + ": " + e.getMessage());
                                            }
                                        }
                                    });
                                    texLoaded++;
                                }
                            }
                            debugLog("Async texture loading done: " + texLoaded + "/" + texLoadQueue.size());
                        }
                    }).start();

                    final int loadedCount = meshStats[0];
                    final int failedCount = meshStats[1];
                    final StringBuilder info = new StringBuilder();
                    info.append("=== 关卡: ").append(entry.name).append(" ===\n");
                    info.append("地形(.meshes)已加载: ").append(terrainCount).append("\n");
                    // Show terrain parse details
                    if (terrainDataList.isEmpty()) {
                        info.append("  无地形数据 — 解析失败!\n");
                        info.append("  检查: .meshes文件是否存在? GEO0解析? meshopt解码?\n");
                    }
                    for (com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData td : terrainDataList) {
                        info.append("  地形: ").append(td.vertexCount).append(" 顶点, ")
                           .append(td.indices.length).append(" indices\n");
                        if (td.vertexCount > 0) {
                            info.append("  范围: [")
                               .append(String.format("%.1f,%.1f,%.1f", td.boundsMin[0], td.boundsMin[1], td.boundsMin[2]))
                               .append("] to [")
                               .append(String.format("%.1f,%.1f,%.1f", td.boundsMax[0], td.boundsMax[1], td.boundsMax[2]))
                               .append("]\n");
                            // Show raw (pre-centering) first 3 vertices
                            info.append("  原始顶点: ");
                            for (int vi = 0; vi < Math.min(3, td.vertexCount); vi++) {
                                info.append(String.format("(%.1f,%.1f,%.1f) ",
                                    td.positions[vi*3], td.positions[vi*3+1], td.positions[vi*3+2]));
                            }
                            info.append("\n");
                            boolean hasNaN = false;
                            int nanC = 0;
                            for (int ci = 0; ci < td.vertexCount; ci++) {
                                if (Float.isNaN(td.positions[ci*3]) || Float.isInfinite(td.positions[ci*3])) {
                                    nanC++;
                                    hasNaN = true;
                                }
                            }
                            if (hasNaN) {
                                info.append("  警告: ").append(nanC).append("/").append(td.vertexCount)
                                   .append(" NaN/Inf vertices!\n");
                            } else {
                                info.append("  全部 ").append(td.vertexCount).append(" 顶点有效(无NaN)\n");
                            }
                        }
                    }
                    info.append("总节点数: ").append(parseResult.allNodeNames.size()).append("\n");
                    info.append("模型节点数: ").append(parseResult.meshEntries.size()).append("\n");
                    int texCount = 0;
                    for (LevelFileParser.LevelMeshEntry e : parseResult.meshEntries) {
                        if (e.diffuse1Tex != null || e.diffuse2Tex != null) texCount++;
                    }
                    info.append("含贴图节点: ").append(texCount).append("\n");
                    info.append("模型已加载: ").append(loadedCount).append("\n");
                    info.append("模型加载失败: ").append(failedCount).append("\n");
                    if (parseResult.meshEntries.size() > maxMeshes) {
                        info.append("(限制前" + maxMeshes + "个模型)\n");
                    }
                    info.append("\n=== 已加载模型 ===\n");
                    for (int i = 0; i < loadedMeshes.size() && i < 30; i++) {
                        info.append(loadedMeshes.get(i)).append("\n");
                    }
                    if (loadedMeshes.size() > 30) {
                        info.append("... 还有 ").append(loadedMeshes.size() - 30).append(" 个\n");
                    }
                    if (!failedMeshes.isEmpty()) {
                        info.append("\n=== 加载失败模型 ===\n");
                        for (int i = 0; i < failedMeshes.size() && i < 20; i++) {
                            info.append(failedMeshes.get(i)).append("\n");
                        }
                        if (failedMeshes.size() > 20) {
                            info.append("... 还有 ").append(failedMeshes.size() - 20).append(" 个\n");
                        }
                    }

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("Level: " + terrainCount + " terrain + " + loadedCount + " meshes");
                            detailsBox.setText(info.toString());
                            btnExport.setEnabled(false);
                        }
                    });

                } catch (final Exception e) {
                    debugLog("Level file load failed: " + e.getMessage());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("关卡加载失败");
                            detailsBox.setText(e.toString());
                        }
                    });
                }
            }
        }).start();
    }

    private String findMeshInApk(String apkPath, String meshName) {
        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apkPath);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            String meshFile = meshName + ".mesh";
            String meshFileLower = meshFile.toLowerCase();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.toLowerCase().endsWith(meshFileLower)) {
                    zipFile.close();
                    return name;
                }
            }
            zipFile.close();
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private List<float[]> computeSmoothNormalsStatic(MeshData meshData) {
        float[][] normals = new float[meshData.vertices.size()][3];
        for (int[] tri : meshData.indices) {
            int a = tri[0], b = tri[1], c = tri[2];
            if (a < 0 || a >= meshData.vertices.size() || b < 0 || b >= meshData.vertices.size() ||
                c < 0 || c >= meshData.vertices.size()) continue;
            float[] va = meshData.vertices.get(a);
            float[] vb = meshData.vertices.get(b);
            float[] vc = meshData.vertices.get(c);
            float ux = vb[0] - va[0], uy = vb[1] - va[1], uz = vb[2] - va[2];
            float vx = vc[0] - va[0], vy = vc[1] - va[1], vz = vc[2] - va[2];
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            normals[a][0] += nx; normals[a][1] += ny; normals[a][2] += nz;
            normals[b][0] += nx; normals[b][1] += ny; normals[b][2] += nz;
            normals[c][0] += nx; normals[c][1] += ny; normals[c][2] += nz;
        }
        List<float[]> result = new ArrayList<float[]>(normals.length);
        for (float[] n : normals) {
            float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len > 1e-10f) {
                result.add(new float[]{n[0] / len, n[1] / len, n[2] / len});
            } else {
                result.add(new float[]{0f, 1f, 0f});
            }
        }
        return result;
    }

    private void loadKtxTexture(final MeshCatalogEntry entry, final String apkPath, final int loadId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    debugLog("Loading KTX: " + entry.fullPath);
                    final byte[] raw = SkyAssetScanner.readApkEntry(apkPath, entry.fullPath);
                    if (raw == null) throw new RuntimeException("Cannot read KTX: " + entry.fullPath);
                    debugLog("KTX read: " + raw.length + " bytes");

                    // Try to load as OpenGL texture and render it as a textured quad
                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            if (loadId != currentLoadId) return; // Cancelled
                            try {
                                int texId = KtxTextureLoader.loadTexture(raw);
                                if (texId == 0) {
                                    debugLog("KTX loadTexture returned 0, trying standard image loader");
                                    texId = KtxTextureLoader.loadStandardImage(raw);
                                }
                                if (texId != 0) {
                                    debugLog("KTX loaded as texture ID: " + texId);
                                    // Show textured quad for KTX preview
                                    meshViewport.getMeshRenderer().showTexturedQuad(texId);
                                } else {
                                    debugLog("KTX failed to load as texture");
                                }
                            } catch (Exception e) {
                                debugLog("KTX GL error: " + e.getMessage());
                            } finally {
                                // ALWAYS request render
                                meshViewport.requestRender();
                            }
                        }
                    });

                    // Parse KTX header for info
                    String info = "=== KTX Texture Info ===\n";
                    info += "Name: " + entry.name + "\n";
                    info += "Path: " + entry.fullPath + "\n";
                    info += "Size: " + raw.length + " bytes\n";
                    if (raw.length >= 48) {
                        int width = (raw[36] & 0xFF) | ((raw[37] & 0xFF) << 8) | ((raw[38] & 0xFF) << 16) | ((raw[39] & 0xFF) << 24);
                        int height = (raw[40] & 0xFF) | ((raw[41] & 0xFF) << 8) | ((raw[42] & 0xFF) << 16) | ((raw[43] & 0xFF) << 24);
                        int internalFormat = (raw[28] & 0xFF) | ((raw[29] & 0xFF) << 8) | ((raw[30] & 0xFF) << 16) | ((raw[31] & 0xFF) << 24);
                        info += "Width: " + width + "\n";
                        info += "Height: " + height + "\n";
                        info += "Internal Format: 0x" + Integer.toHexString(internalFormat) + "\n";
                        String formatName;
                        switch (internalFormat) {
                            case 0x9274: formatName = "COMPRESSED_RGB8_ETC2"; break;
                            case 0x9275: formatName = "COMPRESSED_SRGB8_ETC2"; break;
                            case 0x9278: formatName = "COMPRESSED_RGBA8_ETC2_EAC"; break;
                            case 0x9279: formatName = "COMPRESSED_SRGB8_ALPHA8_ETC2_EAC"; break;
                            case 0x1908: formatName = "GL_RGBA"; break;
                            case 0x8058: formatName = "GL_RGBA8"; break;
                            default: formatName = "Unknown"; break;
                        }
                        info += "Format: " + formatName + "\n";
                    }
                    info += "\nTexture loaded to 3D viewport for preview.\n";

                    final String infoStr = info;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("贴图: " + entry.name);
                            detailsBox.setText(infoStr);
                        }
                    });

                } catch (final Exception e) {
                    debugLog("KTX load failed: " + e.getMessage());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("贴图加载失败");
                            detailsBox.setText(e.toString());
                        }
                    });
                }
            }
        }).start();
    }

    private void exportCurrentMesh() {
        final MeshData meshData = currentMeshData;
        if (meshData == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, meshData.name + ".glb");
            startActivityForResult(intent, REQUEST_CREATE_GLB);
        } catch (Exception e) {
            Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startGlbExport(final Uri uri) {
        final MeshData meshData = currentMeshData;
        final String apkPath = currentApkPath;
        final MeshCatalogEntry meshEntry = currentMeshEntry;
        if (meshData == null || apkPath == null || meshEntry == null) return;

        statusText.setText("正在导出GLB...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    float scale = currentScale;
                    String meshPath = meshEntry.fullPath;

                    Object[] texData = resolveTextureData(apkPath, meshPath);
                    byte[] textureData = (byte[]) texData[0];
                    String textureMime = (String) texData[1];

                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        GlbExporter.export(os, meshData, textureData, textureMime, scale, true);
                        os.close();
                    }

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("已导出 " + meshData.name);
                            Toast.makeText(MainActivity.this, "已导出 " + meshData.name + ".glb", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    debugLog("Export failed: " + e.getMessage());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("导出失败");
                            Toast.makeText(MainActivity.this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void batchExportMeshes(final List<MeshCatalogEntry> meshes, final String apkPath, final Uri outputUri) {
        statusText.setText("批量导出 " + meshes.size() + " 个模型...");
        btnBatchExport.setEnabled(false);
        btnExport.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                int success = 0, skip = 0, fail = 0;

                Uri docFile = null;
                try {
                    docFile = DocumentsContract.buildDocumentUriUsingTree(
                        outputUri, DocumentsContract.getTreeDocumentId(outputUri));
                } catch (Exception e) { }

                for (int i = 0; i < meshes.size(); i++) {
                    final MeshCatalogEntry mesh = meshes.get(i);
                    final int idx = i;

                    // Skip level files in batch export
                    if ("level".equals(mesh.fileType)) {
                        skip++;
                        continue;
                    }

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("批量 " + (idx + 1) + "/" + meshes.size() + ": " + mesh.name);
                        }
                    });

                    try {
                        byte[] raw = SkyAssetScanner.readApkEntry(apkPath, mesh.fullPath);
                        if (raw == null) throw new RuntimeException("Cannot read: " + mesh.fullPath);
                        MeshData meshData = TgcMeshReader.readMesh(raw, mesh.fullPath);

                        if (meshData.vertices.isEmpty() || meshData.indices.isEmpty()) {
                            skip++;
                            continue;
                        }

                        Float scaleVal = SkyResourceResolver.resolveScale(apkPath, mesh.fullPath);
                        float scale = scaleVal != null ? scaleVal : 1f;
                        Object[] texData = resolveTextureData(apkPath, mesh.fullPath);
                        byte[] textureData = (byte[]) texData[0];
                        String textureMime = (String) texData[1];

                        String safeName = mesh.name.replaceAll("[^a-zA-Z0-9._-]", "_");

                        Uri glbUri = null;
                        try {
                            glbUri = DocumentsContract.createDocument(
                                getContentResolver(), docFile,
                                "application/octet-stream", safeName + ".glb");
                        } catch (Exception e) { }

                        if (glbUri != null) {
                            OutputStream os = getContentResolver().openOutputStream(glbUri);
                            if (os != null) {
                                GlbExporter.export(os, meshData, textureData, textureMime, scale, true);
                                os.close();
                            }
                            success++;
                        } else {
                            fail++;
                        }
                    } catch (Exception e) {
                        debugLog("Batch export item failed: " + mesh.name + ": " + e.getMessage());
                        fail++;
                    }
                }

                final String summary = "批量完成: 成功 " + success + ", 跳过 " + skip + ", 失败 " + fail;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        statusText.setText(summary);
                        btnBatchExport.setEnabled(!meshListAdapter.getVisibleEntries().isEmpty());
                        btnExport.setEnabled(currentMeshData != null);
                        Toast.makeText(MainActivity.this, summary, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private Object[] resolveTextureData(String apkPath, String meshEntryPath) {
        String meshNameForTexture = SkyResourceResolver.stripMeshVariantSuffix(
            nameWithoutExtension(new File(meshEntryPath).getName())
        );
        MaterialInfo material = SkyResourceResolver.resolveMaterial(apkPath, meshEntryPath);
        String texturePath = SkyResourceResolver.findTextureFile(
            apkPath,
            new String[]{material != null ? material.diffuseTex : null,
                         material != null ? material.diffuse2Tex : null,
                         meshNameForTexture}
        );

        if (texturePath == null || texturePath.isEmpty()) return new Object[]{null, null};

        String fileName = texturePath.substring(texturePath.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String extension = dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        byte[] data = SkyAssetScanner.readApkEntry(apkPath, texturePath);
        if (data == null) return new Object[]{null, null};

        if (extension.equals("png")) return new Object[]{data, "image/png"};
        if (extension.equals("jpg") || extension.equals("jpeg")) return new Object[]{data, "image/jpeg"};
        return new Object[]{null, null};
    }

    private String copyUriToTempFile(Uri uri) {
        try {
            File tempFile = new File(getCacheDir(), "temp_sky.apk");
            InputStream input = getContentResolver().openInputStream(uri);
            if (input != null) {
                FileOutputStream output = new FileOutputStream(tempFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) > 0) {
                    output.write(buf, 0, n);
                }
                output.close();
                input.close();
            }
            return tempFile.getAbsolutePath();
        } catch (Exception e) {
            debugLog("copyUriToTempFile error: " + e.getMessage());
            return null;
        }
    }

    private static String nameWithoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) return fileName.substring(0, dot);
        return fileName;
    }
}
