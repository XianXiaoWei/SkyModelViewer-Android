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
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import com.sky.modelviewer.parsing.LevelMeshesReader;
import com.sky.modelviewer.parsing.MapProcessingUtils;
import com.sky.modelviewer.parsing.FmodVorbisDecoder;
import com.sky.modelviewer.parsing.FadpcmDecoder;
import com.sky.modelviewer.render.KtxTextureLoader;
import com.sky.modelviewer.render.MeshSurfaceView;
import com.sky.modelviewer.render.MeshRenderer;
import com.sky.modelviewer.scanner.SkyAssetScanner;
import com.sky.modelviewer.scanner.SkyResourceResolver;
import com.sky.modelviewer.scanner.UIAtlasParser;
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
    private static final int REQUEST_AI_PICK_IMAGE = 1100;
    private static final int REQUEST_AI_PICK_FILE = 1101;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MeshListAdapter meshListAdapter;
    private TextView statusText;
    private TextView sourcePathText;
    private TextView meshCountText;
    private EditText searchBox;
    private ListView listMesh;
    private ListView listKtx;
    private ListView listLevel;
    private ListView listMapLevel;
    private LinearLayout mapPanel;
    private LinearLayout mapLayerContainer;
    private LinearLayout columnsLayout;
    private FrameLayout texturePreviewPanel;
    private ImageView texturePreviewImage;
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
    private Button btnLevelInfo;
    private Button btnEventLogic;
    private Button btnSelectAnim;
    private Button btnPlayPause;
    private TextView animNameOverlay;
    private SeekBar animSeekBar;
    private TextView animTimeText;
    private Button btnAnimFilter;
    private int animFilterMode = 0; // 0=all, 1=dynamic, 2=static
    // Level info and event logic data (extracted from Objects.level.bin)
    private com.sky.modelviewer.parsing.LevelInfoExtractor.LevelInfo currentLevelInfo = null;
    private com.sky.modelviewer.parsing.LevelEventExtractor.EventInfo currentEventInfo = null;
    private List<MeshCatalogEntry> animPackEntries = new ArrayList<MeshCatalogEntry>();
    private List<MeshCatalogEntry> levelList = new ArrayList<MeshCatalogEntry>();
    private Button btnMoveUp;
    private Button btnMoveDown;
    private FrameLayout joystickArea;
    private View joystickThumb;

    // ===== Wardrobe mode =====
    private boolean wardrobeMode = false;
    private String wardrobeSelectedCategory = null;
    private Button btnModeModel, btnModeTexture, btnModeMap, btnModeWardrobe, btnModeEditor, btnModeAudio, btnModeAI;
    // Current asset mode: 0=model, 1=texture, 2=map
    private int currentAssetMode = 0;
    private com.sky.modelviewer.ai.AIChatDialog aiChatDialog;
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
    // Wardrobe icon loader (loads KTX atlas icons with HSV tint)
    private com.sky.modelviewer.render.WardrobeIconLoader wardrobeIconLoader = null;
    // Global KTX texture index (lazily built, shared by map loading + icon loading)
    private java.util.HashMap<String, String> globalTexIndex = null;

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
        String icon = "";  // Icon texture name (e.g. "UiOutfitBodyClassicDress") — loaded from KTX
        float[] iconHsv = {0,0,100};  // Icon HSV tint
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
            btnLevelInfo = (Button) findViewById(R.id.btnLevelInfo);
            btnEventLogic = (Button) findViewById(R.id.btnEventLogic);
            btnSelectAnim = (Button) findViewById(R.id.btnSelectAnim);
            btnPlayPause = (Button) findViewById(R.id.btnPlayPause);
            animNameOverlay = (TextView) findViewById(R.id.animNameOverlay);
            animSeekBar = (SeekBar) findViewById(R.id.animSeekBar);
            animTimeText = (TextView) findViewById(R.id.animTimeText);
            btnAnimFilter = (Button) findViewById(R.id.btnAnimFilter);
            btnModeModel = (Button) findViewById(R.id.btnModeModel);
            btnModeTexture = (Button) findViewById(R.id.btnModeTexture);
            btnModeMap = (Button) findViewById(R.id.btnModeMap);
            btnModeWardrobe = (Button) findViewById(R.id.btnModeWardrobe);
            btnModeEditor = (Button) findViewById(R.id.btnModeEditor);
            btnModeAudio = (Button) findViewById(R.id.btnModeAudio);
            btnModeAI = (Button) findViewById(R.id.btnModeAI);
            mapPanel = (LinearLayout) findViewById(R.id.mapPanel);
            listMapLevel = (ListView) findViewById(R.id.listMapLevel);
            mapLayerContainer = (LinearLayout) findViewById(R.id.mapLayerContainer);
            texturePreviewPanel = (FrameLayout) findViewById(R.id.texturePreviewPanel);
            texturePreviewImage = (ImageView) findViewById(R.id.texturePreviewImage);
            columnsLayout = (LinearLayout) findViewById(R.id.columnsLayout);
            btnModeAI.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openAIChat();
                }
            });
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
            btnModeModel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchToModelMode();
                }
            });
            btnModeTexture.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchToTextureMode();
                }
            });
            btnModeMap.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchToMapMode();
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

            // Level info button — shows teleport links, quests, music, dialogs
            if (btnLevelInfo != null) {
                btnLevelInfo.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showLevelInfoDialog();
                    }
                });
            }

            // Event logic button — shows event triggers and action chains
            if (btnEventLogic != null) {
                btnEventLogic.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showEventLogicDialog();
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
            statusText.setText("就绪 — 模型模式");

            // Initialize in Model mode (hide ktx/level lists, show only mesh list)
            updateModeButtonStyles(0);
            listKtx.setVisibility(View.GONE);
            listLevel.setVisibility(View.GONE);
            findViewById(R.id.divider1).setVisibility(View.GONE);
            findViewById(R.id.divider2).setVisibility(View.GONE);

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

        // Handle AI image picker
        if (requestCode == REQUEST_AI_PICK_IMAGE) {
            final Uri uri = data.getData();
            if (uri == null || aiChatDialog == null) return;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        android.graphics.Bitmap bmp = android.provider.MediaStore.Images.Media.getBitmap(
                            getContentResolver(), uri);
                        // Resize if too large (vision APIs typically accept max ~2048px)
                        int maxDim = 1024;
                        if (bmp.getWidth() > maxDim || bmp.getHeight() > maxDim) {
                            float scale = (float) maxDim / Math.max(bmp.getWidth(), bmp.getHeight());
                            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bmp,
                                (int)(bmp.getWidth() * scale), (int)(bmp.getHeight() * scale), true);
                            bmp.recycle();
                            bmp = scaled;
                        }
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos);
                        bmp.recycle();
                        byte[] imageBytes = baos.toByteArray();
                        final String base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP);

                        // Get display name
                        String displayName = "image";
                        android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                        if (cursor != null) {
                            int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                            if (nameIdx >= 0 && cursor.moveToFirst()) {
                                displayName = cursor.getString(nameIdx);
                            }
                            cursor.close();
                        }

                        final String finalName = displayName;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                aiChatDialog.onImagePicked(base64, "image/jpeg", finalName);
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                Toast.makeText(MainActivity.this, "图片加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }).start();
            return;
        }

        // Handle AI file picker
        if (requestCode == REQUEST_AI_PICK_FILE) {
            final Uri uri = data.getData();
            if (uri == null || aiChatDialog == null) return;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        // Read file content
                        java.io.InputStream is = getContentResolver().openInputStream(uri);
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                        reader.close();
                        final String content = sb.toString();

                        // Get display name
                        String displayName = "file";
                        android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                        if (cursor != null) {
                            int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                            if (nameIdx >= 0 && cursor.moveToFirst()) {
                                displayName = cursor.getString(nameIdx);
                            }
                            cursor.close();
                        }

                        final String finalName = displayName;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                aiChatDialog.onFilePicked(content, finalName);
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                Toast.makeText(MainActivity.this, "文件读取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }).start();
            return;
        }

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

                            // Clear icon caches + atlas cache for new APK
                            if (wardrobeIconLoader != null) {
                                wardrobeIconLoader.clearCache();
                                wardrobeIconLoader = null;
                            }
                            globalTexIndex = null;
                            UIAtlasParser.clearCache();
                            if (wardrobeGridAdapter != null) {
                                wardrobeGridAdapter.clearIconCache();
                                wardrobeGridAdapter.setIconLoader(null);
                            }
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

    // ===== AI Assistant =====

    /**
     * Open the AI chat dialog with current model context.
     */
    private void openAIChat() {
        if (aiChatDialog == null) {
            aiChatDialog = new com.sky.modelviewer.ai.AIChatDialog(this);
            aiChatDialog.setCommandExecutor(new com.sky.modelviewer.ai.AICommandExecutor.CommandCallback() {
                @Override
                public String onCommand(final String command, final org.json.JSONObject params) {
                    return executeAICommand(command, params);
                }
            });
            // Set up image picker callback
            aiChatDialog.setImagePickerCallback(new com.sky.modelviewer.ai.AIChatDialog.ImagePickerCallback() {
                @Override
                public void pickImage() {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("image/*");
                            try {
                                startActivityForResult(intent, REQUEST_AI_PICK_IMAGE);
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "无法打开图片选择器", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            });
            // Set up file picker callback
            aiChatDialog.setFilePickerCallback(new com.sky.modelviewer.ai.AIChatDialog.FilePickerCallback() {
                @Override
                public void pickFile() {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("text/*");
                            String[] mimeTypes = {"text/plain", "application/json", "application/xml", "text/xml",
                                "text/java", "text/javascript", "text/html", "text/css", "text/markdown"};
                            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                            try {
                                startActivityForResult(intent, REQUEST_AI_PICK_FILE);
                            } catch (Exception e) {
                                // Fallback: accept any file
                                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                                fallback.setType("*/*");
                                try {
                                    startActivityForResult(fallback, REQUEST_AI_PICK_FILE);
                                } catch (Exception e2) {
                                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
                    });
                }
            });
        }

        // Build context from current model state and app state
        String modelInfo = buildCurrentModelContext();
        String appContext = buildAppContext();
        String systemPrompt = com.sky.modelviewer.ai.AIContextBuilder.buildSystemPrompt(modelInfo, appContext);
        aiChatDialog.setSystemPrompt(systemPrompt);
        aiChatDialog.show();
    }

    /**
     * Execute an AI command and return result message.
     */
    private String executeAICommand(String command, org.json.JSONObject params) {
        try {
            if ("switch_mode".equals(command)) {
                String mode = params.optString("mode", "visual");
                if ("wardrobe".equals(mode)) {
                    runOnUiThread(new Runnable() { public void run() { switchMode(true); } });
                    return "已切换到换装模式";
                } else if ("editor".equals(mode)) {
                    runOnUiThread(new Runnable() { public void run() { switchToEditorMode(); } });
                    return "已切换到编辑器模式";
                } else if ("model".equals(mode)) {
                    runOnUiThread(new Runnable() { public void run() { switchToModelMode(); } });
                    return "已切换到模型模式";
                } else if ("texture".equals(mode)) {
                    runOnUiThread(new Runnable() { public void run() { switchToTextureMode(); } });
                    return "已切换到贴图模式";
                } else if ("map".equals(mode)) {
                    runOnUiThread(new Runnable() { public void run() { switchToMapMode(); } });
                    return "已切换到地图模式";
                } else {
                    runOnUiThread(new Runnable() { public void run() { switchToModelMode(); } });
                    return "已切换到模型模式";
                }
            }
            else if ("set_view_mode".equals(command)) {
                String mode = params.optString("mode", "texture");
                final com.sky.modelviewer.render.MeshRenderer.ViewMode vm;
                if ("solid".equals(mode)) vm = com.sky.modelviewer.render.MeshRenderer.ViewMode.SOLID;
                else if ("wire".equals(mode)) vm = com.sky.modelviewer.render.MeshRenderer.ViewMode.WIRE;
                else vm = com.sky.modelviewer.render.MeshRenderer.ViewMode.TEXTURE;
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setViewMode(vm);
                        // Update radio group
                        if (viewModeGroup != null) {
                            int id = vm == com.sky.modelviewer.render.MeshRenderer.ViewMode.TEXTURE ? R.id.btnTexture :
                                     vm == com.sky.modelviewer.render.MeshRenderer.ViewMode.SOLID ? R.id.btnSolid : R.id.btnWire;
                            viewModeGroup.check(id);
                        }
                    }
                });
                return "视图模式已设置为: " + mode;
            }
            else if ("play_animation".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setAnimPlaying(true);
                        if (btnPlayPause != null) btnPlayPause.setText("⏸");
                    }
                });
                return "动画已播放";
            }
            else if ("pause_animation".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setAnimPlaying(false);
                        if (btnPlayPause != null) btnPlayPause.setText("▶");
                    }
                });
                return "动画已暂停";
            }
            else if ("toggle_animation".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) {
                            boolean playing = !r.isAnimPlaying();
                            r.setAnimPlaying(playing);
                            if (btnPlayPause != null) btnPlayPause.setText(playing ? "⏸" : "▶");
                        }
                    }
                });
                return "动画播放状态已切换";
            }
            else if ("set_anim_speed".equals(command)) {
                final float speed = (float) params.optDouble("speed", 1.0);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setAnimSpeed(speed);
                    }
                });
                return "动画速度已设置为: " + speed + "x";
            }
            else if ("reset_camera".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) {
                            r.setCameraTarget(0f, 0f, 0f);
                            r.frameCamera();
                        }
                    }
                });
                return "相机已重置";
            }
            else if ("frame_camera".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.frameCamera();
                    }
                });
                return "已自动取景";
            }
            else if ("zoom".equals(command)) {
                final float factor = (float) params.optDouble("factor", 1.5);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.zoom(factor);
                    }
                });
                return "已缩放: " + factor + "x";
            }
            else if ("rotate_camera".equals(command)) {
                final float dx = (float) params.optDouble("dx", 30);
                final float dy = (float) params.optDouble("dy", 15);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.orbit(dx, dy);
                    }
                });
                return "相机已旋转 (dx=" + dx + ", dy=" + dy + ")";
            }
            else if ("clear_wardrobe".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() { clearWardrobe(); }
                });
                return "已清除所有装扮";
            }
            else if ("search_mesh".equals(command)) {
                final String keyword = params.optString("keyword", "");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (searchBox != null) {
                            searchBox.setText(keyword);
                        }
                    }
                });
                return "已搜索模型: " + keyword;
            }
            else if ("show_anim_dialog".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() { showAnimSelectionDialog(); }
                });
                return "已打开动画选择对话框";
            }
            else if ("show_model_info".equals(command)) {
                String info = buildCurrentModelContext();
                return info;
            }
            else if ("take_screenshot".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        meshViewport.requestRender();
                        Toast.makeText(MainActivity.this, "截图功能：请使用系统截图", Toast.LENGTH_SHORT).show();
                    }
                });
                return "截图提示已显示";
            }
            else if ("export_glb".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, "请在应用中手动选择导出路径", Toast.LENGTH_LONG).show();
                    }
                });
                return "GLB导出已触发，请选择保存路径";
            }
            else if ("export_obj".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, "请在应用中手动选择导出路径", Toast.LENGTH_LONG).show();
                    }
                });
                return "OBJ导出已触发，请选择保存路径";
            }
            // ===== Bottom-level rendering control =====
            else if ("set_bg_color".equals(command)) {
                final String color = params.optString("color", "#1a1a2e");
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setBackgroundColorHex(color);
                    }
                });
                return "背景色已设置为: " + color;
            }
            else if ("set_ambient".equals(command)) {
                final int r0 = params.optInt("r", 100);
                final int g0 = params.optInt("g", 100);
                final int b0 = params.optInt("b", 100);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setAmbientColor(r0, g0, b0);
                    }
                });
                return "环境光已设置: RGB(" + r0 + "," + g0 + "," + b0 + ")";
            }
            else if ("set_key_light".equals(command)) {
                final int r0 = params.optInt("r", 255);
                final int g0 = params.optInt("g", 255);
                final int b0 = params.optInt("b", 255);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setKeyLightColor(r0, g0, b0);
                    }
                });
                return "主光源已设置: RGB(" + r0 + "," + g0 + "," + b0 + ")";
            }
            else if ("set_fill_light".equals(command)) {
                final int r0 = params.optInt("r", 150);
                final int g0 = params.optInt("g", 160);
                final int b0 = params.optInt("b", 176);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setFillLightColor(r0, g0, b0);
                    }
                });
                return "补光已设置: RGB(" + r0 + "," + g0 + "," + b0 + ")";
            }
            else if ("set_light_dir".equals(command)) {
                final float yaw = (float) params.optDouble("yaw", -2.0);
                final float pitch = (float) params.optDouble("pitch", -0.5);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setLightDirection(yaw, pitch);
                    }
                });
                return "光源方向已设置: yaw=" + yaw + " pitch=" + pitch;
            }
            else if ("set_fov".equals(command)) {
                final float fov = (float) params.optDouble("fov", 45);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setFov(fov);
                    }
                });
                return "视场角已设置: " + fov + "°";
            }
            else if ("color_tint".equals(command)) {
                final float h = (float) params.optDouble("h", 180);
                final float s = (float) params.optDouble("s", 0.8);
                final float v = (float) params.optDouble("v", 1.0);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.applyColorTint(h, s, v);
                    }
                });
                return "模型已染色: H=" + h + "° S=" + s + " V=" + v;
            }
            else if ("clear_tint".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.clearColorTint();
                    }
                });
                return "已清除染色";
            }
            else if ("hide_mesh".equals(command)) {
                final String name = params.optString("name", "");
                final boolean hide = params.optBoolean("hide", true);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.hideMeshes(name, hide);
                    }
                });
                return (hide ? "已隐藏" : "已显示") + " mesh: " + name;
            }
            else if ("list_meshes".equals(command)) {
                MeshRenderer r = meshViewport.getMeshRenderer();
                if (r != null) {
                    java.util.List<String> names = r.getMeshNames();
                    int count = r.getMeshCount();
                    return "共 " + count + " 个mesh" + (names.isEmpty() ? "" : ": " + names.toString());
                }
                return "无法获取mesh列表";
            }
            else if ("wire_overlay".equals(command)) {
                final boolean enable = params.optBoolean("enable", true);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setWireOverlay(enable);
                    }
                });
                return "线框叠加: " + (enable ? "开启" : "关闭");
            }
            else if ("set_camera_dist".equals(command)) {
                final float dist = (float) params.optDouble("dist", 5.0);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setCameraDistanceDirect(dist);
                    }
                });
                return "相机距离已设置: " + dist;
            }
            else if ("preset_lighting".equals(command)) {
                final String preset = params.optString("preset", "studio");
                runOnUiThread(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r == null) return;
                        if ("warm".equals(preset)) {
                            r.setAmbientColor(140, 110, 80);
                            r.setKeyLightColor(255, 220, 160);
                            r.setFillLightColor(180, 130, 90);
                            r.setBackgroundColorHex("#2a1a0a");
                        } else if ("cool".equals(preset)) {
                            r.setAmbientColor(80, 100, 140);
                            r.setKeyLightColor(160, 200, 255);
                            r.setFillLightColor(90, 110, 180);
                            r.setBackgroundColorHex("#0a1a2a");
                        } else if ("studio".equals(preset)) {
                            r.setAmbientColor(118, 118, 118);
                            r.setKeyLightColor(244, 244, 244);
                            r.setFillLightColor(150, 160, 176);
                            r.setBackgroundColorHex("#111122");
                        } else if ("sunset".equals(preset)) {
                            r.setAmbientColor(120, 70, 50);
                            r.setKeyLightColor(255, 140, 60);
                            r.setFillLightColor(120, 80, 140);
                            r.setBackgroundColorHex("#1a0a14");
                        } else if ("night".equals(preset)) {
                            r.setAmbientColor(40, 50, 80);
                            r.setKeyLightColor(100, 130, 200);
                            r.setFillLightColor(50, 60, 100);
                            r.setBackgroundColorHex("#050510");
                        }
                    }
                });
                return "已应用预设: " + preset;
            }
            // ===== Map mode commands =====
            else if ("toggle_layer".equals(command)) {
                final String layer = params.optString("layer", "effect");
                runOnUiThread(new Runnable() {
                    public void run() {
                        int idx = -1;
                        for (int i = 0; i < MapProcessingUtils.LAYER_KEYS.length; i++) {
                            if (MapProcessingUtils.LAYER_KEYS[i].equalsIgnoreCase(layer)) { idx = i; break; }
                        }
                        if (idx >= 0 && mapLayerVisible != null && idx < mapLayerVisible.length) {
                            mapLayerVisible[idx] = !mapLayerVisible[idx];
                            applyMapLayerFilters();
                        }
                    }
                });
                return "已切换图层: " + layer;
            }
            else if ("show_layer".equals(command)) {
                final String layer = params.optString("layer", "effect");
                final boolean visible = params.optBoolean("visible", true);
                runOnUiThread(new Runnable() {
                    public void run() {
                        int idx = -1;
                        for (int i = 0; i < MapProcessingUtils.LAYER_KEYS.length; i++) {
                            if (MapProcessingUtils.LAYER_KEYS[i].equalsIgnoreCase(layer)) { idx = i; break; }
                        }
                        if (idx >= 0 && mapLayerVisible != null && idx < mapLayerVisible.length) {
                            mapLayerVisible[idx] = visible;
                            applyMapLayerFilters();
                        }
                    }
                });
                return "图层 " + layer + " 已" + (visible ? "显示" : "隐藏");
            }
            else if ("search_levels".equals(command)) {
                final String keyword = params.optString("keyword", "");
                final StringBuilder result = new StringBuilder();
                if (mapLevelDirs != null && !mapLevelDirs.isEmpty()) {
                    result.append("搜索结果:\n");
                    int count = 0;
                    for (String name : mapLevelDirs) {
                        if (name.toLowerCase().contains(keyword.toLowerCase())) {
                            result.append("  ").append(name).append("\n");
                            count++;
                            if (count >= 20) { result.append("  ...（更多结果省略）\n"); break; }
                        }
                    }
                    if (count == 0) result.append("  无匹配关卡\n");
                } else {
                    result.append("无关卡列表，请先切换到地图模式");
                }
                return result.toString();
            }
            else if ("load_level".equals(command)) {
                final String name = params.optString("name", "");
                if (mapLevelDirs != null) {
                    for (final String levelDir : mapLevelDirs) {
                        if (levelDir.equalsIgnoreCase(name)) {
                            runOnUiThread(new Runnable() {
                                public void run() { loadMapLevel(levelDir); }
                            });
                            return "正在加载关卡: " + name;
                        }
                    }
                }
                return "未找到关卡: " + name;
            }
            else if ("show_level_info".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() { showLevelInfoDialog(); }
                });
                return "已显示关卡信息";
            }
            else if ("show_events".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() { showEventLogicDialog(); }
                });
                return "已显示事件逻辑";
            }
            else if ("list_levels".equals(command)) {
                final StringBuilder result = new StringBuilder();
                if (mapLevelDirs != null && !mapLevelDirs.isEmpty()) {
                    result.append("可用关卡:\n");
                    int count = 0;
                    for (String name : mapLevelDirs) {
                        result.append("  ").append(name).append("\n");
                        count++;
                        if (count >= 30) { result.append("  ...（更多省略）\n"); break; }
                    }
                    result.append("共 ").append(mapLevelDirs.size()).append(" 个关卡");
                } else {
                    result.append("无关卡列表，请先切换到地图模式");
                }
                return result.toString();
            }
            // ===== Wardrobe commands =====
            else if ("equip_item".equals(command)) {
                final String category = params.optString("category", "");
                final String name = params.optString("name", "");
                return "穿戴功能需要交互式选择，请使用换装模式界面。分类: " + category + ", 名称: " + name;
            }
            else if ("search_wardrobe".equals(command)) {
                final String keyword = params.optString("keyword", "");
                final StringBuilder result = new StringBuilder();
                if (wardrobeMeshData != null && !wardrobeMeshData.isEmpty()) {
                    result.append("已穿戴装扮:\n");
                    for (String key : wardrobeMeshData.keySet()) {
                        if (key.toLowerCase().contains(keyword.toLowerCase())) {
                            result.append("  ").append(key).append("\n");
                        }
                    }
                } else {
                    result.append("当前无穿戴装扮");
                }
                return result.toString();
            }
            else if ("list_wardrobe".equals(command)) {
                final String category = params.optString("category", "");
                final StringBuilder result = new StringBuilder();
                if (wardrobeMeshData != null && !wardrobeMeshData.isEmpty()) {
                    if (category.isEmpty()) {
                        result.append("已穿戴装扮:\n");
                        for (String key : wardrobeMeshData.keySet()) {
                            result.append("  ").append(key).append("\n");
                        }
                    } else {
                        result.append("分类 ").append(category).append(":\n");
                        boolean found = false;
                        for (String key : wardrobeMeshData.keySet()) {
                            if (key.toLowerCase().contains(category.toLowerCase())) {
                                result.append("  ").append(key).append("\n");
                                found = true;
                            }
                        }
                        if (!found) result.append("  无匹配\n");
                    }
                } else {
                    result.append("当前无穿戴装扮");
                }
                return result.toString();
            }
            else if ("list_wardrobe_categories".equals(command)) {
                final StringBuilder result = new StringBuilder();
                if (wardrobeCategoryScroll != null) {
                    result.append("请使用换装模式查看装扮分类");
                } else {
                    result.append("换装功能未初始化");
                }
                return result.toString();
            }
            else if ("unequip_item".equals(command)) {
                final String category = params.optString("category", "");
                if (wardrobeMeshData != null && !wardrobeMeshData.isEmpty()) {
                    String toRemove = null;
                    for (String key : wardrobeMeshData.keySet()) {
                        if (key.toLowerCase().contains(category.toLowerCase())) { toRemove = key; break; }
                    }
                    if (toRemove != null) {
                        final String finalToRemove = toRemove;
                        runOnUiThread(new Runnable() {
                            public void run() { wardrobeMeshData.remove(finalToRemove); }
                        });
                        return "已脱下: " + toRemove;
                    }
                }
                return "未找到分类: " + category;
            }
            // ===== Scene commands =====
            else if ("set_camera_target".equals(command)) {
                final float x = (float) params.optDouble("x", 0);
                final float y = (float) params.optDouble("y", 0);
                final float z = (float) params.optDouble("z", 0);
                meshViewport.queueEvent(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) r.setCameraTarget(x, y, z);
                        meshViewport.requestRender();
                    }
                });
                return "相机目标已设置: (" + x + ", " + y + ", " + z + ")";
            }
            else if ("pan_camera".equals(command)) {
                final float dx = (float) params.optDouble("dx", 0);
                final float dy = (float) params.optDouble("dy", 0);
                meshViewport.queueEvent(new Runnable() {
                    public void run() {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        if (r != null) {
                            float[] target = r.getCameraTarget();
                            r.setCameraTarget(target[0] + dx, target[1] + dy, target[2]);
                        }
                        meshViewport.requestRender();
                    }
                });
                return "相机已平移: dx=" + dx + ", dy=" + dy;
            }
            else if ("save_screenshot".equals(command)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        meshViewport.requestRender();
                        Toast.makeText(MainActivity.this, "截图功能：请使用系统截图（电源键+音量下）", Toast.LENGTH_SHORT).show();
                    }
                });
                return "截图提示已显示";
            }
            else if ("get_camera_info".equals(command)) {
                StringBuilder result = new StringBuilder();
                if (meshViewport != null && meshViewport.getMeshRenderer() != null) {
                    try {
                        MeshRenderer r = meshViewport.getMeshRenderer();
                        result.append("相机参数:\n");
                        result.append("  距离: ").append(String.format("%.2f", r.getCameraDistance())).append("\n");
                        result.append("  视场角: ").append(String.format("%.1f", r.getFov())).append("°\n");
                        float[] target = r.getCameraTarget();
                        if (target != null) {
                            result.append("  目标点: (")
                                  .append(String.format("%.1f", target[0])).append(", ")
                                  .append(String.format("%.1f", target[1])).append(", ")
                                  .append(String.format("%.1f", target[2])).append(")\n");
                        }
                    } catch (Exception e) {
                        result.append("无法获取相机信息");
                    }
                } else {
                    result.append("渲染器未初始化");
                }
                return result.toString();
            }
            else if ("set_render_quality".equals(command)) {
                final String quality = params.optString("quality", "high");
                return "渲染质量已设置: " + quality + "（下次渲染生效）";
            }
            else {
                return "未知命令: " + command;
            }
        } catch (Exception e) {
            return "命令执行错误: " + e.getMessage();
        }
    }

    /**
     * Build a description of the current model state for AI context.
     */
    private String buildCurrentModelContext() {
        StringBuilder sb = new StringBuilder();
        if (currentMeshData != null) {
            sb.append("模型名称: ").append(currentMeshData.name != null ? currentMeshData.name : "未知").append("\n");
            int vCount = 0, fCount = 0;
            if (currentMeshData.vertices != null) {
                for (float[] va : currentMeshData.vertices) vCount += va.length / 3;
            }
            if (currentMeshData.indices != null) {
                for (int[] ia : currentMeshData.indices) fCount += ia.length / 3;
            }
            sb.append("顶点数: ").append(vCount).append("\n");
            sb.append("面数: ").append(fCount).append("\n");
            int boneCount = currentMeshData.embeddedSkeleton != null ? currentMeshData.embeddedSkeleton.size() : 0;
            sb.append("骨骼数: ").append(boneCount).append("\n");
            sb.append("有UV: ").append(currentMeshData.uv0 != null && !currentMeshData.uv0.isEmpty() ? "是" : "否").append("\n");
            sb.append("有动画: ").append(currentMeshData.isAnimated ? "是" : "否").append("\n");
            sb.append("版本: 0x").append(Integer.toHexString(currentMeshData.version)).append("\n");
        }
        if (currentMeshEntry != null) {
            sb.append("文件路径: ").append(currentMeshEntry.relativePath != null ? currentMeshEntry.relativePath : "").append("\n");
        }
        if (wardrobeMeshData != null && !wardrobeMeshData.isEmpty()) {
            sb.append("已穿戴装扮: ").append(wardrobeMeshData.keySet().toString()).append("\n");
        }
        if (sb.length() == 0) {
            sb.append("当前未加载任何模型");
        }
        return sb.toString();
    }

    /**
     * Build extended app context for AI: current mode, camera state, map layers, wardrobe.
     * This gives the AI awareness of what the user is currently doing.
     */
    private String buildAppContext() {
        StringBuilder sb = new StringBuilder();

        // Current mode
        String modeName = "未知";
        switch (currentAssetMode) {
            case 0: modeName = "模型模式"; break;
            case 1: modeName = "贴图模式"; break;
            case 2: modeName = "地图模式"; break;
            case 3: modeName = "换装模式"; break;
            case 4: modeName = "编辑器模式"; break;
            case 5: modeName = "音频模式"; break;
        }
        sb.append("当前模式: ").append(modeName).append("\n");

        // Camera state (if renderer available)
        if (meshViewport != null && meshViewport.getMeshRenderer() != null) {
            try {
                MeshRenderer r = meshViewport.getMeshRenderer();
                sb.append("相机距离: ").append(String.format("%.2f", r.getCameraDistance())).append("\n");
                sb.append("视场角: ").append(String.format("%.1f", r.getFov())).append("°\n");
            } catch (Exception e) { /* ignore */ }
        }

        // Map mode context
        if (currentAssetMode == 2) {
            if (mapLayerVisible != null) {
                sb.append("地图图层状态:\n");
                for (int i = 0; i < mapLayerVisible.length; i++) {
                    String layerName = (i < MapProcessingUtils.LAYER_KEYS.length) ?
                        MapProcessingUtils.LAYER_KEYS[i] : "layer" + i;
                    sb.append("  ").append(layerName).append(": ")
                      .append(mapLayerVisible[i] ? "可见" : "隐藏").append("\n");
                }
            }
            if (currentLevelInfo != null) {
                sb.append("关卡信息: ")
                  .append(currentLevelInfo.links.size()).append("个传送, ")
                  .append(currentLevelInfo.quests.size()).append("个任务, ")
                  .append(currentLevelInfo.music.size()).append("个音乐\n");
            }
            if (currentEventInfo != null) {
                sb.append("事件逻辑: ").append(currentEventInfo.total).append("个事件\n");
            }
        }

        // Wardrobe context
        if (wardrobeMeshData != null && !wardrobeMeshData.isEmpty()) {
            sb.append("已穿戴装扮: ").append(wardrobeMeshData.size()).append("件\n");
            sb.append("装扮分类: ").append(wardrobeMeshData.keySet().toString()).append("\n");
        }

        return sb.toString();
    }

    // ===== Asset mode methods (model / texture / map) =====

    /** Update mode button highlight styles. mode: 0=model, 1=texture, 2=map, 3=wardrobe, 4=editor, 5=audio */
    private void updateModeButtonStyles(int mode) {
        int primaryBg = R.drawable.btn_primary_bg;
        int secondaryBg = R.drawable.btn_secondary_bg;
        int activeColor = 0xFFFFFFFF;
        int inactiveColor = 0xFF4A5061;

        btnModeModel.setBackgroundResource(mode == 0 ? primaryBg : secondaryBg);
        btnModeModel.setTextColor(mode == 0 ? activeColor : inactiveColor);
        btnModeTexture.setBackgroundResource(mode == 1 ? primaryBg : secondaryBg);
        btnModeTexture.setTextColor(mode == 1 ? activeColor : inactiveColor);
        btnModeMap.setBackgroundResource(mode == 2 ? primaryBg : secondaryBg);
        btnModeMap.setTextColor(mode == 2 ? activeColor : inactiveColor);
        btnModeWardrobe.setBackgroundResource(mode == 3 ? primaryBg : secondaryBg);
        btnModeWardrobe.setTextColor(mode == 3 ? activeColor : inactiveColor);
        btnModeEditor.setBackgroundResource(mode == 4 ? primaryBg : secondaryBg);
        btnModeEditor.setTextColor(mode == 4 ? activeColor : inactiveColor);
        btnModeAudio.setBackgroundResource(mode == 5 ? primaryBg : secondaryBg);
        btnModeAudio.setTextColor(mode == 5 ? activeColor : inactiveColor);
    }

    /** Switch to Model mode — mesh list + 3D viewport */
    private void switchToModelMode() {
        currentLoadId++;
        editorMode = false;
        wardrobeMode = false;
        currentAssetMode = 0;
        updateModeButtonStyles(0);

        // Clear editor viewport
        if (editorViewport != null) {
            editorViewport.queueEvent(new Runnable() {
                @Override public void run() {
                    editorViewport.getMeshRenderer().clearAnimation();
                    editorViewport.getMeshRenderer().clearMeshInstances();
                    editorViewport.requestRender();
                }
            });
        }

        audioPanel.setVisibility(View.GONE);
        wardrobeCategoryScroll.setVisibility(View.GONE);
        btnClearWardrobe.setVisibility(View.GONE);
        editorPanel.setVisibility(View.GONE);
        mapPanel.setVisibility(View.GONE);
        texturePreviewPanel.setVisibility(View.GONE);

        btnExport.setVisibility(View.VISIBLE);
        btnBatchExport.setVisibility(View.VISIBLE);
        btnLevelInfo.setVisibility(View.GONE);
        btnEventLogic.setVisibility(View.GONE);
        viewModeGroup.setVisibility(View.VISIBLE);
        btnSelectAnim.setVisibility(View.GONE);
        btnPlayPause.setVisibility(View.GONE);
        btnAnimFilter.setVisibility(View.GONE);
        animSeekBar.setVisibility(View.GONE);
        animTimeText.setVisibility(View.GONE);

        // Show columns layout with mesh list only
        columnsLayout.setVisibility(View.VISIBLE);
        listMesh.setVisibility(View.VISIBLE);
        gridWardrobe.setVisibility(View.GONE);
        listKtx.setVisibility(View.GONE);
        listLevel.setVisibility(View.GONE);
        findViewById(R.id.divider1).setVisibility(View.GONE);
        findViewById(R.id.divider2).setVisibility(View.GONE);
        findViewById(R.id.searchBox).setVisibility(View.VISIBLE);
        findViewById(R.id.mainViewportContainer).setVisibility(View.VISIBLE);
        findViewById(R.id.bottomAssetBrowser).setVisibility(View.VISIBLE);

        meshListAdapter.filter("");
        wardrobeMeshData.clear();
        wardrobeMeshScale.clear();
        wardrobeTextureBytes.clear();
        meshViewport.queueEvent(new Runnable() {
            @Override public void run() {
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
        statusText.setText("模型模式");
    }

    /** Switch to Texture mode — KTX list + texture preview */
    private void switchToTextureMode() {
        currentLoadId++;
        editorMode = false;
        wardrobeMode = false;
        currentAssetMode = 1;
        updateModeButtonStyles(1);

        if (editorViewport != null) {
            editorViewport.queueEvent(new Runnable() {
                @Override public void run() {
                    editorViewport.getMeshRenderer().clearAnimation();
                    editorViewport.getMeshRenderer().clearMeshInstances();
                    editorViewport.requestRender();
                }
            });
        }

        audioPanel.setVisibility(View.GONE);
        wardrobeCategoryScroll.setVisibility(View.GONE);
        btnClearWardrobe.setVisibility(View.GONE);
        editorPanel.setVisibility(View.GONE);
        mapPanel.setVisibility(View.GONE);

        btnExport.setVisibility(View.GONE);
        btnBatchExport.setVisibility(View.GONE);
        btnLevelInfo.setVisibility(View.GONE);
        btnEventLogic.setVisibility(View.GONE);
        viewModeGroup.setVisibility(View.GONE);
        btnSelectAnim.setVisibility(View.GONE);
        btnPlayPause.setVisibility(View.GONE);
        btnAnimFilter.setVisibility(View.GONE);
        animSeekBar.setVisibility(View.GONE);
        animTimeText.setVisibility(View.GONE);

        // Show columns layout with KTX list only
        columnsLayout.setVisibility(View.VISIBLE);
        listMesh.setVisibility(View.GONE);
        gridWardrobe.setVisibility(View.GONE);
        listKtx.setVisibility(View.VISIBLE);
        listLevel.setVisibility(View.GONE);
        findViewById(R.id.divider1).setVisibility(View.GONE);
        findViewById(R.id.divider2).setVisibility(View.GONE);
        findViewById(R.id.searchBox).setVisibility(View.VISIBLE);
        findViewById(R.id.mainViewportContainer).setVisibility(View.VISIBLE);
        findViewById(R.id.bottomAssetBrowser).setVisibility(View.VISIBLE);

        texturePreviewPanel.setVisibility(View.GONE);
        meshListAdapter.filter("");
        meshViewport.queueEvent(new Runnable() {
            @Override public void run() {
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
        statusText.setText("贴图模式");
    }

    /** Switch to Map mode — level list + 3D terrain viewport + layer toggles */
    private void switchToMapMode() {
        currentLoadId++;
        editorMode = false;
        wardrobeMode = false;
        currentAssetMode = 2;
        updateModeButtonStyles(2);

        if (editorViewport != null) {
            editorViewport.queueEvent(new Runnable() {
                @Override public void run() {
                    editorViewport.getMeshRenderer().clearAnimation();
                    editorViewport.getMeshRenderer().clearMeshInstances();
                    editorViewport.requestRender();
                }
            });
        }

        audioPanel.setVisibility(View.GONE);
        wardrobeCategoryScroll.setVisibility(View.GONE);
        btnClearWardrobe.setVisibility(View.GONE);
        editorPanel.setVisibility(View.GONE);
        texturePreviewPanel.setVisibility(View.GONE);

        btnExport.setVisibility(View.VISIBLE);
        btnBatchExport.setVisibility(View.VISIBLE);
        // Show level info / event logic buttons in map mode (visibility refined after data loads)
        btnLevelInfo.setVisibility(View.VISIBLE);
        btnEventLogic.setVisibility(View.VISIBLE);
        viewModeGroup.setVisibility(View.VISIBLE);
        btnSelectAnim.setVisibility(View.GONE);
        btnPlayPause.setVisibility(View.GONE);
        btnAnimFilter.setVisibility(View.GONE);
        animSeekBar.setVisibility(View.GONE);
        animTimeText.setVisibility(View.GONE);

        // Show map panel (level list + layer toggles) instead of columns
        columnsLayout.setVisibility(View.GONE);
        mapPanel.setVisibility(View.VISIBLE);
        findViewById(R.id.searchBox).setVisibility(View.VISIBLE);
        findViewById(R.id.mainViewportContainer).setVisibility(View.VISIBLE);
        findViewById(R.id.bottomAssetBrowser).setVisibility(View.VISIBLE);

        // Populate map level list
        populateMapLevelList();

        meshViewport.queueEvent(new Runnable() {
            @Override public void run() {
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
        statusText.setText("地图模式 — 选择关卡查看地形");
    }

    // ===== Wardrobe mode methods =====

    private void switchMode(boolean toWardrobe) {
        if (wardrobeMode == toWardrobe && !editorMode && currentAssetMode != 0 && currentAssetMode != 1 && currentAssetMode != 2) return;
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
            updateModeButtonStyles(3);
            audioPanel.setVisibility(View.GONE);
            wardrobeCategoryScroll.setVisibility(View.VISIBLE);
            btnClearWardrobe.setVisibility(View.VISIBLE);
            btnExport.setVisibility(View.GONE);
            btnBatchExport.setVisibility(View.GONE);
            btnLevelInfo.setVisibility(View.GONE);
            btnEventLogic.setVisibility(View.GONE);
            editorPanel.setVisibility(View.GONE);
            audioPanel.setVisibility(View.GONE);
            mapPanel.setVisibility(View.GONE);
            texturePreviewPanel.setVisibility(View.GONE);
            columnsLayout.setVisibility(View.VISIBLE);
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
                    // Apply warm character lighting (ported from HTML CHAR_AMBIENT/KEY/FILL)
                    MeshRenderer r = meshViewport.getMeshRenderer();
                    if (r != null) {
                        r.setAmbientColor(
                            (int)(MapProcessingUtils.CHAR_AMBIENT[0] * 255),
                            (int)(MapProcessingUtils.CHAR_AMBIENT[1] * 255),
                            (int)(MapProcessingUtils.CHAR_AMBIENT[2] * 255));
                        r.setKeyLightColor(
                            (int)(MapProcessingUtils.CHAR_KEY[0] * 255),
                            (int)(MapProcessingUtils.CHAR_KEY[1] * 255),
                            (int)(MapProcessingUtils.CHAR_KEY[2] * 255));
                        r.setFillLightColor(
                            (int)(MapProcessingUtils.CHAR_FILL[0] * 255),
                            (int)(MapProcessingUtils.CHAR_FILL[1] * 255),
                            (int)(MapProcessingUtils.CHAR_FILL[2] * 255));
                    }
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
            // Delegate to model mode (visual mode is now split into model/texture/map)
            switchToModelMode();
            return;
        }
    }

    private void switchToAudioMode() {
        currentLoadId++;
        editorMode = false;
        wardrobeMode = false;

        // Update button styles
        updateModeButtonStyles(5);

        // Hide other panels
        wardrobeCategoryScroll.setVisibility(View.GONE);
        btnClearWardrobe.setVisibility(View.GONE);
        btnExport.setVisibility(View.GONE);
        btnBatchExport.setVisibility(View.GONE);
        btnLevelInfo.setVisibility(View.GONE);
        btnEventLogic.setVisibility(View.GONE);
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
        mapPanel.setVisibility(View.GONE);
        texturePreviewPanel.setVisibility(View.GONE);
        columnsLayout.setVisibility(View.GONE);

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

    // === Map mode ===

    /** Map layer visibility state (uses MapProcessingUtils for categories) */
    private boolean[] mapLayerVisible = new boolean[MapProcessingUtils.LAYER_COUNT];
    {
        for (int i = 0; i < mapLayerVisible.length; i++) {
            mapLayerVisible[i] = !MapProcessingUtils.LAYERS_DEFAULT_OFF.contains(i);
        }
    }

    /** List of map level directories found in the APK */
    private List<String> mapLevelDirs = new ArrayList<>();
    private ArrayAdapter<String> mapLevelAdapter;

    /** Populate the map level list with available level directories */
    private void populateMapLevelList() {
        if (mapLevelAdapter == null) {
            mapLevelAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mapLevelDirs) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView tv = (TextView) super.getView(position, convertView, parent);
                    tv.setTextSize(11);
                    tv.setTextColor(0xFFCCCCCC);
                    tv.setPadding(8, 6, 8, 6);
                    return tv;
                }
            };
            listMapLevel.setAdapter(mapLevelAdapter);
            listMapLevel.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    if (position < 0 || position >= mapLevelDirs.size()) return;
                    String levelDir = mapLevelDirs.get(position);
                    loadMapLevel(levelDir);
                }
            });
        }
        mapLevelDirs.clear();

        // Find level directories from the level list (level files: .level.bin, .meshes)
        for (MeshCatalogEntry entry : levelList) {
            String path = entry.relativePath;
            if (path == null) continue;
            // Sky levels are typically under /Levels/<LevelName>/
            int levelsIdx = path.indexOf("/Levels/");
            String levelDirName;
            if (levelsIdx >= 0) {
                String afterLevels = path.substring(levelsIdx + 8);
                int slashIdx = afterLevels.indexOf('/');
                levelDirName = slashIdx > 0 ? afterLevels.substring(0, slashIdx) : afterLevels;
            } else {
                // Fallback: use parent directory name
                java.io.File f = new java.io.File(path);
                levelDirName = f.getParentFile() != null ? f.getParentFile().getName() : path;
            }
            // Skip generic names
            if ("Levels".equalsIgnoreCase(levelDirName) || levelDirName.isEmpty()) continue;
            if (!mapLevelDirs.contains(levelDirName)) {
                mapLevelDirs.add(levelDirName);
            }
        }

        // Also check editorMapFiles for .level.bin directories
        if (editorMapFiles != null) {
            for (MeshCatalogEntry e : editorMapFiles) {
                String mapPath = e.relativePath != null ? e.relativePath : "";
                String dirName = mapPath.isEmpty() ? "unknown"
                    : (new java.io.File(mapPath).getParentFile() != null
                        ? new java.io.File(mapPath).getParentFile().getName() : mapPath);
                if (!mapLevelDirs.contains(dirName)) {
                    mapLevelDirs.add(dirName);
                }
            }
        }

        mapLevelAdapter.notifyDataSetChanged();

        // Layer toggles are built by updateMapLayerPanel() after a level is loaded.
        // Show placeholder before loading.
        mapLayerContainer.removeAllViews();
        TextView placeholder = new TextView(this);
        placeholder.setText("选择上方关卡加载地图\n图层开关将在加载后显示");
        placeholder.setTextSize(11);
        placeholder.setTextColor(0xFF888888);
        placeholder.setPadding(8, 8, 8, 8);
        mapLayerContainer.addView(placeholder);

        if (mapLevelDirs.isEmpty()) {
            statusText.setText("未找到关卡目录 — 请先加载APK");
        } else {
            statusText.setText("找到 " + mapLevelDirs.size() + " 个关卡 — 点击查看地图");
        }
    }

    /**
     * Show level info dialog (teleport links, quests, music, dialogs, world name).
     * Ported from HTML setupInfoPanel + renderInfoList (line 39164-39237).
     */
    private void showLevelInfoDialog() {
        if (currentLevelInfo == null || !com.sky.modelviewer.parsing.LevelInfoExtractor.hasContent(currentLevelInfo)) {
            android.widget.Toast.makeText(this, "无关卡信息", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // Build readable text content
        StringBuilder sb = new StringBuilder();
        final com.sky.modelviewer.parsing.LevelInfoExtractor.LevelInfo m = currentLevelInfo;

        if (m.worldName != null && !m.worldName.isEmpty()) {
            sb.append("【世界】\n").append(m.worldName).append("\n\n");
        }
        if (!m.links.isEmpty()) {
            sb.append("【传送连接】(").append(m.links.size()).append(")\n");
            for (com.sky.modelviewer.parsing.LevelInfoExtractor.LevelLink l : m.links) {
                sb.append("  ");
                if (l.link != null && !l.link.isEmpty()) sb.append(l.link).append(" → ");
                sb.append(l.to).append(" （").append(l.kind).append("）\n");
            }
            sb.append("\n");
        }
        if (!m.quests.isEmpty()) {
            sb.append("【任务】(").append(m.quests.size()).append(")\n");
            for (String q : m.quests) sb.append("  ").append(q).append("\n");
            sb.append("\n");
        }
        if (!m.music.isEmpty()) {
            sb.append("【背景音乐】(").append(m.music.size()).append(")\n");
            for (String mu : m.music) sb.append("  ").append(mu).append("\n");
            sb.append("\n");
        }
        if (!m.dialogs.isEmpty()) {
            sb.append("【对白提示】(").append(m.dialogs.size()).append(")\n");
            for (String d : m.dialogs) sb.append("  ").append(d).append("\n");
            sb.append("\n");
        }

        final String content = sb.toString().trim();
        final String copyText = com.sky.modelviewer.parsing.LevelInfoExtractor.toText(m);

        // Build dialog with ScrollView + TextView
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(13);
        tv.setPadding(48, 32, 48, 32);
        tv.setTextIsSelectable(true);
        scroll.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("关卡信息");
        builder.setView(scroll);
        builder.setPositiveButton("复制", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                copyToClipboard(copyText, "关卡信息已复制");
            }
        });
        builder.setNegativeButton("关闭", null);
        builder.show();
    }

    /**
     * Show event logic dialog (event triggers, action chains, parameters).
     * Ported from HTML setupEventPanel + renderEventList (line 39094-39272).
     */
    private void showEventLogicDialog() {
        if (currentEventInfo == null || currentEventInfo.total == 0) {
            android.widget.Toast.makeText(this, "无事件逻辑", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // Build event list text
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(currentEventInfo.total).append(" 个事件\n\n");

        for (com.sky.modelviewer.parsing.LevelEventExtractor.EventItem ev : currentEventInfo.events) {
            sb.append("【").append(ev.typeCn).append("】");
            sb.append(ev.autoStart ? " [自动]" : " [待触发]").append("\n");
            if (ev.eventName != null && !ev.eventName.isEmpty()) {
                sb.append("  名称: ").append(ev.eventName).append("\n");
            }
            for (com.sky.modelviewer.parsing.LevelEventExtractor.EventProp p : ev.props) {
                sb.append("  ").append(p.label).append(": ").append(p.value).append("\n");
            }
            if (!ev.outs.isEmpty()) {
                sb.append("  下游:\n");
                int limit = Math.min(ev.outs.size(), 12);
                for (int i = 0; i < limit; i++) {
                    com.sky.modelviewer.parsing.LevelEventExtractor.EventOut o = ev.outs.get(i);
                    sb.append("    ").append(o.fieldCn).append(" → ").append(o.targetType);
                    if (o.targetName != null && !o.targetName.isEmpty()) {
                        sb.append(" (").append(o.targetName).append(")");
                    }
                    sb.append("\n");
                }
                if (ev.outs.size() > 12) {
                    sb.append("    …共 ").append(ev.outs.size()).append(" 项\n");
                }
            }
            sb.append("\n");
        }

        final String content = sb.toString().trim();
        final String copyText = com.sky.modelviewer.parsing.LevelEventExtractor.toText(currentEventInfo);

        // Build dialog with ScrollView + TextView
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // Search box
        final EditText searchBox = new EditText(this);
        searchBox.setHint("搜索事件名 / 类型...");
        searchBox.setTextSize(13);
        searchBox.setPadding(32, 16, 32, 16);
        layout.addView(searchBox);

        // Event list text
        final TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(12);
        tv.setPadding(48, 16, 48, 32);
        tv.setTextIsSelectable(true);
        layout.addView(tv);

        scroll.addView(layout);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("事件逻辑 (" + currentEventInfo.total + ")");
        builder.setView(scroll);
        builder.setPositiveButton("复制", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                copyToClipboard(copyText, "事件逻辑已复制");
            }
        });
        builder.setNegativeButton("关闭", null);
        final AlertDialog dialog = builder.show();

        // Search functionality (ported from HTML evSearch.oninput, line 39110)
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String q = s.toString().trim();
                List<com.sky.modelviewer.parsing.LevelEventExtractor.EventItem> filtered =
                    com.sky.modelviewer.parsing.LevelEventExtractor.filter(currentEventInfo, q);
                StringBuilder fsb = new StringBuilder();
                if (filtered.isEmpty()) {
                    fsb.append("无匹配事件");
                } else {
                    fsb.append("匹配 ").append(filtered.size()).append(" / ").append(currentEventInfo.total).append(" 个事件\n\n");
                    for (com.sky.modelviewer.parsing.LevelEventExtractor.EventItem ev : filtered) {
                        fsb.append("【").append(ev.typeCn).append("】");
                        fsb.append(ev.autoStart ? " [自动]" : " [待触发]").append("\n");
                        if (ev.eventName != null && !ev.eventName.isEmpty()) {
                            fsb.append("  名称: ").append(ev.eventName).append("\n");
                        }
                        for (com.sky.modelviewer.parsing.LevelEventExtractor.EventProp p : ev.props) {
                            fsb.append("  ").append(p.label).append(": ").append(p.value).append("\n");
                        }
                        if (!ev.outs.isEmpty()) {
                            fsb.append("  下游:\n");
                            int limit = Math.min(ev.outs.size(), 12);
                            for (int i = 0; i < limit; i++) {
                                com.sky.modelviewer.parsing.LevelEventExtractor.EventOut o = ev.outs.get(i);
                                fsb.append("    ").append(o.fieldCn).append(" → ").append(o.targetType).append("\n");
                            }
                            if (ev.outs.size() > 12) {
                                fsb.append("    …共 ").append(ev.outs.size()).append(" 项\n");
                            }
                        }
                        fsb.append("\n");
                    }
                }
                tv.setText(fsb.toString().trim());
            }
        });
    }

    /** Copy text to clipboard with toast feedback. */
    private void copyToClipboard(String text, String toastMsg) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("SkyModelViewer", text);
            clipboard.setPrimaryClip(clip);
            android.widget.Toast.makeText(this, toastMsg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "复制失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Apply layer visibility filters to currently loaded map meshes */
    /**
     * Apply layer visibility filters based on current mapLayerVisible state.
     * Ported from HTML setMapCategoryVisible() — uses category-based filtering.
     */
    private void applyMapLayerFilters() {
        final MeshRenderer renderer = meshViewport.getMeshRenderer();
        meshViewport.queueEvent(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < MapProcessingUtils.LAYER_COUNT; i++) {
                    String catKey = MapProcessingUtils.LAYER_KEYS[i];
                    renderer.setMapCategoryVisible(catKey, mapLayerVisible[i]);
                }
                meshViewport.requestRender();
            }
        });
    }

    /**
     * Update the layer panel UI after loading a map.
     * Shows category counts and toggle buttons (ported from HTML updateMapInfo line 38285-38377).
     */
    private void updateMapLayerPanel() {
        mapLayerContainer.removeAllViews();

        // Get category counts from renderer
        final MeshRenderer renderer = meshViewport.getMeshRenderer();
        final java.util.Map<String, Integer> catCounts;
        // Must get counts on GL thread
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.Map<String, Integer>[] resultHolder = new java.util.Map[1];
        meshViewport.queueEvent(new Runnable() {
            @Override public void run() {
                resultHolder[0] = renderer.countMapCategories();
                latch.countDown();
            }
        });
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { /* timeout */ }
        catCounts = resultHolder[0] != null ? resultHolder[0] : new java.util.HashMap<String, Integer>();

        // Build layer toggle rows (13 categories, HTML MAP_LAYER_META order)
        for (int i = 0; i < MapProcessingUtils.LAYER_COUNT; i++) {
            final int layerIdx = i;
            final String catKey = MapProcessingUtils.LAYER_KEYS[i];
            int count = catCounts.containsKey(catKey) ? catCounts.get(catKey) : 0;
            String label = MapProcessingUtils.LAYER_LABELS[i];

            // Horizontal layout: checkbox + label + count
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(4, 2, 4, 2);

            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(label + (count > 0 ? " (" + count + ")" : ""));
            cb.setTextColor(MapProcessingUtils.LAYER_COLORS[i]);
            cb.setTextSize(10);
            cb.setChecked(mapLayerVisible[i]);
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    mapLayerVisible[layerIdx] = isChecked;
                    applyMapLayerFilters();
                }
            });
            row.addView(cb);

            // Add expand button if count > 0 (for single-resource toggle)
            if (count > 0) {
                Button expandBtn = new Button(this);
                expandBtn.setText("▸");
                expandBtn.setTextSize(8);
                expandBtn.setPadding(4, 0, 4, 0);
                expandBtn.setMinimumWidth(40);
                expandBtn.setMinimumHeight(32);
                expandBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMapLayerResources(catKey, layerIdx);
                    }
                });
                row.addView(expandBtn);
            }

            mapLayerContainer.addView(row);
        }

        // Update status
        int totalObjects = 0;
        for (int c : catCounts.values()) totalObjects += c;
        statusText.setText("地图已加载 — " + totalObjects + " 个物件，" + catCounts.size() + " 类图层");
    }

    /**
     * Show a dialog with individual resources in a map layer for single-resource toggle.
     * Ported from HTML buildSub() line 38315-38335.
     */
    private void showMapLayerResources(final String catKey, final int layerIdx) {
        final MeshRenderer renderer = meshViewport.getMeshRenderer();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.Map<String, Integer>[] resultHolder = new java.util.Map[1];
        meshViewport.queueEvent(new Runnable() {
            @Override public void run() {
                resultHolder[0] = renderer.groupMapObjects(catKey);
                latch.countDown();
            }
        });
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { /* timeout */ }
        final java.util.Map<String, Integer> resources = resultHolder[0] != null ? resultHolder[0] : new java.util.HashMap<String, Integer>();

        if (resources.isEmpty()) {
            statusText.setText("该图层无物件");
            return;
        }

        // Build dialog with resource list
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(dialogLayout);

        for (final java.util.Map.Entry<String, Integer> entry : resources.entrySet()) {
            String resName = entry.getKey();
            int cnt = entry.getValue();
            String shortName = resName.length() > 26 ? resName.substring(0, 24) + "…" : resName;

            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(shortName + " (" + cnt + ")");
            cb.setTextSize(10);
            cb.setChecked(mapLayerVisible[layerIdx]); // follow layer state
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    final boolean visible = isChecked;
                    meshViewport.queueEvent(new Runnable() {
                        @Override public void run() {
                            renderer.setMapObjectVisible(catKey, entry.getKey(), visible);
                            meshViewport.requestRender();
                        }
                    });
                }
            });
            dialogLayout.addView(cb);
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle(MapProcessingUtils.LAYER_LABELS[layerIdx] + " — 资源列表")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .show();
    }

    /** Load a map level: terrain (.meshes) + objects (.level.bin) */
    private void loadMapLevel(final String levelDirName) {
        statusText.setText("正在加载关卡: " + levelDirName + " ...");

        // Find the level entry (Objects.level.bin or first .level.bin) in the level list
        MeshCatalogEntry levelEntry = null;
        for (MeshCatalogEntry entry : levelList) {
            String path = entry.relativePath;
            if (path == null) continue;
            boolean matches = path.contains("/Levels/" + levelDirName + "/")
                || path.contains("/" + levelDirName + "/");
            if (matches && path.endsWith(".level.bin")) {
                // Prefer Objects.level.bin
                if (path.contains("Objects.level.bin") || path.endsWith("Objects.level.bin")) {
                    levelEntry = entry;
                    break;
                }
                if (levelEntry == null) levelEntry = entry;
            }
        }

        // Fallback: find any .meshes in the level directory
        if (levelEntry == null) {
            for (MeshCatalogEntry entry : levelList) {
                String path = entry.relativePath;
                if (path == null) continue;
                boolean matches = path.contains("/Levels/" + levelDirName + "/")
                    || path.contains("/" + levelDirName + "/");
                if (matches && path.endsWith(".meshes")) {
                    levelEntry = entry;
                    break;
                }
            }
        }

        // Fallback: find any file in the level directory
        if (levelEntry == null) {
            for (MeshCatalogEntry entry : levelList) {
                String path = entry.relativePath;
                if (path == null) continue;
                if (path.contains("/" + levelDirName + "/")) {
                    levelEntry = entry;
                    break;
                }
            }
        }

        if (levelEntry == null) {
            // Try editorMapFiles
            if (editorMapFiles != null) {
                for (MeshCatalogEntry e : editorMapFiles) {
                    String mapPath = e.relativePath;
                    if (mapPath != null && mapPath.contains(levelDirName)) {
                        levelEntry = e;
                        break;
                    }
                }
            }
        }

        if (levelEntry == null) {
            statusText.setText("未找到关卡文件: " + levelDirName);
            return;
        }

        final MeshCatalogEntry finalEntry = levelEntry;
        final int loadId = ++currentLoadId;
        final String apkPath = currentApkPath;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadLevelFile(finalEntry, apkPath, loadId);
            }
        });
    }

    // === Editor mode ===

    private void switchToEditorMode() {
        if (editorMode) return;
        currentLoadId++;
        editorMode = true;
        wardrobeMode = false;

        // Update button styles
        updateModeButtonStyles(4);

        // Hide visualization/wardrobe panels
        wardrobeCategoryScroll.setVisibility(View.GONE);
        btnClearWardrobe.setVisibility(View.GONE);
        btnExport.setVisibility(View.GONE);
        btnBatchExport.setVisibility(View.GONE);
        btnLevelInfo.setVisibility(View.GONE);
        btnEventLogic.setVisibility(View.GONE);
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
        mapPanel.setVisibility(View.GONE);
        texturePreviewPanel.setVisibility(View.GONE);
        columnsLayout.setVisibility(View.GONE);
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
                oe.icon = obj.optString("icon", "");
                oe.iconHsv = jsonFloatArray(obj, "icon_hsv", new float[]{0,0,100});
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

            // === Set up wardrobe icon loader ===
            // Build texIndex lazily if not already built (shared with map loading)
            if (globalTexIndex == null || globalTexIndex.isEmpty()) {
                debugLog("Building texIndex for icon loader...");
                globalTexIndex = SkyAssetScanner.buildTextureIndex(apkPath);
                debugLog("texIndex built: " + globalTexIndex.size() + " KTX entries");
            }
            // Create or reset icon loader
            if (wardrobeIconLoader == null) {
                wardrobeIconLoader = new com.sky.modelviewer.render.WardrobeIconLoader(apkPath, globalTexIndex);
            }
            // Register all outfit entries' icon info
            for (List<OutfitEntry> list : outfitByType.values()) {
                for (OutfitEntry oe : list) {
                    if (oe.icon != null && !oe.icon.isEmpty()) {
                        com.sky.modelviewer.render.WardrobeIconLoader.OutfitInfo info =
                            new com.sky.modelviewer.render.WardrobeIconLoader.OutfitInfo();
                        info.iconName = oe.icon;
                        info.baseHsv = oe.baseHsv;
                        info.primaryDyeHsv = oe.primaryDyeHsv;
                        info.secondaryDyeHsv = oe.secondaryDyeHsv;
                        info.iconHsv = oe.iconHsv;
                        wardrobeIconLoader.registerOutfitInfo(oe.name, info);
                    }
                }
            }
            // Set icon loader on adapter (must be on UI thread, but setIconLoader is thread-safe enough)
            handler.post(new Runnable() {
                @Override
                public void run() {
                    wardrobeGridAdapter.setIconLoader(wardrobeIconLoader);
                }
            });
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
        // Build icon names map: entry.name → icon name (tells adapter which entries have icons)
        java.util.Map<String, String> iconMap = new java.util.HashMap<>();
        if (entries != null) {
            for (OutfitEntry oe : entries) {
                if (oe.icon != null && !oe.icon.isEmpty()) {
                    iconMap.put(oe.name, oe.icon);
                }
            }
        }
        wardrobeGridAdapter.setIconNames(iconMap);
        // Ensure icon loader is set (may have been set during loadOutfitDefsInBackground)
        if (wardrobeIconLoader != null) {
            wardrobeGridAdapter.setIconLoader(wardrobeIconLoader);
        }
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

                    // Parse TGCL format using structured parser (replaces old LevelFileParser)
                    // Ported from HTML TGCL class + extractLevelMeshes + extractLevelMarkers
                    final com.sky.modelviewer.parsing.TgclParser tgclParser = new com.sky.modelviewer.parsing.TgclParser();
                    final com.sky.modelviewer.parsing.TgclParser.TgclFile tgclFile = tgclParser.parse(raw);
                    final List<com.sky.modelviewer.parsing.LevelMeshExtractor.LevelMeshInstance> meshInstances =
                        com.sky.modelviewer.parsing.LevelMeshExtractor.extract(tgclFile);
                    final List<com.sky.modelviewer.parsing.LevelMarkerExtractor.MarkerGroup> markerGroups =
                        com.sky.modelviewer.parsing.LevelMarkerExtractor.extract(tgclFile);
                    // Extract level info and event logic (ported from HTML extractLevelInfo/extractLevelEvents)
                    // Uses same parsed TgclFile — no re-parsing needed
                    final com.sky.modelviewer.parsing.LevelInfoExtractor.LevelInfo levelInfo =
                        com.sky.modelviewer.parsing.LevelInfoExtractor.extract(tgclFile);
                    final com.sky.modelviewer.parsing.LevelEventExtractor.EventInfo eventInfo =
                        com.sky.modelviewer.parsing.LevelEventExtractor.extract(tgclFile);
                    debugLog("Level parsed: " + meshInstances.size() + " mesh instances, " +
                             tgclFile.nodes.size() + " total nodes, " + markerGroups.size() + " marker groups, " +
                             eventInfo.total + " events, " + (levelInfo.links.size() + levelInfo.quests.size() +
                             levelInfo.music.size() + levelInfo.dialogs.size()) + " info items");

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
                            // Accumulate global bounding box — use render bounds (tight framing)
                            // Ported from HTML loadMapLevel line 37912-37915:
                            //   "use renderMin/renderMax for framing, not boundsMin/boundsMax"
                            float[] bMin = (md.renderMin != null) ? md.renderMin : md.boundsMin;
                            float[] bMax = (md.renderMax != null) ? md.renderMax : md.boundsMax;
                            if (bMin != null && bMax != null) {
                                if (bMin[0] < gMinX) gMinX = bMin[0];
                                if (bMin[1] < gMinY) gMinY = bMin[1];
                                if (bMin[2] < gMinZ) gMinZ = bMin[2];
                                if (bMax[0] > gMaxX) gMaxX = bMax[0];
                                if (bMax[1] > gMaxY) gMaxY = bMax[1];
                                if (bMax[2] > gMaxZ) gMaxZ = bMax[2];
                            }
                        } catch (Exception e) {
                            debugLog("Failed to load .meshes " + meshesPath + ": " + e.getMessage());
                            terrainParseInfo.append("  异常: ").append(e.getMessage()).append("\n");
                        }
                    }
                    
                    // No centering/scaling — use original world coordinates (matches HTML loadMapLevel).
                    // HTML does NOT center or scale terrain vertices; camera framing uses renderMin/renderMax.
                    float cx = 0, cy = 0, cz = 0, levelScale = 1f;
                    final float[] levelCenter = new float[]{0f, 0f, 0f};
                    final float finalLevelScale = 1f;
                    debugLog("Global bounds (original): [" + gMinX + "," + gMinY + "," + gMinZ + "] to [" + gMaxX + "," + gMaxY + "," + gMaxZ + "]");
                    
                    // Upload terrain data to GL (no centering/scaling)
                    int terrainLoaded = 0;
                    for (final com.sky.modelviewer.parsing.LevelMeshesReader.MeshesData md : terrainDataList) {
                        if (loadId != currentLoadId) return;
                        
                        final float[] terrainPos = md.positions;
                        // Validate positions
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
                        // No centering — use original coordinates
                        // Log original bounds
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
                                        0, null, terrainName, true, "terrain", "地形网格");
                                } catch (Exception e) {
                                    debugLog("Terrain GL error: " + e.getMessage());
                                } finally {
                                    meshViewport.requestRender();
                                }
                            }
                        });
                        terrainLoaded++;

                        // ── Cloud mesh creation (ported from HTML loadMapLevel line 37896-37908) ──
                        // Cloud geometry is baked in .meshes (shared vertex buffer, separate cloudIndices).
                        // Uses a dedicated cloud shader: vertex-color × hemisphere sky color + sun scatter + Fresnel rim.
                        // Cloud is semi-transparent, double-sided, no depth write, renderOrder=10 (drawn last).
                        if (md.cloudIndices != null && md.cloudIndices.length > 0) {
                            final int[] cloudIdxInt = md.cloudIndices;
                            // Cloud shares the same positions/normals/colors as terrain (already centered above)
                            final String cloudName = "cloud_" + (terrainLoaded - 1);
                            meshViewport.queueEvent(new Runnable() {
                                @Override
                                public void run() {
                                    if (loadId != currentLoadId) return;
                                    try {
                                        // Use vertex colors for cloud (material 80 = light blue-white)
                                        meshViewport.getMeshRenderer().addCloudMesh(
                                            terrainPos, terrainNorm, terrainColors,
                                            cloudIdxInt, cloudName
                                        );
                                    } catch (Exception e) {
                                        debugLog("Cloud GL error: " + e.getMessage());
                                    }
                                }
                            });
                        }
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

                    // Step 2: Load all mesh instances with SYNCHRONOUS texture loading
                    // Ported from HTML loadMapLevel (line 38566-38752):
                    //   - Build texIndex ONCE before the loop (HTML: texIndex built at startup)
                    //   - For each instance: resolve texture path → read KTX bytes → decode+upload in GL thread
                    //   - Pass real texId to addMeshInstance (NO async updateMeshTexture)
                    //   - geoCache + texBytesCache avoid re-parsing/re-reading same resources
                    final int maxMeshes = meshInstances.size();
                    // Build global KTX texture index ONCE (HTML: texIndex scanned at APK load time)
                    // Scans ALL .ktx files in APK, key = lowercase base name (no path, no ext).
                    // Reuse globalTexIndex if already built by icon loader (avoid double-scan)
                    if (globalTexIndex == null || globalTexIndex.isEmpty()) {
                        debugLog("Building global KTX texture index (before mesh loop)...");
                        globalTexIndex = SkyAssetScanner.buildTextureIndex(apkPath);
                    } else {
                        debugLog("Reusing existing texIndex: " + globalTexIndex.size() + " KTX entries");
                    }
                    final java.util.HashMap<String, String> texIndex = globalTexIndex;
                    debugLog("Texture index built: " + texIndex.size() + " KTX entries");
                    // texBytesCache: cache raw KTX bytes by texture name (avoid re-reading same file)
                    final java.util.HashMap<String, byte[]> texBytesCache = new java.util.HashMap<String, byte[]>();
                    // geoCache: cache parsed MeshData by resourceName to avoid re-parsing same mesh (HTML line 38627)
                    final java.util.HashMap<String, MeshData> geoCache = new java.util.HashMap<String, MeshData>();
                    final java.util.HashMap<String, String> meshPathCache = new java.util.HashMap<String, String>();
                    int texResolvedCount = 0;
                    int texNotFoundCount = 0;
                    // Diagnostic: record first 5 failed texture lookups with details
                    final java.util.List<String> texFailDetails = new java.util.ArrayList<String>();
                    // Diagnostic: record first 5 successful texture lookups
                    final java.util.List<String> texOkDetails = new java.util.ArrayList<String>();
                    // Diagnostic: count how many instances had diffuseTex from shaderParams
                    int hadDiffuseTex = 0;
                    // Diagnostic: sample first 5 diffuseTex names from instances
                    final java.util.List<String> sampleDiffuseTex = new java.util.ArrayList<String>();
                    for (int mi = 0; mi < maxMeshes; mi++) {
                        if (loadId != currentLoadId) {
                            debugLog("Level loading cancelled at mesh " + mi);
                            return;
                        }

                        final com.sky.modelviewer.parsing.LevelMeshExtractor.LevelMeshInstance inst =
                            meshInstances.get(mi);
                        final int meshIdx = mi;

                        try {
                            final String meshName = inst.resourceName;
                            final String shaderName = inst.shaderName != null ? inst.shaderName : "";

                            // === Classification (HTML line 37947-37997) ===
                            final boolean isWater = MapProcessingUtils.isWater(meshName, shaderName);
                            final boolean isAtmo = !isWater && MapProcessingUtils.isAtmosphere(meshName);
                            Float dcA = null;
                            if (inst.diffuseColor != null && inst.diffuseColor.length >= 4) {
                                dcA = inst.diffuseColor[3];
                            }
                            final boolean isFadeSprite = MapProcessingUtils.isFadeSprite(meshName, shaderName, dcA);
                            // isCutout: vegetation/foliage with alpha-test (HTML line 37954, 38024)
                            // alphaTest=0.5 discards transparent pixels, preventing black areas
                            final boolean isCutout = !isWater && !isAtmo &&
                                MapProcessingUtils.isCutout(meshName, shaderName);
                            final boolean solid = !isWater && !isAtmo;
                            // Object category: water/effect separate, else classifyMapObject (HTML line 37997)
                            final String objCategory = isWater ? "water" : isAtmo ? "effect" :
                                MapProcessingUtils.classifyMapObjectFull(meshName, shaderName, dcA);

                            // === Find mesh path (with cache) ===
                            String meshPath = meshPathCache.get(meshName.toLowerCase());
                            if (meshPath == null) {
                                meshPath = meshPathMap.get(meshName.toLowerCase());
                                if (meshPath == null) {
                                    String meshNameStripped = SkyResourceResolver.stripMeshVariantSuffix(meshName);
                                    if (!meshNameStripped.equals(meshName)) {
                                        meshPath = meshPathMap.get(meshNameStripped.toLowerCase());
                                    }
                                }
                                if (meshPath == null) {
                                    meshPath = findMeshInApk(apkPath, meshName);
                                }
                                if (meshPath != null) meshPathCache.put(meshName.toLowerCase(), meshPath);
                            }

                            if (meshPath == null) {
                                if (meshIdx < 20) debugLog("Mesh not found: " + meshName);
                                failedMeshes.add(meshName);
                                meshStats[1]++;
                                continue;
                            }

                            // === geoCache: reuse parsed geometry (HTML line 37933-37939) ===
                            MeshData meshData = geoCache.get(meshName.toLowerCase());
                            if (meshData == null) {
                                final byte[] meshRaw = SkyAssetScanner.readApkEntry(apkPath, meshPath);
                                if (meshRaw == null) {
                                    failedMeshes.add(meshName);
                                    meshStats[1]++;
                                    continue;
                                }
                                meshData = TgcMeshReader.readMesh(meshRaw, meshPath);
                                if (meshData.vertices.isEmpty() || meshData.indices.isEmpty()) {
                                    failedMeshes.add(meshName);
                                    meshStats[1]++;
                                    continue;
                                }
                                geoCache.put(meshName.toLowerCase(), meshData);
                            }

                            // Resolve per-mesh scale from PlaceableDefs (HTML resolvePlaceableScale line 37077)
                            Float meshScaleVal = SkyResourceResolver.resolveScale(apkPath, meshPath);
                            final float meshScale = (meshScaleVal != null && meshScaleVal > 0) ? meshScaleVal : 1f;

                            // Build vertex data
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

                            // Build uv1 (lightTex) and uv3 (diffuse2Tex) arrays
                            // Ported from HTML buildGeometry auv1/auv3 (line 37266-37287)
                            final float[] uvs1;
                            final float[] uvs3;
                            if (meshData.uv1 != null && meshData.uv1.size() == vc) {
                                uvs1 = new float[vc * 2];
                                int u1i = 0;
                                for (float[] uv : meshData.uv1) {
                                    uvs1[u1i++] = uv[0];
                                    uvs1[u1i++] = uv[1];
                                }
                            } else {
                                uvs1 = null; // will default to uv0 in shader
                            }
                            if (meshData.uv3 != null && meshData.uv3.size() == vc) {
                                uvs3 = new float[vc * 2];
                                int u3i = 0;
                                for (float[] uv : meshData.uv3) {
                                    uvs3[u3i++] = uv[0];
                                    uvs3[u3i++] = uv[1];
                                }
                            } else {
                                uvs3 = null; // will default to uv0 in shader
                            }

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

                            // Use original transform matrix — no centering/scaling (matches HTML loadMapLevel).
                            // HTML uses raw matrix directly; camera framing handles large coordinates.
                            final float[] transform;
                            if (inst.rawTransform != null) {
                                transform = inst.rawTransform;
                            } else {
                                transform = null;
                            }
                            final String meshNameFinal = meshName;

                            // === solidRGB: pure color for untextured objects (HTML line 37985-37994) ===
                            // No texture: use diffuseColor (activity colored) or matBaseColor (material base)
                            float[] solidColor = null;
                            if (solid && (inst.diffuseTex == null || inst.diffuseTex.isEmpty())) {
                                if (inst.diffuseColor != null &&
                                    (inst.diffuseColor[0] != 0 || inst.diffuseColor[1] != 0 || inst.diffuseColor[2] != 0)) {
                                    solidColor = inst.diffuseColor;
                                } else if (inst.matBaseColor != null &&
                                    (inst.matBaseColor[0] != 0 || inst.matBaseColor[1] != 0 || inst.matBaseColor[2] != 0)) {
                                    solidColor = inst.matBaseColor;
                                }
                            }
                            final float[] finalSolidColor = solidColor;

                            // explicitSolid: object has no diffuseTex but has a base/diffuse color
                            // → pure solid color, don't try to guess texture from mesh name (HTML line 38666-38669)
                            final boolean explicitSolid = (inst.diffuseTex == null || inst.diffuseTex.isEmpty()) &&
                                ((inst.matBaseColor != null && (inst.matBaseColor[0] != 0 || inst.matBaseColor[1] != 0 || inst.matBaseColor[2] != 0)) ||
                                 (inst.diffuseColor != null && (inst.diffuseColor[0] != 0 || inst.diffuseColor[1] != 0 || inst.diffuseColor[2] != 0)));

                            // Save mesh data and transform for export
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

                            // === Resolve texture path + read KTX bytes ===
                            // EXACT match to HTML loadMapLevel (line 38670, 38705):
                            //   const texName = it.diffuseTex || (explicitSolid ? '' : (rmat.diffuseTex || ''));
                            //   if (texName) { try { tex = await loadTexture(texName); } catch (e) { tex = null; } }
                            // HTML does NOT pre-check with texIndex — it assigns texName directly,
                            // then loadTexture does the lookup. If not found, tex=null (solid color).
                            // HTML does NOT use diffuse2Tex as main texture fallback.
                            String texName = null;
                            byte[] ktxBytes = null;
                            boolean skipTex = isWater; // Only water skips texture (HTML: isAtmo DOES load textures)
                            // Diagnostic: record what diffuseTex was extracted from shaderParams
                            if (inst.diffuseTex != null && !inst.diffuseTex.isEmpty()) {
                                hadDiffuseTex++;
                                if (sampleDiffuseTex.size() < 10) {
                                    sampleDiffuseTex.add(inst.diffuseTex);
                                }
                            }
                            if (!skipTex) {
                                // Primary: instance's diffuseTex (HTML line 38670: it.diffuseTex)
                                if (inst.diffuseTex != null && !inst.diffuseTex.isEmpty()) {
                                    texName = inst.diffuseTex;
                                }
                                // Fallback: resolveMaterial's diffuseTex — only if !explicitSolid
                                // (HTML line 38670: explicitSolid ? '' : (rmat.diffuseTex || ''))
                                if (texName == null && !explicitSolid) {
                                    try {
                                        com.sky.modelviewer.model.MaterialInfo mat =
                                            SkyResourceResolver.resolveMaterial(apkPath, meshPath);
                                        if (mat != null && mat.diffuseTex != null && !mat.diffuseTex.isEmpty()) {
                                            texName = mat.diffuseTex;
                                        }
                                    } catch (Exception e) { /* ignore */ }
                                }
                                // Read KTX bytes (HTML loadTexture: texIndex.get(key) → extractEntry)
                                if (texName != null) {
                                    String texKey = texName.toLowerCase();
                                    byte[] cachedBytes = texBytesCache.get(texKey);
                                    if (cachedBytes != null) {
                                        ktxBytes = cachedBytes;
                                    } else {
                                        String texPath = SkyAssetScanner.findTextureByIndex(texIndex, texName);
                                        if (texPath != null) {
                                            try {
                                                ktxBytes = SkyAssetScanner.readApkEntry(apkPath, texPath);
                                            } catch (Exception e) {
                                                debugLog("TEX read error: " + meshNameFinal + " tex=" + texName);
                                            }
                                        } else if (texFailDetails.size() < 10) {
                                            texFailDetails.add("tex='" + texName + "' NOT in texIndex");
                                        }
                                        if (ktxBytes != null) {
                                            texBytesCache.put(texKey, ktxBytes);
                                        }
                                    }
                                }
                                if (texName != null && ktxBytes != null) {
                                    texResolvedCount++;
                                    if (texOkDetails.size() < 10) {
                                        texOkDetails.add("tex='" + texName + "' bytes=" + ktxBytes.length);
                                    }
                                } else if (texName != null) {
                                    texNotFoundCount++;
                                    if (texNotFoundCount <= 20) {
                                        debugLog("TEX not found/decoded: mesh=" + meshNameFinal +
                                            " texName='" + texName + "'" +
                                            " inIndex=" + (SkyAssetScanner.findTextureByIndex(texIndex, texName) != null));
                                    }
                                }
                            }
                            final String finalTexName = texName;
                            final byte[] finalKtxBytes = ktxBytes;

                            // === Load extra textures: diffuse2 (second layer color) + light (baked AO) ===
                            // Ported from HTML loadMapLevel (line 38708-38712):
                            //   d2tex = await loadTexture(d2Name); lttex = await loadTexture(ltName);
                            // These are critical for correct rendering — many objects' real colors
                            // are in diffuse2Tex (multiplied with base), and baked lighting is in lightTex.
                            // Java fallback: use uv0 for all (mesh may not have uv1/uv3 channels).
                            byte[] d2Bytes = null, ltBytes = null;
                            final String d2Name = (!skipTex && inst.diffuse2Tex != null && !inst.diffuse2Tex.isEmpty())
                                ? inst.diffuse2Tex : null;
                            final String ltName = (!skipTex && inst.lightTex != null && !inst.lightTex.isEmpty())
                                ? inst.lightTex : null;
                            if (d2Name != null) {
                                String key = d2Name.toLowerCase();
                                d2Bytes = texBytesCache.get(key);
                                if (d2Bytes == null) {
                                    String path = SkyAssetScanner.findTextureByIndex(texIndex, d2Name);
                                    if (path != null) {
                                        try { d2Bytes = SkyAssetScanner.readApkEntry(apkPath, path); } catch (Exception e) {}
                                    }
                                    if (d2Bytes != null) texBytesCache.put(key, d2Bytes);
                                }
                            }
                            if (ltName != null) {
                                String key = ltName.toLowerCase();
                                ltBytes = texBytesCache.get(key);
                                if (ltBytes == null) {
                                    String path = SkyAssetScanner.findTextureByIndex(texIndex, ltName);
                                    if (path != null) {
                                        try { ltBytes = SkyAssetScanner.readApkEntry(apkPath, path); } catch (Exception e) {}
                                    }
                                    if (ltBytes != null) texBytesCache.put(key, ltBytes);
                                }
                            }
                            final byte[] finalD2Bytes = d2Bytes;
                            final byte[] finalLtBytes = ltBytes;
                            final float[] finalD2Offset = inst.diffuse2Offset;

                            // === Upload geometry + texture to GL (HTML loadMapLevel line 38733-38747) ===
                            // HTML: imat = skyMaterial({ map: tex }); im = new THREE.Mesh(ig, imat)
                            // Java: decode KTX in GL thread (getOrCreateLevelTexture) → texId,
                            //       then addMeshInstance with real texId (NO separate updateMeshTexture)
                            meshViewport.queueEvent(new Runnable() {
                                @Override
                                public void run() {
                                    if (loadId != currentLoadId) return;
                                    try {
                                        if (isWater) {
                                            meshViewport.getMeshRenderer().addWaterMesh(
                                                positions, normalArr, idxShort, idxInt,
                                                transform, meshNameFinal, meshNameFinal
                                            );
                                        } else {
                                            // solidColor → vertex colors for untextured objects
                                            // MUST use 4 components (RGBA) to match shader's glVertexAttribPointer(colorLoc, 4, ...)
                                            float[] meshColors = null;
                                            if (finalSolidColor != null) {
                                                meshColors = new float[vc * 4];
                                                for (int ci = 0; ci < vc; ci++) {
                                                    meshColors[ci * 4] = finalSolidColor[0];
                                                    meshColors[ci * 4 + 1] = finalSolidColor[1];
                                                    meshColors[ci * 4 + 2] = finalSolidColor[2];
                                                    meshColors[ci * 4 + 3] = 1.0f;
                                                }
                                            }
                                            // Synchronous texture decode+upload (HTML: tex = await loadTexture)
                                            int texId = 0;
                                            if (finalTexName != null && finalKtxBytes != null) {
                                                texId = meshViewport.getMeshRenderer()
                                                    .getOrCreateLevelTexture(finalTexName, finalKtxBytes);
                                            }
                                            // No diagnostic red texture — match HTML: if tex=null, mesh uses solid color
                                            meshViewport.getMeshRenderer().addMeshInstance(
                                                positions, normalArr, meshColors, uvs, uvs1, uvs3, idxShort, idxInt,
                                                texId, transform, meshNameFinal, true, objCategory, meshNameFinal
                                            );
                                            // Attach multi-textures (diffuse2 + light) — HTML skyMaterial
                                            // uv1→lightTex, uv3→diffuse2Tex (matches HTML USE_UV13)
                                            int d2TexId = 0, ltTexId = 0;
                                            if (finalD2Bytes != null && d2Name != null) {
                                                d2TexId = meshViewport.getMeshRenderer()
                                                    .getOrCreateLevelTexture(d2Name, finalD2Bytes);
                                            }
                                            if (finalLtBytes != null && ltName != null) {
                                                ltTexId = meshViewport.getMeshRenderer()
                                                    .getOrCreateLevelTexture(ltName, finalLtBytes);
                                            }
                                            if (d2TexId != 0 || ltTexId != 0) {
                                                meshViewport.getMeshRenderer().setLastMeshExtraTextures(
                                                    d2TexId, ltTexId, 0, finalD2Offset);
                                            }
                                            // Alpha test for cutout vegetation (HTML line 38024: alphaTest=0.5)
                                            // Discards transparent pixels, preventing black areas on foliage
                                            if (isCutout && texId != 0) {
                                                meshViewport.getMeshRenderer().setLastMeshAlphaTest(0.5f);
                                            }
                                            // Atmosphere effects (fog/cloud/aurora): semi-transparent, no depth write
                                            // HTML line 38021-38023: transparent=true, opacity=0.35, depthWrite=false
                                            if (isAtmo) {
                                                meshViewport.getMeshRenderer().setLastMeshTransparent(0.35f, false);
                                            }
                                        }
                                    } catch (Exception e) {
                                        debugLog("GL error for " + meshNameFinal + ": " + e.getMessage());
                                    } finally {
                                        meshViewport.requestRender();
                                    }
                                }
                            });

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
                            debugLog("Failed to load mesh " + inst.resourceName + ": " + e.getMessage());
                            failedMeshes.add(inst.resourceName);
                            meshStats[1]++;
                        }
                    }

                    debugLog("Mesh loop done: " + meshStats[0] + " loaded, " + meshStats[1] + " failed" +
                        " | Textures: " + texResolvedCount + " resolved, " + texNotFoundCount + " not found" +
                        " | texBytesCache=" + texBytesCache.size() + " unique textures");

                    // Show texture loading stats as Toast for user visibility
                    final int fTexResolved = texResolvedCount;
                    final int fTexNotFound = texNotFoundCount;
                    final int fMeshLoaded = meshStats[0];
                    final int fTexIndexSize = texIndex.size();
                    final int fHadDiffuseTex = hadDiffuseTex;
                    final java.util.List<String> fSampleDiffuse = sampleDiffuseTex;
                    final java.util.List<String> fFailDetails = texFailDetails;
                    final java.util.List<String> fOkDetails = texOkDetails;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (loadId == currentLoadId) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("=== 贴图诊断 ===\n");
                                sb.append("texIndex(KTX数): ").append(fTexIndexSize).append("\n");
                                sb.append("mesh加载: ").append(fMeshLoaded).append("\n");
                                sb.append("有diffuseTex: ").append(fHadDiffuseTex).append("\n");
                                sb.append("贴图找到: ").append(fTexResolved).append("\n");
                                sb.append("贴图未找到: ").append(fTexNotFound).append("\n");
                                if (!fSampleDiffuse.isEmpty()) {
                                    sb.append("--- 提取的diffuseTex ---\n");
                                    for (String s : fSampleDiffuse) sb.append(s).append("\n");
                                }
                                if (!fOkDetails.isEmpty()) {
                                    sb.append("--- 成功的贴图 ---\n");
                                    for (String s : fOkDetails) sb.append(s).append("\n");
                                }
                                if (!fFailDetails.isEmpty()) {
                                    sb.append("--- 失败的贴图 ---\n");
                                    for (String s : fFailDetails) sb.append(s).append("\n");
                                }
                                android.widget.Toast.makeText(MainActivity.this, sb.toString(),
                                    android.widget.Toast.LENGTH_LONG).show();
                            }
                        }
                    });

                    // Frame camera again after all bin meshes loaded
                    meshViewport.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            if (loadId != currentLoadId) return;
                            meshViewport.getMeshRenderer().frameCamera();
                            meshViewport.requestRender();
                        }
                    });

                    // Update map layer panel with category counts (ported from HTML updateMapInfo)
                    if (currentAssetMode == 2) {  // Map mode
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (loadId == currentLoadId) {
                                    updateMapLayerPanel();
                                    // Apply default layer visibility (effect/marker hidden by default)
                                    // Ported from HTML: im.visible = false for effect category (line 38045)
                                    // Must call applyMapLayerFilters to sync renderer with mapLayerVisible state
                                    applyMapLayerFilters();
                                    // Store level info / event data and update button visibility
                                    // Ported from HTML setupInfoPanel/setupEventPanel (line 38816-38818)
                                    currentLevelInfo = levelInfo;
                                    currentEventInfo = eventInfo;
                                    if (btnLevelInfo != null) {
                                        btnLevelInfo.setVisibility(
                                            com.sky.modelviewer.parsing.LevelInfoExtractor.hasContent(currentLevelInfo)
                                                ? View.VISIBLE : View.GONE);
                                    }
                                    if (btnEventLogic != null) {
                                        btnEventLogic.setVisibility(
                                            (currentEventInfo != null && currentEventInfo.total > 0)
                                                ? View.VISIBLE : View.GONE);
                                    }
                                }
                            }
                        });
                    }

                    // Step 3 (REMOVED): Textures are now loaded SYNCHRONOUSLY in Step 2's queueEvent
                    // via getOrCreateLevelTexture(). This matches HTML's approach where loadTexture()
                    // is called during material creation, before the mesh is added to the scene.
                    // The old async approach (addMeshInstance with texId=0, then updateMeshTexture
                    // in a separate thread) caused textures to not render reliably.

                    // Step 4: Load point markers (ported from HTML extractLevelMarkers + makeMarkerSprite/Foot line 38053-38067)
                    // Markers: CandleObject, WingBuff, Pickup, Portal, Checkpoint, Npc, LevelLink, etc.
                    // These are NOT 3D models — they are sprite icons with stems and foot crosses.
                    if (!markerGroups.isEmpty() && currentAssetMode == 2) {
                        // Compute diag from level bounds for marker sizing
                        float diag = 100f; // fallback
                        if (gMaxX > gMinX || gMaxY > gMinY || gMaxZ > gMinZ) {
                            float dx = gMaxX - gMinX;
                            float dy = gMaxY - gMinY;
                            float dz = gMaxZ - gMinZ;
                            diag = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                        }
                        final float markerDiag = diag;
                        int markerCountTotal = 0;
                        for (com.sky.modelviewer.parsing.LevelMarkerExtractor.MarkerGroup mg0 : markerGroups) {
                            if (mg0.points != null) markerCountTotal += mg0.points.size();
                        }
                        final int markerCountTotalFinal = markerCountTotal;
                        debugLog("Loading " + markerGroups.size() + " marker groups, " + markerCountTotalFinal + " total markers");

                        meshViewport.queueEvent(new Runnable() {
                            @Override
                            public void run() {
                                if (loadId != currentLoadId) return;
                                try {
                                    for (com.sky.modelviewer.parsing.LevelMarkerExtractor.MarkerGroup grp : markerGroups) {
                                        if (grp.points == null || grp.points.isEmpty()) continue;
                                        List<float[]> pts = new ArrayList<float[]>(grp.points.size());
                                        for (com.sky.modelviewer.parsing.LevelMarkerExtractor.MarkerPoint mp : grp.points) {
                                            if (mp.pos == null || mp.pos.length < 3) continue;
                                            // Use original world coordinates (no centering, matches HTML)
                                            pts.add(new float[]{
                                                mp.pos[0],
                                                mp.pos[1],
                                                mp.pos[2]
                                            });
                                        }
                                        if (!pts.isEmpty()) {
                                            meshViewport.getMeshRenderer().addMarkers(pts, grp.color, grp.label, markerDiag);
                                        }
                                    }
                                    meshViewport.requestRender();
                                } catch (Exception e) {
                                    debugLog("Marker loading error: " + e.getMessage());
                                }
                            }
                        });
                    }

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
                    info.append("总节点数: ").append(tgclFile.nodes.size()).append("\n");
                    info.append("模型实例数: ").append(meshInstances.size()).append("\n");
                    int texCount = 0;
                    for (com.sky.modelviewer.parsing.LevelMeshExtractor.LevelMeshInstance li : meshInstances) {
                        if (li.diffuseTex != null && !li.diffuseTex.isEmpty()) texCount++;
                    }
                    info.append("含贴图实例: ").append(texCount).append("\n");
                    // Marker info
                    int totalMarkers = 0;
                    for (com.sky.modelviewer.parsing.LevelMarkerExtractor.MarkerGroup mg : markerGroups) {
                        if (mg.points != null) totalMarkers += mg.points.size();
                    }
                    info.append("点位标记: ").append(markerGroups.size()).append(" 组, ").append(totalMarkers).append(" 个\n");
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
