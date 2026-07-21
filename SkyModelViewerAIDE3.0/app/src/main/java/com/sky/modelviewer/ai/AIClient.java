package com.sky.modelviewer.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AI API Client - supports OpenAI-compatible APIs (DeepSeek, Qwen, OpenAI, etc.)
 *
 * Features:
 * - Chat completions with text and vision (image) support
 * - Deep thinking mode (higher max_tokens + reasoning guidance)
 * - Web search integration (DuckDuckGo instant answers)
 * - Configurable API endpoint, key, and model
 * - Context-aware conversations (system prompt + history)
 */
public class AIClient {

    private static final String TAG = "AIClient";
    private static final String PREFS_NAME = "ai_config";
    private static final String DEFAULT_API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final String DEFAULT_API_KEY = "";

    // === Chat message with multimodal support ===

    public static class ChatMessage {
        public String role;  // "system", "user", "assistant"
        public String content;           // text content
        public String imageBase64;       // base64-encoded image (for vision)
        public String imageMimeType;     // "image/png" or "image/jpeg"

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public ChatMessage(String role, String content, String imageBase64, String imageMimeType) {
            this.role = role;
            this.content = content;
            this.imageBase64 = imageBase64;
            this.imageMimeType = imageMimeType;
        }

        public boolean hasImage() {
            return imageBase64 != null && !imageBase64.isEmpty();
        }

        /**
         * Convert to JSON. For vision messages, content is an array of text + image_url parts.
         */
        public JSONObject toJson() throws Exception {
            JSONObject obj = new JSONObject();
            obj.put("role", role);
            if (hasImage()) {
                // Multimodal format (OpenAI vision API compatible)
                JSONArray contentArr = new JSONArray();
                if (content != null && !content.isEmpty()) {
                    JSONObject textPart = new JSONObject();
                    textPart.put("type", "text");
                    textPart.put("text", content);
                    contentArr.put(textPart);
                }
                JSONObject imagePart = new JSONObject();
                imagePart.put("type", "image_url");
                JSONObject imageUrl = new JSONObject();
                imageUrl.put("url", "data:" + imageMimeType + ";base64," + imageBase64);
                imagePart.put("image_url", imageUrl);
                contentArr.put(imagePart);
                obj.put("content", contentArr);
            } else {
                obj.put("content", content != null ? content : "");
            }
            return obj;
        }
    }

    // === Chat options ===

    public static class ChatOptions {
        public boolean deepThink = false;    // deep thinking mode
        public boolean webSearch = false;    // web search before sending
        public int maxTokens = 2048;         // max response tokens
        public double temperature = 0.7;     // sampling temperature

        public ChatOptions deepThink(boolean v) { this.deepThink = v; return this; }
        public ChatOptions webSearch(boolean v) { this.webSearch = v; return this; }
        public ChatOptions maxTokens(int v) { this.maxTokens = v; return this; }
        public ChatOptions temperature(double v) { this.temperature = v; return this; }
    }

    public interface ChatCallback {
        void onResponse(String reply);
        void onError(String error);
        void onStatus(String status);  // progress updates (searching, thinking, etc.)
    }

    // === Config helpers ===

    private static String getApiUrl(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getString("api_url", DEFAULT_API_URL);
    }

    private static String getApiKey(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getString("api_key", DEFAULT_API_KEY);
    }

    private static String getModel(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getString("model", DEFAULT_MODEL);
    }

    public static boolean isConfigured(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = sp.getString("api_key", "");
        boolean configured = key != null && key.trim().length() > 5;
        Log.d(TAG, "isConfigured: keyLen=" + (key != null ? key.length() : 0) + " configured=" + configured);
        return configured;
    }

    // === Main chat method ===

    /**
     * Send a chat request to the LLM API with options.
     * Runs on a background thread, calls callback on completion.
     */
    public static void chat(final Context ctx, final List<ChatMessage> messages,
                            final ChatOptions options, final ChatCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String apiKey = getApiKey(ctx);
                    String apiUrl = getApiUrl(ctx);
                    String model = getModel(ctx);

                    if (apiKey == null || apiKey.isEmpty()) {
                        callback.onError("请先配置API密钥");
                        return;
                    }

                    // Step 1: Web search if enabled
                    List<ChatMessage> finalMessages = new ArrayList<>(messages);
                    if (options != null && options.webSearch) {
                        // Extract the last user message as search query
                        String searchQuery = "";
                        for (int i = finalMessages.size() - 1; i >= 0; i--) {
                            if ("user".equals(finalMessages.get(i).role) &&
                                finalMessages.get(i).content != null && !finalMessages.get(i).content.isEmpty()) {
                                searchQuery = finalMessages.get(i).content;
                                break;
                            }
                        }
                        if (!searchQuery.isEmpty()) {
                            callback.onStatus("正在联网搜索...");
                            String searchResults = webSearch(searchQuery, 5);
                            if (searchResults != null && !searchResults.isEmpty()) {
                                // Insert search results as a system message before the user's question
                                String searchContext = "【网络搜索结果】（联网搜索已生效，以下是为用户问题搜索到的网络信息）：\n" +
                                    "搜索关键词: " + searchQuery + "\n\n" +
                                    searchResults + "\n\n" +
                                    "请基于以上网络搜索结果回答用户问题。如果搜索结果与问题相关，请引用来源；如果不相关，请说明未找到相关信息并基于自身知识回答。";
                                // Find the position of the last user message and insert before it
                                int insertPos = finalMessages.size() - 1;
                                for (int i = finalMessages.size() - 1; i >= 0; i--) {
                                    if ("user".equals(finalMessages.get(i).role)) {
                                        insertPos = i;
                                        break;
                                    }
                                }
                                finalMessages.add(insertPos, new ChatMessage("system", searchContext));
                            } else {
                                // Search returned empty — still inform AI that search was attempted
                                String searchContext = "【网络搜索结果】联网搜索已执行，但未找到相关结果。搜索关键词: " + searchQuery + "。请基于自身知识回答用户问题。";
                                int insertPos = finalMessages.size() - 1;
                                for (int i = finalMessages.size() - 1; i >= 0; i--) {
                                    if ("user".equals(finalMessages.get(i).role)) {
                                        insertPos = i;
                                        break;
                                    }
                                }
                                finalMessages.add(insertPos, new ChatMessage("system", searchContext));
                            }
                        }
                    }

                    // Step 2: Deep thinking mode adjustments
                    int maxTokens = options != null ? options.maxTokens : 2048;
                    double temperature = options != null ? options.temperature : 0.7;
                    if (options != null && options.deepThink) {
                        callback.onStatus("深度思考中...");
                        maxTokens = Math.max(maxTokens, 4096);
                        temperature = 0.3; // lower temperature for more focused reasoning
                        // For reasoning models (deepseek-reasoner), the API auto-returns reasoning_content
                        // We just need to request more tokens
                    }

                    // Build request body
                    JSONObject requestBody = new JSONObject();
                    requestBody.put("model", model);
                    requestBody.put("temperature", temperature);
                    requestBody.put("max_tokens", maxTokens);
                    requestBody.put("stream", false);

                    JSONArray msgArray = new JSONArray();
                    for (ChatMessage msg : finalMessages) {
                        msgArray.put(msg.toJson());
                    }
                    requestBody.put("messages", msgArray);

                    // Create connection
                    URL url = new URL(apiUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(120000); // longer timeout for deep thinking
                    conn.setDoOutput(true);

                    // Send request
                    byte[] bodyBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream os = conn.getOutputStream();
                    os.write(bodyBytes);
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        String errMsg = readStream(conn.getErrorStream());
                        Log.e(TAG, "API error " + code + ": " + errMsg);
                        callback.onError("API错误(" + code + "): " + extractErrorMessage(errMsg));
                        return;
                    }

                    // Parse response
                    String responseStr = readStream(conn.getInputStream());
                    JSONObject response = new JSONObject(responseStr);
                    JSONArray choices = response.optJSONArray("choices");
                    if (choices != null && choices.length() > 0) {
                        JSONObject choice = choices.getJSONObject(0);
                        JSONObject message = choice.getJSONObject("message");
                        String reply = message.getString("content");

                        // Check for reasoning content (deepseek-reasoner etc.)
                        String reasoning = message.optString("reasoning_content", "");
                        if (reasoning != null && !reasoning.isEmpty() && options != null && options.deepThink) {
                            // Prepend reasoning as a collapsible section
                            reply = "【思考过程】\n" + reasoning.trim() + "\n\n【回答】\n" + reply.trim();
                        }

                        callback.onResponse(reply.trim());
                    } else {
                        callback.onError("API返回空结果");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Chat request failed", e);
                    callback.onError("请求失败: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    /** Backward-compatible chat without options. */
    public static void chat(Context ctx, List<ChatMessage> messages, ChatCallback callback) {
        ChatOptions opts = new ChatOptions();
        // Wrap callback to handle onStatus (no-op for old callers)
        final ChatCallback finalCallback = callback;
        ChatCallback wrappedCallback = new ChatCallback() {
            public void onResponse(String reply) { finalCallback.onResponse(reply); }
            public void onError(String error) { finalCallback.onError(error); }
            public void onStatus(String status) { /* no-op */ }
        };
        chat(ctx, messages, opts, wrappedCallback);
    }

    // === Web search ===

    /**
     * Search the web using DuckDuckGo Instant Answer API.
     * Returns a formatted string with search results.
     */
    public static String webSearch(String query, int maxResults) {
        HttpURLConnection conn = null;
        try {
            // Use DuckDuckGo HTML lite endpoint for search results
            String encodedQuery = URLEncoder.encode(query, "UTF-8");
            String searchUrl = "https://lite.duckduckgo.com/lite/?q=" + encodedQuery + "&kl=cn-zh";

            URL url = new URL(searchUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Web search failed: " + code);
                return null;
            }

            String html = readStream(conn.getInputStream());
            return parseSearchResults(html, maxResults);
        } catch (Exception e) {
            Log.w(TAG, "Web search error: " + e.getMessage());
            // Fallback: try DuckDuckGo Instant Answer API
            return ddgInstantAnswer(query);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Fallback: DuckDuckGo Instant Answer API (JSON). */
    private static String ddgInstantAnswer(String query) {
        HttpURLConnection conn = null;
        try {
            String encodedQuery = URLEncoder.encode(query, "UTF-8");
            String apiUrl = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1&skip_disambig=1";

            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            if (conn.getResponseCode() != 200) return null;

            String responseStr = readStream(conn.getInputStream());
            JSONObject json = new JSONObject(responseStr);

            StringBuilder sb = new StringBuilder();
            String abstractText = json.optString("AbstractText", "");
            if (!abstractText.isEmpty()) {
                sb.append(abstractText).append("\n");
            }
            String abstractSource = json.optString("AbstractSource", "");
            if (!abstractSource.isEmpty()) {
                sb.append("来源: ").append(abstractSource).append("\n");
            }
            // Related topics
            JSONArray topics = json.optJSONArray("RelatedTopics");
            if (topics != null) {
                int count = 0;
                for (int i = 0; i < topics.length() && count < 5; i++) {
                    JSONObject topic = topics.optJSONObject(i);
                    if (topic != null) {
                        String text = topic.optString("Text", "");
                        if (!text.isEmpty()) {
                            sb.append("- ").append(text).append("\n");
                            count++;
                        }
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            Log.w(TAG, "DDG instant answer error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Parse DuckDuckGo lite HTML results. */
    private static String parseSearchResults(String html, int maxResults) {
        if (html == null || html.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        try {
            // DuckDuckGo lite results are in <a class="result-link" href="...">title</a>
            // followed by <td class="result-snippet">snippet</td>
            // Simple regex-free extraction
            int count = 0;
            int idx = 0;
            while (count < maxResults) {
                // Find result link
                int linkStart = html.indexOf("result-link", idx);
                if (linkStart == -1) break;
                int hrefStart = html.indexOf("href=\"", linkStart);
                if (hrefStart == -1) break;
                int hrefEnd = html.indexOf("\"", hrefStart + 6);
                String href = html.substring(hrefStart + 6, hrefEnd);

                int titleStart = html.indexOf(">", hrefEnd);
                int titleEnd = html.indexOf("</a>", titleStart);
                String title = html.substring(titleStart + 1, titleEnd).trim();
                // Strip HTML tags from title
                title = title.replaceAll("<[^>]+>", "");

                // Find snippet
                int snippetStart = html.indexOf("result-snippet", titleEnd);
                String snippet = "";
                if (snippetStart != -1 && snippetStart - titleEnd < 500) {
                    int sStart = html.indexOf(">", snippetStart);
                    int sEnd = html.indexOf("</td>", sStart);
                    snippet = html.substring(sStart + 1, sEnd).trim();
                    snippet = snippet.replaceAll("<[^>]+>", "");
                }

                if (!title.isEmpty()) {
                    sb.append("[").append(count + 1).append("] ").append(title).append("\n");
                    if (!snippet.isEmpty()) {
                        sb.append(snippet).append("\n");
                    }
                    if (!href.isEmpty()) {
                        sb.append("链接: ").append(href).append("\n");
                    }
                    sb.append("\n");
                    count++;
                }
                idx = snippetStart != -1 ? snippetStart : titleEnd;
            }
        } catch (Exception e) {
            Log.w(TAG, "Parse search results error: " + e.getMessage());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // === Utility ===

    private static String readStream(java.io.InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "readStream error", e);
        }
        return sb.toString();
    }

    private static String extractErrorMessage(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONObject err = obj.optJSONObject("error");
            if (err != null) {
                return err.optString("message", json);
            }
        } catch (Exception e) {
            // Not JSON, return as-is
        }
        return json.length() > 200 ? json.substring(0, 200) : json;
    }

    /**
     * Save API configuration. Uses commit() for synchronous write.
     */
    public static void saveConfig(Context ctx, String apiUrl, String apiKey, String model) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        if (apiUrl != null) editor.putString("api_url", apiUrl);
        if (apiKey != null) editor.putString("api_key", apiKey);
        if (model != null) editor.putString("model", model);
        editor.commit();
    }

    /**
     * Verify that the API key was actually saved.
     */
    public static boolean verifyConfig(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = sp.getString("api_key", "");
        String url = sp.getString("api_url", "");
        String model = sp.getString("model", "");
        Log.d(TAG, "verifyConfig: url=" + url + " model=" + model + " keyLen=" + (key != null ? key.length() : -1));
        return key != null && key.trim().length() > 10;
    }

    public static String[] getConfig(Context ctx) {
        return new String[] { getApiUrl(ctx), getApiKey(ctx), getModel(ctx) };
    }
}
