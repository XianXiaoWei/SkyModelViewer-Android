package com.sky.modelviewer.ai;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Chat Dialog - floating chat panel for AI interaction.
 *
 * Features:
 * - Chat history display (user + AI messages)
 * - Quick prompt buttons
 * - API key configuration dialog
 * - Loading indicator
 * - Context-aware (sends current model info to AI)
 */
public class AIChatDialog {

    private static final String TAG = "AIChatDialog";

    private Context context;
    private Dialog dialog;
    private LinearLayout chatContainer;
    private ScrollView chatScroll;
    private EditText inputField;
    private Button sendButton;
    private ProgressBar loadingBar;
    private TextView statusText;

    private List<AIClient.ChatMessage> chatHistory = new ArrayList<>();
    private String systemPrompt = "";
    private AICommandExecutor commandExecutor;

    // Feature toggles and pending attachments
    private boolean[] deepThinkFlag = {false};
    private boolean[] webSearchFlag = {false};
    private String pendingImageBase64 = null;
    private String pendingImageMime = null;
    private String pendingFileContent = null;
    private String pendingFileName = null;
    private android.widget.LinearLayout attachmentBarRef = null;
    private android.widget.TextView attachmentLabelRef = null;

    // Callbacks for image/file picking (set by host Activity)
    public interface ImagePickerCallback { void pickImage(); }
    public interface FilePickerCallback { void pickFile(); }
    private ImagePickerCallback imagePickerCallback = null;
    private FilePickerCallback filePickerCallback = null;

    /** Set image picker callback (host Activity handles Intent). */
    public void setImagePickerCallback(ImagePickerCallback cb) { this.imagePickerCallback = cb; }
    /** Set file picker callback (host Activity handles Intent). */
    public void setFilePickerCallback(FilePickerCallback cb) { this.filePickerCallback = cb; }

    /**
     * Called by host Activity when an image is picked.
     * @param base64 base64-encoded image data
     * @param mimeType e.g. "image/png", "image/jpeg"
     * @param displayName display name for the attachment bar
     */
    public void onImagePicked(String base64, String mimeType, String displayName) {
        pendingImageBase64 = base64;
        pendingImageMime = mimeType;
        pendingFileContent = null;
        pendingFileName = null;
        if (attachmentBarRef != null && attachmentLabelRef != null) {
            attachmentLabelRef.setText("图片: " + displayName + " (点击发送)");
            attachmentBarRef.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Called by host Activity when a file is picked.
     * @param content file text content
     * @param displayName file name
     */
    public void onFilePicked(String content, String displayName) {
        pendingFileContent = content;
        pendingFileName = displayName;
        pendingImageBase64 = null;
        pendingImageMime = null;
        if (attachmentBarRef != null && attachmentLabelRef != null) {
            attachmentLabelRef.setText("文件: " + displayName + " (" + content.length() + " 字符)");
            attachmentBarRef.setVisibility(View.VISIBLE);
        }
    }

    public AIChatDialog(Context context) {
        this.context = context;
        createDialog();
    }

    /**
     * Set the command executor that handles AI commands.
     */
    public void setCommandExecutor(AICommandExecutor.CommandCallback callback) {
        this.commandExecutor = new AICommandExecutor(callback);
    }

    private void createDialog() {
        dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar);
        dialog.setContentView(createChatView());
        dialog.setCancelable(true);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                // Save history on close instead of clearing
                saveHistory();
            }
        });
    }

    /**
     * Show dialog and restore previous chat history.
     */
    public void show() {
        loadHistory();
        dialog.show();
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * Save chat history to SharedPreferences (persistent across sessions).
     * Max 50 messages to avoid excessive storage.
     */
    private void saveHistory() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("ai_chat_history", Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = prefs.edit();
            editor.putInt("count", Math.min(chatHistory.size(), 50));
            for (int i = 0; i < Math.min(chatHistory.size(), 50); i++) {
                AIClient.ChatMessage msg = chatHistory.get(i);
                editor.putString("role_" + i, msg.role);
                editor.putString("content_" + i, msg.content != null ? msg.content : "");
            }
            editor.apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save history: " + e.getMessage());
        }
    }

    /**
     * Load chat history from SharedPreferences and display in UI.
     */
    private void loadHistory() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("ai_chat_history", Context.MODE_PRIVATE);
            int count = prefs.getInt("count", 0);
            if (count == 0) return;
            for (int i = 0; i < count; i++) {
                String role = prefs.getString("role_" + i, "");
                String content = prefs.getString("content_" + i, "");
                if (role != null && !role.isEmpty() && content != null && !content.isEmpty()) {
                    chatHistory.add(new AIClient.ChatMessage(role, content));
                    // Only display user and assistant messages (skip system)
                    if ("user".equals(role) || "assistant".equals(role)) {
                        String displayText = content;
                        if ("assistant".equals(role) && commandExecutor != null) {
                            displayText = commandExecutor.processResponse(content);
                        }
                        addMessage(role.equals("user") ? "user" : "ai", displayText);
                    }
                }
            }
            // Scroll to bottom after loading history
            chatScroll.post(new Runnable() {
                @Override
                public void run() {
                    chatScroll.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to load history: " + e.getMessage());
        }
    }

    private View createChatView() {
        // Root container
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF0181818);

        // Title bar
        LinearLayout titleBar = new LinearLayout(context);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(0xFF2A2A3E);
        titleBar.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(context);
        title.setText("AI 助手");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleBar.addView(title, titleLp);

        Button configBtn = new Button(context);
        configBtn.setText("设置");
        configBtn.setTextColor(0xFF6C9CFF);
        configBtn.setBackgroundColor(Color.TRANSPARENT);
        configBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        configBtn.setPadding(dp(12), 0, dp(12), 0);
        configBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfigDialog();
            }
        });
        titleBar.addView(configBtn);

        Button clearBtn = new Button(context);
        clearBtn.setText("清空");
        clearBtn.setTextColor(0xFFFFAA00);
        clearBtn.setBackgroundColor(Color.TRANSPARENT);
        clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clearBtn.setPadding(dp(12), 0, dp(12), 0);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chatHistory.clear();
                if (chatContainer != null) chatContainer.removeAllViews();
                saveHistory();
                addMessage("system", "对话已清空");
            }
        });
        titleBar.addView(clearBtn);

        Button closeBtn = new Button(context);
        closeBtn.setText("关闭");
        closeBtn.setTextColor(0xFFFF6B6B);
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        closeBtn.setPadding(dp(12), 0, dp(12), 0);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        titleBar.addView(closeBtn);

        root.addView(titleBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Chat scroll area
        chatScroll = new ScrollView(context);
        chatScroll.setFillViewport(true);

        chatContainer = new LinearLayout(context);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        chatContainer.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Welcome message
        addMessage("ai", "你好！我是AI助手，可以帮你分析模型、推荐搭配、解答3D渲染问题。\n\n请先点击「设置」配置你的API密钥，然后就可以开始对话了。");

        chatScroll.addView(chatContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(chatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Status text
        statusText = new TextView(context);
        statusText.setTextColor(0xFF888888);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusText.setPadding(dp(16), dp(4), dp(16), dp(4));
        statusText.setGravity(Gravity.CENTER);
        updateStatusText();
        root.addView(statusText);

        // Loading bar
        loadingBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setIndeterminate(false);
        loadingBar.setMax(100);
        loadingBar.setVisibility(View.GONE);
        root.addView(loadingBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // Quick prompts (horizontal scroll)
        HorizontalScrollView quickScroll = new HorizontalScrollView(context);
        LinearLayout quickContainer = new LinearLayout(context);
        quickContainer.setOrientation(LinearLayout.HORIZONTAL);
        quickContainer.setPadding(dp(8), dp(4), dp(8), dp(4));

        for (final String prompt : AIContextBuilder.QUICK_PROMPTS) {
            Button quickBtn = new Button(context);
            quickBtn.setText(prompt);
            quickBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            quickBtn.setTextColor(0xFFCCCCCC);
            quickBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
            quickBtn.setMinimumHeight(dp(28));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF2A2A3E);
            bg.setCornerRadius(dp(14));
            quickBtn.setBackground(bg);

            quickBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    inputField.setText(prompt);
                    sendMessage();
                }
            });
            quickContainer.addView(quickBtn);
        }
        quickScroll.addView(quickContainer);
        root.addView(quickScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Input area — feature buttons row (deep think / web search / image / file)
        final LinearLayout featureBar = new LinearLayout(context);
        featureBar.setOrientation(LinearLayout.HORIZONTAL);
        featureBar.setPadding(dp(8), dp(4), dp(8), dp(2));
        featureBar.setBackgroundColor(0xFF1E1E2E);

        // Deep think toggle button
        final Button btnDeepThink = new Button(context);
        btnDeepThink.setText("深度思考");
        btnDeepThink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnDeepThink.setTextColor(0xFF888899);
        btnDeepThink.setBackgroundColor(0xFF2A2A3E);
        btnDeepThink.setPadding(dp(10), dp(2), dp(10), dp(2));
        btnDeepThink.setMinimumHeight(0);
        btnDeepThink.setMinHeight(0);
        btnDeepThink.setMinimumWidth(0);
        btnDeepThink.setMinWidth(0);
        final boolean[] deepThinkOn = {false};
        btnDeepThink.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                deepThinkOn[0] = !deepThinkOn[0];
                btnDeepThink.setTextColor(deepThinkOn[0] ? 0xFF6C9CFF : 0xFF888899);
                btnDeepThink.setText(deepThinkOn[0] ? "深度思考✓" : "深度思考");
            }
        });
        featureBar.addView(btnDeepThink, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), 0));

        // Web search toggle button
        final Button btnWebSearch = new Button(context);
        btnWebSearch.setText("联网搜索");
        btnWebSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnWebSearch.setTextColor(0xFF888899);
        btnWebSearch.setBackgroundColor(0xFF2A2A3E);
        btnWebSearch.setPadding(dp(10), dp(2), dp(10), dp(2));
        btnWebSearch.setMinimumHeight(0);
        btnWebSearch.setMinHeight(0);
        btnWebSearch.setMinimumWidth(0);
        btnWebSearch.setMinWidth(0);
        final boolean[] webSearchOn = {false};
        btnWebSearch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                webSearchOn[0] = !webSearchOn[0];
                btnWebSearch.setTextColor(webSearchOn[0] ? 0xFF6C9CFF : 0xFF888899);
                btnWebSearch.setText(webSearchOn[0] ? "联网搜索✓" : "联网搜索");
            }
        });
        LinearLayout.LayoutParams wsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), 0);
        wsLp.setMarginStart(dp(4));
        featureBar.addView(btnWebSearch, wsLp);

        // Image button
        final Button btnImage = new Button(context);
        btnImage.setText("图片");
        btnImage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnImage.setTextColor(0xFF888899);
        btnImage.setBackgroundColor(0xFF2A2A3E);
        btnImage.setPadding(dp(10), dp(2), dp(10), dp(2));
        btnImage.setMinimumHeight(0);
        btnImage.setMinHeight(0);
        btnImage.setMinimumWidth(0);
        btnImage.setMinWidth(0);
        btnImage.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (imagePickerCallback != null) imagePickerCallback.pickImage();
            }
        });
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), 0);
        imgLp.setMarginStart(dp(4));
        featureBar.addView(btnImage, imgLp);

        // File button
        final Button btnFile = new Button(context);
        btnFile.setText("文件");
        btnFile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btnFile.setTextColor(0xFF888899);
        btnFile.setBackgroundColor(0xFF2A2A3E);
        btnFile.setPadding(dp(10), dp(2), dp(10), dp(2));
        btnFile.setMinimumHeight(0);
        btnFile.setMinHeight(0);
        btnFile.setMinimumWidth(0);
        btnFile.setMinWidth(0);
        btnFile.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (filePickerCallback != null) filePickerCallback.pickFile();
            }
        });
        LinearLayout.LayoutParams fileLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), 0);
        fileLp.setMarginStart(dp(4));
        featureBar.addView(btnFile, fileLp);

        root.addView(featureBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Pending attachment indicator
        final LinearLayout attachmentBar = new LinearLayout(context);
        attachmentBar.setOrientation(LinearLayout.HORIZONTAL);
        attachmentBar.setPadding(dp(12), dp(2), dp(12), dp(2));
        attachmentBar.setBackgroundColor(0xFF1E1E2E);
        attachmentBar.setVisibility(View.GONE);
        final TextView attachmentLabel = new TextView(context);
        attachmentLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        attachmentLabel.setTextColor(0xFF66AA66);
        attachmentBar.addView(attachmentLabel);
        final Button attachmentRemove = new Button(context);
        attachmentRemove.setText("✕");
        attachmentRemove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        attachmentRemove.setTextColor(0xFFFF6666);
        attachmentRemove.setBackgroundColor(0x00000000);
        attachmentRemove.setMinimumWidth(0);
        attachmentRemove.setMinimumHeight(0);
        attachmentRemove.setMinWidth(0);
        attachmentRemove.setMinHeight(0);
        attachmentRemove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pendingImageBase64 = null;
                pendingImageMime = null;
                pendingFileContent = null;
                pendingFileName = null;
                attachmentBar.setVisibility(View.GONE);
            }
        });
        LinearLayout.LayoutParams attLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        attLp.setMarginStart(dp(8));
        attachmentBar.addView(attachmentRemove, attLp);
        root.addView(attachmentBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Store references for sendMessage
        deepThinkFlag = deepThinkOn;
        webSearchFlag = webSearchOn;
        attachmentBarRef = attachmentBar;
        attachmentLabelRef = attachmentLabel;

        // Input area
        LinearLayout inputBar = new LinearLayout(context);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        inputBar.setBackgroundColor(0xFF1E1E2E);

        inputField = new EditText(context);
        inputField.setHint("输入问题...");
        inputField.setHintTextColor(0xFF666666);
        inputField.setTextColor(Color.WHITE);
        inputField.setBackgroundColor(0xFF2A2A3E);
        inputField.setPadding(dp(12), dp(8), dp(12), dp(8));
        inputField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        inputField.setSingleLine(false);
        inputField.setMaxLines(3);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        inputBar.addView(inputField, inputLp);

        sendButton = new Button(context);
        sendButton.setText("发送");
        sendButton.setTextColor(Color.WHITE);
        sendButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setColor(0xFF6C9CFF);
        sendBg.setCornerRadius(dp(8));
        sendButton.setBackground(sendBg);
        sendButton.setPadding(dp(20), dp(8), dp(20), dp(8));

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage();
            }
        });
        inputBar.addView(sendButton);

        root.addView(inputBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private void sendMessage() {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;

        if (!AIClient.isConfigured(context)) {
            // Debug: show what's actually saved
            String[] cfg = AIClient.getConfig(context);
            String debugMsg = "API未配置\nURL: " + cfg[0] + "\nModel: " + cfg[2] + "\nKey长度: " + (cfg[1] != null ? cfg[1].length() : 0);
            Toast.makeText(context, debugMsg, Toast.LENGTH_LONG).show();
            showConfigDialog();
            return;
        }

        // Add user message to UI and history
        addMessage("user", text);

        // Build chat message — support image attachment (vision) and file content
        String userContent = text;
        if (pendingFileContent != null && !pendingFileContent.isEmpty()) {
            // Inject file content into the message
            userContent = "【文件: " + pendingFileName + "】\n```\n" +
                (pendingFileContent.length() > 8000 ? pendingFileContent.substring(0, 8000) + "\n...(文件过长已截断)" : pendingFileContent) +
                "\n```\n\n" + text;
        }

        AIClient.ChatMessage userMsg;
        if (pendingImageBase64 != null && !pendingImageBase64.isEmpty()) {
            // Check if current model supports vision (image input)
            String[] cfg = AIClient.getConfig(context);
            String currentModel = cfg.length > 2 ? cfg[2] : "";
            if (!isVisionModel(currentModel)) {
                // Model doesn't support images — show friendly error and abort
                addMessage("error", "当前模型 \"" + currentModel + "\" 不支持图片输入。\n" +
                    "请在设置中切换到支持视觉的模型，例如：\n" +
                    "• GPT-4o / GPT-4o-mini (OpenAI)\n" +
                    "• Qwen-VL-Plus / Qwen-VL-Max (通义千问)\n" +
                    "• GLM-4V (智谱AI)\n" +
                    "• Claude 3.5 Sonnet (Anthropic)\n\n" +
                    "DeepSeek-chat 是纯文本模型，无法解析图片。");
                // Clear pending attachment
                pendingImageBase64 = null;
                pendingImageMime = null;
                if (attachmentBarRef != null) attachmentBarRef.setVisibility(View.GONE);
                inputField.setText("");
                return;
            }
            // Vision message with image
            userMsg = new AIClient.ChatMessage("user", userContent, pendingImageBase64,
                pendingImageMime != null ? pendingImageMime : "image/jpeg");
            addMessage("system", "📷 已附带图片");
        } else {
            userMsg = new AIClient.ChatMessage("user", userContent);
        }
        chatHistory.add(userMsg);

        // Clear pending attachment
        pendingImageBase64 = null;
        pendingImageMime = null;
        pendingFileContent = null;
        pendingFileName = null;
        if (attachmentBarRef != null) attachmentBarRef.setVisibility(View.GONE);

        inputField.setText("");

        loadingBar.setVisibility(View.VISIBLE);
        loadingBar.setIndeterminate(true);
        sendButton.setEnabled(false);
        sendButton.setText("思考中...");

        // Show feature status to user
        if (webSearchFlag[0]) {
            addMessage("system", "🔍 联网搜索已开启，正在搜索网络...");
        }
        if (deepThinkFlag[0]) {
            addMessage("system", "🧠 深度思考已开启，AI将逐步推理...");
        }

        // Build messages with system prompt
        List<AIClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new AIClient.ChatMessage("system", systemPrompt));
        messages.addAll(chatHistory);

        // Build chat options from feature toggles
        AIClient.ChatOptions options = new AIClient.ChatOptions()
            .deepThink(deepThinkFlag[0])
            .webSearch(webSearchFlag[0]);

        // Add deep thinking guidance to system prompt
        if (deepThinkFlag[0]) {
            messages.add(0, new AIClient.ChatMessage("system",
                "请进行深度思考，逐步分析问题，给出详细的推理过程。先理解问题本质，再从多个角度分析，最后给出综合结论。"));
        }

        // Send to AI with options
        AIClient.chat(context, messages, options, new AIClient.ChatCallback() {
            @Override
            public void onStatus(final String status) {
                ((android.app.Activity) context).runOnUiThread(new Runnable() {
                    public void run() {
                        sendButton.setText(status);
                    }
                });
            }

            @Override
            public void onResponse(final String reply) {
                ((android.app.Activity) context).runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadingBar.setVisibility(View.GONE);
                        sendButton.setEnabled(true);
                        sendButton.setText("发送");

                        // Process commands in the reply
                        String displayText = reply;
                        if (commandExecutor != null) {
                            displayText = commandExecutor.processResponse(reply);
                        }

                        addMessage("ai", displayText);
                        chatHistory.add(new AIClient.ChatMessage("assistant", reply));
                    }
                });
            }

            @Override
            public void onError(final String error) {
                ((android.app.Activity) context).runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadingBar.setVisibility(View.GONE);
                        sendButton.setEnabled(true);
                        sendButton.setText("发送");
                        addMessage("error", error);
                    }
                });
            }
        });
    }

    private void addMessage(String type, String text) {
        TextView msgView = new TextView(context);
        msgView.setText(text);
        msgView.setMovementMethod(ScrollingMovementMethod.getInstance());
        msgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        msgView.setPadding(dp(14), dp(10), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));

        switch (type) {
            case "user":
                msgView.setTextColor(Color.WHITE);
                bg.setColor(0xFF6C9CFF);
                lp.gravity = Gravity.END;
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                msgView.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
                msgView.setBackgroundColor(0xFF6C9CFF);
                break;
            case "ai":
                msgView.setTextColor(0xFFE0E0E0);
                bg.setColor(0xFF2A2A3E);
                msgView.setBackground(bg);
                break;
            case "error":
                msgView.setTextColor(0xFFFF6B6B);
                bg.setColor(0x33FF0000);
                msgView.setBackground(bg);
                break;
        }

        chatContainer.addView(msgView, lp);
        chatScroll.post(new Runnable() {
            @Override
            public void run() {
                chatScroll.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    private void showConfigDialog() {
        String[] config = AIClient.getConfig(context);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(16), dp(20), dp(16));

        // API URL
        TextView urlLabel = new TextView(context);
        urlLabel.setText("API 地址 (OpenAI兼容格式)");
        urlLabel.setTextColor(0xFFCCCCCC);
        urlLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        layout.addView(urlLabel);

        final EditText urlInput = new EditText(context);
        urlInput.setText(config[0]);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setBackgroundColor(0xFF2A2A3E);
        urlInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        urlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        urlInput.setSingleLine();
        layout.addView(urlInput);

        // Model
        TextView modelLabel = new TextView(context);
        modelLabel.setText("模型名称");
        modelLabel.setTextColor(0xFFCCCCCC);
        modelLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        modelLabel.setPadding(0, dp(10), 0, 0);
        layout.addView(modelLabel);

        final EditText modelInput = new EditText(context);
        modelInput.setText(config[2]);
        modelInput.setTextColor(Color.WHITE);
        modelInput.setBackgroundColor(0xFF2A2A3E);
        modelInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        modelInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        modelInput.setSingleLine();
        layout.addView(modelInput);

        // API Key
        TextView keyLabel = new TextView(context);
        keyLabel.setText("API 密钥");
        keyLabel.setTextColor(0xFFCCCCCC);
        keyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        keyLabel.setPadding(0, dp(10), 0, 0);
        layout.addView(keyLabel);

        final EditText keyInput = new EditText(context);
        keyInput.setText(config[1]);
        keyInput.setHint("sk-xxxxxxxx");
        keyInput.setHintTextColor(0xFF666666);
        keyInput.setTextColor(Color.WHITE);
        keyInput.setBackgroundColor(0xFF2A2A3E);
        keyInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        keyInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        keyInput.setSingleLine();
        layout.addView(keyInput);

        // Help text
        TextView help = new TextView(context);
        help.setText("\n支持的API：\n• DeepSeek: api.deepseek.com\n• 通义千问: dashscope.aliyuncs.com\n• OpenAI: api.openai.com\n• 其他兼容OpenAI格式的API");
        help.setTextColor(0xFF888888);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        help.setPadding(0, dp(8), 0, 0);
        layout.addView(help);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("AI API 设置");
        builder.setView(layout);
        builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                String url = urlInput.getText().toString().trim();
                String key = keyInput.getText().toString().trim();
                String model = modelInput.getText().toString().trim();

                if (key.isEmpty()) {
                    Toast.makeText(context, "API密钥不能为空！请输入你的API Key", Toast.LENGTH_LONG).show();
                    return;
                }

                // Save synchronously
                AIClient.saveConfig(context, url, key, model);

                // Verify save
                if (AIClient.verifyConfig(context)) {
                    updateStatusText();
                    Toast.makeText(context, "设置已保存 ✓ Key长度: " + key.length(), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show();
                }
            }
        });
        builder.setNegativeButton("取消", null);

        // Preset buttons
        builder.setNeutralButton("预设", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                // Show preset selection
                showPresetDialog();
            }
        });

        builder.show();
    }

    private void showPresetDialog() {
        final String[][] presets = {
            {"DeepSeek (文本)", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"},
            {"DeepSeek-R1 (深度思考)", "https://api.deepseek.com/v1/chat/completions", "deepseek-reasoner"},
            {"通义千问(Qwen-Plus 文本)", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"},
            {"通义千问(Qwen-VL 图片)", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-vl-plus"},
            {"OpenAI GPT-4o (图片+文本)", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"},
            {"智谱AI(GLM-4-Flash 文本)", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-flash"},
            {"智谱AI(GLM-4V 图片)", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4v"},
            {"月之暗面(Kimi 8K)", "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k"},
        };

        String[] names = new String[presets.length];
        for (int i = 0; i < presets.length; i++) names[i] = presets[i][0];

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("选择API提供商（选择后需输入密钥）");
        builder.setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                // Save URL and model, keep existing key (use "" to not overwrite)
                String existingKey = AIClient.getConfig(context)[1];
                AIClient.saveConfig(context, presets[which][1], existingKey, presets[which][2]);
                updateStatusText();
                // Reopen config dialog with URL/model pre-filled, focus on key input
                showConfigDialog();
                Toast.makeText(context, "已选择" + presets[which][0] + "，请输入API密钥", Toast.LENGTH_LONG).show();
            }
        });
        builder.show();
    }

    private void updateStatusText() {
        if (AIClient.isConfigured(context)) {
            String[] config = AIClient.getConfig(context);
            statusText.setText("已连接 · " + config[2]);
            statusText.setTextColor(0xFF4CAF50);
        } else {
            statusText.setText("未配置API密钥 · 点击「设置」");
            statusText.setTextColor(0xFF888888);
        }
    }

    /**
     * Set the system prompt with current model context.
     */
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }

    /**
     * Check if a model supports vision (image input).
     * Known vision models: GPT-4o*, Qwen-VL*, GLM-4V*, Claude-3*, gpt-4-vision,
     * moonshot-v1-vision, step-1v, yi-vision, etc.
     */
    private boolean isVisionModel(String model) {
        if (model == null || model.isEmpty()) return false;
        String m = model.toLowerCase();
        // Vision-capable model patterns
        if (m.contains("gpt-4o") || m.contains("gpt-4-vision") || m.contains("gpt-4-turbo")) return true;
        if (m.contains("qwen-vl") || m.contains("qwen2-vl") || m.contains("qwen2.5-vl")) return true;
        if (m.contains("glm-4v")) return true;
        if (m.contains("claude-3")) return true;  // Claude 3+ all support vision
        if (m.contains("moonshot-v1-vision") || m.contains("kimi-vision")) return true;
        if (m.contains("step-1v") || m.contains("step-1.5v")) return true;
        if (m.contains("yi-vision")) return true;
        if (m.contains("gemini")) return true;  // Gemini models support vision
        if (m.contains("llava")) return true;
        if (m.contains("minicpm")) return true;
        if (m.contains("internvl")) return true;
        if (m.contains("deepseek-vl")) return true;
        // Default: text-only models (deepseek-chat, qwen-plus, glm-4-flash, etc.)
        return false;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
