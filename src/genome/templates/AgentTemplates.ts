export const AGENT_TEMPLATES: Record<string, string> = {

'UltraApplication.java': `package {{packageName}};

import android.app.Application;

public class UltraApplication extends Application {
    private static UltraApplication instance;
    private AgentDatabase database;
    private AiClient aiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        database = new AgentDatabase(this);
        aiClient = new AiClient("{{apiBaseUrl}}", "{{defaultModel}}");
    }

    public static UltraApplication getInstance() { return instance; }
    public AgentDatabase getDatabase() { return database; }
    public AiClient getAiClient() { return aiClient; }
}`,

'AiClient.java': `package {{packageName}};

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class AiClient {
    private final String baseUrl;
    private final String defaultModel;
    private String apiKey;
    private final List<int[]> backoffMs = new ArrayList<>();

    public AiClient(String baseUrl, String defaultModel) {
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
    }

    public void setApiKey(String key) { this.apiKey = key; }
    public String getApiKey() { return apiKey; }

    public String chat(String systemPrompt, String userMessage) throws Exception {
        return chat(defaultModel, systemPrompt, userMessage, {{defaultMaxTokens}});
    }

    public String chat(String model, String systemPrompt, String userMessage, int maxTokens) throws Exception {
        return chatWithMessages(model, new String[][]{{"system", systemPrompt}, {"user", userMessage}}, maxTokens);
    }

    public String chatWithMessages(String model, String[][] messages, int maxTokens) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < {{maxRetries}}; attempt++) {
            try {
                return doChat(model, messages, maxTokens);
            } catch (Exception e) {
                lastError = e;
                if (attempt < {{maxRetries}} - 1) {
                    long delay = (long) Math.pow(2, attempt) * 1000 + (long)(Math.random() * 500);
                    Thread.sleep(delay);
                }
            }
        }
        throw lastError;
    }

    private String doChat(String model, String[][] messages, int maxTokens) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", {{temperature}});

        JSONArray msgArray = new JSONArray();
        for (String[] msg : messages) {
            msgArray.put(new JSONObject().put("role", msg[0]).put("content", msg[1]));
        }
        body.put("messages", msgArray);

        URL url = new URL(baseUrl + "/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setDoOutput(true);

        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();

        int code = conn.getResponseCode();
        if (code != 200) {
            BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = err.readLine()) != null) sb.append(line);
            err.close();
            throw new Exception("AI API error " + code + ": " + sb.toString());
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();

        JSONObject response = new JSONObject(sb.toString());
        return response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content");
    }
}`,

'AgentDatabase.java': `package {{packageName}};

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class AgentDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "{{dbName}}";
    private static final int DB_VERSION = {{dbVersion}};

    public AgentDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations (" +
            "id TEXT PRIMARY KEY, title TEXT, created_at INTEGER, summary TEXT)");
        db.execSQL("CREATE TABLE messages (" +
            "id TEXT PRIMARY KEY, conversation_id TEXT, role TEXT, content TEXT, " +
            "created_at INTEGER, meta TEXT, " +
            "FOREIGN KEY(conversation_id) REFERENCES conversations(id))");
        db.execSQL("CREATE TABLE genome (" +
            "id TEXT PRIMARY KEY, json TEXT, hash TEXT, created_at INTEGER)");
        db.execSQL("CREATE TABLE build_history (" +
            "id TEXT PRIMARY KEY, spec_json TEXT, apk_path TEXT, success INTEGER, " +
            "error TEXT, created_at INTEGER)");
        db.execSQL("CREATE TABLE mutations (" +
            "id TEXT PRIMARY KEY, genome_id TEXT, operation TEXT, target TEXT, " +
            "description TEXT, diff TEXT, fitness_impact REAL, created_at INTEGER)");
        db.execSQL("CREATE TABLE settings (" +
            "key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(conversation_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
        }
    }

    public void saveConversation(String id, String title) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("title", title);
        cv.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void saveMessage(String id, String conversationId, String role, String content, String meta) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("conversation_id", conversationId);
        cv.put("role", role);
        cv.put("content", content);
        cv.put("meta", meta);
        cv.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("messages", null, cv);
    }

    public List<String[]> getMessages(String conversationId) {
        List<String[]> msgs = new ArrayList<>();
        Cursor c = getReadableDatabase().query("messages", null,
            "conversation_id=?", new String[]{conversationId}, null, null, "created_at ASC");
        while (c.moveToNext()) {
            msgs.add(new String[]{
                c.getString(c.getColumnIndexOrThrow("role")),
                c.getString(c.getColumnIndexOrThrow("content")),
                c.getString(c.getColumnIndexOrThrow("meta")),
            });
        }
        c.close();
        return msgs;
    }

    public void saveGenome(String id, String json, String hash) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("json", json);
        cv.put("hash", hash);
        cv.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("genome", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String loadLatestGenome() {
        Cursor c = getReadableDatabase().query("genome", new String[]{"json"},
            null, null, null, null, "created_at DESC", "1");
        String json = null;
        if (c.moveToFirst()) json = c.getString(0);
        c.close();
        return json;
    }

    public void setSetting(String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        getWritableDatabase().insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getSetting(String key, String defaultValue) {
        Cursor c = getReadableDatabase().query("settings", new String[]{"value"},
            "key=?", new String[]{key}, null, null, null);
        String val = defaultValue;
        if (c.moveToFirst()) val = c.getString(0);
        c.close();
        return val;
    }

    public void saveBuild(String id, String specJson, String apkPath, boolean success, String error) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("spec_json", specJson);
        cv.put("apk_path", apkPath);
        cv.put("success", success ? 1 : 0);
        cv.put("error", error);
        cv.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("build_history", null, cv);
    }
}`,

'SafetyGate.java': `package {{packageName}};

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class SafetyGate {
    public enum RiskTier { SAFE, MODERATE, DANGEROUS, CRITICAL, BLOCKED }

    private static final Set<String> APPROVAL_REQUIRED = new HashSet<>(Arrays.asList(
        {{#each approvalRequired}}"{{this}}"{{#unless @last}}, {{/unless}}{{/each}}
    ));
    private static final Set<String> AUTO_APPROVED = new HashSet<>(Arrays.asList(
        {{#each autoApproved}}"{{this}}"{{#unless @last}}, {{/unless}}{{/each}}
    ));
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
        {{#each blocked}}"{{this}}"{{#unless @last}}, {{/unless}}{{/each}}
    ));

    private final Context context;
    private final AgentDatabase db;
    private final ConcurrentHashMap<String, Boolean> approvalCache = new ConcurrentHashMap<>();

    public SafetyGate(Context context, AgentDatabase db) {
        this.context = context;
        this.db = db;
    }

    public CompletableFuture<Boolean> check(String actionType, String description) {
        if (BLOCKED.contains(actionType)) {
            logDecision(actionType, description, false, "BLOCKED");
            return CompletableFuture.completedFuture(false);
        }
        if (AUTO_APPROVED.contains(actionType)) {
            logDecision(actionType, description, true, "AUTO_APPROVED");
            return CompletableFuture.completedFuture(true);
        }
        String cacheKey = actionType + ":" + description.hashCode();
        Boolean cached = approvalCache.get(cacheKey);
        if (cached != null) {
            logDecision(actionType, description, cached, "CACHED");
            return CompletableFuture.completedFuture(cached);
        }
        if (APPROVAL_REQUIRED.contains(actionType)) {
            return requestApproval(actionType, description, cacheKey);
        }
        return requestApproval(actionType, description, cacheKey);
    }

    private CompletableFuture<Boolean> requestApproval(String actionType, String description, String cacheKey) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            new AlertDialog.Builder(context)
                .setTitle("Action Approval Required")
                .setMessage("Ultra wants to: " + actionType + "\\n\\n" + description)
                .setPositiveButton("Allow", (d, w) -> {
                    approvalCache.put(cacheKey, true);
                    logDecision(actionType, description, true, "USER_APPROVED");
                    future.complete(true);
                })
                .setNegativeButton("Deny", (d, w) -> {
                    approvalCache.put(cacheKey, false);
                    logDecision(actionType, description, false, "USER_DENIED");
                    future.complete(false);
                })
                .setCancelable(false)
                .show();
        });
        return future;
    }

    public void clearCache() { approvalCache.clear(); }

    private void logDecision(String action, String description, boolean approved, String reason) {
        String meta = "{\\"action\\":\\"" + action + "\\",\\"approved\\":" + approved +
            ",\\"reason\\":\\"" + reason + "\\"}";
        db.saveMessage(
            "safety_" + System.currentTimeMillis(),
            "__system__",
            "safety",
            (approved ? "APPROVED" : "DENIED") + ": " + action + " - " + description,
            meta
        );
    }
}`,

'FileManager.java': `package {{packageName}};

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FileManager {
    private final File baseDir;

    public FileManager(Context context) {
        this.baseDir = new File(context.getFilesDir(), "ultra_projects");
        this.baseDir.mkdirs();
    }

    public File getBaseDir() { return baseDir; }

    public File projectDir(String name) {
        File dir = new File(baseDir, name);
        dir.mkdirs();
        return dir;
    }

    public void writeFile(String path, String content) throws IOException {
        File f = new File(path);
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public String readFile(String path) throws IOException {
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            fis.read(data);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    public void writeFile(File f, byte[] data) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    public byte[] readFileBytes(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            fis.read(data);
        }
        return data;
    }

    public List<String> listFiles(String dir, String suffix) {
        List<String> result = new ArrayList<>();
        collectFiles(new File(dir), suffix, result);
        return result;
    }

    private void collectFiles(File dir, String suffix, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectFiles(f, suffix, out);
            else if (suffix == null || f.getName().endsWith(suffix)) out.add(f.getAbsolutePath());
        }
    }

    public void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}`,

'AppInstaller.java': `package {{packageName}};

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import java.io.*;

public class AppInstaller {
    private final Context context;

    public AppInstaller(Context context) {
        this.context = context;
    }

    public void install(String apkPath) {
        try {
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);

            FileInputStream fis = new FileInputStream(apkPath);
            OutputStream out = session.openWrite("package", 0, -1);
            byte[] buf = new byte[65536];
            int len;
            while ((len = fis.read(buf)) > 0) out.write(buf, 0, len);
            session.fsync(out);
            out.close();
            fis.close();

            Intent intent = new Intent(Intent.ACTION_VIEW);
            PendingIntent pi = PendingIntent.getActivity(context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pi.getIntentSender());
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(
                    android.net.Uri.fromFile(new java.io.File(apkPath)),
                    "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e2) {
                throw new RuntimeException("Install failed: " + e.getMessage(), e);
            }
        }
    }
}`,

'MainActivity.java': `package {{packageName}};

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.UUID;

public class MainActivity extends Activity {
    private LinearLayout messageContainer;
    private ScrollView scrollView;
    private EditText inputField;
    private Button sendButton;
    private AgentCore agentCore;
    private String conversationId;

    private static final int BG_COLOR = Color.parseColor("{{backgroundColor}}");
    private static final int USER_COLOR = Color.parseColor("{{userBubbleColor}}");
    private static final int AGENT_COLOR = Color.parseColor("{{agentBubbleColor}}");
    private static final int TEXT_COLOR = Color.parseColor("{{textColor}}");
    private static final int ACCENT_COLOR = Color.parseColor("{{accentColor}}");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        conversationId = UUID.randomUUID().toString();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_COLOR);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(darken(BG_COLOR, 0.7f));
        toolbar.setPadding(dp(16), dp(12), dp(16), dp(12));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("{{agentName}}");
        title.setTextColor(TEXT_COLOR);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        toolbar.addView(title);

        Button settingsBtn = new Button(this);
        settingsBtn.setText("\\u2699");
        settingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        settingsBtn.setTextColor(TEXT_COLOR);
        settingsBtn.setBackgroundColor(Color.TRANSPARENT);
        settingsBtn.setOnClickListener(v -> openSettings());
        toolbar.addView(settingsBtn);
        root.addView(toolbar);

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        scrollView.setFillViewport(true);

        messageContainer = new LinearLayout(this);
        messageContainer.setOrientation(LinearLayout.VERTICAL);
        messageContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        scrollView.addView(messageContainer);
        root.addView(scrollView);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setBackgroundColor(darken(BG_COLOR, 0.8f));
        inputRow.setPadding(dp(8), dp(8), dp(8), dp(8));
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        inputField = new EditText(this);
        inputField.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        inputField.setHint("Describe an app or ask anything...");
        inputField.setHintTextColor(Color.argb(100, 255, 255, 255));
        inputField.setTextColor(TEXT_COLOR);
        inputField.setBackgroundColor(darken(BG_COLOR, 0.6f));
        inputField.setPadding(dp(12), dp(10), dp(12), dp(10));
        inputField.setMaxLines(4);
        inputField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); return true; }
            return false;
        });
        setRoundedBackground(inputField, darken(BG_COLOR, 0.6f), dp(20));
        inputRow.addView(inputField);

        sendButton = new Button(this);
        sendButton.setText("\\u2192");
        sendButton.setTextColor(Color.WHITE);
        sendButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        btnParams.setMarginStart(dp(8));
        sendButton.setLayoutParams(btnParams);
        setRoundedBackground(sendButton, ACCENT_COLOR, dp(24));
        sendButton.setOnClickListener(v -> sendMessage());
        inputRow.addView(sendButton);
        root.addView(inputRow);

        setContentView(root);

        initAgent();

        addMessage("assistant", "{{greeting}}");
    }

    private void initAgent() {
        UltraApplication app = UltraApplication.getInstance();
        agentCore = new AgentCore(
            app.getAiClient(),
            app.getDatabase(),
            new FileManager(this),
            new SafetyGate(this, app.getDatabase()),
            new AppInstaller(this)
        );

        String apiKey = app.getDatabase().getSetting("api_key", "");
        if (!apiKey.isEmpty()) {
            app.getAiClient().setApiKey(apiKey);
        }
    }

    private void sendMessage() {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;
        inputField.setText("");
        addMessage("user", text);

        inputField.setEnabled(false);
        sendButton.setEnabled(false);

        new Thread(() -> {
            try {
                agentCore.process(conversationId, text, new AgentCore.ProgressListener() {
                    @Override
                    public void onProgress(String phase, String message) {
                        runOnUiThread(() -> updateProgress(phase, message));
                    }
                    @Override
                    public void onComplete(String response) {
                        runOnUiThread(() -> {
                            addMessage("assistant", response);
                            inputField.setEnabled(true);
                            sendButton.setEnabled(true);
                            inputField.requestFocus();
                        });
                    }
                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            addMessage("error", "Error: " + error);
                            inputField.setEnabled(true);
                            sendButton.setEnabled(true);
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    addMessage("error", "Fatal: " + e.getMessage());
                    inputField.setEnabled(true);
                    sendButton.setEnabled(true);
                });
            }
        }).start();
    }

    public void addMessage(String role, String content) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));

        int bgColor;
        switch (role) {
            case "user": params.gravity = Gravity.END; bgColor = USER_COLOR; break;
            case "error": params.gravity = Gravity.START; bgColor = Color.parseColor("#3A1A1A"); break;
            default: params.gravity = Gravity.START; bgColor = AGENT_COLOR; break;
        }
        params.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.78);
        bubble.setLayoutParams(params);
        setRoundedBackground(bubble, bgColor, dp(12));

        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextColor(role.equals("error") ? Color.parseColor("#FF6B6B") : TEXT_COLOR);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setMovementMethod(new ScrollingMovementMethod());
        tv.setTextIsSelectable(true);
        bubble.addView(tv);

        messageContainer.addView(bubble);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void updateProgress(String phase, String message) {
        View last = messageContainer.getChildCount() > 0
            ? messageContainer.getChildAt(messageContainer.getChildCount() - 1) : null;
        if (last != null && last.getTag() != null && last.getTag().equals("progress")) {
            TextView tv = (TextView) ((LinearLayout) last).getChildAt(0);
            tv.setText("[" + phase.toUpperCase() + "] " + message);
        } else {
            LinearLayout bubble = new LinearLayout(this);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
            bubble.setTag("progress");

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(4), 0, dp(4));
            bubble.setLayoutParams(params);
            setRoundedBackground(bubble, Color.parseColor("#1A1A2E"), dp(8));

            TextView tv = new TextView(this);
            tv.setText("[" + phase.toUpperCase() + "] " + message);
            tv.setTextColor(Color.parseColor("#6C63FF"));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTypeface(Typeface.MONOSPACE);
            bubble.addView(tv);

            messageContainer.addView(bubble);
        }
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void openSettings() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(16), dp(20), dp(0));

        TextView label = new TextView(this);
        label.setText("API Key:");
        label.setTextColor(TEXT_COLOR);
        layout.addView(label);

        EditText apiInput = new EditText(this);
        apiInput.setTextColor(TEXT_COLOR);
        String current = UltraApplication.getInstance().getDatabase().getSetting("api_key", "");
        apiInput.setText(current);
        apiInput.setHint("Enter API key");
        layout.addView(apiInput);

        new android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Save", (d, w) -> {
                String key = apiInput.getText().toString().trim();
                UltraApplication.getInstance().getDatabase().setSetting("api_key", key);
                UltraApplication.getInstance().getAiClient().setApiKey(key);
                addMessage("assistant", "API key updated.");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            getResources().getDisplayMetrics());
    }

    private void setRoundedBackground(View view, int color, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        view.setBackground(gd);
    }

    private int darken(int color, float factor) {
        int r = (int) (Color.red(color) * factor);
        int g = (int) (Color.green(color) * factor);
        int b = (int) (Color.blue(color) * factor);
        return Color.rgb(Math.max(r, 0), Math.max(g, 0), Math.max(b, 0));
    }
}`,

};
