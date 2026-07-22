package com.sky.modelviewer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 光遇快报 Activity
 *
 * Displays 4 Sky game info APIs from api.vqqc.cn:
 * 1. 每日任务位置图片 - GET https://api.vqqc.cn/api/sky/sc/scrw?key=KEY
 * 2. 红石天气预报     - GET https://api.vqqc.cn/api/sky/gytq?key=KEY  (returns JSON with image URLs)
 * 3. 免费魔法图片     - GET https://api.vqqc.cn/api/sky/mf/magic?key=KEY
 * 4. 大蜡烛位置图片   - GET https://api.vqqc.cn/api/sky/sc/scdl?key=KEY
 *
 * All APIs are free (10000 requests/day limit), require a key from api.vqqc.cn.
 * The key is stored in SharedPreferences and can be entered in the UI.
 *
 * NOTE: No lambda expressions used - compatible with AIDE mobile compiler.
 */
public class SkyNewsActivity extends Activity {

    private static final String TAG = "SkyNewsActivity";
    private static final String PREF_NAME = "sky_news_prefs";
    private static final String PREF_KEY = "api_key";

    // API endpoints
    private static final String API_DAILY_QUEST = "https://api.vqqc.cn/api/sky/sc/scrw";
    private static final String API_RED_STONE   = "https://api.vqqc.cn/api/sky/gytq";
    private static final String API_FREE_MAGIC  = "https://api.vqqc.cn/api/sky/mf/magic";
    private static final String API_BIG_CANDLE  = "https://api.vqqc.cn/api/sky/sc/scdl";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String apiKey = "";

    // Views for each card
    private ImageView ivDailyQuest;
    private ProgressBar pbDailyQuest;
    private TextView tvDailyQuestError;

    private LinearLayout llRedStoneImages;
    private TextView tvRedStoneInfo;
    private ProgressBar pbRedStone;
    private TextView tvRedStoneError;

    private ImageView ivFreeMagic;
    private ProgressBar pbFreeMagic;
    private TextView tvFreeMagicError;

    private ImageView ivBigCandle;
    private ProgressBar pbBigCandle;
    private TextView tvBigCandleError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sky_news);

        // Load saved API key
        final SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        apiKey = prefs.getString(PREF_KEY, "");

        // Setup views
        final EditText etApiKey = (EditText) findViewById(R.id.etApiKey);
        etApiKey.setText(apiKey);

        // Save key button
        findViewById(R.id.btnSaveKey).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apiKey = etApiKey.getText().toString().trim();
                prefs.edit().putString(PREF_KEY, apiKey).apply();
                Toast.makeText(SkyNewsActivity.this, "密钥已保存", Toast.LENGTH_SHORT).show();
            }
        });

        // Back button
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Refresh all button
        findViewById(R.id.btnRefreshAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkKey()) loadAll();
            }
        });

        // Individual load buttons
        findViewById(R.id.btnLoadDailyQuest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkKey()) loadDailyQuest();
            }
        });
        findViewById(R.id.btnLoadRedStone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkKey()) loadRedStone();
            }
        });
        findViewById(R.id.btnLoadFreeMagic).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkKey()) loadFreeMagic();
            }
        });
        findViewById(R.id.btnLoadBigCandle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkKey()) loadBigCandle();
            }
        });

        // Init view references
        ivDailyQuest = (ImageView) findViewById(R.id.ivDailyQuest);
        pbDailyQuest = (ProgressBar) findViewById(R.id.pbDailyQuest);
        tvDailyQuestError = (TextView) findViewById(R.id.tvDailyQuestError);

        llRedStoneImages = (LinearLayout) findViewById(R.id.llRedStoneImages);
        tvRedStoneInfo = (TextView) findViewById(R.id.tvRedStoneInfo);
        pbRedStone = (ProgressBar) findViewById(R.id.pbRedStone);
        tvRedStoneError = (TextView) findViewById(R.id.tvRedStoneError);

        ivFreeMagic = (ImageView) findViewById(R.id.ivFreeMagic);
        pbFreeMagic = (ProgressBar) findViewById(R.id.pbFreeMagic);
        tvFreeMagicError = (TextView) findViewById(R.id.tvFreeMagicError);

        ivBigCandle = (ImageView) findViewById(R.id.ivBigCandle);
        pbBigCandle = (ProgressBar) findViewById(R.id.pbBigCandle);
        tvBigCandleError = (TextView) findViewById(R.id.tvBigCandleError);

        // Auto-load if key exists
        if (apiKey != null && !apiKey.isEmpty()) {
            loadAll();
        }
    }

    private boolean checkKey() {
        if (apiKey == null || apiKey.isEmpty()) {
            Toast.makeText(this, "请先输入并保存 API Key", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void loadAll() {
        loadDailyQuest();
        loadRedStone();
        loadFreeMagic();
        loadBigCandle();
    }

    // === Card 1: Daily Quest Image ===
    private void loadDailyQuest() {
        pbDailyQuest.setVisibility(View.VISIBLE);
        tvDailyQuestError.setVisibility(View.GONE);
        ivDailyQuest.setVisibility(View.GONE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String urlStr = API_DAILY_QUEST + "?key=" + apiKey;
                    final Bitmap bmp = fetchImage(urlStr);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbDailyQuest.setVisibility(View.GONE);
                            if (bmp != null) {
                                ivDailyQuest.setImageBitmap(bmp);
                                ivDailyQuest.setVisibility(View.VISIBLE);
                            } else {
                                tvDailyQuestError.setText("加载失败，请检查密钥或网络");
                                tvDailyQuestError.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbDailyQuest.setVisibility(View.GONE);
                            tvDailyQuestError.setText("错误: " + e.getMessage());
                            tvDailyQuestError.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    // === Card 2: Red Stone Weather (JSON with multiple images) ===
    private void loadRedStone() {
        pbRedStone.setVisibility(View.VISIBLE);
        tvRedStoneError.setVisibility(View.GONE);
        tvRedStoneInfo.setVisibility(View.GONE);
        llRedStoneImages.setVisibility(View.GONE);
        llRedStoneImages.removeAllViews();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String urlStr = API_RED_STONE + "?key=" + apiKey;
                    String response = fetchText(urlStr);
                    final JSONObject json = new JSONObject(response);

                    final int code = json.optInt("code", 0);
                    final String msg = json.optString("msg", "");
                    final String hs = json.optString("hs", "");

                    if (code != 200) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                pbRedStone.setVisibility(View.GONE);
                                tvRedStoneError.setText("API错误: " + msg + " (code=" + code + ")");
                                tvRedStoneError.setVisibility(View.VISIBLE);
                            }
                        });
                        return;
                    }

                    final JSONObject data = json.optJSONObject("data");
                    if (data == null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                pbRedStone.setVisibility(View.GONE);
                                tvRedStoneError.setText("返回数据为空");
                                tvRedStoneError.setVisibility(View.VISIBLE);
                            }
                        });
                        return;
                    }

                    // Extract image URLs from data (img0, img1, img2, img3...)
                    final List<String> imgUrls = new ArrayList<String>();
                    for (int i = 0; i < 10; i++) {
                        Object imgObj = data.opt("img" + i);
                        if (imgObj == null) break;
                        if (imgObj instanceof JSONArray) {
                            JSONArray arr = (JSONArray) imgObj;
                            if (arr.length() > 0) {
                                imgUrls.add(arr.getString(0));
                            }
                        } else if (imgObj instanceof String) {
                            imgUrls.add((String) imgObj);
                        }
                    }

                    final int imgCount = imgUrls.size();

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbRedStone.setVisibility(View.GONE);
                            if (hs != null && !hs.isEmpty()) {
                                tvRedStoneInfo.setText("红石位置: " + hs);
                                tvRedStoneInfo.setVisibility(View.VISIBLE);
                            }
                            if (imgCount > 0) {
                                llRedStoneImages.setVisibility(View.VISIBLE);
                            }
                        }
                    });

                    // Load each image
                    for (int i = 0; i < imgUrls.size(); i++) {
                        final String imgUrl = imgUrls.get(i);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    final Bitmap bmp = fetchImageDirect(imgUrl);
                                    if (bmp != null) {
                                        mainHandler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                ImageView iv = new ImageView(SkyNewsActivity.this);
                                                iv.setLayoutParams(new LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT));
                                                iv.setAdjustViewBounds(true);
                                                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                                                iv.setImageBitmap(bmp);
                                                llRedStoneImages.addView(iv);
                                            }
                                        });
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "Failed to load red stone image: " + e.getMessage());
                                }
                            }
                        }).start();
                    }

                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbRedStone.setVisibility(View.GONE);
                            tvRedStoneError.setText("错误: " + e.getMessage());
                            tvRedStoneError.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    // === Card 3: Free Magic Image ===
    private void loadFreeMagic() {
        pbFreeMagic.setVisibility(View.VISIBLE);
        tvFreeMagicError.setVisibility(View.GONE);
        ivFreeMagic.setVisibility(View.GONE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String urlStr = API_FREE_MAGIC + "?key=" + apiKey;
                    final Bitmap bmp = fetchImage(urlStr);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbFreeMagic.setVisibility(View.GONE);
                            if (bmp != null) {
                                ivFreeMagic.setImageBitmap(bmp);
                                ivFreeMagic.setVisibility(View.VISIBLE);
                            } else {
                                tvFreeMagicError.setText("加载失败，请检查密钥或网络");
                                tvFreeMagicError.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbFreeMagic.setVisibility(View.GONE);
                            tvFreeMagicError.setText("错误: " + e.getMessage());
                            tvFreeMagicError.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    // === Card 4: Big Candle Image ===
    private void loadBigCandle() {
        pbBigCandle.setVisibility(View.VISIBLE);
        tvBigCandleError.setVisibility(View.GONE);
        ivBigCandle.setVisibility(View.GONE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String urlStr = API_BIG_CANDLE + "?key=" + apiKey;
                    final Bitmap bmp = fetchImage(urlStr);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbBigCandle.setVisibility(View.GONE);
                            if (bmp != null) {
                                ivBigCandle.setImageBitmap(bmp);
                                ivBigCandle.setVisibility(View.VISIBLE);
                            } else {
                                tvBigCandleError.setText("加载失败，请检查密钥或网络");
                                tvBigCandleError.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pbBigCandle.setVisibility(View.GONE);
                            tvBigCandleError.setText("错误: " + e.getMessage());
                            tvBigCandleError.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    // === Network Helpers ===

    /**
     * Fetch an image from a URL. Handles both direct image responses and
     * JSON responses containing an image URL.
     */
    private Bitmap fetchImage(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("HTTP " + responseCode);
            }

            String contentType = conn.getContentType();
            if (contentType != null) contentType = contentType.toLowerCase();

            InputStream is = conn.getInputStream();

            // If response is JSON, parse it to find image URL
            if (contentType != null && contentType.contains("json")) {
                String jsonText = readStream(is);
                Log.d(TAG, "Got JSON response: " + jsonText.substring(0, Math.min(200, jsonText.length())));
                JSONObject json = new JSONObject(jsonText);

                // Try common field names for image URL
                String imgUrl = null;
                if (json.has("image")) imgUrl = json.getString("image");
                else if (json.has("imgae")) imgUrl = json.getString("imgae"); // typo in API doc
                else if (json.has("img")) imgUrl = json.getString("img");
                else if (json.has("url")) imgUrl = json.getString("url");
                else if (json.has("data")) {
                    Object dataObj = json.get("data");
                    if (dataObj instanceof String) {
                        imgUrl = (String) dataObj;
                    } else if (dataObj instanceof JSONObject) {
                        JSONObject dataJson = (JSONObject) dataObj;
                        if (dataJson.has("img0")) {
                            Object img0 = dataJson.get("img0");
                            if (img0 instanceof JSONArray) {
                                imgUrl = ((JSONArray) img0).getString(0);
                            } else {
                                imgUrl = img0.toString();
                            }
                        } else if (dataJson.has("url")) {
                            imgUrl = dataJson.getString("url");
                        }
                    }
                }

                if (imgUrl != null && !imgUrl.isEmpty()) {
                    // Fetch the actual image from the URL
                    return fetchImageDirect(imgUrl);
                }
                return null;
            } else {
                // Direct image response
                return BitmapFactory.decodeStream(is);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Fetch an image directly from a URL (no JSON parsing).
     */
    private Bitmap fetchImageDirect(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("HTTP " + responseCode);
            }

            InputStream is = conn.getInputStream();
            return BitmapFactory.decodeStream(is);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Fetch text content from a URL.
     */
    private String fetchText(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("HTTP " + responseCode);
            }

            return readStream(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toString("UTF-8");
    }
}
