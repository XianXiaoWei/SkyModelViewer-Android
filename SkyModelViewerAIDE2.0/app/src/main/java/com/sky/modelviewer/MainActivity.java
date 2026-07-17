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
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.Toast;
import com.sky.modelviewer.ui.WardrobeGridAdapter;

import com.sky.modelviewer.export.GlbExporter;
import com.sky.modelviewer.model.MeshCatalogEntry;
import com.sky.modelviewer.model.MeshData;
import com.sky.modelviewer.model.ScanResult;
import com.sky.modelviewer.model.MaterialInfo;
import com.sky.modelviewer.parsing.TgcMeshReader;
import com.sky.modelviewer.parsing.LevelFileParser;
import com.sky.modelviewer.parsing.FmodVorbisDecoder;
import com.sky.modelviewer.parsing.FadpcmDecoder;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.graphics.Bitmap;

public class MainActivity extends Activity {

    private static final String TAG = "SkyModelViewer";
    private static final int REQUEST_OPEN_APK = 1001;
    private static final int REQUEST_CREATE_GLB = 1002;
    private static final int REQUEST_BATCH_DIR = 1003;
    private static final int REQUEST_STORAGE = 1004;
    private static final int REQUEST_EXPORT_OBJ = 1005;
    private static final int REQUEST_EXPORT_FBX = 1006;
    private static final int REQUEST_EXPORT_PNG = 1007;
    private static final int REQUEST_EXPORT_JPEG = 1008;
    private static final int REQUEST_EXPORT_COMBINED_OBJ = 1009;
    private static final int REQUEST_EXPORT_COMBINED_GLB = 1010;
    private static final int REQUEST_EXPORT_COMBINED_FBX = 1011;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MeshListAdapter meshListAdapter;
    private TextView statusText;
    private TextView sourcePathText;
    private TextView meshCountText;
    private EditText searchBox;
    private ListView listMesh;
    private ListView listKtx;
    private ListView listLevel;
    private MeshListAdapter meshAdapter;
    private MeshListAdapter ktxAdapter;
    private MeshListAdapter levelAdapter;
    private TextView selectionTitle;
    private TextView selectionMeta;
    private TextView detailsBox;
    private MeshSurfaceView meshViewport;
    private RadioGroup viewModeGroup;
    private Button btnExport;
    private Button btnBatchExport;
    private Button btnToggleBeamo;
    private Button btnSelectAnim;
    private Button btnPlayPause;
    private TextView animNameOverlay;
    private SeekBar animSeekBar;
    private TextView animTimeText;
    private Button btnAnimFilter;
    private int animFilterMode = 0; // 0=all, 1=dynamic, 2=static
    private boolean beamoVisible = true;
    private List<MeshCatalogEntry> animPackEntries = new ArrayList<MeshCatalogEntry>();
    private List<MeshCatalogEntry> levelList = new ArrayList<MeshCatalogEntry>();
    private Button btnMoveUp;
    private Button btnMoveDown;
    private FrameLayout joystickArea;
    private View joystickThumb;

    // ===== Wardrobe mode =====
    private boolean wardrobeMode = false;
    private String wardrobeSelectedCategory = null;
    private Button btnModeVisual, btnModeWardrobe, btnModeEditor, btnModeAudio;
    private android.widget.LinearLayout wardrobeCategoryBar;
    private View wardrobeCategoryScroll;
    private Button btnClearWardrobe;
    private GridView gridWardrobe;
    private WardrobeGridAdapter wardrobeGridAdapter;
    // Category type → Chinese label mapping (used for display)
    private static final String[][] WARDROBE_TYPE_LABELS = {
        {"body", "裤子"}, {"hat", "帽子"}, {"hair", "头发"}, {"mask", "面具"},
        {"face", "面饰"}, {"horn", "头饰"}, {"wing", "斗篷"}, {"neck", "项链"},
        {"feet", "鞋子"}, {"prop", "背饰"}
    };
    // Outfit entries loaded from OutfitDefs.json: type → list of entries
    private java.util.Map<String, List<OutfitEntry>> outfitByType = new java.util.LinkedHashMap<>();
    // Types found in OutfitDefs (preserves order)
    private List<String> outfitTypes = new ArrayList<>();

    // Simple data class for outfit entries
    private static class OutfitEntry {
        String type;
        String name;
        String mesh;
        MeshCatalogEntry matchedEntry;
        // Color fields
        float[] baseHsv = {0,0,100};
        boolean colorOverride = false;
        float[] primaryDyeHsv = null;
        float[] secondaryDyeHsv = null;
        String diffuseTex = "";
        String attribTex = "";
    }

    // Track loaded mesh data per category for re-applying animation
    private java.util.Map<String, MeshData> wardrobeMeshData = new java.util.HashMap<>();
    private java.util.Map<String, Float> wardrobeMeshScale = new java.util.HashMap<>();
    private java.util.Map<String, byte[]> wardrobeTextureBytes = new java.util.HashMap<>();
    // Track selected OutfitEntry per category (for transform/color info)
    private java.util.Map<String, OutfitEntry> wardrobeOutfitSelections = new java.util.HashMap<>();
    // Currently selected animpack for wardrobe
    private com.sky.modelviewer.parsing.AnimPackParser.AnimPack wardrobeAnim = null;
    private MeshCatalogEntry wardrobeAnimEntry = null;

    // === Editor mode state ===
    private boolean editorMode = false;
    private com.sky.modelviewer.parsing.TgclParser.TgclFile editorFile = null;
    private android.widget.ListView editorNodeList;
    private android.widget.LinearLayout editorPanel;
    private android.widget.LinearLayout audioPanel;
    private Spinner audioBankSpinner;
    private TextView audioInfoLabel;
    private ListView audioList;
    private EditText audioSearch;
    private com.sky.modelviewer.parsing.FmodBankParser audioBank;
    private List<String> audioBankFiles = new ArrayList<>();
    private java.util.zip.ZipFile audioZipCache;
    // Progress
    private android.widget.LinearLayout audioProgressLayout;
    private ProgressBar audioProgressBar;
    private TextView audioProgressText;
    // Audio player
    private android.media.MediaPlayer audioMediaPlayer;
    private SeekBar audioSeekBar;
    private TextView audioNowPlaying, audioCurrentTime, audioTotalTime;
    private ImageButton btnAudioPlay, btnAudioPrev, btnAudioNext;
    private int audioCurrentIndex = -1;
    private int audioPlayGeneration = 0; // prevents stale threads from creating MediaPlayers
    private Runnable audioSeekBarUpdater;
    private boolean audioSeeking = false;
    // Parsed audio entries from bank
    private static class AudioEntry {
        String name; String guid; String type; int size; byte[] data;
        int freq; int channels; String format; long samples; // samples count from FSB5 header
        AudioEntry(String n, String g, String t, int s, byte[] d) {
            name=n; guid=g; type=t; size=s; data=d; freq=0; channels=0; format=""; samples=0;
        }
        AudioEntry(String n, String g, String t, int s, byte[] d, int f, int ch, String fmt) {
            name=n; guid=g; type=t; size=s; data=d; freq=f; channels=ch; format=fmt; samples=0;
        }
        AudioEntry(String n, String g, String t, int s, byte[] d, int f, int ch, String fmt, long sm) {
            name=n; guid=g; type=t; size=s; data=d; freq=f; channels=ch; format=fmt; samples=sm;
        }
    }
    private List<AudioEntry> audioEntries = new ArrayList<>();
    private byte[] vorbisSetupHeader; // cached Vorbis setup header from raw resource
    private android.widget.TextView editorDetailTitle;
    private android.widget.EditText editorSearch;
    private List<String> editorFilteredNames = new ArrayList<>();
    private int editorSelectedNodeIndex = -1;
    // Editor 3D viewport
    private com.sky.modelviewer.render.MeshSurfaceView editorViewport;
    private android.widget.Spinner editorMapSpinner;
    private android.widget.Spinner editorTypeSpinner;
    private android.widget.TextView editorCoordLabel;
    private List<MeshCatalogEntry> editorMapFiles = new ArrayList<>();
    private List<String> editorTypeFilter = new ArrayList<>();
    private String editorSelectedType = "";
    private boolean editorSearchInitialized = false;
    private boolean editorBuildingPanel = false;

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
    private volatile int currentLoadId = 0;

    // Export state
    private String currentEntryType = "mesh"; // "mesh", "ktx", "level"
    private List<com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData> currentTerrainData = null;
    private List<MeshData> currentLevelMeshes = null; // Meshes loaded in level mode
    private List<float[]> currentLevelMeshTransforms = null; // Transform matrices for level meshes
    private byte[] currentKtxRaw = null;
    private String currentKtxName = null;

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
            listMesh = (ListView) findViewById(R.id.listMesh);
            listKtx = (ListView) findViewById(R.id.listKtx);
            listLevel = (ListView) findViewById(R.id.listLevel);
            selectionTitle = (TextView) findViewById(R.id.selectionTitle);
            selectionMeta = (TextView) findViewById(R.id.selectionMeta);
            detailsBox = (TextView) findViewById(R.id.detailsBox);
            meshViewport = (MeshSurfaceView) findViewById(R.id.meshViewport);
            viewModeGroup = (RadioGroup) findViewById(R.id.viewModeGroup);
            btnExport = (Button) findViewById(R.id.btnExport);
            btnBatchExport = (Button) findViewById(R.id.btnBatchExport);
            btnToggleBeamo = (Button) findViewById(R.id.btnToggleBeamo);
            btnSelectAnim = (Button) findViewById(R.id.btnSelectAnim);
            btnPlayPause = (Button) findViewById(R.id.btnPlayPause);
            animNameOverlay = (TextView) findViewById(R.id.animNameOverlay);
            animSeekBar = (SeekBar) findViewById(R.id.animSeekBar);
            animTimeText = (TextView) findViewById(R.id.animTimeText);
            btnAnimFilter = (Button) findViewById(R.id.btnAnimFilter);
            btnModeVisual = (Button) findViewById(R.id.btnModeVisual);
            btnModeWardrobe = (Button) findViewById(R.id.btnModeWardrobe);
            btnModeEditor = (Button) findViewById(R.id.btnModeEditor);
            btnModeAudio = (Button) findViewById(R.id.btnModeAudio);
            audioPanel = (android.widget.LinearLayout) findViewById(R.id.audioPanel);
            audioBankSpinner = (Spinner) findViewById(R.id.audioBankSpinner);
            audioInfoLabel = (TextView) findViewById(R.id.audioInfoLabel);
            audioList = (ListView) findViewById(R.id.audioList);
            audioSearch = (EditText) findViewById(R.id.audioSearch);
            audioProgressLayout = (android.widget.LinearLayout) findViewById(R.id.audioProgressLayout);
            audioProgressBar = (ProgressBar) findViewById(R.id.audioProgressBar);
            audioProgressText = (TextView) findViewById(R.id.audioProgressText);
            audioSeekBar = (SeekBar) findViewById(R.id.audioSeekBar);
            audioNowPlaying = (TextView) findViewById(R.id.audioNowPlaying);
            audioCurrentTime = (TextView) findViewById(R.id.audioCurrentTime);
            audioTotalTime = (TextView) findViewById(R.id.audioTotalTime);
            btnAudioPlay = (ImageButton) findViewById(R.id.btnAudioPlay);
            btnAudioPrev = (ImageButton) findViewById(R.id.btnAudioPrev);
            btnAudioNext = (ImageButton) findViewById(R.id.btnAudioNext);
            wardrobeCategoryBar = (android.widget.LinearLayout) findViewById(R.id.wardrobeCategoryBar);
            wardrobeCategoryScroll = findViewById(R.id.wardrobeCategoryScroll);
            btnClearWardrobe = (Button) findViewById(R.id.btnClearWardrobe);
            gridWardrobe = (GridView) findViewById(R.id.gridWardrobe);
            wardrobeGridAdapter = new WardrobeGridAdapter(this);
            gridWardrobe.setAdapter(wardrobeGridAdapter);

            // Editor panel views
            editorPanel = (android.widget.LinearLayout) findViewById(R.id.editorPanel);
            editorNodeList = (android.widget.ListView) findViewById(R.id.editorNodeList);
            editorDetailTitle = (android.widget.TextView) findViewById(R.id.editorDetailTitle);
            editorSearch = (android.widget.EditText) findViewById(R.id.editorSearch);
            // editorViewport removed from editor layout
            editorMapSpinner = (android.widget.Spinner) findViewById(R.id.editorMapSpinner);
            editorTypeSpinner = (android.widget.Spinner) findViewById(R.id.editorTypeSpinner);
            // editorCoordLabel removed from editor layout
            editorJsonText = (TextView) findViewById(R.id.editorJsonText);
            topologyGraph = (com.sky.modelviewer.render.TopologyGraphView) findViewById(R.id.topologyGraph);
            topoStatsLabel = (android.widget.TextView) findViewById(R.id.topoStatsLabel);
            topoDepthLabel = (android.widget.TextView) findViewById(R.id.topoDepthLabel);
            topoDepthSlider = (android.widget.SeekBar) findViewById(R.id.topoDepthSlider);
            topoDepthInput = (EditText) findViewById(R.id.topoDepthInput);

            // Depth slider: 0-4 maps to levels 1-5
            topoDepthSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    topoDepth = progress + 1;
                    topoDepthLabel.setText(String.valueOf(topoDepth));
                    topoDepthInput.setText("");
                    refreshTopology();
                }
                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
            });

            // Manual depth input for >5
            topoDepthInput.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(android.widget.TextView v, int actionId, android.view.KeyEvent event) {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                        (event != null && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                        try {
                            int d = Integer.parseInt(topoDepthInput.getText().toString());
                            if (d >= 1 && d <= 20) {
                                topoDepth = d;
                                topoDepthLabel.setText(String.valueOf(d));
                                if (d <= 5) topoDepthSlider.setProgress(d - 1);
                                else topoDepthSlider.setProgress(4);
                                refreshTopology();
                            }
                        } catch (NumberFormatException e) { /* ignore */ }
                        return true;
                    }
                    return false;
                }
            });

            debugLog("Views bound");

            MeshListAdapter.OnMeshClickListener clickListener = new MeshListAdapter.OnMeshClickListener() {
                @Override
                public void onMeshClick(MeshCatalogEntry entry) {
                    if (wardrobeMode) {
                        loadWardrobeMesh(entry);
                    } else {
                        loadMesh(entry);
                    }
                }
            };

            meshListAdapter = new MeshListAdapter();
            meshListAdapter.setOnMeshClickListener(clickListener);
            listMesh.setAdapter(meshListAdapter);

            wardrobeGridAdapter.setListener(new WardrobeGridAdapter.OnMeshClickListener() {
                @Override
                public void onMeshClick(MeshCatalogEntry entry) {
                    loadWardrobeMesh(entry);
                }
            });
            // No icon loader — use default placeholder for all wardrobe items

            ktxAdapter = new MeshListAdapter();
            ktxAdapter.setOnMeshClickListener(clickListener);
            listKtx.setAdapter(ktxAdapter);

            levelAdapter = new MeshListAdapter();
            levelAdapter.setOnMeshClickListener(clickListener);
            listLevel.setAdapter(levelAdapter);

            detailsBox.setMovementMethod(new ScrollingMovementMethod());
            debugLog("Adapters set");

            // Disable wardrobe and editor buttons until APK scan completes
            btnModeWardrobe.setEnabled(false);
            btnModeWardrobe.setTextColor(0xFF888888);
            btnModeEditor.setEnabled(false);
            btnModeEditor.setTextColor(0xFF888888);

            // Mode selector handlers
            btnModeVisual.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchMode(false);
                }
            });
            btnModeWardrobe.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!btnModeWardrobe.isEnabled()) return;
                    switchMode(true);
                }
            });
            btnModeEditor.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!btnModeEditor.isEnabled()) return;
                    switchToEditorMode();
                }
            });
            btnModeAudio.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!btnModeAudio.isEnabled()) return;
                    switchToAudioMode();
                }
            });
            // Audio parse button
            findViewById(R.id.btnAudioParse).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { parseSelectedBank(); }
            });
            findViewById(R.id.btnAudioExportJson).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { exportAudioWavZip(); }
            });
            // Audio player buttons
            btnAudioPlay.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggleAudioPlay(); }
            });
            btnAudioPrev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { playAudioPrev(); }
            });
            btnAudioNext.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { playAudioNext(); }
            });
            // Audio list item click - play the selected audio entry
            audioList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    playAudioEntry(position);
                }
            });
            // Audio search filter
            audioSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) { refreshAudioList(); }
            });
            audioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && audioMediaPlayer != null) {
                        audioSeeking = true;
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { audioSeeking = true; }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    if (audioMediaPlayer != null) {
                        int dur = audioMediaPlayer.getDuration();
                        int pos = dur * sb.getProgress() / 100;
                        audioMediaPlayer.seekTo(pos);
                    }
                    audioSeeking = false;
                }
            });
            btnClearWardrobe.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearWardrobe();
                }
            });

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

            if (btnToggleBeamo != null) {
                btnToggleBeamo.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        beamoVisible = !beamoVisible;
                        btnToggleBeamo.setText(beamoVisible ? "Beamo:ON" : "Beamo:OFF");
                        if (meshViewport != null) {
                            meshViewport.queueEvent(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        meshViewport.getMeshRenderer().setMeshesHidden("beamo", !beamoVisible);
                                    } catch (Exception e) {
                                        // ignore
                                    }
                                    meshViewport.requestRender();
                                }
                            });
                        }
                    }
                });
            }

            btnSelectAnim.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAnimSelectionDialog();
                }
            });

            btnAnimFilter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    animFilterMode = (animFilterMode + 1) % 3;
                    String[] labels = {"全部", "动态", "静态"};
                    btnAnimFilter.setText(labels[animFilterMode]);
                }
            });

            btnPlayPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            final boolean playing = !meshViewport.getMeshRenderer().isAnimPlaying();
                            meshViewport.getMeshRenderer().setAnimPlaying(playing);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    btnPlayPause.setText(playing ? "⏸" : "▶");
                                }
                            });
                        }
                    });
                }
            });

            animSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    final float normalized = progress / 100f;
                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            meshViewport.getMeshRenderer().setAnimTimeNormalized(normalized);
                        }
                    });
                    if (animTimeText != null) {
                        float duration = meshViewport.getMeshRenderer().getAnimDuration();
                        animTimeText.setText(String.format("%.1f/%.1fs", normalized * duration, duration));
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    // Pause while scrubbing
                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            meshViewport.getMeshRenderer().setAnimPlaying(false);
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnPlayPause.setText("▶");
                        }
                    });
                }
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    String q = s != null ? s.toString() : "";
                    if (wardrobeMode) {
                        wardrobeGridAdapter.filter(q);
                    } else {
                        meshListAdapter.filter(q);
                        ktxAdapter.filter(q);
                        levelAdapter.filter(q);
                    }
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
        } else if (requestCode == REQUEST_EXPORT_OBJ) {
            Uri uri = data.getData();
            if (uri == null) return;
            startObjExport(uri);
        } else if (requestCode == REQUEST_EXPORT_FBX) {
            Uri uri = data.getData();
            if (uri == null) return;
            startFbxExport(uri);
        } else if (requestCode == REQUEST_EXPORT_PNG) {
            Uri uri = data.getData();
            if (uri == null) return;
            startTextureExport(uri, true);
        } else if (requestCode == REQUEST_EXPORT_JPEG) {
            Uri uri = data.getData();
            if (uri == null) return;
            startTextureExport(uri, false);
        } else if (requestCode == REQUEST_EXPORT_COMBINED_OBJ) {
            Uri uri = data.getData();
            if (uri == null) return;
            startCombinedObjExport(uri);
        } else if (requestCode == REQUEST_EXPORT_COMBINED_GLB) {
            Uri uri = data.getData();
            if (uri == null) return;
            startCombinedGlbExport(uri);
        } else if (requestCode == REQUEST_EXPORT_COMBINED_FBX) {
            Uri uri = data.getData();
            if (uri == null) return;
            startCombinedFbxExport(uri);
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

                            // Split entries by type into three columns
                            List<MeshCatalogEntry> meshList = new ArrayList<MeshCatalogEntry>();
                            List<MeshCatalogEntry> ktxList = new ArrayList<MeshCatalogEntry>();
                            levelList.clear();
                            int animCount = 0;
                            animPackEntries.clear();
                            for (MeshCatalogEntry e : scanResult.meshes) {
                                if ("level".equals(e.fileType)) {
                                    levelList.add(e);
                                } else if ("ktx".equals(e.fileType)) {
                                    ktxList.add(e);
                                } else if ("animpack".equals(e.fileType)) {
                                    meshList.add(e);
                                    animPackEntries.add(e);
                                    animCount++;
                                } else {
                                    meshList.add(e);
                                }
                            }
                            meshListAdapter.setEntries(meshList);
                            ktxAdapter.setEntries(ktxList);
                            levelAdapter.setEntries(levelList);

                            sourcePathText.setText(new File(apkPath).getName());
                            statusText.setText("已索引 模型:" + meshList.size() + " 贴图:" + ktxList.size() + " 地图:" + levelList.size());
                            meshCountText.setText(meshList.size() + " 模型/" + animCount + "动画");
                            detailsBox.setText("索引完成\nAPK: " + new File(apkPath).getName() +
                                "\n模型: " + meshList.size() +
                                "\n贴图: " + ktxList.size() +
                                "\n地图: " + levelList.size() +
                                "\n动画: " + animCount + "\n");
                            btnBatchExport.setEnabled(!meshListAdapter.getVisibleEntries().isEmpty());
                            // Enable wardrobe and editor buttons now that scan is complete
                            btnModeWardrobe.setEnabled(true);
                            btnModeWardrobe.setTextColor(0xFF4A5061);
                            btnModeEditor.setEnabled(true);
                            btnModeEditor.setTextColor(0xFF4A5061);
                            btnModeAudio.setEnabled(true);
                            btnModeAudio.setTextColor(0xFF4A5061);
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

        // Track entry type for export
        currentEntryType = entry.fileType != null ? entry.fileType : "mesh";

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

        // Check if this is an animpack
        if ("animpack".equals(entry.fileType)) {
            loadAnimPack(entry, apkPath, loadId);
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
                    if (entry.name != null && entry.name.toLowerCase().contains("beamo")) {
                        debugLog("Beamo mesh, skipping texture");
                        textureBytes = null;
                    } else {
                    byte[] tempTextureBytes = null;
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
                        tempTextureBytes = texturePath != null ? SkyAssetScanner.readApkEntry(apkPath, texturePath) : null;
                    } catch (Exception e) {
                        debugLog("Texture resolution failed: " + e.getMessage());
                        tempTextureBytes = null;
                    }
                    textureBytes = tempTextureBytes;
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
                    final int boneWeightCount = meshData.boneWeights != null ? meshData.boneWeights.size() : 0;
                    final boolean hasBoneWeights = boneWeightCount > 0;
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
                                "Weighted Vertices: " + boneWeightCount + "\n" +
                                "Embedded Skeleton Bones: " + skeletonCount + "\n" +
                                "Scale: " + scale + "\n" +
                                "Material: " + materialSummary + "\n"
                            );
                            btnExport.setEnabled(true);

                            // Show animation controls only if mesh has bone weights
                            if (hasBoneWeights) {
                                btnSelectAnim.setVisibility(View.VISIBLE);
                                btnAnimFilter.setVisibility(View.VISIBLE);
                                if (animNameOverlay.getVisibility() != View.VISIBLE) {
                                    animNameOverlay.setVisibility(View.VISIBLE);
                                }
                                animNameOverlay.setText("无动画");
                            } else {
                                btnSelectAnim.setVisibility(View.GONE);
                                btnPlayPause.setVisibility(View.GONE);
                                btnAnimFilter.setVisibility(View.GONE);
                                animNameOverlay.setVisibility(View.GONE);
                                animSeekBar.setVisibility(View.GONE);
                                animTimeText.setVisibility(View.GONE);
                                meshViewport.setContinuousRender(false);
                            }
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
                } catch (final OutOfMemoryError oom) {
                    System.gc();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("内存不足");
                        }
                    });
                }
            }
        }).start();
    }

    // ===== Wardrobe mode methods =====

    private void switchMode(boolean toWardrobe) {
        if (wardrobeMode == toWardrobe && !editorMode) return;
        // Cancel all in-flight background loading threads
        currentLoadId++;
        editorMode = false;
        wardrobeMode = toWardrobe;
        // Clear editor viewport when leaving editor mode
        if (editorViewport != null) {
            editorViewport.queueEvent(new Runnable() {
                @Override
                public void run() {
                    editorViewport.getMeshRenderer().clearAnimation();
                    editorViewport.getMeshRenderer().clearMeshInstances();
                    editorViewport.requestRender();
                }
            });
        }

        if (toWardrobe) {
            btnModeVisual.setBackgroundResource(R.drawable.btn_secondary_bg);
            btnModeVisual.setTextColor(0xFF4A5061);
            btnModeWardrobe.setBackgroundResource(R.drawable.btn_primary_bg);
            btnModeWardrobe.setTextColor(0xFFFFFFFF);
            btnModeEditor.setBackgroundResource(R.drawable.btn_secondary_bg);
            btnModeEditor.setTextColor(0xFF4A5061);
            btnModeAudio.setBackgroundResource(R.drawable.btn_secondary_bg);
            btnModeAudio.setTextColor(0xFF4A5061);
            audioPanel.setVisibility(View.GONE);
            wardrobeCategoryScroll.setVisibility(View.VISIBLE);
            btnClearWardrobe.setVisibility(View.VISIBLE);
            btnExport.setVisibility(View.GONE);
            btnBatchExport.setVisibility(View.GONE);
            btnToggleBeamo.setVisibility(View.GONE);
            editorPanel.setVisibility(View.GONE);
            audioPanel.setVisibility(View.GONE);
            findViewById(R.id.mainViewportContainer).setVisibility(View.VISIBLE);
            findViewById(R.id.bottomAssetBrowser).setVisibility(View.VISIBLE);
            viewModeGroup.setVisibility(View.GONE);
            btnSelectAnim.setVisibility(View.VISIBLE);
            listMesh.setVisibility(View.GONE);
            gridWardrobe.setVisibility(View.VISIBLE);
            listKtx.setVisibility(View.GONE);
            listLevel.setVisibility(View.GONE);
            findViewById(R.id.divider1).setVisibility(View.GONE);
            findViewById(R.id.divider2).setVisibility(View.GONE);
            // Load OutfitDefs only once — reuse on subsequent switches
            final String apkPath = currentApkPath;
            if (outfitByType == null || outfitByType.isEmpty()) {
                statusText.setText("正在加载衣柜定义...");
                final int loadId = ++currentLoadId;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<MeshCatalogEntry> meshSnapshot = new ArrayList<>(meshListAdapter.getAllEntries());
                        loadOutfitDefsInBackground(apkPath, meshSnapshot);
                        if (loadId != currentLoadId) return; // Cancelled
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (loadId != currentLoadId) return; // Cancelled
                                buildWardrobeCategoryTabs();
                                if (outfitTypes.size() > 0) {
                                    selectWardrobeCategory(outfitTypes.get(0));
                                } else {
                                    statusText.setText("未找到衣柜定义文件");
                                }
                            }
                        });
                    }
                }).start();
            } else {
                // Already loaded — just rebuild tabs and select first category
                buildWardrobeCategoryTabs();
                if (outfitTypes.size() > 0) {
                    selectWardrobeCategory(outfitTypes.get(0));
                }
                statusText.setText("衣柜模式");
            }
            // Clear all 3D state from visualization mode — full isolation
            meshViewport.queueEvent(new Runnable() {
                @Override
                public void run() {
                    meshViewport.getMeshRenderer().clearAnimation();
                    meshViewport.getMeshRenderer().clearMeshInstances();
                    meshViewport.requestRender();
                }
            });
            meshViewport.setContinuousRender(false);
            animNameOverlay.setText("");
            if (animSeekBarUpdater != null) {
                handler.removeCallbacks(animSeekBarUpdater);
                animSeekBarUpdater = null;
            }
            meshListAdapter.filter("");
        } else {
            // Visual mode
            btnModeVisual.setBackgroundResource(R.drawable.btn_primary_bg);
            btnModeVisual.setTextColor(0xFFFFFFFF);
            btnModeWardrobe.setBackgroundResource(R.drawable.btn_secondary_bg);
            btnModeWardrobe.setTextColor(0xFF4A5061);
            btnModeEditor.setBackgroundResource(R.drawable.btn_secondary_bg);
            btnModeEditor.setTextColor(0xFF4A5061);
            btnModeAudio.setBackgroundResource(R.drawable.btn_secondary_bg);
            btnModeAudio.setTextColor(0xFF4A5061);
            audioPanel.setVisibility(View.GONE);
            wardrobeCategoryScroll.setVisibility(View.GONE);
            btnClearWardrobe.setVisibility(View.GONE);
            btnExport.setVisibility(View.VISIBLE);
            btnBatchExport.setVisibility(View.VISIBLE);
            btnToggleBeamo.setVisibility(View.VISIBLE);
            viewModeGroup.setVisibility(View.VISIBLE);
            btnSelectAnim.setVisibility(View.GONE);
            btnPlayPause.setVisibility(View.GONE);
            btnAnimFilter.setVisibility(View.GONE);
            animSeekBar.setVisibility(View.GONE);
            animTimeText.setVisibility(View.GONE);
            listMesh.setVisibility(View.VISIBLE);
            gridWardrobe.setVisibility(View.GONE);
            listKtx.setVisibility(View.VISIBLE);
            editorPanel.setVisibility(View.GONE);
            listLevel.setVisibility(View.VISIBLE);
            findViewById(R.id.divider1).setVisibility(View.VISIBLE);
            findViewById(R.id.divider2).setVisibility(View.VISIBLE);
            findViewById(R.id.searchBox).setVisibility(View.VISIBLE);
            findViewById(R.id.mainViewportContainer).setVisibility(View.VISIBLE);
            findViewById(R.id.bottomAssetBrowser).setVisibility(View.VISIBLE);
            meshListAdapter.filter("");
            wardrobeMeshData.clear();
            wardrobeMeshScale.clear();
            wardrobeTextureBytes.clear();
            wardrobeOutfitSelections.clear();
            wardrobeSelectedCategory = null;
            wardrobeAnim = null;
            wardrobeAnimEntry = null;
            meshViewport.setContinuousRender(false);
            animNameOverlay.setText("");
            if (animSeekBarUpdater != null) {
                handler.removeCallbacks(animSeekBarUpdater);
                animSeekBarUpdater = null;
            }
            meshViewport.queueEvent(new Runnable() {
                @Override
                public void run() {
                    meshViewport.getMeshRenderer().clearAnimation();
                    meshViewport.getMeshRenderer().clearMeshInstances();
                    meshViewport.requestRender();
                }
            });
        }
    }

    private void switchToAudioMode() {
        currentLoadId++;
        editorMode = false;
        wardrobeMode = false;

        // Update button styles
        btnModeVisual.setBackgroundResource(R.drawable.btn_secondary_bg);
        btnModeVisual.setTextColor(0xFF4A5061);
        btnModeWardrobe.setBackgroundResource(R.drawable.btn_secondary_bg);
        btnModeWardrobe.setTextColor(0xFF4A5061);
        btnModeEditor.setBackgroundResource(R.drawable.btn_secondary_bg);
        btnModeEditor.setTextColor(0xFF4A5061);
        btnModeAudio.setBackgroundResource(R.drawable.btn_primary_bg);
        btnModeAudio.setTextColor(0xFFFFFFFF);

        // Hide other panels
        wardrobeCategoryScroll.setVisibility(View.GONE);
        btnClearWardrobe.setVisibility(View.GONE);
        btnExport.setVisibility(View.GONE);
        btnBatchExport.setVisibility(View.GONE);
        btnToggleBeamo.setVisibility(View.GONE);
        viewModeGroup.setVisibility(View.GONE);
        btnSelectAnim.setVisibility(View.GONE);
        btnPlayPause.setVisibility(View.GONE);
        btnAnimFilter.setVisibility(View.GONE);
        animSeekBar.setVisibility(View.GONE);
        animTimeText.setVisibility(View.GONE);
        listMesh.setVisibility(View.GONE);
        gridWardrobe.setVisibility(View.GONE);
        listKtx.setVisibility(View.GONE);
        listLevel.setVisibility(View.GONE);
        findViewById(R.id.divider1).setVisibility(View.GONE);
        findViewById(R.id.divider2).setVisibility(View.GONE);
        findViewById(R.id.searchBox).setVisibility(View.GONE);
        findViewById(R.id.mainViewportContainer).setVisibility(View.GONE);
        findViewById(R.id.bottomAssetBrowser).setVisibility(View.GONE);
        editorPanel.setVisibility(View.GONE);

        // Show audio panel
        audioPanel.setVisibility(View.VISIBLE);

        // Clear 3D state
        meshViewport.queueEvent(new Runnable() {
            @Override
            public void run() {
                meshViewport.getMeshRenderer().clearAnimation();
                meshViewport.getMeshRenderer().clearMeshInstances();
                meshViewport.requestRender();
            }
        });
        meshViewport.setContinuousRender(false);

        // Scan for .bank files in APK
        scanAudioBanks();
    }

    private void scanAudioBanks() {
        audioBankFiles.clear();
        audioEntries.clear();
        if (audioZipCache != null) {
            try { audioZipCache.close(); } catch (Exception e) {}
            audioZipCache = null;
        }
        if (currentApkPath == null) {
            audioInfoLabel.setText("请先加载APK");
            return;
        }
        audioInfoLabel.setText("正在扫描bank文件...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    audioZipCache = new java.util.zip.ZipFile(currentApkPath);
                    // Collect bank files with sizes
                    final List<String[]> bankInfo = new ArrayList<>(); // {path, displayName}
                    long maxSize = 0;
                    int maxIdx = -1;
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = audioZipCache.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry ze = en.nextElement();
                        String name = ze.getName();
                        if (!ze.isDirectory() && name.endsWith(".bank") && name.contains("Audio")) {
                            long size = ze.getSize();
                            String fname = name.substring(name.lastIndexOf('/') + 1);
                            // Show name + size in spinner
                            String display = fname + " (" + formatSize(size) + ")";
                            bankInfo.add(new String[]{name, display});
                            if (size > maxSize) {
                                maxSize = size;
                                maxIdx = bankInfo.size() - 1;
                            }
                        }
                    }
                    // Sort by size descending (largest first)
                    final List<String> paths = new ArrayList<>();
                    final List<String> displays = new ArrayList<>();
                    // Simple sort: put largest first, then the rest
                    if (maxIdx >= 0) {
                        paths.add(bankInfo.get(maxIdx)[0]);
                        displays.add(bankInfo.get(maxIdx)[1]);
                    }
                    for (int i = 0; i < bankInfo.size(); i++) {
                        if (i != maxIdx) {
                            paths.add(bankInfo.get(i)[0]);
                            displays.add(bankInfo.get(i)[1]);
                        }
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            audioBankFiles = paths;
                            ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this,
                                android.R.layout.simple_spinner_dropdown_item,
                                displays.isEmpty() ? java.util.Collections.singletonList("(无bank文件)") : displays);
                            audioBankSpinner.setAdapter(adapter);
                            if (paths.isEmpty()) {
                                audioInfoLabel.setText("未找到bank文件");
                            } else {
                                audioInfoLabel.setText("找到 " + paths.size() + " 个bank文件，已自动选择最大的文件，点击「解析」");
                                // Auto-select first (largest) bank
                                audioBankSpinner.setSelection(0);
                            }
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() { audioInfoLabel.setText("扫描失败: " + e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private byte[] readBankFromCache(String entryName) {
        if (audioZipCache == null) return null;
        try {
            java.util.zip.ZipEntry entry = audioZipCache.getEntry(entryName);
            if (entry == null) return null;
            java.io.InputStream input = audioZipCache.getInputStream(entry);
            int size = (int) entry.getSize();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(size > 0 ? size : 8192);
            byte[] buf = new byte[16384];
            int n;
            while ((n = input.read(buf)) > 0) baos.write(buf, 0, n);
            input.close();
            return baos.toByteArray();
        } catch (Exception e) { return null; }
    }

    private void parseSelectedBank() {
        int pos = audioBankSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= audioBankFiles.size()) {
            Toast.makeText(this, "请先选择bank文件", Toast.LENGTH_SHORT).show();
            return;
        }
        final String bankPath = audioBankFiles.get(pos);
        final String bankName = bankPath.substring(bankPath.lastIndexOf('/') + 1);

        audioProgressLayout.setVisibility(View.VISIBLE);
        audioProgressBar.setProgress(0);
        audioProgressText.setText("正在读取 " + bankName + " ...");
        // Reset playback state to prevent crashes when switching banks
        audioCurrentIndex = -1;
        stopSeekBarUpdater();
        if (audioMediaPlayer != null) {
            try { audioMediaPlayer.release(); } catch (Exception ignored) {}
            audioMediaPlayer = null;
        }
        audioEntries.clear();
        audioInfoLabel.setText("解析中...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    updateAudioProgress(10, "正在读取文件数据...");
                    byte[] data = readBankFromCache(bankPath);
                    if (data == null) {
                        handler.post(new Runnable() {
                            @Override public void run() {
                                audioProgressLayout.setVisibility(View.GONE);
                                audioInfoLabel.setText("读取失败");
                            }
                        });
                        return;
                    }

                    updateAudioProgress(20, "正在读取字符串文件...");
                    // Try multiple .strings.bank naming patterns
                    String stringsPath = bankPath.replace(".bank", ".strings.bank");
                    byte[] stringsData = readBankFromCache(stringsPath);
                    if (stringsData == null) {
                        // Try without .assets prefix
                        String altPath = bankPath.replace(".assets.bank", ".strings.bank");
                        stringsData = readBankFromCache(altPath);
                    }

                    updateAudioProgress(30, "正在解析RIFF结构...");
                    final com.sky.modelviewer.parsing.FmodBankParser parser =
                        new com.sky.modelviewer.parsing.FmodBankParser();
                    parser.parse(data, bankName);

                    // Parse .strings.bank separately - it contains event path names
                    final List<String> eventNames = new ArrayList<>();
                    if (stringsData != null) {
                        updateAudioProgress(50, "正在合并字符串数据...");
                        com.sky.modelviewer.parsing.FmodBankParser strParser =
                            new com.sky.modelviewer.parsing.FmodBankParser();
                        strParser.parse(stringsData, bankName + ".strings");
                        if (!strParser.getStrings().isEmpty()) {
                            parser.mergeStrings(strParser.getStrings());
                            // Collect event-like strings (contain / or are meaningful names)
                            for (com.sky.modelviewer.parsing.FmodBankParser.StringEntry se : strParser.getStrings()) {
                                if (se.text.contains("/") || se.text.contains("event:") || se.text.length() > 6) {
                                    eventNames.add(se.text);
                                }
                            }
                        }
                    }

                    updateAudioProgress(70, "正在提取音频条目...");
                    audioEntries.clear();
                    List<com.sky.modelviewer.parsing.FmodBankParser.StringEntry> strings = parser.getStrings();

                    // Add events with names from .strings.bank
                    int nameIdx = 0;
                    for (com.sky.modelviewer.parsing.FmodBankParser.EventEntry e : parser.getEvents()) {
                        String name = "(unnamed)";
                        if (nameIdx < eventNames.size()) {
                            name = eventNames.get(nameIdx);
                            nameIdx++;
                        }
                        audioEntries.add(new AudioEntry(name, e.guid, "EVNT", 0, null));
                    }

                    // Add audio modules
                    int strIdx = 0;
                    for (com.sky.modelviewer.parsing.FmodBankParser.AudioSampleEntry s : parser.getSamples()) {
                        String name = "(unnamed)";
                        if (strIdx < strings.size()) {
                            // Find next meaningful string
                            while (strIdx < strings.size()) {
                                String candidate = strings.get(strIdx).text;
                                strIdx++;
                                if (candidate.length() > 5 && !candidate.equals(candidate.toUpperCase())) {
                                    name = candidate;
                                    break;
                                }
                            }
                        }
                        audioEntries.add(new AudioEntry(name, s.guid, s.type, s.size, null));
                    }

                    // If no structured entries, add meaningful strings
                    if (audioEntries.isEmpty() && !strings.isEmpty()) {
                        for (com.sky.modelviewer.parsing.FmodBankParser.StringEntry s : strings) {
                            // Skip short all-uppercase strings (likely chunk IDs)
                            if (s.text.length() >= 5 && !s.text.equals(s.text.toUpperCase())) {
                                audioEntries.add(new AudioEntry(s.text, "", "STR", 0, null));
                            }
                        }
                        // If still empty, add all strings
                        if (audioEntries.isEmpty()) {
                            for (com.sky.modelviewer.parsing.FmodBankParser.StringEntry s : strings) {
                                audioEntries.add(new AudioEntry(s.text, "", "STR", 0, null));
                            }
                        }
                    }

                    updateAudioProgress(85, "正在搜索音频数据...");
                    List<Integer> fsb5Offsets = findAllFsb5(data);
                    if (!fsb5Offsets.isEmpty()) {
                        for (int fi = 0; fi < fsb5Offsets.size(); fi++) {
                            updateAudioProgress(88 + fi, "正在解析FSB5#" + fi + "音频...");
                            extractFsb5Samples(data, fsb5Offsets.get(fi), bankName, fi);
                        }
                    }

                    final int dataSize = data.length;
                    final int strSize = stringsData != null ? stringsData.length : 0;
                    final int entryCount = audioEntries.size();
                    final boolean hasFsb5 = !fsb5Offsets.isEmpty();
                    final int fsb5Count = fsb5Offsets.size();

                    updateAudioProgress(100, "完成!");

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            audioBank = parser;
                            audioProgressLayout.setVisibility(View.GONE);
                            StringBuilder info = new StringBuilder();
                            info.append(bankName).append(" | ").append(formatSize(dataSize));
                            if (strSize > 0) info.append(" +strings:").append(formatSize(strSize));
                            info.append(" | 解析出 ").append(entryCount).append(" 个条目");
                            if (hasFsb5) {
                                info.append(" (").append(fsb5Count).append("个FSB5音频块)");
                            } else if (dataSize < 10000) {
                                info.append("\n此文件太小，可能只是索引。请选择更大的bank文件！");
                            } else {
                                info.append("\n此bank为元数据文件，音频数据在其他bank文件中");
                            }
                            audioInfoLabel.setText(info.toString());
                            refreshAudioList();
                        }
                    });
                } catch (final Throwable e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            audioProgressLayout.setVisibility(View.GONE);
                            audioInfoLabel.setText("解析失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void updateAudioProgress(final int percent, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                audioProgressBar.setProgress(percent);
                audioProgressText.setText(msg + " (" + percent + "%)");
            }
        });
    }

    // FSB5 sound format modes
    private static final int FSB_MODE_PCM8 = 1;
    private static final int FSB_MODE_PCM16 = 2;
    private static final int FSB_MODE_PCM24 = 3;
    private static final int FSB_MODE_PCM32 = 4;
    private static final int FSB_MODE_PCMFLOAT = 5;
    private static final int FSB_MODE_GCADPCM = 6;
    private static final int FSB_MODE_IMAADPCM = 7;
    private static final int FSB_MODE_VAG = 8;
    private static final int FSB_MODE_HEVAG = 9;
    private static final int FSB_MODE_XMA = 10;
    private static final int FSB_MODE_MPEG = 11;
    private static final int FSB_MODE_CELT = 12;
    private static final int FSB_MODE_AT9 = 13;
    private static final int FSB_MODE_FADPCM = 14;
    private static final int FSB_MODE_VORBIS = 15;
    private static final int FSB_MODE_FADPCM2 = 16; // FMOD Studio 2.x FADPCM (140-byte frames)

    private String fsbModeName(int mode) {
        switch (mode) {
            case FSB_MODE_PCM8: return "PCM8";
            case FSB_MODE_PCM16: return "PCM16";
            case FSB_MODE_PCM24: return "PCM24";
            case FSB_MODE_PCM32: return "PCM32";
            case FSB_MODE_PCMFLOAT: return "PCM_FLOAT";
            case FSB_MODE_GCADPCM: return "GCADPCM";
            case FSB_MODE_IMAADPCM: return "IMAADPCM";
            case FSB_MODE_VAG: return "VAG";
            case FSB_MODE_HEVAG: return "HEVAG";
            case FSB_MODE_XMA: return "XMA";
            case FSB_MODE_MPEG: return "MPEG";
            case FSB_MODE_CELT: return "CELT";
            case FSB_MODE_AT9: return "AT9";
            case FSB_MODE_FADPCM: return "FADPCM";
            case FSB_MODE_VORBIS: return "VORBIS";
            case FSB_MODE_FADPCM2: return "FADPCM"; // mode 16 = FMOD ADPCM (140-byte frames)
            default: return "MODE_" + mode;
        }
    }

    /** Find all FSB5 block offsets in the data (a bank can contain multiple FSB5 blocks). */
    private List<Integer> findAllFsb5(byte[] data) {
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i <= data.length - 4; i++) {
            if (data[i] == 'F' && data[i+1] == 'S' && data[i+2] == 'B' && data[i+3] == '5') {
                offsets.add(i);
            }
        }
        return offsets;
    }

    /**
     * Parse FSB5 sample data using the correct format from python-fsb5.
     *
     * FSB5 layout: [header 60/64 bytes][sample headers][name table][sample data]
     *
     * Header: magic(4s) version(u32) numSamples(u32) sampleHeadersSize(u32)
     *         nameTableSize(u32) dataSize(u32) mode(u32) zero(8s) hash(16s) dummy(8s) = 60 bytes
     *         (version==0 adds 4 more bytes → 64)
     *
     * Each sample header = 8-byte uint64 bitfield:
     *   bit 0     : next_chunk   (1 bit)  — metadata chunks follow
     *   bits 1-4  : frequency    (4 bits) — index into freq table
     *   bit 5     : channels     (1 bit)  — channels = bit + 1
     *   bits 6-33 : dataOffset   (28 bits)— × 16 = byte offset into data region
     *   bits 34-63: samples      (30 bits)— number of audio samples
     *
     * Metadata chunk (uint32) if next_chunk==1:
     *   bit 0     : next_chunk   (1 bit)
     *   bits 1-24 : chunk_size   (24 bits)
     *   bits 25-31: chunk_type   (7 bits)
     *   Types: CHANNELS(1) FREQUENCY(2) LOOP(3) XMASEEK(6) DSPCOEFF(7) XWMADATA(10) VORBISDATA(11)
     *
     * Name table: numSamples × uint32 offsets (relative to nameTableStart),
     *   followed by null-terminated UTF-8 strings.
     *
     * Audio data: sample i spans [dataOffset×16, nextSample.dataOffset×16),
     *   last sample spans [dataOffset×16, dataSize).
     */
    private void extractFsb5Samples(byte[] data, int offset, String bankName, int fsbIndex) {
        try {
            if (offset + 60 > data.length) return;

            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data, offset, data.length - offset)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);

            // Header
            bb.getInt();             // magic "FSB5"
            int version = bb.getInt();
            int numSamples = bb.getInt();
            int sampleHeadersSize = bb.getInt();
            int nameTableSize = bb.getInt();
            int dataSize = bb.getInt();
            int mode = bb.getInt();
            // Skip zero(8) + hash(16) + dummy(8) = 32 bytes
            bb.position(bb.position() + 32);

            // headerEnd: 60 bytes, or 64 for version 0
            int headerSize = (version == 0) ? 64 : 60;
            int headerEnd = offset + headerSize;
            int sampleHeadersStart = headerEnd;
            int nameTableStart = sampleHeadersStart + sampleHeadersSize;
            int sampleDataStart = nameTableStart + nameTableSize;

            // Validate header to filter out false "FSB5" matches inside audio data
            if (version < 0 || version > 256) return;  // known versions: 0, 1
            if (mode < 0 || mode > 32) return;          // known modes: 1-16
            if (numSamples <= 0 || numSamples > 100000) return;
            if (sampleHeadersSize < 0 || sampleHeadersSize > data.length) return;
            if (nameTableSize < 0 || nameTableSize > data.length) return;
            if (dataSize < 0 || dataSize > data.length) return;
            if (sampleHeadersStart + sampleHeadersSize > data.length) return;

            // Frequency lookup table (index → Hz)
            int[] freqMap = {0, 8000, 11000, 11025, 16000, 22050, 24000, 32000, 44100, 48000};

            String fmtName = fsbModeName(mode);

            // ── Parse name table ──
            // Structure: numSamples × uint32 offsets (relative to nameTableStart),
            //            then null-terminated UTF-8 strings
            String[] names = new String[numSamples];
            if (nameTableSize > 0) {
                int[] nameOffsets = new int[numSamples];
                java.nio.ByteBuffer ntBuf = java.nio.ByteBuffer.wrap(data, nameTableStart,
                        Math.min(nameTableSize, data.length - nameTableStart))
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < numSamples && ntBuf.remaining() >= 4; i++) {
                    nameOffsets[i] = ntBuf.getInt();
                }
                for (int i = 0; i < numSamples; i++) {
                    int namePos = nameTableStart + nameOffsets[i];
                    if (namePos >= 0 && namePos < data.length) {
                        StringBuilder sb = new StringBuilder();
                        while (namePos < data.length && data[namePos] != 0) {
                            sb.append((char)(data[namePos] & 0xFF));
                            namePos++;
                        }
                        names[i] = sb.toString();
                    }
                    if (names[i] == null || names[i].isEmpty()) {
                        names[i] = String.format("%04d", i);
                    }
                }
            } else {
                for (int i = 0; i < numSamples; i++) names[i] = String.format("%04d", i);
            }

            // ── Parse sample headers ──
            // Store per-sample info for data extraction
            int[] sampleFreqs = new int[numSamples];
            int[] sampleChans = new int[numSamples];
            long[] sampleDataOffsets = new long[numSamples]; // already × 16
            long[] sampleCounts = new long[numSamples]; // total PCM samples per channel
            boolean[] sampleHasVorbis = new boolean[numSamples];

            int shOff = sampleHeadersStart;
            for (int i = 0; i < numSamples && shOff + 8 <= nameTableStart; i++) {
                if (i % 50 == 0) {
                    final int pct = 90 + (i * 10 / Math.max(1, numSamples));
                    final int fi = i;
                    final int fn = numSamples;
                    final int fbi = fsbIndex;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            audioProgressBar.setProgress(pct);
                            audioProgressText.setText("FSB5#" + fbi + " 样本 " + fi + "/" + fn + " (" + pct + "%)");
                        }
                    });
                }

                // Read 8-byte little-endian uint64
                long hdr = ((long)data[shOff] & 0xFF)
                         | ((long)(data[shOff+1] & 0xFF) << 8)
                         | ((long)(data[shOff+2] & 0xFF) << 16)
                         | ((long)(data[shOff+3] & 0xFF) << 24)
                         | ((long)(data[shOff+4] & 0xFF) << 32)
                         | ((long)(data[shOff+5] & 0xFF) << 40)
                         | ((long)(data[shOff+6] & 0xFF) << 48)
                         | ((long)(data[shOff+7] & 0xFF) << 56);

                boolean nextChunk = (hdr & 1L) != 0;
                int freqIdx = (int)((hdr >>> 1) & 0xF);
                int channels = (int)((hdr >>> 5) & 1) + 1;
                long dataOffset = (hdr >>> 6) & 0xFFFFFFF;        // 28 bits
                long samples = (hdr >>> 34) & 0x3FFFFFFF;          // 30 bits

                int freq = (freqIdx > 0 && freqIdx < freqMap.length) ? freqMap[freqIdx] : 44100;

                shOff += 8;

                // Parse metadata chunks
                while (nextChunk && shOff + 4 <= nameTableStart) {
                    long chunkDesc = ((long)data[shOff] & 0xFF)
                                   | ((long)(data[shOff+1] & 0xFF) << 8)
                                   | ((long)(data[shOff+2] & 0xFF) << 16)
                                   | ((long)(data[shOff+3] & 0xFF) << 24);

                    boolean nextChunk2 = (chunkDesc & 1L) != 0;
                    int chunkSize = (int)((chunkDesc >>> 1) & 0xFFFFFF);   // 24 bits
                    int chunkType = (int)((chunkDesc >>> 25) & 0x7F);      // 7 bits

                    shOff += 4;

                    // Process known chunk types
                    if (chunkType == 1 && chunkSize >= 1 && shOff < data.length) {
                        // CHANNELS override
                        channels = data[shOff] & 0xFF;
                        if (channels == 0) channels = 1;
                    } else if (chunkType == 2 && chunkSize >= 4 && shOff + 4 <= data.length) {
                        // FREQUENCY override (exact Hz)
                        freq = (data[shOff] & 0xFF)
                             | ((data[shOff+1] & 0xFF) << 8)
                             | ((data[shOff+2] & 0xFF) << 16)
                             | ((data[shOff+3] & 0xFF) << 24);
                    } else if (chunkType == 11) {
                        // VORBISDATA — marks this sample as FMOD Vorbis
                        sampleHasVorbis[i] = true;
                    }

                    shOff += chunkSize;
                    nextChunk = nextChunk2;
                }

                sampleFreqs[i] = freq;
                sampleChans[i] = channels;
                sampleDataOffsets[i] = dataOffset * 16;
                sampleCounts[i] = samples;
            }

            // ── Extract audio data for each sample ──
            for (int i = 0; i < numSamples; i++) {
                int dataStart = sampleDataStart + (int)sampleDataOffsets[i];
                int dataEnd;
                if (i < numSamples - 1) {
                    dataEnd = sampleDataStart + (int)sampleDataOffsets[i + 1];
                } else {
                    dataEnd = sampleDataStart + dataSize;
                }
                int dataLen = dataEnd - dataStart;
                if (dataLen < 0) dataLen = 0;
                if (dataStart < 0 || dataStart + dataLen > data.length) dataLen = 0;

                String name = names[i];
                String label = name + " [" + sampleFreqs[i] + "Hz " + sampleChans[i] + "ch " + fmtName + "]";

                byte[] audioData = null;
                if (dataLen > 0) {
                    audioData = new byte[dataLen];
                    System.arraycopy(data, dataStart, audioData, 0, dataLen);
                }

                // Use format string for playback logic
                String playFmt = sampleHasVorbis[i] ? "VORBIS" : fmtName;

                audioEntries.add(new AudioEntry(
                    label, "", "FSB5", dataLen, audioData,
                    sampleFreqs[i], sampleChans[i], playFmt, sampleCounts[i]));
            }
        } catch (Exception e) {
            android.util.Log.w("Fsb5Parse", "FSB5 parse error: " + e.getMessage());
        }
    }

    private void refreshAudioList() {
        List<String> items = new ArrayList<>();
        String query = audioSearch.getText().toString().toLowerCase().trim();

        if (audioEntries.isEmpty()) {
            items.add("(无数据 - 请选择bank文件并点击解析)");
        } else {
            for (int i = 0; i < audioEntries.size(); i++) {
                AudioEntry e = audioEntries.get(i);
                // Only show entries with actual audio data
                if (e.data == null || e.data.length == 0) continue;
                if (!query.isEmpty() && !e.name.toLowerCase().contains(query) && !e.guid.toLowerCase().contains(query))
                    continue;
                String icon = "[▶]";
                String label = icon + " " + e.name;
                if (e.size > 0) label += " (" + formatSize(e.size) + ")";
                if (e.format != null && !e.format.isEmpty()) label += " " + e.format;
                items.add(label);
            }
        }

        if (items.isEmpty()) items.add("(无可播放的音频 - 请选择含FSB5数据的bank)");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, items);
        audioList.setAdapter(adapter);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }

    // ===== Audio Player =====

    private void playAudioEntry(int position) {
        String query = audioSearch.getText().toString().toLowerCase().trim();
        int entryIdx = -1;
        int matchCount = 0;
        for (int i = 0; i < audioEntries.size(); i++) {
            AudioEntry e = audioEntries.get(i);
            // Only count entries with actual audio data (matching what's shown in the list)
            if (e.data == null || e.data.length == 0) continue;
            if (!query.isEmpty() && !e.name.toLowerCase().contains(query) && !e.guid.toLowerCase().contains(query))
                continue;
            if (matchCount == position) { entryIdx = i; break; }
            matchCount++;
        }
        if (entryIdx < 0) return;
        final AudioEntry entry = audioEntries.get(entryIdx);
        audioCurrentIndex = entryIdx;
        playAudioData(entry);
    }

    /** Lazily load the Vorbis setup header from raw resources. */
    private byte[] getVorbisSetupHeader() {
        if (vorbisSetupHeader != null) return vorbisSetupHeader;
        try {
            java.io.InputStream is = getResources().openRawResource(R.raw.vorbis_setup_d7913109);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            is.close();
            vorbisSetupHeader = baos.toByteArray();
            Log.i(TAG, "Loaded Vorbis setup header: " + vorbisSetupHeader.length + " bytes");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load vorbis setup header", e);
        }
        return vorbisSetupHeader;
    }

    private void playAudioData(final AudioEntry entry) {
        audioNowPlaying.setText("加载中: " + entry.name);
        stopSeekBarUpdater();
        if (audioMediaPlayer != null) {
            try { audioMediaPlayer.stop(); } catch (Exception ignored) {}
            try { audioMediaPlayer.release(); } catch (Exception ignored) {}
            audioMediaPlayer = null;
        }
        if (entry.data == null || entry.data.length == 0) {
            audioNowPlaying.setText("无音频数据");
            return;
        }

        // Check format for playback strategy
        final String fmt = entry.format != null ? entry.format : "";
        final boolean isVorbis = "VORBIS".equals(fmt);
        final boolean isFadpcm = "FADPCM".equals(fmt);
        final boolean isMpeg = "MPEG".equals(fmt);
        final boolean isPcm16 = "PCM16".equals(fmt);
        final boolean isPcm8 = "PCM8".equals(fmt);
        final boolean isPcmFloat = "PCM_FLOAT".equals(fmt);

        if (!isVorbis && !isFadpcm && !isMpeg && !isPcm16 && !isPcm8 && !isPcmFloat) {
            audioNowPlaying.setText("⚠ " + fmt + " 格式暂不支持播放\n" + entry.name);
            return;
        }

        // Increment generation to invalidate any stale threads
        final int myGeneration = ++audioPlayGeneration;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final java.io.File tempFile = new java.io.File(getCacheDir(),
                        "audio_" + System.currentTimeMillis() +
                        (isVorbis ? ".ogg" : (isMpeg ? ".mp3" : ".wav")));
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);

                    if (isVorbis) {
                        byte[] setupHeader = getVorbisSetupHeader();
                        if (setupHeader == null) {
                            fos.close();
                            if (myGeneration == audioPlayGeneration) {
                                handler.post(new Runnable() {
                                    @Override public void run() {
                                        if (myGeneration == audioPlayGeneration)
                                            audioNowPlaying.setText("⚠ 无法加载Vorbis解码配置\n" + entry.name);
                                    }
                                });
                            }
                            return;
                        }
                        int channels = entry.channels > 0 ? entry.channels : 1;
                        int sampleRate = entry.freq > 0 ? entry.freq : 44100;
                        byte[] oggData = FmodVorbisDecoder.rebuildOgg(
                            entry.data, channels, sampleRate, setupHeader, entry.samples);
                        if (oggData == null) {
                            fos.close();
                            if (myGeneration == audioPlayGeneration) {
                                handler.post(new Runnable() {
                                    @Override public void run() {
                                        if (myGeneration == audioPlayGeneration)
                                            audioNowPlaying.setText("⚠ OGG重建失败\n" + entry.name);
                                    }
                                });
                            }
                            return;
                        }
                        fos.write(oggData);
                    } else if (isFadpcm) {
                        int channels = entry.channels > 0 ? entry.channels : 1;
                        int sampleRate = entry.freq > 0 ? entry.freq : 44100;
                        byte[] pcmBytes = FadpcmDecoder.decode(entry.data, channels);
                        if (pcmBytes == null) {
                            fos.close();
                            if (myGeneration == audioPlayGeneration) {
                                handler.post(new Runnable() {
                                    @Override public void run() {
                                        if (myGeneration == audioPlayGeneration)
                                            audioNowPlaying.setText("⚠ FADPCM解码失败\n" + entry.name);
                                    }
                                });
                            }
                            return;
                        }
                        byte[] wavHeader = buildWavHeader(pcmBytes.length, sampleRate, channels, 16, 1);
                        fos.write(wavHeader);
                        fos.write(pcmBytes);
                    } else if (isMpeg) {
                        fos.write(entry.data);
                    } else {
                        int bitsPerSample = isPcm16 ? 16 : (isPcm8 ? 8 : 32);
                        int audioFormat = isPcmFloat ? 3 : 1;
                        int channels = entry.channels > 0 ? entry.channels : 1;
                        int sampleRate = entry.freq > 0 ? entry.freq : 44100;
                        byte[] wavHeader = buildWavHeader(entry.data.length, sampleRate, channels, bitsPerSample, audioFormat);
                        fos.write(wavHeader);
                        fos.write(entry.data);
                    }
                    fos.close();

                    // Check if this thread is still current before creating MediaPlayer
                    if (myGeneration != audioPlayGeneration) {
                        tempFile.delete();
                        return;
                    }

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            // Double-check generation on UI thread
                            if (myGeneration != audioPlayGeneration) {
                                tempFile.delete();
                                return;
                            }
                            try {
                                // Release any existing player (safety check)
                                if (audioMediaPlayer != null) {
                                    try { audioMediaPlayer.release(); } catch (Exception ignored) {}
                                    audioMediaPlayer = null;
                                }

                                final android.media.MediaPlayer mp = new android.media.MediaPlayer();
                                audioMediaPlayer = mp;
                                mp.setDataSource(tempFile.getAbsolutePath());
                                mp.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                                    @Override
                                    public void onPrepared(android.media.MediaPlayer p) {
                                        if (myGeneration != audioPlayGeneration || p != mp) {
                                            try { p.release(); } catch (Exception ignored) {}
                                            return;
                                        }
                                        p.start();
                                        btnAudioPlay.setImageResource(android.R.drawable.ic_media_pause);
                                        audioNowPlaying.setText("播放中: " + entry.name);
                                        audioTotalTime.setText(formatTime(p.getDuration()));
                                        startSeekBarUpdater();
                                    }
                                });
                                mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                                    @Override
                                    public void onCompletion(android.media.MediaPlayer p) {
                                        if (p != mp) return;
                                        btnAudioPlay.setImageResource(android.R.drawable.ic_media_play);
                                        audioNowPlaying.setText("已完成: " + entry.name);
                                        stopSeekBarUpdater();
                                    }
                                });
                                mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                                    @Override
                                    public boolean onError(android.media.MediaPlayer p, int what, int extra) {
                                        if (p != mp) return true;
                                        audioNowPlaying.setText("播放错误: " + what + "/" + extra +
                                            "\n" + fmt + " 格式可能不兼容MediaPlayer");
                                        return true;
                                    }
                                });
                                mp.prepareAsync();
                            } catch (final Exception e) {
                                if (myGeneration == audioPlayGeneration)
                                    audioNowPlaying.setText("播放失败: " + e.getMessage());
                            }
                        }
                    });
                } catch (final Exception e) {
                    if (myGeneration == audioPlayGeneration) {
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (myGeneration == audioPlayGeneration)
                                    audioNowPlaying.setText("加载失败: " + e.getMessage());
                            }
                        });
                    }
                }
            }
        }).start();
    }

    /** Build a 44-byte WAV header for PCM/float audio data. */
    private static byte[] buildWavHeader(int dataLen, int sampleRate, int channels, int bitsPerSample, int audioFormat) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int chunkSize = 36 + dataLen;
        byte[] h = new byte[44];
        h[0]='R'; h[1]='I'; h[2]='F'; h[3]='F';
        h[4]=(byte)(chunkSize); h[5]=(byte)(chunkSize>>8); h[6]=(byte)(chunkSize>>16); h[7]=(byte)(chunkSize>>24);
        h[8]='W'; h[9]='A'; h[10]='V'; h[11]='E';
        h[12]='f'; h[13]='m'; h[14]='t'; h[15]=' ';
        h[16]=16; h[17]=0; h[18]=0; h[19]=0; // subchunk1 size = 16
        h[20]=(byte)audioFormat; h[21]=0;
        h[22]=(byte)channels; h[23]=0;
        h[24]=(byte)(sampleRate); h[25]=(byte)(sampleRate>>8); h[26]=(byte)(sampleRate>>16); h[27]=(byte)(sampleRate>>24);
        h[28]=(byte)(byteRate); h[29]=(byte)(byteRate>>8); h[30]=(byte)(byteRate>>16); h[31]=(byte)(byteRate>>24);
        h[32]=(byte)(blockAlign); h[33]=0;
        h[34]=(byte)(bitsPerSample); h[35]=0;
        h[36]='d'; h[37]='a'; h[38]='t'; h[39]='a';
        h[40]=(byte)(dataLen); h[41]=(byte)(dataLen>>8); h[42]=(byte)(dataLen>>16); h[43]=(byte)(dataLen>>24);
        return h;
    }

    private void toggleAudioPlay() {
        if (audioMediaPlayer == null) {
            for (AudioEntry e : audioEntries) {
                if (e.data != null && e.data.length > 0) { playAudioData(e); return; }
            }
            Toast.makeText(this, "没有可播放的音频", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (audioMediaPlayer.isPlaying()) {
                audioMediaPlayer.pause();
                btnAudioPlay.setImageResource(android.R.drawable.ic_media_play);
                audioNowPlaying.setText("已暂停");
            } else {
                audioMediaPlayer.start();
                btnAudioPlay.setImageResource(android.R.drawable.ic_media_pause);
                audioNowPlaying.setText("播放中...");
                startSeekBarUpdater();
            }
        } catch (Exception e) {
            // MediaPlayer might be in PREPARING state
            audioNowPlaying.setText("正在加载中，请稍候...");
        }
    }

    private void playAudioPrev() {
        if (audioEntries.isEmpty()) {
            Toast.makeText(this, "没有音频条目", Toast.LENGTH_SHORT).show();
            return;
        }
        // Clamp index to valid range (in case bank was switched)
        if (audioCurrentIndex < 0 || audioCurrentIndex >= audioEntries.size()) {
            audioCurrentIndex = audioEntries.size();
        }
        // Search backwards with wrap-around
        for (int i = audioCurrentIndex - 1; i >= 0; i--) {
            if (audioEntries.get(i).data != null && audioEntries.get(i).data.length > 0) {
                audioCurrentIndex = i;
                playAudioData(audioEntries.get(i));
                return;
            }
        }
        // Wrap to end: search from last entry backwards
        for (int i = audioEntries.size() - 1; i > audioCurrentIndex - 1; i--) {
            if (i >= 0 && i < audioEntries.size() &&
                audioEntries.get(i).data != null && audioEntries.get(i).data.length > 0) {
                audioCurrentIndex = i;
                playAudioData(audioEntries.get(i));
                return;
            }
        }
        Toast.makeText(this, "没有可播放的音频", Toast.LENGTH_SHORT).show();
    }

    private void playAudioNext() {
        if (audioEntries.isEmpty()) {
            Toast.makeText(this, "没有音频条目", Toast.LENGTH_SHORT).show();
            return;
        }
        // Clamp index to valid range (in case bank was switched)
        if (audioCurrentIndex < 0 || audioCurrentIndex >= audioEntries.size()) {
            audioCurrentIndex = -1;
        }
        // Search forwards with wrap-around
        for (int i = audioCurrentIndex + 1; i < audioEntries.size(); i++) {
            if (audioEntries.get(i).data != null && audioEntries.get(i).data.length > 0) {
                audioCurrentIndex = i;
                playAudioData(audioEntries.get(i));
                return;
            }
        }
        // Wrap to beginning: search from first entry forwards
        for (int i = 0; i <= audioCurrentIndex && i < audioEntries.size(); i++) {
            if (audioEntries.get(i).data != null && audioEntries.get(i).data.length > 0) {
                audioCurrentIndex = i;
                playAudioData(audioEntries.get(i));
                return;
            }
        }
        Toast.makeText(this, "没有可播放的音频", Toast.LENGTH_SHORT).show();
    }

    private void startSeekBarUpdater() {
        stopSeekBarUpdater();
        audioSeekBarUpdater = new Runnable() {
            @Override
            public void run() {
                if (audioMediaPlayer != null && !audioSeeking) {
                    try {
                        int pos = audioMediaPlayer.getCurrentPosition();
                        int dur = audioMediaPlayer.getDuration();
                        if (dur > 0) audioSeekBar.setProgress(pos * 100 / dur);
                        audioCurrentTime.setText(formatTime(pos));
                    } catch (Exception e) {}
                }
                handler.postDelayed(this, 200);
            }
        };
        handler.post(audioSeekBarUpdater);
    }

    private void stopSeekBarUpdater() {
        if (audioSeekBarUpdater != null) {
            handler.removeCallbacks(audioSeekBarUpdater);
            audioSeekBarUpdater = null;
        }
    }

    private String formatTime(int ms) {
        int s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void exportAudioJson() {
        if (audioBank == null && audioEntries.isEmpty()) {
            Toast.makeText(this, "请先解析bank文件", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        if (audioBank != null) {
            json.append("  \"bank\": \"").append(escapeJson(audioBank.getBankName())).append("\",\n");
            json.append("  \"guid\": \"").append(audioBank.getBankGuid()).append("\",\n");
            json.append("  \"version\": ").append(audioBank.getVersion()).append(",\n");
        }
        json.append("  \"entries\": [\n");
        for (int i = 0; i < audioEntries.size(); i++) {
            AudioEntry e = audioEntries.get(i);
            json.append("    {\n");
            json.append("      \"index\": ").append(i).append(",\n");
            json.append("      \"name\": \"").append(escapeJson(e.name)).append("\",\n");
            json.append("      \"guid\": \"").append(e.guid).append("\",\n");
            json.append("      \"type\": \"").append(e.type).append("\",\n");
            json.append("      \"size\": ").append(e.size).append(",\n");
            json.append("      \"hasData\": ").append(e.data != null).append("\n");
            json.append("    }");
            if (i < audioEntries.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n}\n");

        String outName = (audioBank != null ? audioBank.getBankName() : "audio") + "_entries.json";
        try {
            java.io.File outFile = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                "Download/" + outName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            fos.write(json.toString().getBytes("UTF-8"));
            fos.close();
            Toast.makeText(this, "已导出: " + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try {
                java.io.File outFile = new java.io.File(getExternalFilesDir(null), outName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                fos.write(json.toString().getBytes("UTF-8"));
                fos.close();
                Toast.makeText(this, "已导出: " + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Toast.makeText(this, "导出失败: " + e2.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Export all playable audio entries as WAV/OGG files packed into a ZIP. */
    private void exportAudioWavZip() {
        // Request storage permission if needed
        requestStoragePermission();
        // Collect playable entries
        List<AudioEntry> playable = new ArrayList<>();
        for (AudioEntry e : audioEntries) {
            if (e.data != null && e.data.length > 0) playable.add(e);
        }
        if (playable.isEmpty()) {
            Toast.makeText(this, "没有可导出的音频", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<AudioEntry> toExport = playable;
        audioProgressLayout.setVisibility(View.VISIBLE);
        audioProgressBar.setProgress(0);
        audioProgressText.setText("准备导出 " + toExport.size() + " 个音频...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                java.util.zip.ZipOutputStream zos = null;
                int successCount = 0;
                int failCount = 0;
                try {
                    String zipName = (audioBank != null ? audioBank.getBankName() : "audio") + "_wav.zip";
                    java.io.File zipFile = new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(), "Download/" + zipName);
                    java.io.File parent = zipFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(zipFile);
                    zos = new java.util.zip.ZipOutputStream(fos);

                    byte[] setupHeader = getVorbisSetupHeader();
                    java.util.Set<String> usedNames = new java.util.HashSet<>();

                    for (int i = 0; i < toExport.size(); i++) {
                        AudioEntry e = toExport.get(i);
                        final int pct = (int)((i * 100f) / toExport.size());
                        updateAudioProgress(pct, "导出 " + (i+1) + "/" + toExport.size() + ": " + e.name);

                        try {
                            String safeName = e.name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                            if (safeName.length() > 60) safeName = safeName.substring(0, 60);
                            if (safeName.isEmpty()) safeName = "audio_" + i;

                            byte[] fileData = null;
                            String ext = ".wav";

                            String fmt = e.format != null ? e.format : "";
                            int channels = e.channels > 0 ? e.channels : 1;
                            int sampleRate = e.freq > 0 ? e.freq : 44100;

                            if ("VORBIS".equals(fmt)) {
                                // Rebuild OGG (Android MediaPlayer compatible)
                                if (setupHeader != null) {
                                    fileData = FmodVorbisDecoder.rebuildOgg(
                                        e.data, channels, sampleRate, setupHeader, e.samples);
                                    ext = ".ogg";
                                }
                            } else if ("FADPCM".equals(fmt)) {
                                // Decode FADPCM to PCM, wrap in WAV
                                byte[] pcm = FadpcmDecoder.decode(e.data, channels);
                                if (pcm != null) {
                                    byte[] wavHdr = buildWavHeader(pcm.length, sampleRate, channels, 16, 1);
                                    fileData = new byte[wavHdr.length + pcm.length];
                                    System.arraycopy(wavHdr, 0, fileData, 0, wavHdr.length);
                                    System.arraycopy(pcm, 0, fileData, wavHdr.length, pcm.length);
                                    ext = ".wav";
                                }
                            } else if ("PCM16".equals(fmt) || "PCM8".equals(fmt) || "PCM_FLOAT".equals(fmt)) {
                                int bits = "PCM16".equals(fmt) ? 16 : ("PCM8".equals(fmt) ? 8 : 32);
                                int af = "PCM_FLOAT".equals(fmt) ? 3 : 1;
                                byte[] wavHdr = buildWavHeader(e.data.length, sampleRate, channels, bits, af);
                                fileData = new byte[wavHdr.length + e.data.length];
                                System.arraycopy(wavHdr, 0, fileData, 0, wavHdr.length);
                                System.arraycopy(e.data, 0, fileData, wavHdr.length, e.data.length);
                                ext = ".wav";
                            } else if ("MPEG".equals(fmt)) {
                                fileData = e.data;
                                ext = ".mp3";
                            }

                            if (fileData != null && fileData.length > 0) {
                                // Handle duplicate names
                                String entryName = safeName + ext;
                                if (usedNames.contains(entryName)) {
                                    int dup = 2;
                                    while (usedNames.contains(safeName + "_" + dup + ext)) dup++;
                                    entryName = safeName + "_" + dup + ext;
                                }
                                usedNames.add(entryName);
                                java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(entryName);
                                zos.putNextEntry(ze);
                                zos.write(fileData);
                                zos.closeEntry();
                                successCount++;
                            } else {
                                failCount++;
                            }
                        } catch (Exception ex) {
                            failCount++;
                        }
                    }

                    zos.close();
                    final int sc = successCount, fc = failCount;
                    final java.io.File finalZipFile = zipFile;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            audioProgressLayout.setVisibility(View.GONE);
                            String msg = "已导出 " + sc + " 个音频到 ZIP";
                            if (fc > 0) msg += " (" + fc + " 个失败)";
                            msg += "\n" + finalZipFile.getAbsolutePath();
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                            audioInfoLabel.setText("导出完成: " + sc + " 成功" +
                                (fc > 0 ? ", " + fc + " 失败" : "") + " → " + finalZipFile.getName());
                        }
                    });
                } catch (final Exception e) {
                    try { if (zos != null) zos.close(); } catch (Exception ignored) {}
                    handler.post(new Runnable() {
                        @Override public void run() {
                            audioProgressLayout.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    // === Editor mode ===

    private void switchToEditorMode() {
        if (editorMode) return;
        currentLoadId++;
        editorMode = true;
        wardrobeMode = false;

        // Update button styles
        btnModeVisual.setBackgroundResource(R.drawable.btn_secondary_bg);
        btnModeVisual.setTextColor(0xFF4A5061);
        btnModeWardrobe.setBackgroundResource(R.drawable.btn_secondary_bg);
        btnModeWardrobe.setTextColor(0xFF4A5061);
        btnModeEditor.setBackgroundResource(R.drawable.btn_primary_bg);
        btnModeEditor.setTextColor(0xFFFFFFFF);
        btnModeAudio.setBackgroundResource(R.drawable.btn_secondary_bg);
        btnModeAudio.setTextColor(0xFF4A5061);

        // Hide visualization/wardrobe panels
        wardrobeCategoryScroll.setVisibility(View.GONE);
        btnClearWardrobe.setVisibility(View.GONE);
        btnExport.setVisibility(View.GONE);
        btnBatchExport.setVisibility(View.GONE);
        btnToggleBeamo.setVisibility(View.GONE);
        viewModeGroup.setVisibility(View.GONE);
        btnSelectAnim.setVisibility(View.GONE);
        btnPlayPause.setVisibility(View.GONE);
        btnAnimFilter.setVisibility(View.GONE);
        animSeekBar.setVisibility(View.GONE);
        animTimeText.setVisibility(View.GONE);
        listMesh.setVisibility(View.GONE);
        gridWardrobe.setVisibility(View.GONE);
        listKtx.setVisibility(View.GONE);
        listLevel.setVisibility(View.GONE);
        findViewById(R.id.divider1).setVisibility(View.GONE);
        findViewById(R.id.divider2).setVisibility(View.GONE);
        findViewById(R.id.searchBox).setVisibility(View.GONE);
        // Hide main viewport, show editor panel in its place
        findViewById(R.id.mainViewportContainer).setVisibility(View.GONE);
        findViewById(R.id.bottomAssetBrowser).setVisibility(View.GONE);

        // Show editor panel
        editorPanel.setVisibility(View.VISIBLE);
        audioPanel.setVisibility(View.GONE);

        // Clear all 3D state
        meshViewport.queueEvent(new Runnable() {
            @Override
            public void run() {
                meshViewport.getMeshRenderer().clearAnimation();
                meshViewport.getMeshRenderer().clearMeshInstances();
                meshViewport.requestRender();
            }
        });
        meshViewport.setContinuousRender(false);
        animNameOverlay.setText("");

        // Stop animation seek bar updater
        if (animSeekBarUpdater != null) {
            handler.removeCallbacks(animSeekBarUpdater);
            animSeekBarUpdater = null;
        }

        // Find and load Objects.level.bin from the APK
        if (currentApkPath == null) {
            statusText.setText("请先选择APK文件");
            return;
        }

        // Populate map file spinner
        editorMapFiles.clear();
        for (MeshCatalogEntry e : levelList) {
            if (e.relativePath != null && e.relativePath.endsWith(".level.bin")) {
                editorMapFiles.add(e);
            }
        }
        List<String> mapNames = new ArrayList<>();
        for (MeshCatalogEntry e : editorMapFiles) {
            String n = e.relativePath;
            // Use parent folder name as the map display name
            int slash = n.lastIndexOf('/');
            if (slash >= 0) {
                String parent = n.substring(0, slash);
                int slash2 = parent.lastIndexOf('/');
                if (slash2 >= 0) parent = parent.substring(slash2 + 1);
                n = parent;
            } else {
                // No folder, use filename without extension
                int dot = n.lastIndexOf('.');
                if (dot > 0) n = n.substring(0, dot);
            }
            mapNames.add(n);
        }
        android.widget.ArrayAdapter<String> mapAdapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, mapNames);
        mapAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        editorMapSpinner.setAdapter(mapAdapter);

        // Auto-select Objects.level.bin if present
        int defaultMapIdx = 0;
        for (int i = 0; i < editorMapFiles.size(); i++) {
            if (editorMapFiles.get(i).relativePath.contains("Objects.level.bin")) {
                defaultMapIdx = i;
                break;
            }
        }
        if (editorMapFiles.isEmpty()) {
            statusText.setText("未找到 .level.bin 文件");
            return;
        }
        editorMapSpinner.setSelection(defaultMapIdx);

        // Map spinner handler — load selected map
        editorMapSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean firstCall = true;
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (firstCall) { firstCall = false; return; }
                loadEditorMap(position);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // Type spinner handler — filter nodes by class type
        editorTypeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean firstCall = true;
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (firstCall) { firstCall = false; return; }
                if (position == 0) {
                    editorSelectedType = "";
                } else if (position - 1 < editorTypeFilter.size()) {
                    editorSelectedType = editorTypeFilter.get(position - 1);
                }
                refreshEditorNodeList();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // Export JSON button
        findViewById(R.id.btnEditorExportJson).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportEditorJson();
            }
        });

        // Search text watcher (only add once)
        if (!editorSearchInitialized) {
            editorSearchInitialized = true;
            editorSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (editorBuildingPanel) return;
                    editorSelectedNodeIndex = -1;
                    refreshEditorNodeList();
                }
            });

            // Audio bank spinner
            audioBankSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    // Selection only; parsing triggered by the Parse button (parseSelectedBank)
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });

            // Topology graph node click → select in list and show details
            topologyGraph.setOnNodeClickListener(new com.sky.modelviewer.render.TopologyGraphView.OnNodeClickListener() {
                @Override
                public void onNodeClick(int nodeIndex) {
                    // nodeIndex is the subgraph position; get original file index
                    int fileIdx = topologyGraph.getNodeFileIndex(nodeIndex);
                    if (fileIdx < 0) return;
                    // Find position in filtered list
                    String sq = editorSearch.getText().toString().toLowerCase().trim();
                    int filteredPos = 0;
                    for (int i = 0; i < editorFile.nodes.size(); i++) {
                        if (i == fileIdx) break;
                        com.sky.modelviewer.parsing.TgclParser.BstNode n = editorFile.nodes.get(i);
                        String cn = editorFile.classNames.size() > n.classIndex ?
                            editorFile.classNames.get(n.classIndex) : "Unknown";
                        if (!editorSelectedType.isEmpty() && !cn.equals(editorSelectedType)) continue;
                        if (!sq.isEmpty() && !n.name.toLowerCase().contains(sq) && !cn.toLowerCase().contains(sq)) continue;
                        filteredPos++;
                    }
                    if (editorNodeList != null && filteredPos < editorFilteredNames.size()) {
                        editorNodeList.setItemChecked(filteredPos, true);
                        editorNodeList.setSelection(filteredPos);
                        showEditorNodeDetails(filteredPos);
                    }
                }
            });
        }

        // Load the default map
        loadEditorMap(defaultMapIdx);
    }

    private void loadEditorMap(final int mapIndex) {
        if (mapIndex < 0 || mapIndex >= editorMapFiles.size()) return;
        final MeshCatalogEntry levelEntry = editorMapFiles.get(mapIndex);
        final String apkPath = currentApkPath;
        final int loadId = ++currentLoadId;

        statusText.setText("正在加载: " + levelEntry.relativePath);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw = SkyAssetScanner.readApkEntry(apkPath, levelEntry.fullPath);
                    if (loadId != currentLoadId) return;
                    if (raw == null) throw new RuntimeException("无法读取: " + levelEntry.fullPath);

                    final com.sky.modelviewer.parsing.TgclParser parser = new com.sky.modelviewer.parsing.TgclParser();
                    final com.sky.modelviewer.parsing.TgclParser.TgclFile file = parser.parse(raw);
                    try {
                        parser.resolveClumpReferences();
                    } catch (Exception e) {
                        // Non-fatal: clump resolution failure shouldn't crash
                    }

                    if (loadId != currentLoadId) return;

                    final String levelPath = levelEntry.relativePath;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (loadId != currentLoadId) return;
                            editorFile = file;
                            editorSelectedNodeIndex = -1;
                            editorSelectedType = "";
                            populateEditorTypeSpinner();
                            refreshEditorNodeList();
                            statusText.setText("已加载: " + levelPath +
                                " | " + file.nodes.size() + " 节点 | " + file.classes.size() + " 类");
                            // Update topology graph (in background thread to avoid UI freeze)
                            if (topologyGraph != null) {
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            topologyGraph.setGraphData(file);
                                            handler.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    try {
                                                        topoStatsLabel.setText(topologyGraph.getStatsText());
                                                    } catch (Throwable t) { /* ignore */ }
                                                }
                                            });
                                        } catch (final Throwable t) {
                                            handler.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    topoStatsLabel.setText("拓扑图构建失败: " + t.getMessage());
                                                }
                                            });
                                        }
                                    }
                                }).start();
                            }
                        }
                    });
                } catch (final Throwable e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("解析失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void populateEditorTypeSpinner() {
        if (editorFile == null) return;
        // Collect unique class names from nodes
        java.util.Set<String> typeSet = new java.util.TreeSet<>();
        for (com.sky.modelviewer.parsing.TgclParser.BstNode node : editorFile.nodes) {
            String cls = editorFile.classNames.size() > node.classIndex ?
                editorFile.classNames.get(node.classIndex) : "Unknown";
            typeSet.add(cls);
        }
        editorTypeFilter.clear();
        editorTypeFilter.addAll(typeSet);

        List<String> items = new ArrayList<>();
        items.add("全部类型 (" + editorFile.nodes.size() + ")");
        for (int i = 0; i < editorTypeFilter.size(); i++) {
            int count = 0;
            String cls = editorTypeFilter.get(i);
            for (com.sky.modelviewer.parsing.TgclParser.BstNode node : editorFile.nodes) {
                String ncls = editorFile.classNames.size() > node.classIndex ?
                    editorFile.classNames.get(node.classIndex) : "Unknown";
                if (ncls.equals(cls)) count++;
            }
            items.add(cls + " (" + count + ")");
        }
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        editorTypeSpinner.setAdapter(adapter);
    }

    private void refreshEditorNodeList() {
        if (editorFile == null) return;

        editorFilteredNames.clear();
        String searchQuery = editorSearch.getText().toString().toLowerCase().trim();

        for (int i = 0; i < editorFile.nodes.size(); i++) {
            com.sky.modelviewer.parsing.TgclParser.BstNode node = editorFile.nodes.get(i);
            String className = editorFile.classNames.size() > node.classIndex ?
                editorFile.classNames.get(node.classIndex) : "Unknown";

            // Apply type filter
            if (!editorSelectedType.isEmpty() && !className.equals(editorSelectedType)) continue;

            // Apply search filter
            String displayStr = node.name + "  [" + className + "]";
            if (searchQuery.isEmpty() || node.name.toLowerCase().contains(searchQuery) ||
                className.toLowerCase().contains(searchQuery)) {
                editorFilteredNames.add(displayStr);
            }
        }

        final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, editorFilteredNames) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                android.widget.TextView tv = (android.widget.TextView) super.getView(position, convertView, parent);
                tv.setTextSize(9);
                tv.setPadding(8, 6, 8, 6);
                tv.setTextColor(position == editorSelectedNodeIndex ? 0xFFFFFFFF : 0xFF333333);
                tv.setBackgroundColor(position == editorSelectedNodeIndex ? 0xFF4B3FE3 : 0xFFFFFFFF);
                return tv;
            }
        };
        editorNodeList.setAdapter(adapter);

        editorNodeList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                editorSelectedNodeIndex = position;
                adapter.notifyDataSetChanged();
                showEditorNodeDetails(position);
            }
        });
    }

    private int editorCurrentNodeIndex = -1;
    private TextView editorJsonText;
    private com.sky.modelviewer.render.TopologyGraphView topologyGraph;
    private android.widget.TextView topoStatsLabel;
    private android.widget.TextView topoDepthLabel;
    private android.widget.SeekBar topoDepthSlider;
    private EditText topoDepthInput;
    private int topoDepth = 1;

    private void refreshTopology() {
        if (topologyGraph != null && editorCurrentNodeIndex >= 0) {
            try {
                topologyGraph.setFocusSubgraph(editorCurrentNodeIndex, topoDepth);
                topoStatsLabel.setText(topologyGraph.getStatsText());
            } catch (Throwable t) { /* ignore */ }
        }
    }

    @SuppressWarnings("unchecked")
    private void showEditorNodeDetails(int position) {
        if (editorFile == null || position < 0 || position >= editorFilteredNames.size()) return;

        int actualNodeIndex = -1;
        int matchCount = 0;
        for (int i = 0; i < editorFile.nodes.size(); i++) {
            com.sky.modelviewer.parsing.TgclParser.BstNode node = editorFile.nodes.get(i);
            String className = editorFile.classNames.size() > node.classIndex ?
                editorFile.classNames.get(node.classIndex) : "Unknown";
            if (!editorSelectedType.isEmpty() && !className.equals(editorSelectedType)) continue;
            String sq = editorSearch.getText().toString().toLowerCase().trim();
            if (!sq.isEmpty() && !node.name.toLowerCase().contains(sq) && !className.toLowerCase().contains(sq)) continue;
            if (matchCount == position) { actualNodeIndex = i; break; }
            matchCount++;
        }
        if (actualNodeIndex < 0) return;
        editorCurrentNodeIndex = actualNodeIndex;

        com.sky.modelviewer.parsing.TgclParser.BstNode node = editorFile.nodes.get(actualNodeIndex);
        String className = editorFile.classNames.size() > node.classIndex ?
            editorFile.classNames.get(node.classIndex) : "Unknown";

        editorDetailTitle.setText(node.name + " [" + className + "]");

        // Build JSON matching Python script format
        editorBuildingPanel = true;
        try {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append(indent(1)).append("\"").append(escapeJson(className)).append("\": {\n");

        List<com.sky.modelviewer.parsing.TgclParser.PropertyDef> props =
            editorFile.propertiesByClass.size() > node.classIndex ?
            editorFile.propertiesByClass.get(node.classIndex) : new ArrayList<>();

        int pi = 0;
        for (com.sky.modelviewer.parsing.TgclParser.PropertyDef prop : props) {
            String key = prop.propertyName;
            Object val = node.properties.get(key);
            if (val == null) {
                val = node.properties.get("[CLUMP]" + key);
                if (val != null) key = "[CLUMP]" + key;
            }
            if (val == null) { key = "[Unknown Property]" + key; val = node.properties.get(key); }
            if (val == null) { key = "[UNKNOWN]" + key; val = node.properties.get(key); }

            json.append(indent(2)).append("\"").append(escapeJson(key)).append("\": ");
            json.append(formatPythonJsonValue(val, prop, 2, editorFile));
            if (pi < props.size() - 1) json.append(",");
            json.append("\n");
            pi++;
        }

        json.append(indent(1)).append("}\n");
        json.append("}\n");

        editorJsonText.setText(json.toString());
        editorBuildingPanel = false;

        // Focus node in topology graph
        if (topologyGraph != null) {
            try {
                topologyGraph.setFocusSubgraph(actualNodeIndex, topoDepth);
            } catch (Throwable t) {
                // ignore topology errors
            }
        }
        if (topoStatsLabel != null) {
            topoStatsLabel.setText(topologyGraph != null ? topologyGraph.getStatsText() : "");
        }
        } catch (Throwable t) {
            editorJsonText.setText("// 加载失败: " + t.getMessage());
            editorBuildingPanel = false;
        }
    }

    /**
     * Format value matching the Python script's read_class output:
     * - Bool: true/false
     * - String: "value"
     * - Number as string: "123" or "1.000000"
     * - Vector4 (size 16): ["x","y","z","w"] (array of strings)
     * - Transform (size 64): [["x","y","z","w"],...] (4x4 array of strings)
     * - Clump (type 2): "NodeName" (resolved from index)
     * - Array (type 3): {"Num": "count", "[CLUMP]data": ["name1","name2"]}
     */
    @SuppressWarnings("unchecked")
    private String formatPythonJsonValue(Object val, com.sky.modelviewer.parsing.TgclParser.PropertyDef prop,
                                         int level, com.sky.modelviewer.parsing.TgclParser.TgclFile file) {
        if (val == null) return "null";

        int ptype = prop.propertyType;
        int psize = prop.objectByteSize;

        // Type 0: General Value
        if (ptype == 0) {
            if (psize == 1) {
                // Bool
                if (val instanceof Boolean) return String.valueOf(val);
                if (val instanceof String) {
                    try { return String.valueOf(Integer.parseInt((String) val) != 0); }
                    catch (NumberFormatException e) { return "false"; }
                }
                return "false";
            } else if (psize == 2) {
                // Unsigned short → string
                return "\"" + escapeJson(String.valueOf(val)) + "\"";
            } else if (psize == 4) {
                // Float or Int → string
                String s = String.valueOf(val);
                if (val instanceof Float || val instanceof Double) {
                    float f = ((Number) val).floatValue();
                    s = String.format("%.6f", f);
                    if (s.equals("0.000000") || s.equals("-0.000000")) {
                        // Store as integer string
                        s = String.valueOf((int) Float.floatToIntBits(f));
                    }
                }
                return "\"" + escapeJson(s) + "\"";
            } else if (psize == 8) {
                // Double → string
                return "\"" + escapeJson(String.valueOf(val)) + "\"";
            } else if (psize == 16) {
                // Vector4 → array of strings
                if (val instanceof float[]) {
                    float[] arr = (float[]) val;
                    StringBuilder sb = new StringBuilder("[\n");
                    for (int i = 0; i < 4; i++) {
                        sb.append(indent(level + 1)).append("\"");
                        float f = i < arr.length ? arr[i] : 0f;
                        String s = String.format("%.6f", f);
                        if (s.equals("0.000000") || s.equals("-0.000000")) s = String.valueOf((int) Float.floatToIntBits(f));
                        sb.append(s).append("\"");
                        if (i < 3) sb.append(",");
                        sb.append("\n");
                    }
                    sb.append(indent(level)).append("]");
                    return sb.toString();
                }
                return "[]";
            } else if (psize == 64) {
                // Transform → 4x4 array of strings
                if (val instanceof float[]) {
                    float[] arr = (float[]) val;
                    StringBuilder sb = new StringBuilder("[\n");
                    for (int row = 0; row < 4; row++) {
                        sb.append(indent(level + 1)).append("[\n");
                        for (int col = 0; col < 4; col++) {
                            int idx = row * 4 + col;
                            float f = idx < arr.length ? arr[idx] : 0f;
                            String s = String.format("%.6f", f);
                            if (s.equals("0.000000") || s.equals("-0.000000")) s = String.valueOf((int) Float.floatToIntBits(f));
                            sb.append(indent(level + 2)).append("\"").append(s).append("\"");
                            if (col < 3) sb.append(",");
                            sb.append("\n");
                        }
                        sb.append(indent(level + 1)).append("]");
                        if (row < 3) sb.append(",");
                        sb.append("\n");
                    }
                    sb.append(indent(level)).append("]");
                    return sb.toString();
                }
                return "[]";
            } else {
                return "\"" + escapeJson(String.valueOf(val)) + "\"";
            }
        }

        // Type 1: String
        if (ptype == 1) {
            return "\"" + escapeJson(String.valueOf(val)) + "\"";
        }

        // Type 2: Clump reference → resolved node name
        if (ptype == 2) {
            String refName = resolveClumpToName(val, file);
            return "\"" + escapeJson(refName) + "\"";
        }

        // Type 3: Array
        if (ptype == 3) {
            if (prop.arrayIndex == 0xFFFFFFFF) {
                // Clump data array: {"Num": "count", "[CLUMP]data": ["name1", "name2"]}
                List<String> resolvedNames = new ArrayList<>();
                if (val instanceof Map) {
                    Object clumpData = ((Map<String, Object>) val).get("[CLUMP]data");
                    if (clumpData instanceof List) {
                        for (Object item : (List<?>) clumpData) {
                            resolvedNames.add(resolveClumpToName(item, file));
                        }
                    }
                } else if (val instanceof List) {
                    for (Object item : (List<?>) val) {
                        resolvedNames.add(resolveClumpToName(item, file));
                    }
                }
                StringBuilder sb = new StringBuilder("{\n");
                sb.append(indent(level + 1)).append("\"Num\": \"").append(resolvedNames.size()).append("\",\n");
                sb.append(indent(level + 1)).append("\"[CLUMP]data\": [\n");
                for (int i = 0; i < resolvedNames.size(); i++) {
                    sb.append(indent(level + 2)).append("\"").append(escapeJson(resolvedNames.get(i))).append("\"");
                    if (i < resolvedNames.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append(indent(level + 1)).append("]\n");
                sb.append(indent(level)).append("}");
                return sb.toString();
            } else {
                // Structured array — just show as JSON array
                if (val instanceof List) {
                    List<?> list = (List<?>) val;
                    if (list.isEmpty()) return "[]";
                    StringBuilder sb = new StringBuilder("[\n");
                    for (int i = 0; i < list.size(); i++) {
                        sb.append(indent(level + 1)).append(formatJsonValue(list.get(i), level + 1));
                        if (i < list.size() - 1) sb.append(",");
                        sb.append("\n");
                    }
                    sb.append(indent(level)).append("]");
                    return sb.toString();
                }
                return "[]";
            }
        }

        // Unknown
        return "\"" + escapeJson(String.valueOf(val)) + "\"";
    }

    private String resolveClumpToName(Object val, com.sky.modelviewer.parsing.TgclParser.TgclFile file) {
        if (val == null) return "-1";
        String s = String.valueOf(val);
        // Try as integer index
        try {
            int idx = Integer.parseInt(s);
            if (idx >= 0 && idx < file.nodes.size()) {
                return file.nodes.get(idx).name;
            }
            return s;
        } catch (NumberFormatException e) {
            // Already a name or nan
            return s;
        }
    }

    private String indent(int level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) sb.append("    ");
        return sb.toString();
    }

    private String formatJsonKey(String key, Object val, int level) {
        return indent(level) + "\"" + escapeJson(key) + "\": " + formatJsonValue(val, level);
    }

    @SuppressWarnings("unchecked")
    private String formatJsonValue(Object val, int level) {
        if (val == null) return "null";
        if (val instanceof Boolean) return String.valueOf(val);
        if (val instanceof Number) return String.valueOf(val);

        if (val instanceof float[]) {
            float[] arr = (float[]) val;
            if (arr.length == 0) return "[]";
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < arr.length; i++) {
                sb.append(indent(level + 1));
                if (Float.isNaN(arr[i]) || Float.isInfinite(arr[i])) sb.append("0.0");
                else sb.append(String.format("%.6f", arr[i]));
                if (i < arr.length - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append(indent(level)).append("]");
            return sb.toString();
        }

        if (val instanceof List) {
            List<?> list = (List<?>) val;
            if (list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < list.size(); i++) {
                Object elem = list.get(i);
                sb.append(indent(level + 1));
                if (elem instanceof Map) {
                    sb.append(formatJsonMap((Map<String, Object>) elem, level + 1));
                } else if (elem instanceof float[]) {
                    sb.append(formatJsonValue(elem, level + 1));
                } else if (elem instanceof List) {
                    sb.append(formatJsonValue(elem, level + 1));
                } else if (elem instanceof Boolean) {
                    sb.append(String.valueOf(elem));
                } else {
                    String s = String.valueOf(elem);
                    try { Double.parseDouble(s); sb.append(s); }
                    catch (NumberFormatException e) { sb.append("\"").append(escapeJson(s)).append("\""); }
                }
                if (i < list.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append(indent(level)).append("]");
            return sb.toString();
        }

        if (val instanceof Map) {
            return formatJsonMap((Map<String, Object>) val, level);
        }

        // String or other
        String s = String.valueOf(val);
        try {
            Double.parseDouble(s);
            return s;
        } catch (NumberFormatException e) {
            return "\"" + escapeJson(s) + "\"";
        }
    }

    @SuppressWarnings("unchecked")
    private String formatJsonMap(Map<String, Object> map, int level) {
        if (map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            sb.append(indent(level + 1)).append("\"").append(escapeJson(e.getKey())).append("\": ");
            sb.append(formatJsonValue(e.getValue(), level + 1));
            if (i < map.size() - 1) sb.append(",");
            sb.append("\n");
            i++;
        }
        sb.append(indent(level)).append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean isTransformProperty(String name) {
        String ln = name.toLowerCase();
        return ln.contains("transform") || ln.contains("matrix") || ln.contains("local") ||
               ln.contains("world") || ln.contains("localtm");
    }

    private boolean isPositionProperty(String name) {
        String ln = name.toLowerCase();
        return ln.contains("pos") || ln.contains("loc") || ln.contains("origin") ||
               ln.contains("translation") || ln.contains("center");
    }

    private String getTypeString(int propertyType) {
        switch (propertyType) {
            case 0: return "Value";
            case 1: return "String";
            case 2: return "Clump";
            case 3: return "Array";
            default: return "?";
        }
    }

    private void applyEditorChanges() {
        if (editorFile == null || editorCurrentNodeIndex < 0) return;
        com.sky.modelviewer.parsing.TgclParser.BstNode node = editorFile.nodes.get(editorCurrentNodeIndex);

        String jsonText = editorJsonText.getText().toString();
        try {
            org.json.JSONObject jsonObj = new org.json.JSONObject(jsonText);
            org.json.JSONObject props = jsonObj.optJSONObject("properties");
            if (props == null) {
                Toast.makeText(this, "JSON中缺少properties字段", Toast.LENGTH_SHORT).show();
                return;
            }

            List<com.sky.modelviewer.parsing.TgclParser.PropertyDef> propDefs =
                editorFile.propertiesByClass.size() > node.classIndex ?
                editorFile.propertiesByClass.get(node.classIndex) : new ArrayList<>();

            int applied = 0;
            for (com.sky.modelviewer.parsing.TgclParser.PropertyDef prop : propDefs) {
                if (!props.has(prop.propertyName)) continue;
                Object jsonVal = props.get(prop.propertyName);
                Object nodeVal = jsonToNodeValue(jsonVal, prop);
                if (nodeVal != null) {
                    node.properties.put(prop.propertyName, nodeVal);
                    applied++;
                }
            }

            // Also update node name if changed
            String newName = jsonObj.optString("name", node.name);
            if (!newName.equals(node.name)) {
                node.name = newName;
            }

            Toast.makeText(this, "已应用 " + applied + " 项更改", Toast.LENGTH_SHORT).show();
            refreshEditorNodeList();
        } catch (org.json.JSONException e) {
            Toast.makeText(this, "JSON解析错误: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Object jsonToNodeValue(Object jsonVal, com.sky.modelviewer.parsing.TgclParser.PropertyDef prop) {
        if (jsonVal == org.json.JSONObject.NULL) return null;
        if (jsonVal instanceof Boolean) return jsonVal;
        if (jsonVal instanceof Number) {
            // Check if original was float[]
            Object existing = prop != null ? null : null;
            return String.valueOf(jsonVal);
        }
        if (jsonVal instanceof String) return jsonVal;
        if (jsonVal instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) jsonVal;
            // Check if all elements are numbers → float[]
            boolean allNums = true;
            for (int i = 0; i < arr.length(); i++) {
                if (!(arr.opt(i) instanceof Number)) { allNums = false; break; }
            }
            if (allNums && arr.length() > 0) {
                float[] floats = new float[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    floats[i] = ((Number) arr.opt(i)).floatValue();
                }
                return floats;
            }
            // Otherwise return as string
            return arr.toString();
        }
        if (jsonVal instanceof org.json.JSONObject) {
            return jsonVal.toString();
        }
        return String.valueOf(jsonVal);
    }

    private void showAddNodeDialog() {
        if (editorFile == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] classNames = new String[editorFile.classNames.size()];
        for (int i = 0; i < editorFile.classNames.size(); i++) {
            String cn = editorFile.classNames.get(i);
            boolean isClump = cn.contains("Clump") || cn.contains("Group") || cn.contains("Container");
            classNames[i] = (isClump ? "[AutoClump] " : "[BstNode] ") + cn;
        }

        final android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("选择要添加的节点类型");
        builder.setItems(classNames, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                String className = editorFile.classNames.get(which);
                boolean isClump = className.contains("Clump") || className.contains("Group") || className.contains("Container");
                addEditorNode(isClump, which);
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void addEditorNode(boolean isAutoClump) {
        // Overload with default class selection
        int classIdx = 0;
        if (editorFile != null) {
            for (int i = 0; i < editorFile.classNames.size(); i++) {
                String cn = editorFile.classNames.get(i);
                if (isAutoClump) {
                    if (cn.contains("Clump") || cn.contains("Group") || cn.contains("Container")) { classIdx = i; break; }
                } else {
                    if (cn.contains("Marker")) { classIdx = i; break; }
                }
            }
        }
        addEditorNode(isAutoClump, classIdx);
    }

    private void addEditorNode(boolean isAutoClump, int classIndex) {
        if (editorFile == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }
        // Create a new node
        com.sky.modelviewer.parsing.TgclParser.BstNode newNode = new com.sky.modelviewer.parsing.TgclParser.BstNode();
        String className = classIndex < editorFile.classNames.size() ? editorFile.classNames.get(classIndex) : "Unknown";
        newNode.name = (isAutoClump ? "AutoClump_" : "BstNode_") + System.currentTimeMillis();
        newNode.classIndex = classIndex;

        // Initialize with default properties for the class
        List<com.sky.modelviewer.parsing.TgclParser.PropertyDef> props =
            editorFile.propertiesByClass.size() > newNode.classIndex ?
            editorFile.propertiesByClass.get(newNode.classIndex) : new ArrayList<>();
        for (com.sky.modelviewer.parsing.TgclParser.PropertyDef prop : props) {
            switch (prop.propertyType) {
                case 0:
                    if (prop.objectByteSize == 1) newNode.properties.put(prop.propertyName, false);
                    else if (prop.objectByteSize == 4) newNode.properties.put(prop.propertyName, "0.000000");
                    else if (prop.objectByteSize == 16) newNode.properties.put(prop.propertyName, new float[]{0,0,0,1});
                    else if (prop.objectByteSize == 64) newNode.properties.put(prop.propertyName, new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1});
                    else newNode.properties.put(prop.propertyName, "0");
                    break;
                case 1: newNode.properties.put(prop.propertyName, ""); break;
                case 2: newNode.properties.put(prop.propertyName, "-1"); break;
                case 3: newNode.properties.put(prop.propertyName, new java.util.ArrayList<Object>()); break;
            }
        }

        editorFile.nodes.add(newNode);
        String type = isAutoClump ? "AutoClump" : "BstNode";
        Toast.makeText(this, "已添加" + type + ": " + newNode.name, Toast.LENGTH_SHORT).show();
        refreshEditorNodeList();
        if (topologyGraph != null) {
            try {
                topologyGraph.setGraphData(editorFile);
                topologyGraph.setFocusSubgraph(editorFile.nodes.size() - 1, topoDepth);
                topoStatsLabel.setText(topologyGraph.getStatsText());
            } catch (Throwable t) { /* ignore */ }
        }
    }

    private void deleteEditorNode() {
        if (editorFile == null || editorCurrentNodeIndex < 0 || editorCurrentNodeIndex >= editorFile.nodes.size()) {
            Toast.makeText(this, "请先选择要删除的节点", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = editorFile.nodes.get(editorCurrentNodeIndex).name;
        editorFile.nodes.remove(editorCurrentNodeIndex);
        editorCurrentNodeIndex = -1;
        Toast.makeText(this, "已删除: " + name, Toast.LENGTH_SHORT).show();
        refreshEditorNodeList();
        if (topologyGraph != null) {
            try {
                topologyGraph.setGraphData(editorFile);
                topologyGraph.clearSelection();
                topoStatsLabel.setText(topologyGraph.getStatsText());
            } catch (Throwable t) { /* ignore */ }
        }
    }

    private void showJsonFullscreen() {
        if (editorJsonText == null) return;
        String json = editorJsonText.getText().toString();
        if (json.isEmpty()) return;

        // Create a fullscreen dialog with the JSON text
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF1A1A2E);
        layout.setPadding(8, 8, 8, 8);

        // Title bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("JSON编辑器 (全屏)");
        title.setTextSize(14);
        title.setTextColor(0xFF4B3FE3);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(title);

        Button closeBtn = new Button(this);
        closeBtn.setText("关闭");
        closeBtn.setTextSize(10);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        topBar.addView(closeBtn);

        // Fullscreen text editor (created before apply button so we can reference it)
        final EditText fullText = new EditText(this);
        fullText.setText(json);
        fullText.setTextSize(11);
        fullText.setTypeface(android.graphics.Typeface.MONOSPACE);
        fullText.setTextColor(0xFFEEEEEE);
        fullText.setBackgroundColor(0xFF111122);
        fullText.setPadding(12, 8, 12, 8);
        fullText.setSingleLine(false);
        fullText.setGravity(android.view.Gravity.TOP);
        fullText.setInputType(android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fullText.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button applyBtn = new Button(this);
        applyBtn.setText("应用并关闭");
        applyBtn.setTextSize(10);
        applyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editorJsonText.setText(fullText.getText().toString());
                dialog.dismiss();
                applyEditorChanges();
            }
        });
        topBar.addView(applyBtn);

        layout.addView(topBar);
        layout.addView(fullText);

        dialog.setContentView(layout);
        dialog.show();
    }

    private void exportEditorJson() {
        if (editorFile == null || currentApkPath == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }
        int mapIdx = editorMapSpinner.getSelectedItemPosition();
        if (mapIdx < 0 || mapIdx >= editorMapFiles.size()) return;
        final String levelPath = editorMapFiles.get(mapIdx).fullPath;

        statusText.setText("正在导出JSON...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String jsonStr = buildFullJson(editorFile);
                    final String outName = levelPath.substring(levelPath.lastIndexOf('/') + 1)
                        .replace(".level.bin", "") + ".json";

                    // Save to Download directory
                    java.io.File outFile = null;
                    String errorMsg = null;
                    try {
                        outFile = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                            "Download/" + outName);
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                        fos.write(jsonStr.getBytes("UTF-8"));
                        fos.close();
                    } catch (Exception e) {
                        // Try app external files dir
                        try {
                            java.io.File outDir = getExternalFilesDir(null);
                            outFile = new java.io.File(outDir, outName);
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                            fos.write(jsonStr.getBytes("UTF-8"));
                            fos.close();
                        } catch (Exception e2) {
                            errorMsg = e2.getMessage();
                        }
                    }

                    final java.io.File finalFile = outFile;
                    final String finalErr = errorMsg;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (finalFile != null) {
                                statusText.setText("已导出: " + finalFile.getAbsolutePath());
                                Toast.makeText(MainActivity.this, "已导出: " + finalFile.getName(),
                                    Toast.LENGTH_LONG).show();
                            } else {
                                statusText.setText("导出失败: " + finalErr);
                                Toast.makeText(MainActivity.this, "导出失败: " + finalErr,
                                    Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                } catch (final Throwable t) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("导出失败: " + t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Build full JSON matching Python script's load_objects output format:
     * {
     *   "version": 1,
     *   "MemorySize": "123",
     *   "classes": { "ClassName": { "prop": {propertyType, objectByteSize, arrayIndex}, ... }, ... },
     *   "BSTNodes": { "NodeName": { "ClassName": { ...props... }, ... }, ... }
     * }
     */
    @SuppressWarnings("unchecked")
    private String buildFullJson(com.sky.modelviewer.parsing.TgclParser.TgclFile file) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        // version
        json.append(indent(1)).append("\"version\": ").append(file.version != 0 ? file.version : 1).append(",\n");
        // MemorySize (object_ptr_count placeholder)
        json.append(indent(1)).append("\"MemorySize\": \"").append(file.nodes.size()).append("\",\n");

        // classes
        json.append(indent(1)).append("\"classes\": {\n");
        for (int ci = 0; ci < file.classes.size(); ci++) {
            com.sky.modelviewer.parsing.TgclParser.ClassDef cls = file.classes.get(ci);
            String className = file.classNames.size() > ci ? file.classNames.get(ci) : "Class_" + ci;
            json.append(indent(2)).append("\"").append(escapeJson(className)).append("\": ");
            List<com.sky.modelviewer.parsing.TgclParser.PropertyDef> props =
                file.propertiesByClass.size() > ci ? file.propertiesByClass.get(ci) : new ArrayList<>();
            if (props.isEmpty()) {
                json.append("null");
            } else {
                json.append("{\n");
                for (int pi = 0; pi < props.size(); pi++) {
                    com.sky.modelviewer.parsing.TgclParser.PropertyDef p = props.get(pi);
                    json.append(indent(3)).append("\"").append(escapeJson(p.propertyName)).append("\": {\n");
                    json.append(indent(4)).append("\"propertyType\": ").append(p.propertyType).append(",\n");
                    json.append(indent(4)).append("\"objectByteSize\": ").append(p.objectByteSize).append(",\n");
                    json.append(indent(4)).append("\"arrayIndex\": ").append(
                        p.arrayIndex == 0xFFFFFFFF ? "4294967295" : String.valueOf(p.arrayIndex)).append("\n");
                    json.append(indent(3)).append("}");
                    if (pi < props.size() - 1) json.append(",");
                    json.append("\n");
                }
                json.append(indent(2)).append("}");
            }
            if (ci < file.classes.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append(indent(1)).append("},\n");

        // BSTNodes
        json.append(indent(1)).append("\"BSTNodes\": {\n");
        for (int ni = 0; ni < file.nodes.size(); ni++) {
            com.sky.modelviewer.parsing.TgclParser.BstNode node = file.nodes.get(ni);
            String className = file.classNames.size() > node.classIndex ?
                file.classNames.get(node.classIndex) : "Unknown";
            json.append(indent(2)).append("\"").append(escapeJson(node.name)).append("\": {\n");

            // Class wrapper
            json.append(indent(3)).append("\"").append(escapeJson(className)).append("\": {\n");

            List<com.sky.modelviewer.parsing.TgclParser.PropertyDef> props =
                file.propertiesByClass.size() > node.classIndex ?
                file.propertiesByClass.get(node.classIndex) : new ArrayList<>();
            for (int pi = 0; pi < props.size(); pi++) {
                com.sky.modelviewer.parsing.TgclParser.PropertyDef prop = props.get(pi);
                String key = prop.propertyName;
                Object val = node.properties.get(key);
                if (val == null) {
                    val = node.properties.get("[CLUMP]" + key);
                    if (val != null) key = "[CLUMP]" + key;
                }
                if (val == null) { key = "[Unknown Property]" + key; val = node.properties.get(key); }
                if (val == null) { key = "[UNKNOWN]" + key; val = node.properties.get(key); }

                json.append(indent(4)).append("\"").append(escapeJson(key)).append("\": ");
                json.append(formatPythonJsonValue(val, prop, 4, file));
                if (pi < props.size() - 1) json.append(",");
                json.append("\n");
            }

            json.append(indent(3)).append("}\n");
            json.append(indent(2)).append("}");
            if (ni < file.nodes.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append(indent(1)).append("}\n");
        json.append("}\n");

        return json.toString();
    }

    private void exportEditorBin() {
        // No longer used — BIN export removed from UI
    }

    private void showNodeInEditorViewport(com.sky.modelviewer.parsing.TgclParser.BstNode node,
                                          float[] posData, String className) {
        // 3D viewport removed — no-op
    }

    private void loadEditorMapMeshes() {
        // 3D viewport has been replaced by topology graph — this is now a no-op
        Toast.makeText(this, "地形预览已移至拓扑图视图", Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("unchecked")
    private String formatPropertyMap(Map<String, Object> map, String indent) {
        StringBuilder sb = new StringBuilder("{\n");
        for (Map.Entry<String, Object> e : map.entrySet()) {
            sb.append(indent).append(e.getKey()).append(": ");
            Object v = e.getValue();
            if (v instanceof Map) {
                sb.append(formatPropertyMap((Map<String, Object>) v, indent + "  "));
            } else if (v instanceof List) {
                List<?> list = (List<?>) v;
                sb.append("[").append(list.size()).append(" items]");
            } else if (v instanceof float[]) {
                float[] arr = (float[]) v;
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(String.format("%.4f", arr[i]));
                }
            } else {
                sb.append(String.valueOf(v));
            }
            sb.append("\n");
        }
        sb.append(indent.substring(0, Math.max(0, indent.length() - 2))).append("}");
        return sb.toString();
    }

    private String getTypeLabel(String type) {
        for (String[] c : WARDROBE_TYPE_LABELS) {
            if (c[0].equals(type)) return c[1];
        }
        return type;
    }

    private void buildWardrobeCategoryTabs() {
        wardrobeCategoryBar.removeAllViews();
        for (final String type : outfitTypes) {
            Button btn = new Button(this);
            btn.setText(getTypeLabel(type));
            btn.setTextSize(11f);
            btn.setMinHeight(dp_to_px(28));
            btn.setMinimumHeight(0);
            btn.setHeight(dp_to_px(28));
            btn.setPadding(dp_to_px(10), 0, dp_to_px(10), 0);
            btn.setAllCaps(false);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp_to_px(2), 0, dp_to_px(2), 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectWardrobeCategory(type);
                }
            });
            wardrobeCategoryBar.addView(btn);
        }
    }

    private int dp_to_px(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int)(dp * density + 0.5f);
    }

    private static float[] jsonFloatArray(org.json.JSONObject obj, String key, float[] def) {
        org.json.JSONArray arr = obj.optJSONArray(key);
        if (arr == null || arr.length() == 0 || arr.length() < def.length) return def;
        float[] result = new float[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            result[i] = (float) arr.optDouble(i, 0.0);
        }
        return result;
    }

    private static float[] jsonFloatArrayNullable(org.json.JSONObject obj, String key) {
        org.json.JSONArray arr = obj.optJSONArray(key);
        if (arr == null || arr.length() == 0) return null;
        float[] result = new float[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            result[i] = (float) arr.optDouble(i, 0.0);
        }
        return result;
    }

    private void loadOutfitDefsInBackground(String apkPath, List<MeshCatalogEntry> meshEntries) {
        outfitByType.clear();
        outfitTypes.clear();
        if (apkPath == null) return;
        try {
            org.json.JSONArray arr = SkyResourceResolver.getOutfitDefs(apkPath);
            if (arr == null) {
                debugLog("OutfitDefs.json not found");
                return;
            }
            // Build lookup of mesh entries by name (lowercase)
            java.util.Map<String, MeshCatalogEntry> meshByName = new java.util.HashMap<>();
            for (MeshCatalogEntry e : meshEntries) {
                if ("mesh".equals(e.fileType)) meshByName.put(e.name.toLowerCase(), e);
            }
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String type = obj.optString("type", "");
                String mesh = obj.optString("mesh", "");
                String name = obj.optString("name", mesh);
                if (type.isEmpty() || mesh.isEmpty()) continue;
                if ("Outfit_None".equalsIgnoreCase(mesh)) continue;
                OutfitEntry oe = new OutfitEntry();
                oe.type = type; oe.name = name; oe.mesh = mesh;
                // Parse color fields
                oe.baseHsv = jsonFloatArray(obj, "base_hsv", new float[]{0,0,100});
                oe.colorOverride = obj.optBoolean("color_override", false);
                oe.primaryDyeHsv = jsonFloatArrayNullable(obj, "primary_dye_hsv");
                oe.secondaryDyeHsv = jsonFloatArrayNullable(obj, "secondary_dye_hsv");
                oe.diffuseTex = obj.optString("diffuseTex", "");
                oe.attribTex = obj.optString("attribTex", "");
                // Match mesh to MeshCatalogEntry
                String ml = mesh.toLowerCase();
                oe.matchedEntry = meshByName.get(ml);
                if (oe.matchedEntry == null) {
                    for (MeshCatalogEntry e : meshEntries) {
                        if (!"mesh".equals(e.fileType)) continue;
                        String en = e.name.toLowerCase();
                        if (en.startsWith(ml + "_") || en.startsWith(ml)) { oe.matchedEntry = e; break; }
                    }
                }
                if (oe.matchedEntry == null) {
                    for (MeshCatalogEntry e : meshEntries) {
                        if (!"mesh".equals(e.fileType)) continue;
                        String en = e.name.toLowerCase();
                        if (ml.startsWith(en) || ml.equals(en)) { oe.matchedEntry = e; break; }
                    }
                }
                if (oe.matchedEntry == null) {
                    String gp = "assets/Data/Meshes/" + mesh + ".mesh";
                    oe.matchedEntry = new MeshCatalogEntry(mesh, gp, "Data/Meshes/" + mesh + ".mesh", "wardrobe", "mesh");
                }
                if (!outfitByType.containsKey(type)) {
                    outfitByType.put(type, new ArrayList<OutfitEntry>());
                    outfitTypes.add(type);
                }
                outfitByType.get(type).add(oe);
            }
            int total = 0;
            for (List<OutfitEntry> list : outfitByType.values()) total += list.size();
            debugLog("OutfitDefs: " + outfitTypes.size() + " types, total: " + total);
        } catch (Exception e) {
            debugLog("loadOutfitDefs error: " + e.getMessage());
        }
    }

    private void refreshWardrobeTabs() {
        for (int i = 0; i < wardrobeCategoryBar.getChildCount(); i++) {
            Button btn = (Button) wardrobeCategoryBar.getChildAt(i);
            String catKey = outfitTypes.get(i);
            if (catKey.equals(wardrobeSelectedCategory)) {
                btn.setBackgroundResource(R.drawable.btn_primary_bg);
                btn.setTextColor(0xFFFFFFFF);
            } else {
                btn.setBackgroundResource(R.drawable.btn_secondary_bg);
                btn.setTextColor(0xFF4A5061);
            }
        }
    }

    private void selectWardrobeCategory(String category) {
        wardrobeSelectedCategory = category;
        for (int i = 0; i < wardrobeCategoryBar.getChildCount(); i++) {
            Button btn = (Button) wardrobeCategoryBar.getChildAt(i);
            String catKey = outfitTypes.get(i);
            if (catKey.equals(category)) {
                btn.setBackgroundResource(R.drawable.btn_primary_bg);
                btn.setTextColor(0xFFFFFFFF);
            } else {
                btn.setBackgroundResource(R.drawable.btn_secondary_bg);
                btn.setTextColor(0xFF4A5061);
            }
        }

        List<OutfitEntry> entries = outfitByType.get(category);
        List<MeshCatalogEntry> displayEntries = new ArrayList<>();
        // Add "clear" item at position 0 — removes this category's wardrobe mesh
        displayEntries.add(new MeshCatalogEntry("__CLEAR__", "", "", category, "clear"));
        if (entries != null) {
            for (OutfitEntry oe : entries) {
                if (oe.matchedEntry != null) {
                    // Use OutfitEntry's unique name as display name, keep mesh path
                    MeshCatalogEntry me = new MeshCatalogEntry(
                        oe.name, // unique identifier from OutfitDefs
                        oe.matchedEntry.fullPath,
                        oe.matchedEntry.relativePath,
                        oe.matchedEntry.category,
                        oe.matchedEntry.fileType);
                    displayEntries.add(me);
                }
            }
        }
        wardrobeGridAdapter.setEntries(displayEntries);
        wardrobeGridAdapter.setIconNames(new HashMap<String, String>());
        wardrobeGridAdapter.filter("");
        String catLabel = getTypeLabel(category);
        statusText.setText("衣柜: " + catLabel + " — " + displayEntries.size() + " 个模型");
        searchBox.setHint("筛选" + catLabel + "...");
    }

    /**
     * Compute a 4x4 transform matrix (column-major) from the BODY OutfitEntry's offset fields.
     * Offset/scale values are defined on the body (裤子) entry and apply to other types.
     * Returns null if no transform needed.
     */
    private float[] computeWardrobeTransform(OutfitEntry oe, String category) {
        // Only prop (背饰) needs a transform: Y-axis 180 degree rotation
        if (!"prop".equals(category)) return null;
        
        // Ry(180) = [-1,0,0, 0,1,0, 0,0,-1] in column-major
        float[] m = new float[16];
        m[0] = -1; m[1] = 0; m[2] = 0; m[3] = 0;
        m[4] = 0; m[5] = 1; m[6] = 0; m[7] = 0;
        m[8] = 0; m[9] = 0; m[10] = -1; m[11] = 0;
        m[12] = 0; m[13] = 0; m[14] = 0; m[15] = 1;
        return m;
    }

    private void loadWardrobeMesh(final MeshCatalogEntry entry) {
        final String apkPath = currentApkPath;
        if (apkPath == null) return;
        final String category = wardrobeSelectedCategory;
        if (category == null) return;

        // Handle "clear" item — remove this category's wardrobe mesh
        if ("clear".equals(entry.fileType) || "__CLEAR__".equals(entry.name)) {
            clearWardrobeCategory(category);
            return;
        }

        statusText.setText("正在加载 " + entry.name);
        selectionTitle.setText(entry.name);
        final int loadId = ++currentLoadId;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw = SkyAssetScanner.readApkEntry(apkPath, entry.fullPath);
                    if (loadId != currentLoadId) return; // Cancelled
                    if (raw == null) throw new RuntimeException("Cannot read mesh: " + entry.fullPath);
                    final MeshData meshData = TgcMeshReader.readMesh(raw, entry.fullPath);
                    if (loadId != currentLoadId) return; // Cancelled

                    final MaterialInfo material = SkyResourceResolver.resolveMaterial(apkPath, entry.fullPath);
                    Float scaleVal = SkyResourceResolver.resolveScale(apkPath, entry.fullPath);
                    final float scale = scaleVal != null ? scaleVal : 1f;

                    // Find matching OutfitEntry by unique name (OutfitDefs name is the key)
                    OutfitEntry oe = null;
                    List<OutfitEntry> catEntries = outfitByType.get(category);
                    if (catEntries != null) {
                        for (OutfitEntry e : catEntries) {
                            // entry.name is now the OutfitEntry name (unique identifier)
                            if (e.name.equals(entry.name)) {
                                oe = e; break;
                            }
                        }
                    }
                    final OutfitEntry finalOe = oe;
                    wardrobeOutfitSelections.put(category, oe);

                    // Compute transform matrix from OutfitEntry offsets
                    final float[] transform = computeWardrobeTransform(oe, category);
                    // Get color override — always pass base_hsv (default [0,0,100]=white=no change)
                    final float[] baseHsv = (oe != null && oe.baseHsv != null && oe.baseHsv.length >= 3) ? oe.baseHsv : new float[]{0, 0, 100};
                    final boolean colorOverride = (oe != null && oe.colorOverride);
                    // Prop bone binding happens when animation is loaded, not at mesh load time
                    final String propBone = null;

                    final byte[] textureBytes;
                    byte[] tempTextureBytes = null;
                    try {
                        String meshNameForTexture = SkyResourceResolver.stripMeshVariantSuffix(
                            nameWithoutExtension(new File(entry.fullPath).getName()));
                        // Include OutfitDefs diffuseTex in the search list
                        String oeDiffuse = (oe != null && oe.diffuseTex != null && !oe.diffuseTex.isEmpty()) ? oe.diffuseTex : null;
                        String texturePath = SkyResourceResolver.findTextureFile(
                            apkPath,
                            new String[]{material != null ? material.diffuseTex : null,
                                         material != null ? material.diffuse2Tex : null,
                                         oeDiffuse,
                                         meshNameForTexture});
                        tempTextureBytes = texturePath != null ? SkyAssetScanner.readApkEntry(apkPath, texturePath) : null;
                    } catch (Exception e) {
                        debugLog("Wardrobe texture resolution failed: " + e.getMessage());
                    }
                    textureBytes = tempTextureBytes;

                    if (loadId != currentLoadId) return; // Cancelled

                    wardrobeMeshData.put(category, meshData);
                    wardrobeMeshScale.put(category, scale);
                    wardrobeTextureBytes.put(category, textureBytes);

                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                MeshRenderer renderer = meshViewport.getMeshRenderer();
                                int texId = 0;
                                if (textureBytes != null) {
                                    try {
                                        texId = KtxTextureLoader.loadTexture(textureBytes);
                                        if (texId == 0) texId = KtxTextureLoader.loadStandardImage(textureBytes);
                                    } catch (Exception texEx) {
                                        debugLog("Wardrobe texture load error: " + texEx.getMessage());
                                    }
                                }
                                renderer.addWardrobeMesh(meshData, scale, texId, category, transform, baseHsv, colorOverride, propBone);
                                meshViewport.requestRender();
                            } catch (Exception e) {
                                debugLog("Wardrobe GL error: " + e.getMessage());
                            }
                        }
                    });

                    final String catLabel = getTypeLabel(category);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText(catLabel + ": " + entry.name);
                            detailsBox.setText("衣柜: " + catLabel + "\n" +
                                "模型: " + entry.name + "\n" +
                                "顶点: " + meshData.vertices.size() + "\n" +
                                "骨骼: " + (meshData.embeddedSkeleton != null ? meshData.embeddedSkeleton.size() : 0));
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("加载失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void clearWardrobe() {
        wardrobeMeshData.clear();
        wardrobeMeshScale.clear();
        wardrobeTextureBytes.clear();
        wardrobeOutfitSelections.clear();
        meshViewport.queueEvent(new Runnable() {
            @Override
            public void run() {
                meshViewport.getMeshRenderer().clearWardrobeMeshes();
                meshViewport.getMeshRenderer().clearAnimation();
                meshViewport.requestRender();
            }
        });
        // Clear animation UI
        btnPlayPause.setVisibility(View.GONE);
        animSeekBar.setVisibility(View.GONE);
        animTimeText.setVisibility(View.GONE);
        animNameOverlay.setText("");
        meshViewport.setContinuousRender(false);
        statusText.setText("衣柜已清空");
    }

    /**
     * Clear a single wardrobe category (e.g. remove only hair, keep everything else).
     */
    private void clearWardrobeCategory(final String category) {
        wardrobeMeshData.remove(category);
        wardrobeMeshScale.remove(category);
        wardrobeTextureBytes.remove(category);
        wardrobeOutfitSelections.remove(category);
        meshViewport.queueEvent(new Runnable() {
            @Override
            public void run() {
                meshViewport.getMeshRenderer().removeWardrobeMesh(category);
                meshViewport.requestRender();
            }
        });
        String catLabel = getTypeLabel(category);
        statusText.setText(catLabel + "已清除");
        detailsBox.setText("");
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

                    // Prepare lists to store mesh data for export
                    currentLevelMeshes = new ArrayList<MeshData>();
                    currentLevelMeshTransforms = new ArrayList<float[]>();

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

                    // Collect terrain parse info for UI display
                    final StringBuilder terrainParseInfo = new StringBuilder();

                    if (meshesFiles.isEmpty()) {
                        terrainParseInfo.append("  未找到.meshes文件!\n");
                        terrainParseInfo.append("  关卡路径: ").append(entry.fullPath).append("\n");
                        // Try to list what's in the level directory
                        try {
                            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath);
                            String levelDir = entry.fullPath.substring(0, entry.fullPath.lastIndexOf('/'));
                            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries2 = zf.entries();
                            int found = 0;
                            while (entries2.hasMoreElements()) {
                                java.util.zip.ZipEntry ze = entries2.nextElement();
                                String name = ze.getName();
                                if (name.toLowerCase().contains(levelDir.toLowerCase()) &&
                                    (name.endsWith(".meshes") || name.endsWith(".bin"))) {
                                    terrainParseInfo.append("  发现: ").append(name).append("\n");
                                    found++;
                                    if (found >= 10) break;
                                }
                            }
                            if (found == 0) {
                                terrainParseInfo.append("  目录中无.meshes或.bin文件\n");
                            }
                            zf.close();
                        } catch (Exception e) {
                            terrainParseInfo.append("  搜索错误: ").append(e.getMessage()).append("\n");
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
                                terrainParseInfo.append("  读取失败: ").append(meshesPath).append("\n");
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
                                int tocCount = meshesRaw[8] & 0xFF;
                                int fileVer = (meshesRaw[4] & 0xFF) | ((meshesRaw[5] & 0xFF) << 8) |
                                    ((meshesRaw[6] & 0xFF) << 16) | ((meshesRaw[7] & 0xFF) << 24);
                                debugLog("  File version: 0x" + Integer.toHexString(fileVer) + " TOC count: " + tocCount);
                                terrainParseInfo.append("  文件: ").append(new java.io.File(meshesPath).getName()).append("\n");
                                terrainParseInfo.append("  版本: 0x").append(Integer.toHexString(fileVer))
                                    .append(" TOC:").append(tocCount).append("\n");
                                for (int ti = 0; ti < tocCount && ti < 16; ti++) {
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
                                terrainParseInfo.append("  解析结果: 失败(null)\n");
                                // Dump GEO0 segment data if we can find it
                                if (meshesRaw.length >= 12) {
                                    int tocCount2 = meshesRaw[8] & 0xFF;
                                    for (int ti = 0; ti < tocCount2 && ti < 16; ti++) {
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
                            terrainParseInfo.append("  解析结果: 成功! ").append(md.vertexCount)
                                .append("顶点, ").append(md.indices.length).append("索引\n");
                            terrainParseInfo.append("  ").append(md.info).append("\n");
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
                            terrainParseInfo.append("  异常: ").append(e.getMessage()).append("\n");
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
                    final int maxMeshes = parseResult.meshEntries.size();
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

                            // Resolve per-mesh scale from PlaceableDefs
                            Float meshScaleVal = SkyResourceResolver.resolveScale(apkPath, meshPath);
                            final float meshScale = (meshScaleVal != null && meshScaleVal > 0) ? meshScaleVal : 1f;
                            if (meshScale != 1f) {
                                debugLog("Applying mesh scale " + meshScale + " to " + meshName);
                            }

                            // Build vertex data - game world space
                            final int vc = meshData.vertices.size();
                            final float[] positions = new float[vc * 3];
                            final List<float[]> normals = computeSmoothNormalsStatic(meshData);
                            int pi = 0;
                            for (float[] v : meshData.vertices) {
                                positions[pi++] = v[0] * meshScale;
                                positions[pi++] = v[1] * meshScale;
                                positions[pi++] = v[2] * meshScale;
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

                            // Save mesh data and transform for export
                            // Apply mesh scale to stored vertices so export matches preview
                            MeshData exportMeshData = meshData;
                            if (meshScale != 1f) {
                                java.util.List<float[]> scaledVerts = new java.util.ArrayList<float[]>(meshData.vertices.size());
                                for (float[] v : meshData.vertices) {
                                    scaledVerts.add(new float[]{v[0]*meshScale, v[1]*meshScale, v[2]*meshScale});
                                }
                                exportMeshData = new MeshData(
                                    meshData.name, meshData.sourcePath,
                                    scaledVerts, meshData.packedVertexAttrs, meshData.uv0,
                                    meshData.indices, meshData.boneWeights,
                                    meshData.embeddedSkeleton, meshData.version, meshData.isAnimated
                                );
                            }
                            currentLevelMeshes.add(exportMeshData);
                            currentLevelMeshTransforms.add(transform != null ? transform.clone() : null);

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
                            // Skip texture for Beamo and forceWhiteMesh entries
                            boolean skipTex = (meshNameFinal != null && meshNameFinal.toLowerCase().contains("beamo"))
                                || meshEntry.forceWhiteMesh;
                            if (!skipTex && !texQueuedNames.contains(meshNameFinal)) {
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

                    // Step 3: Load textures efficiently — resolve paths, batch read, batch GL upload
                    final String apkPathFinal = apkPath;
                    final int loadIdFinal = loadId;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            debugLog("Starting texture loading: " + texLoadQueue.size() + " textures");

                            // Step 3a: Resolve all texture paths (single thread, avoids ZipFile contention)
                            final java.util.List<Object[]> resolvedList =
                                new java.util.ArrayList<Object[]>(); // [meshName, texturePath]
                            java.util.Set<String> pathsToRead = new java.util.HashSet<String>();

                            for (final String[] texInfo : texLoadQueue) {
                                if (loadIdFinal != currentLoadId) return;
                                final String meshName = texInfo[0];
                                final String diffuse1 = texInfo[1];
                                final String diffuse2 = texInfo[2];
                                final String meshPath = texInfo[3];

                                String texturePath = null;
                                try {
                                    if (diffuse1 != null && !diffuse1.isEmpty()) {
                                        texturePath = SkyAssetScanner.findTextureEntry(apkPathFinal, diffuse1);
                                    }
                                    if (texturePath == null && diffuse2 != null && !diffuse2.isEmpty()) {
                                        texturePath = SkyAssetScanner.findTextureEntry(apkPathFinal, diffuse2);
                                    }
                                    if (texturePath == null) {
                                        String meshNameForTex = SkyResourceResolver.stripMeshVariantSuffix(
                                            nameWithoutExtension(new File(meshPath).getName())
                                        );
                                        com.sky.modelviewer.model.MaterialInfo mat =
                                            SkyResourceResolver.resolveMaterial(apkPathFinal, meshPath);
                                        texturePath = SkyResourceResolver.findTextureFile(
                                            apkPathFinal,
                                            new String[]{mat != null ? mat.diffuseTex : null,
                                                         mat != null ? mat.diffuse2Tex : null,
                                                         meshNameForTex}
                                        );
                                    }
                                } catch (Exception e) {
                                    debugLog("Texture resolve error for " + meshName + ": " + e.getMessage());
                                }

                                if (texturePath != null) {
                                    resolvedList.add(new Object[]{meshName, texturePath});
                                    pathsToRead.add(texturePath);
                                }
                            }

                            debugLog("Resolved " + resolvedList.size() + " texture paths, loading one by one...");

                            // Step 3b: Sequential loading — read one, upload to GL, release, next
                            int texLoaded = 0;
                            int texTotal = resolvedList.size();
                            for (int ti = 0; ti < texTotal; ti++) {
                                if (loadIdFinal != currentLoadId) break;
                                Object[] item = resolvedList.get(ti);
                                final String meshName = (String) item[0];
                                String texPath = (String) item[1];

                                byte[] texBytes = null;
                                try {
                                    texBytes = SkyAssetScanner.readApkEntry(apkPathFinal, texPath);
                                } catch (Exception e) {
                                    debugLog("Read error: " + meshName);
                                }
                                if (texBytes == null) continue;

                                final byte[] finalTexBytes = texBytes;
                                final int[] texIdResult = new int[]{0};
                                final java.util.concurrent.CountDownLatch latch =
                                    new java.util.concurrent.CountDownLatch(1);

                                meshViewport.queueEvent(new Runnable() {
                                    public void run() {
                                        try {
                                            int texId = KtxTextureLoader.loadTexture(finalTexBytes);
                                            if (texId == 0) {
                                                texId = KtxTextureLoader.loadStandardImage(finalTexBytes);
                                            }
                                            texIdResult[0] = texId;
                                            if (texId != 0) {
                                                meshViewport.getMeshRenderer().updateMeshTexture(meshName, texId);
                                            }
                                        } catch (Exception e) {
                                            debugLog("GL tex error: " + meshName);
                                        } finally {
                                            latch.countDown();
                                        }
                                    }
                                });

                                try {
                                    latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    break;
                                }

                                if (texIdResult[0] != 0) {
                                    texLoaded++;
                                }
                            }

                            debugLog("Texture upload done: " + texLoaded + "/" + texTotal);
                            meshViewport.requestRender();
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
                        if (terrainParseInfo.length() > 0) {
                            info.append(terrainParseInfo);
                        } else {
                            info.append("  检查: .meshes文件是否存在? GEO0解析? meshopt解码?\n");
                        }
                    }
                    for (com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData td : terrainDataList) {
                        info.append("  地形: ").append(td.vertexCount).append(" 顶点, ")
                           .append(td.indices.length).append(" indices\n");
                        info.append("  版本: ").append(td.info != null ? td.info : "unknown").append("\n");
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

                    // Collect version info
                    String versionStr = "";
                    if (!terrainDataList.isEmpty()) {
                        com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData firstMd = terrainDataList.get(0);
                        if (firstMd.fileVersion > 0) {
                            versionStr = " v0x" + Integer.toHexString(firstMd.fileVersion);
                        }
                    } else if (terrainParseInfo.length() > 0) {
                        // Extract version from parse info
                        String pi = terrainParseInfo.toString();
                        int vidx = pi.indexOf("版本: 0x");
                        if (vidx >= 0) {
                            int end = pi.indexOf(' ', vidx + 7);
                            if (end > vidx) {
                                versionStr = " " + pi.substring(vidx + 5, end);
                            } else {
                                versionStr = " " + pi.substring(vidx + 5).trim();
                            }
                        }
                    }

                    final String versionInfo = versionStr;

                    // Store terrain data for combined export
                    currentTerrainData = new ArrayList<com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData>(terrainDataList);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("Level" + versionInfo + ": " + terrainCount + " terrain + " + loadedCount + " meshes");
                            detailsBox.setText(info.toString());
                            btnExport.setEnabled(true);
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
                } catch (final OutOfMemoryError oom) {
                    debugLog("Level load OOM: " + oom.getMessage());
                    System.gc();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("内存不足，已加载部分模型");
                        }
                    });
                }
            }
        }).start();
    }

    private String findMeshInApk(String apkPath, String meshName) {
        // Try original name first
        String result = findMeshInApkInternal(apkPath, meshName);
        if (result != null) return result;

        // Try stripping common prefixes
        String[] prefixes = {"CharSkyKid_", "CharSkyNPC_", "CharSky_", "Char_"};
        for (String prefix : prefixes) {
            if (meshName.startsWith(prefix)) {
                String stripped = meshName.substring(prefix.length());
                debugLog("Mesh not found, trying stripped: " + meshName + " -> " + stripped);
                result = findMeshInApkInternal(apkPath, stripped);
                if (result != null) return result;
            }
        }

        // Try matching just the last meaningful part (after last underscore cluster)
        // e.g. "CharSkyKid_Body_FatLabor" -> "FatLabor"
        String[] parts = meshName.split("_");
        if (parts.length > 2) {
            // Try from the last 2 parts
            String shortName = parts[parts.length - 2] + "_" + parts[parts.length - 1];
            debugLog("Mesh not found, trying short name: " + meshName + " -> " + shortName);
            result = findMeshInApkInternal(apkPath, shortName);
            if (result != null) return result;
        }

        return null;
    }

    private String findMeshInApkInternal(String apkPath, String meshName) {
        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apkPath);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            String meshFile = meshName + ".mesh";
            String meshFileLower = meshFile.toLowerCase();
            String meshNameLower = meshName.toLowerCase();
            String fallbackMatch = null;
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String nameLower = name.toLowerCase();
                if (nameLower.endsWith(meshFileLower)) {
                    zipFile.close();
                    return name;
                }
                if (nameLower.endsWith(".mesh") && nameLower.contains(meshNameLower)) {
                    if (fallbackMatch == null) {
                        fallbackMatch = name;
                    }
                }
            }
            zipFile.close();
            if (fallbackMatch != null) {
                debugLog("Mesh fuzzy match: " + meshName + " -> " + fallbackMatch);
                return fallbackMatch;
            }
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

    private Runnable animSeekBarUpdater = null;

    private void startAnimSeekBarUpdate() {
        if (animSeekBarUpdater != null) {
            handler.removeCallbacks(animSeekBarUpdater);
        }
        animSeekBarUpdater = new Runnable() {
            @Override
            public void run() {
                if (animSeekBar == null || animSeekBar.getVisibility() != View.VISIBLE) return;
                float normalized = meshViewport.getMeshRenderer().getAnimTimeNormalized();
                int progress = (int)(normalized * 100f);
                if (progress < 0) progress = 0;
                if (progress > 100) progress = 100;
                animSeekBar.setProgress(progress);
                float duration = meshViewport.getMeshRenderer().getAnimDuration();
                float current = normalized * duration;
                animTimeText.setText(String.format("%.1f/%.1fs", current, duration));
                // Show debug info in animNameOverlay
                String dbg = meshViewport.getMeshRenderer().getAnimDebugInfo();
                if (animNameOverlay != null) {
                    animNameOverlay.setText(dbg);
                }
                handler.postDelayed(this, 50);
            }
        };
        handler.postDelayed(animSeekBarUpdater, 50);
    }

    // Cache for animpack dynamic/static classification
    private java.util.Map<String, Boolean> animDynamicCache = new java.util.HashMap<String, Boolean>();

    private boolean isAnimPackDynamic(MeshCatalogEntry entry) {
        if (animDynamicCache.containsKey(entry.name)) {
            return animDynamicCache.get(entry.name);
        }
        // Heuristic: if name contains "Anim" or "Dance" or "Walk" etc., likely dynamic
        // For now, mark all as dynamic until we can parse them
        // The actual classification happens when loaded
        return true;
    }

    private void showAnimSelectionDialog() {
        if (animPackEntries.isEmpty()) {
            Toast.makeText(this, "没有可用的动画文件", Toast.LENGTH_SHORT).show();
            return;
        }

        // Filter entries based on filter mode
        final List<MeshCatalogEntry> filtered = new ArrayList<MeshCatalogEntry>();
        for (MeshCatalogEntry e : animPackEntries) {
            Boolean isDyn = animDynamicCache.get(e.name);
            if (animFilterMode == 0) {
                // All
                filtered.add(e);
            } else if (animFilterMode == 1) {
                // Dynamic only
                if (isDyn == null || isDyn) filtered.add(e);
            } else {
                // Static only
                if (isDyn != null && !isDyn) filtered.add(e);
            }
        }

        if (filtered.isEmpty()) {
            Toast.makeText(this, "没有符合条件的动画", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] animNames = new String[filtered.size()];
        for (int i = 0; i < filtered.size(); i++) {
            MeshCatalogEntry e = filtered.get(i);
            Boolean isDyn = animDynamicCache.get(e.name);
            String tag = "";
            if (isDyn != null) tag = isDyn ? " [动态]" : " [静态]";
            animNames[i] = e.name + tag;
        }

        String filterText = animFilterMode == 0 ? "全部" : (animFilterMode == 1 ? "动态" : "静态");
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("选择动画 (" + filtered.size() + "/" + animPackEntries.size() + " " + filterText + ")");
        builder.setItems(animNames, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                if (which >= 0 && which < filtered.size()) {
                    MeshCatalogEntry entry = filtered.get(which);
                    loadAnimPackForMesh(entry);
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void loadAnimPackForMesh(final MeshCatalogEntry entry) {
        final String apkPath = currentApkPath;
        if (apkPath == null) return;

        statusText.setText("正在加载动画: " + entry.name);
        animNameOverlay.setText("加载中...");
        final int loadId = ++currentLoadId;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw = SkyAssetScanner.readApkEntry(apkPath, entry.fullPath);
                    if (loadId != currentLoadId) return; // Cancelled
                    if (raw == null) throw new RuntimeException("无法读取: " + entry.fullPath);

                    final com.sky.modelviewer.parsing.AnimPackParser.AnimPack anim =
                        com.sky.modelviewer.parsing.AnimPackParser.parse(raw);
                    if (loadId != currentLoadId) return; // Cancelled

                    if (anim == null) throw new RuntimeException("解析失败");

                    // Classify as dynamic or static
                    boolean isDynamic = false;
                    if (anim.segments != null && !anim.segments.isEmpty()) {
                        com.sky.modelviewer.parsing.AnimPackParser.AnimSegment seg = anim.segments.get(0);
                        if (seg.clipData != null && seg.clipData.rawBytes != null) {
                            com.sky.modelviewer.parsing.AnimPackParser.DecodedAnimation da =
                                com.sky.modelviewer.parsing.AnimPackParser.decodeAnimation(anim);
                            if (da != null && da.hasAnimation && da.frameCount > 1) {
                                isDynamic = true;
                            }
                        }
                    }
                    final boolean dynamic = isDynamic;
                    animDynamicCache.put(entry.name, dynamic);

                    if (loadId != currentLoadId) return; // Cancelled

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            animNameOverlay.setText("动画: " + anim.name + (dynamic ? " [动态]" : " [静态]"));
                            btnPlayPause.setVisibility(View.VISIBLE);
                            btnPlayPause.setText(dynamic ? "⏸" : "▶");
                            btnAnimFilter.setVisibility(View.VISIBLE);
                            animSeekBar.setVisibility(View.VISIBLE);
                            animSeekBar.setProgress(0);
                            animTimeText.setVisibility(View.VISIBLE);
                            animTimeText.setText("0.0s");
                            statusText.setText("动画: " + anim.name + " | 骨骼:" + anim.boneCount + " 压缩:" + anim.compression + (dynamic ? " 动态" : " 静态"));

                            // Store for wardrobe mode
                            if (wardrobeMode) {
                                wardrobeAnim = anim;
                                wardrobeAnimEntry = entry;
                            }

                            // Enable continuous rendering for animation playback
                            meshViewport.setContinuousRender(dynamic);

                            meshViewport.queueEvent(new Runnable() {
                                @Override
                                public void run() {
                                    meshViewport.getMeshRenderer().setAnimation(anim);
                                }
                            });

                            // Start periodic UI update for seek bar position
                            startAnimSeekBarUpdate();
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("动画加载失败: " + e.getMessage());
                            animNameOverlay.setText("动画加载失败");
                        }
                    });
                }
            }
        }).start();
    }

    private void loadAnimPack(final MeshCatalogEntry entry, final String apkPath, final int loadId) {
        statusText.setText("正在加载动画: " + entry.name);
        selectionTitle.setText(entry.name);
        selectionMeta.setText(entry.relativePath);
        detailsBox.setText("正在解析 " + entry.name + "...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw = SkyAssetScanner.readApkEntry(apkPath, entry.fullPath);
                    if (raw == null) throw new RuntimeException("无法读取: " + entry.fullPath);

                    final com.sky.modelviewer.parsing.AnimPackParser.AnimPack anim =
                        com.sky.modelviewer.parsing.AnimPackParser.parse(raw);

                    if (anim == null) throw new RuntimeException("解析失败");

                    StringBuilder sb = new StringBuilder();
                    sb.append("动画: ").append(anim.name).append("\n");
                    sb.append("版本: ").append(anim.version).append("\n");
                    sb.append("骨骼数: ").append(anim.boneCount).append("\n");
                    sb.append("压缩: ").append(anim.compression).append("\n");
                    sb.append("boneDefsFlag: ").append(anim.boneDefsFlag).append("\n");
                    sb.append("refSqtFlag: ").append(anim.refSqtFlag).append("\n");
                    sb.append("动画段: ").append(anim.segments != null ? anim.segments.size() : 0).append("\n");

                    // Segment info
                    if (anim.segments != null) {
                        for (int si = 0; si < Math.min(anim.segments.size(), 5); si++) {
                            com.sky.modelviewer.parsing.AnimPackParser.AnimSegment seg = anim.segments.get(si);
                            sb.append("段[").append(si).append("] refSqt=").append(seg.sqtList != null ? seg.sqtList.size() : 0);
                            sb.append(" comp=").append(seg.compressedSize);
                            sb.append(" decomp=").append(seg.decompressedSize);
                            if (seg.decompressionError != null && seg.decompressionError.length() > 0) {
                                sb.append(" ERR:").append(seg.decompressionError);
                            }
                            if (seg.clipData != null) {
                                sb.append(" clipSqt=").append(seg.clipData.sqtList != null ? seg.clipData.sqtList.size() : 0);
                                if (seg.clipData.keyframeHeader != null) {
                                    com.sky.modelviewer.parsing.AnimPackParser.KeyframeHeader kf = seg.clipData.keyframeHeader;
                                    sb.append("\n  kf: frames=").append(kf.field1).append("-").append(kf.field2);
                                    sb.append(" flags=0x").append(Integer.toHexString(kf.flags));
                                    sb.append(" frameCount=").append(kf.frameCount());
                                    if (kf.perBoneFlags != null) {
                                        int active = 0;
                                        for (byte pf : kf.perBoneFlags) if (pf != 0) active++;
                                        sb.append(" activeBones=").append(active);
                                    }
                                }
                            }
                            sb.append("\n");
                        }
                    }

                    sb.append("骨骼列表:\n");
                    for (int i = 0; i < Math.min(anim.bones.size(), 20); i++) {
                        com.sky.modelviewer.parsing.AnimPackParser.Bone b = anim.bones.get(i);
                        sb.append("  [").append(i).append("] ").append(b.name);
                        sb.append(" parent=").append(b.parentIndex);
                        if (b.isRoot()) sb.append(" (root)");
                        sb.append("\n");
                    }
                    if (anim.bones.size() > 20) {
                        sb.append("  ... (").append(anim.bones.size() - 20).append(" more)\n");
                    }

                    final String infoStr = sb.toString();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            detailsBox.setText(infoStr);
                            statusText.setText("动画已加载: " + anim.name + " (" + anim.boneCount + " bones)");

                            // Apply animation to current mesh on GL thread
                            meshViewport.queueEvent(new Runnable() {
                                @Override
                                public void run() {
                                    meshViewport.getMeshRenderer().setAnimation(anim);
                                    meshViewport.requestRender();
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("动画加载失败: " + e.getMessage());
                            detailsBox.setText("错误:\n" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
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

                    // Store for export
                    currentKtxRaw = raw;
                    currentKtxName = entry.name;

                    // Try to load as OpenGL texture and render it as a textured quad
                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            if (loadId != currentLoadId) return; // Cancelled
                            try {
                                // Decode to bitmap for export
                                android.graphics.Bitmap ktxBmp = null;
                                try {
                                    ktxBmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
                                } catch (Exception e) { }

                                int texId = KtxTextureLoader.loadTexture(raw);
                                if (texId == 0) {
                                    debugLog("KTX loadTexture returned 0, trying standard image loader");
                                    texId = KtxTextureLoader.loadStandardImage(raw);
                                }
                                if (texId != 0) {
                                    debugLog("KTX loaded as texture ID: " + texId);
                                    meshViewport.getMeshRenderer().showTexturedQuad(texId);

                                    // Parse KTX header for dimensions
                                    int kw = 0, kh = 0;
                                    try {
                                        java.nio.ByteBuffer kbb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                                        kw = kbb.getInt(36);
                                        kh = kbb.getInt(40);
                                        debugLog("KTX dimensions: " + kw + "x" + kh);
                                        meshViewport.getMeshRenderer().setTextureInfo(texId, kw, kh);
                                    } catch (Exception e) {
                                        debugLog("KTX header parse error: " + e.getMessage());
                                    }

                                    if (ktxBmp != null) {
                                        // Standard image — bitmap already available
                                        meshViewport.getMeshRenderer().setLastTextureBitmap(ktxBmp);
                                    } else if (kw > 0 && kh > 0) {
                                        // Compressed KTX — read back from GPU immediately while on GL thread
                                        debugLog("Reading texture from GPU for export cache...");
                                        android.graphics.Bitmap gpuBmp = meshViewport.getMeshRenderer().readTextureFromGPU();
                                        if (gpuBmp != null) {
                                            debugLog("GPU texture read OK: " + gpuBmp.getWidth() + "x" + gpuBmp.getHeight());
                                        } else {
                                            debugLog("GPU texture read FAILED");
                                        }
                                    }
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
                            btnExport.setEnabled(true);
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
        if ("ktx".equals(currentEntryType)) {
            // Texture: show PNG/JPEG choice
            showExportFormatDialog(new String[]{"PNG", "JPEG"},
                new Runnable[]{new Runnable(){public void run(){requestExportFile("texture.png", "image/png", REQUEST_EXPORT_PNG);}},
                               new Runnable(){public void run(){requestExportFile("texture.jpg", "image/jpeg", REQUEST_EXPORT_JPEG);}}});
            return;
        }

        if ("level".equals(currentEntryType)) {
            // Level/map: combined export (terrain + mesh instances)
            boolean hasTerrain = currentTerrainData != null && !currentTerrainData.isEmpty();
            boolean hasMeshes = currentLevelMeshes != null && !currentLevelMeshes.isEmpty();
            if (!hasTerrain && !hasMeshes) {
                Toast.makeText(this, "无可导出的关卡数据", Toast.LENGTH_SHORT).show();
                return;
            }
            showExportFormatDialog(new String[]{"OBJ (完整)", "GLB (完整)", "FBX (完整)"},
                new Runnable[]{new Runnable(){public void run(){requestExportFile("terrain.obj", "application/octet-stream", REQUEST_EXPORT_COMBINED_OBJ);}},
                               new Runnable(){public void run(){requestExportFile("terrain.glb", "model/gltf-binary", REQUEST_EXPORT_COMBINED_GLB);}},
                               new Runnable(){public void run(){requestExportFile("terrain.fbx", "application/octet-stream", REQUEST_EXPORT_COMBINED_FBX);}}});
            return;
        }

        // Single mesh: OBJ / GLB / FBX
        final MeshData meshData = currentMeshData;
        if (meshData == null) {
            Toast.makeText(this, "无模型数据", Toast.LENGTH_SHORT).show();
            return;
        }
        showExportFormatDialog(new String[]{"OBJ", "GLB", "FBX"},
            new Runnable[]{new Runnable(){public void run(){requestExportFile(meshData.name + ".obj", "application/octet-stream", REQUEST_EXPORT_OBJ);}},
                           new Runnable(){public void run(){requestExportFile(meshData.name + ".glb", "model/gltf-binary", REQUEST_CREATE_GLB);}},
                           new Runnable(){public void run(){requestExportFile(meshData.name + ".fbx", "application/octet-stream", REQUEST_EXPORT_FBX);}}});
    }

    private void showExportFormatDialog(String[] labels, final Runnable[] actions) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择导出格式");
        builder.setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which >= 0 && which < actions.length) {
                    actions[which].run();
                }
            }
        });
        builder.show();
    }

    private void requestExportFile(String defaultName, String mimeType, int requestCode) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_TITLE, defaultName);
            startActivityForResult(intent, requestCode);
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

    private void startObjExport(final Uri uri) {
        final MeshData meshData = currentMeshData;
        if (meshData == null) return;
        statusText.setText("正在导出OBJ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    float scale = currentScale;
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        com.sky.modelviewer.export.ObjExporter.exportMesh(os, meshData, scale);
                        os.close();
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("已导出 " + meshData.name + ".obj");
                            Toast.makeText(MainActivity.this, "OBJ导出成功", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("OBJ导出失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void startFbxExport(final Uri uri) {
        final MeshData meshData = currentMeshData;
        if (meshData == null) return;
        statusText.setText("正在导出FBX...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    float scale = currentScale;
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        com.sky.modelviewer.export.FbxExporter.exportMesh(os, meshData, scale);
                        os.close();
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("已导出 " + meshData.name + ".fbx");
                            Toast.makeText(MainActivity.this, "FBX导出成功", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("FBX导出失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void startTextureExport(final Uri uri, final boolean isPng) {
        final byte[] ktxRaw = currentKtxRaw;
        if (ktxRaw == null) {
            Toast.makeText(this, "无贴图数据", Toast.LENGTH_SHORT).show();
            return;
        }
        final String fmtName = isPng ? "PNG" : "JPEG";
        statusText.setText("正在解码纹理...");

        // First try BitmapFactory (for standard images like PNG/JPG stored as KTX)
        android.graphics.Bitmap bitmap = null;
        try {
            bitmap = android.graphics.BitmapFactory.decodeByteArray(ktxRaw, 0, ktxRaw.length);
        } catch (Exception e) { }

        if (bitmap != null) {
            doTextureExport(uri, isPng, fmtName, bitmap);
            return;
        }

        // Use pure Java KTX decoder (ETC2, DXT, BC4, BC7, etc.)
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int[] dims = com.sky.modelviewer.parsing.KtxDecoder.getDimensions(ktxRaw);
                    if (dims == null) {
                        throw new RuntimeException("无效的KTX文件");
                    }
                    int w = dims[0], h = dims[1], fmt = dims[2];
                    debugLog("KTX decode: " + w + "x" + h + " format=0x" + Integer.toHexString(fmt));

                    int[] pixels = com.sky.modelviewer.parsing.KtxDecoder.decode(ktxRaw);
                    if (pixels == null) {
                        throw new RuntimeException("不支持的纹理格式: 0x" + Integer.toHexString(fmt));
                    }

                    final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888);
                    doTextureExport(uri, isPng, fmtName, bmp);
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText(fmtName + "导出失败: " + e.getMessage());
                            Toast.makeText(MainActivity.this, fmtName + "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void manualKtxDecodeAndExport(final Uri uri, final boolean isPng,
                                          final String fmtName, final byte[] ktxRaw) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Parse KTX header to get width/height
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(ktxRaw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    int width = bb.getInt(36);
                    int height = bb.getInt(40);
                    int glInternalFormat = bb.getInt(28);
                    int keyValueDataSize = bb.getInt(60);

                    if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
                        throw new RuntimeException("Invalid KTX dimensions: " + width + "x" + height);
                    }

                    // Get first mip level data offset
                    int offset = 64 + keyValueDataSize;
                    if (offset + 4 > ktxRaw.length) {
                        throw new RuntimeException("KTX data too short");
                    }
                    int imageSize = bb.getInt(offset);
                    offset += 4;

                    if (offset + imageSize > ktxRaw.length) {
                        throw new RuntimeException("KTX image data truncated");
                    }

                    // Try to decode based on format
                    // For uncompressed RGBA/RGB formats
                    android.graphics.Bitmap bitmap = null;

                    if (glInternalFormat == 0x1908 || glInternalFormat == 0x8058) {
                        // GL_RGBA / GL_RGBA8 — uncompressed
                        int[] pixels = new int[width * height];
                        for (int i = 0; i < width * height; i++) {
                            int p = offset + i * 4;
                            if (p + 3 >= ktxRaw.length) break;
                            int r = ktxRaw[p] & 0xFF;
                            int g = ktxRaw[p + 1] & 0xFF;
                            int b = ktxRaw[p + 2] & 0xFF;
                            int a = ktxRaw[p + 3] & 0xFF;
                            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                        }
                        bitmap = android.graphics.Bitmap.createBitmap(pixels, width, height,
                                android.graphics.Bitmap.Config.ARGB_8888);
                    } else if (glInternalFormat == 0x8051 || glInternalFormat == 0x1907) {
                        // GL_RGB / GL_RGB8 — uncompressed RGB
                        int[] pixels = new int[width * height];
                        for (int i = 0; i < width * height; i++) {
                            int p = offset + i * 3;
                            if (p + 2 >= ktxRaw.length) break;
                            int r = ktxRaw[p] & 0xFF;
                            int g = ktxRaw[p + 1] & 0xFF;
                            int b = ktxRaw[p + 2] & 0xFF;
                            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                        }
                        bitmap = android.graphics.Bitmap.createBitmap(pixels, width, height,
                                android.graphics.Bitmap.Config.ARGB_8888);
                    } else {
                        // Compressed format (ETC2, BC4, BC7, etc.)
                        // Create a placeholder bitmap with the texture info
                        throw new RuntimeException("压缩格式 0x" + Integer.toHexString(glInternalFormat) +
                                " 需要GPU解码，请先在3D视口中预览贴图后再导出");
                    }

                    if (bitmap == null) {
                        throw new RuntimeException("KTX解码失败");
                    }

                    final android.graphics.Bitmap finalBmp = bitmap;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            doTextureExport(uri, isPng, fmtName, finalBmp);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText(fmtName + "导出失败: " + e.getMessage());
                            Toast.makeText(MainActivity.this, fmtName + "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void doTextureExport(final Uri uri, final boolean isPng,
                                 final String fmtName, final android.graphics.Bitmap bitmap) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        if (isPng) {
                            com.sky.modelviewer.export.TextureExporter.exportPng(os, bitmap);
                        } else {
                            com.sky.modelviewer.export.TextureExporter.exportJpeg(os, bitmap, 95);
                        }
                        os.close();
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("已导出 " + fmtName + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                            Toast.makeText(MainActivity.this, fmtName + "导出成功", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText(fmtName + "导出失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void startCombinedObjExport(final Uri uri) {
        final List<com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData> terrains = currentTerrainData;
        final List<MeshData> levelMeshes = currentLevelMeshes;
        final List<float[]> levelTransforms = currentLevelMeshTransforms;
        statusText.setText("正在导出完整OBJ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData[] terrainArr =
                        (terrains != null) ?
                        terrains.toArray(new com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData[0]) :
                        null;
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        com.sky.modelviewer.export.ObjExporter.exportCombinedLevel(
                            os, terrainArr, levelMeshes, levelTransforms, 1.0f, "level");
                        os.close();
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            String meshInfo = levelMeshes != null ? "+" + levelMeshes.size() + " meshes" : "";
                            statusText.setText("完整OBJ导出成功 " + meshInfo);
                            Toast.makeText(MainActivity.this, "完整OBJ导出成功", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("完整OBJ导出失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void startCombinedGlbExport(final Uri uri) {
        final List<com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData> terrains = currentTerrainData;
        final List<MeshData> levelMeshes = currentLevelMeshes;
        final List<float[]> levelTransforms = currentLevelMeshTransforms;
        statusText.setText("正在导出完整GLB...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData[] terrainArr =
                        (terrains != null) ?
                        terrains.toArray(new com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData[0]) :
                        null;
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        com.sky.modelviewer.export.GlbExporter.exportCombinedLevel(
                            os, terrainArr, levelMeshes, levelTransforms, 1.0f, true);
                        os.close();
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            String meshInfo = levelMeshes != null ? "+" + levelMeshes.size() + " meshes" : "";
                            statusText.setText("完整GLB导出成功 " + meshInfo);
                            Toast.makeText(MainActivity.this, "完整GLB导出成功", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("完整GLB导出失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void startCombinedFbxExport(final Uri uri) {
        final List<com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData> terrains = currentTerrainData;
        final List<MeshData> levelMeshes = currentLevelMeshes;
        final List<float[]> levelTransforms = currentLevelMeshTransforms;
        statusText.setText("正在导出完整FBX...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData[] terrainArr =
                        (terrains != null) ?
                        terrains.toArray(new com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData[0]) :
                        null;
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        com.sky.modelviewer.export.FbxExporter.exportCombinedLevel(
                            os, terrainArr, levelMeshes, levelTransforms, 1.0f, "level");
                        os.close();
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            String meshInfo = levelMeshes != null ? "+" + levelMeshes.size() + " meshes" : "";
                            statusText.setText("完整FBX导出成功 " + meshInfo);
                            Toast.makeText(MainActivity.this, "完整FBX导出成功", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("完整FBX导出失败: " + e.getMessage());
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
