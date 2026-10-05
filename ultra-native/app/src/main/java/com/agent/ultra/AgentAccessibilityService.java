package com.agent.ultra;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.Log;
import android.content.SharedPreferences;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AgentAccessibilityService extends AccessibilityService {
    private static final String TAG = "AgentA11y";
    private static AgentAccessibilityService instance;
    private static final Object instanceLock = new Object();
    private String currentPackage = "";
    private static final Set<String> allowedPackages = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> blockedPackages = new java.util.concurrent.ConcurrentSkipListSet<>();
    public interface UiTreeListener {
        void onUiTreeChanged(String packageName, long timestamp);
    }
    private static volatile UiTreeListener uiTreeListener = null;

    public static void setUiTreeListener(UiTreeListener l) { uiTreeListener = l; }

    // ── The action gate's question, drawn over the app it is about ─────────────────────────────
    //
    // The gate used to bring Ultra to the front to ask "press Delete?". An app that redraws when
    // it comes back loses what was selected: Markor dropped its selection, "Delete" was gone, and
    // a delete the person had just approved could never happen (AndroidWorld MarkorDeleteNote,
    // 2026-09-20 — and the same on a real phone). An accessibility overlay sits on top without
    // taking focus, so the app underneath never pauses and nothing in it changes while the
    // person decides. The gate itself is unchanged: it still asks, it still times out to "no".
    private android.view.View gateOverlay;

    /** Show the question. False if it could not be drawn — the caller then asks the old way. */
    public boolean showGateOverlay(String headline, String detail, Runnable onYes, Runnable onNo) {
        final AtomicBoolean shown = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        Runnable draw = () -> {
            try {
                removeGateOverlay();
                float dp = getResources().getDisplayMetrics().density;
                android.widget.LinearLayout card = new android.widget.LinearLayout(this);
                card.setOrientation(android.widget.LinearLayout.VERTICAL);
                int pad = (int) (18 * dp);
                card.setPadding(pad, pad, pad, pad);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xF2101418);
                bg.setCornerRadius(18 * dp);
                bg.setStroke((int) (2 * dp), 0xFFE0A030);
                card.setBackground(bg);

                android.widget.TextView h = new android.widget.TextView(this);
                h.setText(headline);
                h.setTextColor(0xFFFFFFFF);
                h.setTextSize(18);
                h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                card.addView(h);

                android.widget.TextView d = new android.widget.TextView(this);
                d.setText(detail);
                d.setTextColor(0xFFC8D0D8);
                d.setTextSize(14);
                d.setPadding(0, (int) (6 * dp), 0, (int) (14 * dp));
                card.addView(d);

                android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                android.widget.Button no = new android.widget.Button(this);
                no.setText("Don't");
                android.widget.Button yes = new android.widget.Button(this);
                yes.setText("Do it");
                // A touch that arrives through another window drawn on top is not the person's.
                no.setFilterTouchesWhenObscured(true);
                yes.setFilterTouchesWhenObscured(true);
                no.setOnClickListener(v -> { removeGateOverlay(); onNo.run(); });
                yes.setOnClickListener(v -> { removeGateOverlay(); onYes.run(); });
                android.widget.LinearLayout.LayoutParams half =
                        new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(no, half);
                row.addView(yes, half);
                card.addView(row);

                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.BOTTOM;
                lp.y = (int) (64 * dp);
                lp.horizontalMargin = 0.03f;
                ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).addView(card, lp);
                gateOverlay = card;
                shown.set(true);
                card.post(() -> {
                    int[] a = new int[2];
                    yes.getLocationOnScreen(a);
                    int[] b = new int[2];
                    no.getLocationOnScreen(b);
                    Log.i("UltraActionGate", "OVERLAY shown: do_it=" + (a[0] + yes.getWidth() / 2) + ","
                            + (a[1] + yes.getHeight() / 2) + " dont=" + (b[0] + no.getWidth() / 2) + ","
                            + (b[1] + no.getHeight() / 2));
                });
            } catch (Exception e) {
                Log.w("UltraActionGate", "overlay could not be drawn: " + e.getMessage());
            }
            latch.countDown();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) draw.run();
        else {
            new Handler(Looper.getMainLooper()).post(draw);
            try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        return shown.get();
    }

    public void hideGateOverlay() {
        if (Looper.myLooper() == Looper.getMainLooper()) removeGateOverlay();
        else new Handler(Looper.getMainLooper()).post(this::removeGateOverlay);
    }

    private void removeGateOverlay() {
        if (gateOverlay == null) return;
        try {
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(gateOverlay);
        } catch (Exception ignored) {}
        gateOverlay = null;
    }
    public static AgentAccessibilityService getInstance() {
        synchronized (instanceLock) { return instance; }
    }
    /**
     * Android lists this service as enabled, but has not bound it.
     *
     * A real state, hit after reinstalling: `enabled_accessibility_services`
     * still names the service, so Android's own toggle is drawn as ON, while
     * `dumpsys accessibility` reports `Bound services:{}` and nothing works.
     * The user sees a switch that is already on and an app that says it is off,
     * with no reason to suspect the cure is to turn the switch off and on
     * again — which is the only thing that fixes it.
     *
     * Distinguishing this from "simply not enabled" is the difference between
     * an instruction that works and one that reads as nonsense.
     */
    public static boolean listedButNotBound(android.content.Context ctx) {
        if (isRunning()) return false;
        try {
            String listed = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_accessibility_services");
            return listed != null && listed.contains(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isRunning() {
        synchronized (instanceLock) { return instance != null; }
    }
    public static void allowPackage(String pkg) { allowedPackages.add(pkg); }
    public static void revokePackage(String pkg) { allowedPackages.remove(pkg); }
    public static boolean isPackageAllowed(String pkg) { return allowedPackages.contains(pkg); }
    public static final String BLOCK_PREFS = "ultra_protected_apps";
    public static final String BLOCK_KEY = "blocked";
    public static final String ALLOW_KEY = "allowed";
    public static final String MODE_KEY = "allowlist_mode";

    /** When true, the agent may only work in apps the user has chosen.
     * A blocklist has to name every risk in advance; on a real phone with two
     * hundred apps that is a bet you lose once. This inverts it. */
    private static volatile boolean allowlistMode = false;
    private static final Set<String> allowedApps = new java.util.concurrent.ConcurrentSkipListSet<>();

    public static boolean isAllowlistMode() { return allowlistMode; }

    /** The single question every read and every action must ask. */
    public static boolean agentMayUse(String pkg) {
        if (pkg == null) return false;
        if (allowlistMode) return allowedApps.contains(pkg);
        return !blockedPackages.contains(pkg);
    }

    /** Load the user's protected-app list. Called at service connect, and
     * again whenever Settings changes it. Without this the blocklist existed
     * in code but was never populated — a safety net with no rope in it. */
    public static void loadBlockedPackages(android.content.Context ctx) {
        try {
            android.content.SharedPreferences prefs = ctx
                .getSharedPreferences(BLOCK_PREFS, android.content.Context.MODE_PRIVATE);
            java.util.Set<String> saved = prefs.getStringSet(BLOCK_KEY, new java.util.HashSet<>());
            blockedPackages.clear();
            if (saved != null) blockedPackages.addAll(saved);
            java.util.Set<String> allow = prefs.getStringSet(ALLOW_KEY, new java.util.HashSet<>());
            allowedApps.clear();
            if (allow != null) allowedApps.addAll(allow);
            allowlistMode = prefs.getBoolean(MODE_KEY, false);
            Log.i(TAG, "app policy: mode=" + (allowlistMode ? "allowlist" : "blocklist")
                + " allowed=" + allowedApps.size() + " protected=" + blockedPackages.size());
        } catch (Exception e) {
            Log.w(TAG, "could not load protected apps", e);
        }
    }

    /**
     * Apply a setup file dropped next to the provider seed, then delete it.
     *
     * Same mechanism, same directory, same reason: `Android/data/<pkg>/files` is
     * writable by adb and by no other app, which makes a device test
     * reproducible without anyone tapping through four settings screens. Each
     * hand-driven step is a step that gets skipped, done differently, or done
     * wrong at eleven at night, and a test whose setup is unreliable produces
     * results that are unreliable in the same way.
     *
     * Applied once and deleted, so it configures a device rather than becoming
     * a second source of truth that quietly disagrees with the user's own
     * settings. Logged loudly, because something that changes which apps the
     * agent may enter should never do so silently.
     *
     * Anyone who can write this file already has adb over the device and can do
     * considerably worse; this grants no capability that was not already theirs.
     */
    public static void applySetupFile(Context ctx) {
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "ultra_setup.json");
            if (!f.exists()) return;

            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.toString());

            SharedPreferences p = ctx.getSharedPreferences(BLOCK_PREFS, Context.MODE_PRIVATE);
            if (j.has("allowlistMode")) {
                allowlistMode = j.getBoolean("allowlistMode");
                p.edit().putBoolean(MODE_KEY, allowlistMode).apply();
            }
            if (j.has("allowApps")) {
                JSONArray arr = j.getJSONArray("allowApps");
                java.util.Set<String> allow = new java.util.HashSet<>(allowedApps);
                for (int i = 0; i < arr.length(); i++) allow.add(arr.getString(i));
                allowedApps.clear();
                allowedApps.addAll(allow);
                p.edit().putStringSet(ALLOW_KEY, allow).apply();
            }
            if (j.has("personalData")) {
                // The switch that governs messages, contacts and location. In
                // the setup file only so a device test can exercise the layers
                // BEHIND it — with this off, an attempt to text a secret is
                // refused for the wrong reason and the flow guard is never
                // reached, which is exactly what happened for a whole
                // afternoon.
                ctx.getSharedPreferences("ultra_settings", Context.MODE_PRIVATE)
                    .edit().putBoolean("allow_personal_data", j.getBoolean("personalData")).apply();
                Log.w(TAG, "SETUP FILE: personal data set to " + j.getBoolean("personalData"));
            }
            Log.w(TAG, "SETUP FILE APPLIED — allowlistMode=" + allowlistMode
                + " allowed=" + allowedApps.size() + " (file deleted)");
            f.delete();
        } catch (Exception e) {
            Log.w(TAG, "setup file ignored: " + e.getMessage());
        }
    }

    public static void blockPackage(String pkg) { blockedPackages.add(pkg); allowedPackages.remove(pkg); }
    public static void unblockPackage(String pkg) { blockedPackages.remove(pkg); }
    public static boolean isPackageBlocked(String pkg) { return blockedPackages.contains(pkg); }
    public static java.util.List<String> getBlockedPackages() { return new java.util.ArrayList<>(blockedPackages); }

    // === UltraDevLog v4: A11y Event Stream ===
    private long lastContentChangedLog = 0;
    private static final long CONTENT_THROTTLE_MS = 2000;
    private String lastLoggedWindowPkg = "";
    private String lastLoggedWindowCls = "";
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> pendingA11yLogs =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private void emitA11yLog(String category, String jsonData) {
        long ts = System.currentTimeMillis();
        pendingA11yLogs.add("{\"cat\":\"" + category + "\",\"t\":" + ts + ",\"data\":" + jsonData + "}");
        while (pendingA11yLogs.size() > 500) { pendingA11yLogs.poll(); }
    }

    public static java.util.List<String> drainPendingLogs() {
        java.util.List<String> result = new java.util.ArrayList<>();
        String entry;
        while ((entry = pendingA11yLogs.poll()) != null) { result.add(entry); }
        return result;
    }

    public String getCurrentPackage() { return currentPackage; }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        synchronized (instanceLock) { instance = this; }
        // Before the policy is read, so a seeded setup is in force from the
        // first event rather than from the next restart.
        applySetupFile(this);
        loadBlockedPackages(this);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_CLICKED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED
            | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.i(TAG, "Accessibility service connected, capabilities=" + info.getCapabilities());
        try {
            getSharedPreferences("ultra_a11y", MODE_PRIVATE)
                .edit()
                .putString("state", "connected")
                .putLong("connected_at", System.currentTimeMillis())
                .putLong("last_event", System.currentTimeMillis())
                .apply();
        } catch (Exception e) {}
        android.util.Log.i("AgentA11y", "SERVICE_CONNECTED");
        // === UltraDevLog v4: Crash survival ===
        final Thread.UncaughtExceptionHandler prevHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append(e.toString()).append("\n");
                    for (StackTraceElement el : e.getStackTrace()) { sb.append("  ").append(el.toString()).append("\n"); }
                    Throwable cause = e.getCause();
                    if (cause != null) {
                        sb.append("Caused by: ").append(cause.toString()).append("\n");
                        for (StackTraceElement el : cause.getStackTrace()) { sb.append("  ").append(el.toString()).append("\n"); }
                    }
                    java.io.File f = new java.io.File(getFilesDir(), "ultra_crash.log");
                    java.io.FileWriter fw = new java.io.FileWriter(f, true);
                    fw.write("\n=== CRASH " + new java.util.Date().toString() + " thread=" + t.getName() + " ===\n");
                    fw.write(sb.toString());
                    fw.close();
                    emitA11yLog("CRASH_NATIVE", "{\"thread\":\"" + t.getName() + "\",\"error\":\"" +
                        e.toString().replace("\"", "'").replace("\n", " ").replace("\\", "") + "\"}");
                } catch (Exception ignored) { }
                if (prevHandler != null) { prevHandler.uncaughtException(t, e); }
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            getSharedPreferences("ultra_a11y", MODE_PRIVATE)
                .edit()
                .putLong("last_event", System.currentTimeMillis())
                .putString("last_event_pkg", event.getPackageName() != null ? event.getPackageName().toString() : "")
                .apply();
        } catch (Exception e) {}
        if (event.getPackageName() != null) {
            String evtPkg = event.getPackageName().toString();
            // The keyboard, the status bar and the notification shade all emit
            // events, and none of them means the user has changed app. Taking
            // them as the current package told the policy gate the agent was
            // "in" systemui, and since systemui is on nobody's allowed list
            // every action after a keyboard appeared was refused. A task that
            // types was therefore unable to do anything after typing.
            //
            // This code already knew: it skipped LOGGING these as a package
            // change while still recording them as one.
            if (!isDeviceFurniture(evtPkg)) {
                String prevPkg = currentPackage;
                currentPackage = evtPkg;
                if (!currentPackage.equals(prevPkg)) {
                    Log.i(TAG, "PKG_CHANGE: " + prevPkg + " -> " + currentPackage);
                }
            }
        }
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            emitUiTreeChanged();
        }
        // === UltraDevLog v4: Event stream ===
        try {
            switch (type) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    String pkg = currentPackage;
                    String cls = event.getClassName() != null ? event.getClassName().toString() : "null";
                    if (!pkg.equals(lastLoggedWindowPkg) || !cls.equals(lastLoggedWindowCls)) {
                        lastLoggedWindowPkg = pkg;
                        lastLoggedWindowCls = cls;
                        emitA11yLog("A11Y_WINDOW", "{\"pkg\":\"" + pkg + "\",\"cls\":\"" + cls + "\"}");
                    }
                    // While the user is demonstrating, a screen coming to the
                    // front IS the observation. Off the main thread on purpose:
                    // reading the tree posts to main and waits on a latch, and
                    // this handler already runs on main — calling it here would
                    // deadlock the whole service.
                    if (com.agent.ultra.agent.Demonstration.INSTANCE.isRecording()) {
                        scheduleJourneySample();
                    }
                    break;
                }
                case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED: {
                    String pkg = currentPackage;
                    java.util.List<CharSequence> tl = event.getText();
                    String txt = (tl != null && !tl.isEmpty()) ? tl.get(0).toString() : "";
                    if (txt.length() > 100) txt = txt.substring(0, 100);
                    if (txt.matches(".*[A-Za-z0-9_-]{20,}.*")) { txt = "[REDACTED_TOKEN]"; }
                    txt = txt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
                    emitA11yLog("A11Y_NOTIF", "{\"pkg\":\"" + pkg + "\",\"text\":\"" + txt + "\"}");
                    break;
                }
                case AccessibilityEvent.TYPE_VIEW_CLICKED: {
                    String pkg = currentPackage;
                    boolean isOwnApp = "com.agent.ultra".equals(pkg);
                    boolean isSystemUi = "com.android.systemui".equals(pkg);
                    // The USER'S list, not the auto-allowed set.
                    //
                    // isPackageAllowed holds apps the AGENT has already acted
                    // in, filled by the gate when it passes. Gating capture on
                    // that meant a demonstration in an app the agent had never
                    // touched was discarded — which is every app worth being
                    // shown, since the reason to demonstrate something is that
                    // the agent cannot do it yet. Two runs recorded zero steps
                    // before this was spotted.
                    //
                    // agentMayUse is the question that was actually meant: has
                    // the user let it into this app. Deny-by-default still
                    // holds, so it can only be taught about apps it is allowed
                    // to be in.
                    boolean isAllowed = isOwnApp || isSystemUi || agentMayUse(pkg);
                    String cls = event.getClassName() != null ? event.getClassName().toString() : "null";
                    if (isAllowed) {
                        java.util.List<CharSequence> tl = event.getText();
                        String txt = (tl != null && !tl.isEmpty()) ? tl.get(0).toString() : "";
                        if (txt.length() > 50) txt = txt.substring(0, 50);
                        if (txt.matches(".*[A-Za-z0-9_-]{20,}.*")) { txt = "[REDACTED_LONG_TOKEN]"; }
                        txt = txt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
                        String desc = event.getContentDescription() != null ? event.getContentDescription().toString() : "";
                        if (desc.length() > 50) desc = desc.substring(0, 50);
                        if (desc.matches(".*[A-Za-z0-9_-]{20,}.*")) { desc = "[REDACTED]"; }
                        desc = desc.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
                        // The app's own id for what was tapped. A label moves
                        // with translation and wording; an id is what makes a
                        // demonstrated step replayable later.
                        String vid = "";
                        try {
                            AccessibilityNodeInfo src = event.getSource();
                            if (src != null) { vid = shortName(idOf(src)); src.recycle(); }
                        } catch (Exception ignored) {}
                        emitA11yLog("A11Y_CLICK", "{\"pkg\":\"" + pkg + "\",\"cls\":\"" + cls + "\",\"vid\":\"" + vid + "\",\"text\":\"" + txt + "\",\"desc\":\"" + desc + "\"}");
                        // Package, class and the app's own id — never the label.
                        // The label is what the user tapped and is theirs; the
                        // same rule the demonstration recorder holds to. This
                        // exists because "did it see what I just did" was
                        // otherwise unanswerable without a debugger.
                        Log.i(TAG, "CLICK_SEEN " + pkg + " " + cls + (vid.isEmpty() ? "" : " #" + vid));
                    } else {
                        emitA11yLog("A11Y_CLICK", "{\"pkg\":\"" + pkg + "\",\"cls\":\"" + cls + "\",\"text\":\"[external]\",\"desc\":\"[external]\"}");
                        Log.i(TAG, "CLICK_SEEN " + pkg + " (not an allowed app — recorded as external)");
                    }
                    break;
                }
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                    // Sample here too while recording. A browser reuses one
                    // window for every page, so navigating from one site to
                    // another fires no WINDOW_STATE_CHANGED at all and a
                    // three-page route was recorded as one screen. In-app
                    // navigation is most of what anyone would demonstrate.
                    //
                    // Content changes constantly, so the recorder throttles and
                    // only keeps a sample whose fingerprint actually differs;
                    // the cost is a tree parse every second or so, and only
                    // while someone is deliberately being watched.
                    if (com.agent.ultra.agent.Demonstration.INSTANCE.isRecording()) {
                        scheduleJourneySample();
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastContentChangedLog > CONTENT_THROTTLE_MS) {
                        lastContentChangedLog = now;
                        emitA11yLog("A11Y_CONTENT", "{\"pkg\":\"" + currentPackage + "\"}");
                    }
                    break;
                }
            }
        } catch (Exception ignored) { }
    }

    private void emitUiTreeChanged() {
        UiTreeListener l = uiTreeListener;
        if (l == null) return;
        try {
            l.onUiTreeChanged(currentPackage, System.currentTimeMillis());
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Interrupted");
        try {
            getSharedPreferences("ultra_a11y", MODE_PRIVATE)
                .edit()
                .putString("state", "interrupted")
                .putLong("interrupted_at", System.currentTimeMillis())
                .apply();
        } catch (Exception e) {}
        android.util.Log.w("AgentA11y", "SERVICE_INTERRUPTED");
    }

    @Override
    public void onDestroy() {
        synchronized (instanceLock) { instance = null; }
        try {
            getSharedPreferences("ultra_a11y", MODE_PRIVATE)
                .edit()
                .putString("state", "destroyed")
                .putLong("destroyed_at", System.currentTimeMillis())
                .apply();
        } catch (Exception e) {}
        android.util.Log.w("AgentA11y", "SERVICE_DESTROYED");
        super.onDestroy();
    }

    public String getActivePackage() { Log.i(TAG, "GET_PKG: " + currentPackage); return currentPackage; }

    /**
     * Foreground package computed from LIVE windows, not event history.
     * currentPackage goes stale when this service re-binds after another app
     * already came forward (proven on device: tracker stuck on systemui while
     * Chrome was visibly in front). Falls back to the event tracker if the
     * window scan finds nothing.
     */
    public String getForegroundPackage() {
        try {
            // Every getRoot() here used to leak: one per window on the way in,
            // and another for the winner on the way out. This runs on every
            // gate check, so it leaked more the more careful the agent was.
            String bestPkg = null;
            int bestLayer = Integer.MIN_VALUE;
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                try {
                    CharSequence rp = r.getPackageName();
                    if (rp == null) continue;
                    String p = rp.toString();
                    // Device furniture is never the app the user is in,
                    // however high it is layered.
                    if (isDeviceFurniture(p)) continue;
                    if (w.getLayer() > bestLayer) { bestLayer = w.getLayer(); bestPkg = p; }
                } finally {
                    r.recycle();
                }
            }
            if (bestPkg != null) {
                Log.i(TAG, "FG_PKG(live): " + bestPkg);
                return bestPkg;
            }
        } catch (Exception e) {
            Log.i(TAG, "FG_PKG live scan failed: " + e.getMessage());
        }
        return currentPackage;
    }

    public String getScreenContent() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "{}";
            JSONObject tree = nodeToJson(root, 0, 5);
            root.recycle();
            return tree.toString();
        } catch (Exception e) {
            Log.e(TAG, "getScreenContent error", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo node, int depth, int maxDepth) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("class", node.getClassName() != null ? node.getClassName().toString() : "");
            obj.put("text", node.getText() != null ? node.getText().toString() : "");
            obj.put("desc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
            obj.put("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            obj.put("bounds", bounds.flattenToString());
            obj.put("clickable", node.isClickable());
            obj.put("scrollable", node.isScrollable());
            obj.put("editable", node.isEditable());
            obj.put("enabled", node.isEnabled());
            obj.put("focused", node.isFocused());
            if (depth < maxDepth && node.getChildCount() > 0) {
                JSONArray children = new JSONArray();
                for (int i = 0; i < node.getChildCount() && i < 50; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        children.put(nodeToJson(child, depth + 1, maxDepth));
                        child.recycle();
                    }
                }
                obj.put("children", children);
            }
        } catch (Exception e) {
            try { obj.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return obj;
    }

    public String dumpWindowStack() {
        try {
            java.util.List<AccessibilityWindowInfo> windows = getWindows();
            StringBuilder sb = new StringBuilder();
            sb.append("WINDOWS: count=").append(windows.size());
            for (int i = 0; i < windows.size(); i++) {
                AccessibilityWindowInfo w = windows.get(i);
                AccessibilityNodeInfo root = w.getRoot();
                String pkg = "null";
                if (root != null) {
                    pkg = root.getPackageName() != null ? root.getPackageName().toString() : "null";
                    root.recycle();
                }
                sb.append(" | w").append(i).append("=[layer=").append(w.getLayer())
                  .append(" type=").append(w.getType())
                  .append(" pkg=").append(pkg)
                  .append(" focused=").append(w.isFocused())
                  .append("]");
            }
            String result = sb.toString();
            Log.i(TAG, result);
            return result;
        } catch (Exception e) {
            Log.i(TAG, "WINDOWS: error=" + e.getMessage());
            return "error";
        }
    }

    public String getScreenContentFlat() {
        AtomicReference<String> result = new AtomicReference<>("[]");
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // Find the target app window, not Agent Ultra's own window
                // Topmost, not first: an open menu is a window above the page,
                // and reading the page behind it is why a successful tap on
                // the menu button looked like a failure.
                AccessibilityNodeInfo root = topmostWindowRoot("SCREEN_FLAT");
                // Fallback to default if no other app window found
                if (root == null) {
                    root = getRootInActiveWindow();
                    Log.i(TAG, "SCREEN_FLAT: fallback to getRootInActiveWindow");
                    // GATE: if fallback returns Agent Ultra's own window, reject it
                    if (root != null) {
                        CharSequence fbPkg = root.getPackageName();
                        if (fbPkg != null && "com.agent.ultra".contentEquals(fbPkg)) {
                            Log.i(TAG, "SCREEN_FLAT: BLOCKED self-read via fallback — returning empty");
                            root.recycle();
                            root = null;
                        }
                    }
                }
                // A window whose package reads null while it is still coming up passes the
                // skip above and turns out to be Ultra's own chat: the navigator then drove
                // Ultra's UI, tapping its ☰ forever (AndroidWorld, 2026-09-19). Check again
                // once the root is in hand.
                if (root != null) {
                    CharSequence again = root.getPackageName();
                    if (again != null && "com.agent.ultra".contentEquals(again)) {
                        Log.i(TAG, "SCREEN_FLAT: BLOCKED self-read (window package resolved late)");
                        root.recycle();
                        root = null;
                    }
                }
                if (root != null && isProtected(root)) {
                    Log.i(TAG, "SCREEN_FLAT: BLOCKED — protected app in front");
                    result.set(PROTECTED);
                    root.recycle();
                    root = null;
                    return;
                }
                if (root != null) {
                    CharSequence rootPkg = root.getPackageName();
                    Log.i(TAG, "SCREEN_FLAT: root_pkg=" + (rootPkg != null ? rootPkg.toString() : "null"));
                    dumpWindowStack();
                    JSONArray flat = new JSONArray();
                    flattenNode(root, flat, -1, 0);
                    root.recycle();
                    if (flat.length() > 0) {
                        try {
                            String firstLabel = flat.getJSONObject(0).optString("t", "") + "|" + flat.getJSONObject(0).optString("d", "");
                            String secondLabel = flat.length() > 1 ? flat.getJSONObject(1).optString("t", "") + "|" + flat.getJSONObject(1).optString("d", "") : "";
                            Log.i(TAG, "SCREEN_FLAT: nodes=" + flat.length() + " first=[" + firstLabel + "] second=[" + secondLabel + "]");
                        } catch (Exception ignored) {
                            Log.i(TAG, "SCREEN_FLAT: nodes=" + flat.length());
                        }
                    }
                    result.set(flat.toString());
                } else {
                    Log.i(TAG, "SCREEN_FLAT: root=null");
                }
            } catch (Exception e) {
                Log.e(TAG, "getScreenContentFlat error", e);
            } finally {
                latch.countDown();
            }
        });
        try { latch.await(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return result.get();
    }

    /**
     * Click the node the model chose, by its position in the last flat dump.
     *
     * Two problems this fixes, both of which look identical from outside — the
     * agent "did not tap the button".
     *
     * The index came from a dump taken before the model was asked what to do.
     * Resolving it against a NEW dump means that if anything shifted in
     * between — an advert loading, a page settling, a spinner finishing —
     * index N is now a different node and the agent confidently taps the wrong
     * thing. So the caller passes the label it saw, and a mismatch is reported
     * rather than acted on: seeing something else there is information, and
     * tapping it anyway is how an agent ends up somewhere nobody asked for.
     *
     * And a tap at coordinates misses a node that is behind an overlay, or has
     * moved a few pixels, or is only partly on screen. Asking the node itself
     * to activate goes through the same path the app uses for a real touch.
     * The gesture is kept as a fallback, because some views handle touch and
     * report themselves as not clickable.
     *
     * @return "ok", "moved" when the label no longer matches, "gone" when
     *   there is no such index, or "failed" when both click and tap refused.
     */
    /**
     * Press a control by the app's own id for it, falling back to position.
     *
     * Position has burned this codebase three times now. Choosing from the
     * tree and tapping by flat index meant two traversals disagreed about
     * which node an index named; choosing from a dump a moment old means the
     * screen itself has moved on. The label guard catches that only when the
     * control has a label, and a great many do not — Chrome's whole overflow
     * menu is rows with an id and no text, which is exactly where a replay
     * spends its time.
     *
     * An id is what the developer called that control. It survives the screen
     * being re-read, re-laid out, and renumbered.
     */
    public String clickByViewId(String vid, int fallbackIndex, String expectedLabel) {
        if (vid == null || vid.isEmpty()) return clickByIndex(fallbackIndex, expectedLabel);
        AtomicReference<String> result = new AtomicReference<>("gone");
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            AccessibilityNodeInfo root = null;
            try {
                if (!checkPackageAllowed()) { result.set("failed"); return; }
                root = targetWindowRoot();
                if (root == null) { result.set("gone"); return; }
                java.util.List<AccessibilityNodeInfo> order = new java.util.ArrayList<>();
                collectInFlatOrder(root, order);
                int found = -1;
                for (int i = 0; i < order.size(); i++) {
                    if (vid.equals(shortName(idOf(order.get(i))))) { found = i; break; }
                }
                for (AccessibilityNodeInfo n : order) {
                    try { n.recycle(); } catch (Exception ignored) {}
                }
                if (found < 0) {
                    Log.i(TAG, "CLICK_VID: " + vid + " is not on this screen");
                    result.set("gone");
                    return;
                }
                if (found != fallbackIndex) {
                    Log.i(TAG, "CLICK_VID: " + vid + " moved from [" + fallbackIndex + "] to [" + found + "]");
                }
                result.set("resolved:" + found);
            } catch (Exception e) {
                Log.e(TAG, "CLICK_VID failed: " + e.getMessage());
                result.set("failed");
            } finally {
                if (root != null) root.recycle();
                latch.countDown();
            }
        });
        try { latch.await(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        String r = result.get();
        if (r.startsWith("resolved:")) {
            return clickByIndex(Integer.parseInt(r.substring(9)), expectedLabel);
        }
        return r;
    }

    public String clickByIndex(int index, String expectedLabel) {
        return actByIndex(index, expectedLabel, false);
    }

    /**
     * Press and hold the node at this index. The row is asked first (ACTION_LONG_CLICK on the
     * nearest long-clickable ancestor): a held gesture selected a file in Markor but did nothing
     * in the Files app, whose list only answers the accessibility action (AndroidWorld
     * FilesDeleteFile, 2026-09-20). The gesture is the fallback.
     */
    public String longClickByIndex(int index, String expectedLabel) {
        return actByIndex(index, expectedLabel, true);
    }

    private String actByIndex(int index, String expectedLabel, boolean longPress) {
        AtomicReference<String> result = new AtomicReference<>("gone");
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            AccessibilityNodeInfo root = null;
            try {
                if (!checkPackageAllowed()) { result.set("failed"); return; }
                root = targetWindowRoot();
                if (root == null) { result.set("gone"); return; }
                java.util.List<AccessibilityNodeInfo> order = new java.util.ArrayList<>();
                collectInFlatOrder(root, order);
                if (index < 0 || index >= order.size()) {
                    Log.i(TAG, "CLICK_INDEX: index " + index + " out of range, collected " + order.size());
                    result.set("gone");
                    return;
                }
                AccessibilityNodeInfo target = order.get(index);
                // Everything else walked to find it is dead weight. Without
                // this, every single click leaked the whole node list.
                for (int i = 0; i < order.size(); i++) {
                    if (i != index) {
                        try { order.get(i).recycle(); } catch (Exception ignored) {}
                    }
                }

                String actual = labelOf(target);
                if (expectedLabel != null && !expectedLabel.isEmpty()
                        && !expectedLabel.equals(actual)) {
                    Log.i(TAG, "CLICK_INDEX: index " + index + " now holds \"" + actual
                            + "\", expected \"" + expectedLabel + "\" — refusing");
                    result.set("moved");
                    return;
                }

                AccessibilityNodeInfo clickable = target;
                int hops = 0;
                while (clickable != null && hops < 6
                        && !(longPress ? clickable.isLongClickable() : clickable.isClickable())) {
                    clickable = clickable.getParent();
                    hops++;
                }
                boolean ok = false;
                if (clickable != null) {
                    ok = clickable.performAction(longPress
                            ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "CLICK_INDEX: " + (longPress ? "ACTION_LONG_CLICK" : "ACTION_CLICK")
                            + " on \"" + actual + "\" result=" + ok);
                }
                if (!ok) {
                    Rect b = new Rect();
                    target.getBoundsInScreen(b);
                    if (b.width() > 0 && b.height() > 0) {
                        if (longPress) performSwipe(b.centerX(), b.centerY(), b.centerX(), b.centerY(), 900);
                        else performTap(b.centerX(), b.centerY());
                        Log.i(TAG, "CLICK_INDEX: fell back to a gesture at " + b.centerX() + "," + b.centerY());
                        ok = true;
                    }
                }
                result.set(ok ? "ok" : "failed");
                try { target.recycle(); } catch (Exception ignored) {}
            } catch (Exception e) {
                Log.e(TAG, "CLICK_INDEX failed: " + e.getMessage());
                result.set("failed");
            } finally {
                if (root != null) root.recycle();
                latch.countDown();
            }
        });
        boolean answered = false;
        try { answered = latch.await(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        // A long click can start a drag or a selection inside the app and not return for seconds
        // (the Files app). The press was delivered; whether it did anything is for the caller to
        // judge from the screen, not something to report as "no longer on the screen".
        if (!answered && longPress) return "ok";
        return result.get();
    }

    /**
     * The window the user is actually looking at.
     *
     * Taking the first application window in the list read the page BEHIND an
     * open menu. Measured on Chrome: the overflow button was clicked
     * successfully, over and over — ACTION_CLICK returned true every time —
     * and the agent then described the web page, never saw the menu it had
     * just opened, and concluded the tap had failed.
     *
     * A menu, a dialog and an autocomplete dropdown are all windows layered
     * above the page. The focused one is what the user is dealing with; when
     * nothing claims focus, the highest layer is on top. Order in the list
     * means nothing.
     */
    private AccessibilityNodeInfo topmostWindowRoot(String tag) {
        AccessibilityNodeInfo best = null;
        int bestLayer = Integer.MIN_VALUE;
        boolean bestFocused = false;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                int type = w.getType();
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo wRoot = w.getRoot();
                if (wRoot == null) continue;
                CharSequence pkg = wRoot.getPackageName();
                if (pkg != null && "com.agent.ultra".contentEquals(pkg)) { wRoot.recycle(); continue; }
                // The status bar, the navigation bar and the keyboard are the
                // device's own furniture, not the task. Preferring the topmost
                // window without this picked systemui the moment a keyboard
                // appeared — and since systemui is not on the user's allowed
                // list, the policy gate then correctly refused every following
                // action. The agent looked broken; it was being protected from
                // reading a window it had no business in.
                if (pkg != null && isDeviceFurniture(pkg.toString())) { wRoot.recycle(); continue; }

                boolean focused = w.isFocused() || w.isActive();
                int layer = w.getLayer();
                boolean better = (best == null)
                        || (focused && !bestFocused)
                        || (focused == bestFocused && layer > bestLayer);
                if (better) {
                    if (best != null) best.recycle();
                    best = wRoot;
                    bestLayer = layer;
                    bestFocused = focused;
                } else {
                    wRoot.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, tag + ": window scan failed: " + e.getMessage());
        }
        if (best != null) {
            Log.i(TAG, tag + ": using topmost window layer=" + bestLayer + " focused=" + bestFocused);
            return best;
        }
        return getRootInActiveWindow();
    }

    /** Windows that belong to the phone rather than to whatever is being done. */
    private static boolean isDeviceFurniture(String pkg) {
        if (pkg.equals("com.android.systemui")) return true;
        // Keyboards name themselves in many ways; what they have in common is
        // being an input method, and none of them is ever the task.
        return pkg.contains("inputmethod") || pkg.contains("honeyboard")
                || pkg.contains("latin") || pkg.endsWith(".ime");
    }

    /** The same order flattenNode writes, so an index means the same node. */
    private void collectInFlatOrder(AccessibilityNodeInfo node, java.util.List<AccessibilityNodeInfo> out) {
        if (node == null || out.size() >= FLAT_NODE_LIMIT) return;
        String text = node.getText() != null ? node.getText().toString().trim() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : "";
        boolean hasContent = !text.isEmpty() || !desc.isEmpty();
        boolean interactive = node.isClickable() || node.isScrollable() || node.isEditable();
        if (hasContent || interactive) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) out.add(node);
        }
        for (int i = 0; i < Math.min(node.getChildCount(), 200); i++) {
            collectInFlatOrder(node.getChild(i), out);
        }
    }

    private static String labelOf(AccessibilityNodeInfo n) {
        String t = n.getText() != null ? n.getText().toString().trim() : "";
        if (!t.isEmpty()) return t;
        return n.getContentDescription() != null ? n.getContentDescription().toString().trim() : "";
    }

    /** The foreground app's window, never Agent Ultra's own. */
    private AccessibilityNodeInfo targetWindowRoot() {
        return topmostWindowRoot("CLICK_INDEX");
    }

    private void flattenNode(AccessibilityNodeInfo node, JSONArray flat) {
        flattenNode(node, flat, -1, 0);
    }

    /**
     * The full structural tree, containers included.
     *
     * getScreenContentFlat() emits only labelled or interactive nodes, so a
     * page built from unlabelled wrapper Views — which is most of the modern
     * web — collapses into one flat fan-out and the item boundaries are lost
     * before anything can group them. This keeps every node with real bounds,
     * so a consumer can rebuild the tree and see which labels belong together.
     *
     * Kept separate from getScreenContentFlat on purpose: the navigator taps
     * by index into that list, and adding containers would shift every index.
     */
    public String getScreenTree() {
        AtomicReference<String> result = new AtomicReference<>("[]");
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            AccessibilityNodeInfo root = null;
            try {
                // Same rule as the flat read: whatever is on top, so a menu or
                // dialog is what gets described rather than the page under it.
                root = topmostWindowRoot("SCREEN_TREE");
                if (root != null) {
                    CharSequence rp = root.getPackageName();
                    if (rp != null && "com.agent.ultra".contentEquals(rp)) { root.recycle(); root = null; }
                }
                if (root == null) {
                    root = getRootInActiveWindow();
                    if (root != null) {
                        CharSequence fbPkg = root.getPackageName();
                        if (fbPkg != null && "com.agent.ultra".contentEquals(fbPkg)) { root.recycle(); root = null; }
                    }
                }
                // A window whose package reads null while it is still coming up passes the
                // skip above and turns out to be Ultra's own chat: the navigator then drove
                // Ultra's UI, tapping its ☰ forever (AndroidWorld, 2026-09-19). Check again
                // once the root is in hand.
                if (root != null) {
                    CharSequence again = root.getPackageName();
                    if (again != null && "com.agent.ultra".contentEquals(again)) {
                        Log.i(TAG, "SCREEN_FLAT: BLOCKED self-read (window package resolved late)");
                        root.recycle();
                        root = null;
                    }
                }
                if (root != null && isProtected(root)) {
                    Log.i(TAG, "SCREEN_TREE: BLOCKED — protected app in front");
                    result.set(PROTECTED);
                    root.recycle();
                    root = null;
                    return;
                }
                if (root != null) {
                    JSONArray tree = new JSONArray();
                    treeNode(root, tree, -1, 0);
                    Log.i(TAG, "SCREEN_TREE: nodes=" + tree.length());
                    result.set(tree.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "getScreenTree error", e);
            } finally {
                if (root != null) root.recycle();
                latch.countDown();
            }
        });
        try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return result.get();
    }

    /** Returned instead of nodes when the foreground app is protected, so the
     * caller can say why rather than reporting an empty screen. */
    public static final String PROTECTED = "PROTECTED";

    /** True when the window belongs to an app the user marked protected. */
    private boolean isProtected(AccessibilityNodeInfo root) {
        try {
            CharSequence pkg = root.getPackageName();
            return pkg == null || !agentMayUse(pkg.toString());
        } catch (Exception e) {
            return true;  // fail closed
        }
    }

    private static final int TREE_NODE_LIMIT = 3000;

    /** "android.widget.TextView" -> "TextView", "com.app:id/price" -> "price". */
    private static String shortName(CharSequence raw) {
        if (raw == null) return "";
        String s = raw.toString();
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static CharSequence idOf(AccessibilityNodeInfo node) {
        try {
            return node.getViewIdResourceName();
        } catch (Exception e) {
            return null;   // not every node exposes one, and some throw
        }
    }

    private void treeNode(AccessibilityNodeInfo node, JSONArray tree, int parent, int depth) {
        if (node == null || tree.length() >= TREE_NODE_LIMIT || depth > 60) return;
        int me = parent;
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String text = node.getText() != null ? node.getText().toString().trim() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : "";
            JSONObject obj = new JSONObject();
            obj.put("i", tree.length());
            obj.put("p", parent);
            obj.put("dep", depth);
            obj.put("t", text);
            obj.put("d", desc);
            obj.put("c", node.isClickable());
            obj.put("tp", bounds.top);
            obj.put("b", bounds.bottom);
            // Structural identity. Without these the reader has to reconstruct
            // the page's shape from text statistics, which is guesswork: it
            // scored containers by how uniform their children looked and got
            // it wrong on any page whose records are not the biggest thing on
            // screen. A repeating record has a repeating class path; that is
            // the page telling us directly which nodes are the same kind of
            // thing. Short form only — the package prefix is the same on every
            // node and would trip the node budget for nothing.
            obj.put("cls", shortName(node.getClassName()));
            obj.put("vid", shortName(idOf(node)));
            // Which nodes can be acted on. Needed to remember where a screen's
            // controls are rather than re-deriving them from an indexed list
            // that renumbers itself every time the screen moves.
            obj.put("e", node.isEditable());
            obj.put("en", node.isEnabled());
            me = tree.length();
            tree.put(obj);
        } catch (Exception ignored) {}
        for (int i = 0; i < Math.min(node.getChildCount(), 200); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                treeNode(child, tree, me, depth + 1);
                child.recycle();
            }
        }
    }

    /**
     * Flatten the node tree, keeping enough structure to reconstruct it.
     *
     * A flat list of labels cannot say which price belongs to which product.
     * Each emitted node now carries the index of its nearest emitted ancestor
     * ("p") plus its full bounds, so the consumer can rebuild the tree and
     * group labels into the items they actually belong to.
     *
     * @param parent index in `flat` of the nearest ancestor that was emitted,
     *               or -1 when this node hangs directly off the root.
     */
    /**
     * The SAME limit both walkers use.
     *
     * flattenNode had none while the click resolver stopped at 1200, so on a
     * dense screen the model could be handed a valid index that clickByIndex
     * then reported as "gone" — directly under a comment promising the two
     * produce the same order. A cap only one of them obeys is not a cap, it is
     * a disagreement.
     */
    /**
     * The one thread that samples screens during a demonstration.
     *
     * There used to be no such thing: every window and content event started a
     * thread of its own, each slept, and then all of them raced to record what
     * they had read. Whichever arrived first won and the rest were dropped by a
     * throttle, so the number of screens a demonstration recorded depended on
     * how many events each screen happened to fire and how the scheduler felt
     * about it. The same walk through Chrome recorded three screens, then two,
     * then three.
     *
     * One thread, one pending read, and every new event pushes that read
     * further out. A screen is sampled once, when its event stream goes quiet,
     * which is the same moment the replay considers a screen settled. Bursts of
     * events cost nothing but a rescheduled task.
     */
    private final java.util.concurrent.ScheduledExecutorService journeySampler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ultra-journey");
                t.setDaemon(true);
                return t;
            });

    private java.util.concurrent.ScheduledFuture<?> pendingJourneySample;

    /**
     * Read the screen once the events about it have stopped arriving.
     *
     * Deliberately reads the package at sample time rather than carrying the
     * one from the event that armed it: the tree and the name of the app it
     * belongs to have to come from the same moment, or a fast navigation
     * records one app's screen under another's name.
     */
    private synchronized void scheduleJourneySample() {
        if (pendingJourneySample != null) pendingJourneySample.cancel(false);
        pendingJourneySample = journeySampler.schedule(() -> {
            try {
                if (!com.agent.ultra.agent.Demonstration.INSTANCE.isRecording()) return;
                com.agent.ultra.agent.Demonstration.INSTANCE.noteScreen(
                        currentPackage,
                        com.agent.ultra.agent.ScreenStructure.INSTANCE.parse(getScreenTree()));
            } catch (Exception e) {
                Log.w(TAG, "journey sample failed: " + e.getMessage());
            }
        }, SETTLE_BEFORE_SAMPLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * How long to let a screen finish before recording what it looks like.
     *
     * Matched to the replay's own settle wait. The two must agree: a route is
     * only walkable if the screens in it were fingerprinted the same way they
     * will be recognised.
     */
    private static final long SETTLE_BEFORE_SAMPLE_MS = 1800L;

    static final int FLAT_NODE_LIMIT = 1200;

    private void flattenNode(AccessibilityNodeInfo node, JSONArray flat, int parent, int depth) {
        flattenNode(node, flat, parent, depth, false);
    }

    /**
     * @param clickableParent an ancestor of this node handles taps.
     *
     * Android puts the click on the row and the words on a child TextView, so a menu read as
     * "no interactive elements" though every line of it was tappable (AndroidWorld: Markor's
     * More-options menu, 2026-09-19). The flag travels down as "ca" so a reader can offer the
     * label and tap the row.
     */
    private void flattenNode(AccessibilityNodeInfo node, JSONArray flat, int parent, int depth,
                             boolean clickableParent) {
        if (node == null || flat.length() >= FLAT_NODE_LIMIT) return;
        String text = node.getText() != null ? node.getText().toString().trim() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : "";
        boolean hasContent = !text.isEmpty() || !desc.isEmpty();
        boolean interactive = node.isClickable() || node.isScrollable() || node.isEditable();
        int myIndex = parent;
        if (hasContent || interactive) {
            try {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() > 0 && bounds.height() > 0) {
                    JSONObject obj = new JSONObject();
                    obj.put("i", flat.length());
                    obj.put("p", parent);
                    obj.put("dep", depth);
                    obj.put("t", text);
                    obj.put("d", desc);
                    obj.put("c", node.isClickable());
                    obj.put("ca", clickableParent);
                    obj.put("e", node.isEditable());
                    obj.put("s", node.isScrollable());
                    // The app's own name for this control, so a remembered one
                    // can be found again without depending on an index that
                    // renumbers every time the screen moves.
                    obj.put("vid", shortName(idOf(node)));
                    obj.put("x", bounds.centerX());
                    obj.put("y", bounds.centerY());
                    obj.put("l", bounds.left);
                    obj.put("tp", bounds.top);
                    obj.put("r", bounds.right);
                    obj.put("b", bounds.bottom);
                    myIndex = flat.length();
                    flat.put(obj);
                }
            } catch (Exception ignored) {}
        }
        // Deep perception: result lists and feeds routinely exceed 60 children.
        for (int i = 0; i < Math.min(node.getChildCount(), 200); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                flattenNode(child, flat, myIndex, depth + 1, clickableParent || node.isClickable());
                child.recycle();
            }
        }
    }

    public boolean waitForUiChange(int timeoutMs) {
        Log.i(TAG, "WAIT_UI: timeout=" + timeoutMs);
        String initial = getScreenContentFlat();
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try { Thread.sleep(150); } catch (InterruptedException e) { Log.i(TAG, "WAIT_UI: changed=false (interrupted)"); return false; }
            String current = getScreenContentFlat();
            if (!current.equals(initial) && !current.equals("[]")) { Log.i(TAG, "WAIT_UI: changed=true"); return true; }
        }
        Log.i(TAG, "WAIT_UI: changed=false");
        return false;
    }
    /**
     * The package an action would actually land in.
     *
     * The gate used to decide against `currentPackage`, which is set from event
     * history and — as the comments around it already admitted — goes stale.
     * Every action, meanwhile, resolves its target from the LIVE topmost
     * window: a tap dispatches absolute coordinates, and those coordinates land
     * wherever the top window happens to be. So the gate could pass on a stale
     * but still-allowed app while the tap executed in a different one. The
     * boundary failed open, which is the only direction a security check must
     * never fail.
     *
     * Now the check and the action ask the same question of the same source. If
     * no window can be resolved at all, fall back to the tracked package rather
     * than to "allow" — not knowing where we are is a reason to be more
     * careful, not less.
     */
    private String actionTargetPackage() {
        AccessibilityNodeInfo root = null;
        try {
            root = topmostWindowRoot("GATE");
            if (root == null) return currentPackage;
            CharSequence p = root.getPackageName();
            return p != null ? p.toString() : currentPackage;
        } catch (Exception e) {
            Log.e(TAG, "GATE: could not resolve the target window: " + e.getMessage());
            return currentPackage;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean checkPackageAllowed() {
        String target = actionTargetPackage();
        if (!target.equals(currentPackage)) {
            // Worth seeing when it happens: it is the exact divergence that
            // used to let an action through into the wrong app.
            Log.i(TAG, "GATE: deciding on the live target " + target
                + " (event tracker still says " + currentPackage + ")");
        }
        // Never allow actions on Agent Ultra's own UI — prevents self-interaction
        if ("com.agent.ultra".equals(target)) {
            emitA11yLog("A11Y_GATE", "{\"action\":\"BLOCKED_SELF\",\"pkg\":\"" + target + "\"}");
            Log.i(TAG, "GATE: BLOCKED_SELF pkg=" + target);
            return false;
        }
        if (!agentMayUse(target)) {
            emitA11yLog("A11Y_GATE", "{\"action\":\"BLOCKED_USER\",\"pkg\":\"" + target + "\"}");
            Log.i(TAG, "GATE: BLOCKED pkg=" + target
                + " (" + (allowlistMode ? "not on the allowed list" : "protected") + ")");
            return false;
        }
        if (!isPackageAllowed(target)) {
            allowPackage(target);
            emitA11yLog("A11Y_GATE", "{\"action\":\"AUTO_ALLOWED\",\"pkg\":\"" + target + "\"}");
            Log.i(TAG, "GATE: AUTO_ALLOWED pkg=" + target);
        }
        emitA11yLog("A11Y_GATE", "{\"action\":\"PASSED\",\"pkg\":\"" + target + "\"}");
        Log.i(TAG, "GATE: PASSED pkg=" + target);
        return true;
    }
    public boolean performTap(int x, int y) {
        if (!checkPackageAllowed()) {
            emitA11yLog("A11Y_TAP", "{\"action\":\"BLOCKED\",\"x\":" + x + ",\"y\":" + y + ",\"pkg\":\"" + currentPackage + "\"}");
            Log.i(TAG, "TAP: BLOCKED x=" + x + " y=" + y + " pkg=" + currentPackage);
            return false;
        }
        emitA11yLog("A11Y_TAP", "{\"action\":\"DISPATCH\",\"x\":" + x + ",\"y\":" + y + ",\"pkg\":\"" + currentPackage + "\"}");
        Log.i(TAG, "TAP: DISPATCH x=" + x + " y=" + y + " pkg=" + currentPackage);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Path path = new Path();
                path.moveTo(x, y);
                GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                    .build();
                dispatchGesture(gesture, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        emitA11yLog("A11Y_TAP", "{\"action\":\"COMPLETED\",\"x\":" + x + ",\"y\":" + y + "}");
                        Log.i(TAG, "TAP: COMPLETED x=" + x + " y=" + y);
                        success.set(true); latch.countDown();
                    }
                    @Override
                    public void onCancelled(GestureDescription g) {
                        emitA11yLog("A11Y_TAP", "{\"action\":\"CANCELLED\",\"x\":" + x + ",\"y\":" + y + "}");
                        Log.i(TAG, "TAP: CANCELLED x=" + x + " y=" + y);
                        latch.countDown();
                    }
                }, null);
            } catch (Exception e) {
                emitA11yLog("A11Y_TAP", "{\"action\":\"ERROR\",\"x\":" + x + ",\"y\":" + y + ",\"error\":\"" + e.getMessage() + "\"}");
                Log.i(TAG, "TAP: ERROR x=" + x + " y=" + y + " error=" + e.getMessage());
                Log.e(TAG, "performTap error", e); latch.countDown();
            }
        });
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        boolean result = success.get();
        if (!result) { emitA11yLog("A11Y_TAP", "{\"action\":\"TIMEOUT_OR_FAIL\",\"x\":" + x + ",\"y\":" + y + "}"); Log.i(TAG, "TAP: TIMEOUT_OR_FAIL x=" + x + " y=" + y); }
        return result;
    }
    public boolean performSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (!checkPackageAllowed()) return false;
        Log.i(TAG, "SWIPE: " + x1 + "," + y1 + " -> " + x2 + "," + y2 + " pkg=" + currentPackage);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Path path = new Path();
                path.moveTo(x1, y1);
                path.lineTo(x2, y2);
                GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(durationMs, 100)))
                    .build();
                dispatchGesture(gesture, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) { Log.i(TAG, "SWIPE: COMPLETED"); success.set(true); latch.countDown(); }
                    @Override
                    public void onCancelled(GestureDescription g) { Log.i(TAG, "SWIPE: CANCELLED"); latch.countDown(); }
                }, null);
            } catch (Exception e) { Log.e(TAG, "performSwipe error", e); latch.countDown(); }
        });
        try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return success.get();
    }

    public boolean performClick(String selector) {
        if (!checkPackageAllowed()) return false;
        Log.i(TAG, "CLICK: selector=" + selector + " pkg=" + currentPackage);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = findNode(root, selector);
        boolean result = false;
        if (target != null) { result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK); target.recycle(); }
        root.recycle();
        return result;
    }

    public boolean performText(String selector, String text) {
        if (!checkPackageAllowed()) return false;
        // Length, never content. This logged the first 30 characters of
        // whatever was being typed, which on a login screen is the password and
        // on a bank screen is the one-time code — written to logcat, where any
        // app holding READ_LOGS on an older device could read it back.
        Log.i(TAG, "TEXT: selector=" + selector + " len=" + text.length() + " pkg=" + currentPackage);
        // Multi-window scan (same pattern as performImeAction): once the
        // keyboard opens, getRootInActiveWindow() is the IME window, not the
        // target app — proven failure on Chrome's omnibox (TEXT result=false
        // loop). Search application windows first, active window last.
        AccessibilityNodeInfo target = null;
        AccessibilityNodeInfo root = null;
        try {
            java.util.List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    AccessibilityNodeInfo wRoot = w.getRoot();
                    if (wRoot != null) {
                        CharSequence wPkg = wRoot.getPackageName();
                        if (wPkg != null && "com.agent.ultra".contentEquals(wPkg)) { wRoot.recycle(); continue; }
                        target = selector.isEmpty() ? findFocusedEditable(wRoot) : findNode(wRoot, selector);
                        if (target == null && selector.isEmpty()) target = findAnyEditable(wRoot);
                        wRoot.recycle();
                        if (target != null) break;
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "TEXT: window scan failed: " + e.getMessage());
        }
        if (target == null) {
            root = getRootInActiveWindow();
            if (root != null) {
                target = selector.isEmpty() ? findFocusedEditable(root) : findNode(root, selector);
                if (target == null && selector.isEmpty()) target = findAnyEditable(root);
            }
        }
        if (target == null && root == null) return false;
        boolean result = false;
        if (target != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            target.recycle();
        }
        if (root != null) root.recycle();
        Log.i(TAG, "TEXT: result=" + result);
        return result;
    }

    public boolean performScroll(String direction) {
        if (!checkPackageAllowed()) return false;
        Log.i(TAG, "SCROLL: direction=" + direction + " pkg=" + currentPackage);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        boolean result = false;
        if (scrollable != null) {
            int action = "up".equals(direction) || "backward".equals(direction)
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            result = scrollable.performAction(action);
            scrollable.recycle();
        }
        root.recycle();
        Log.i(TAG, "SCROLL: result=" + result);
        return result;
    }

    /**
     * Scroll for deep reading. Two differences from performScroll:
     * it picks the target app's window the same way getScreenContentFlat does
     * (getRootInActiveWindow can be the keyboard or an overlay), and it scrolls
     * the LARGEST scrollable container rather than the first one found — the
     * first is usually a narrow carousel, not the list you want to read.
     */
    public boolean performScrollDeep(String direction) {
        if (!checkPackageAllowed()) return false;
        AccessibilityNodeInfo root = null;
        try {
            java.util.List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    AccessibilityNodeInfo wRoot = w.getRoot();
                    if (wRoot != null) {
                        CharSequence pkg = wRoot.getPackageName();
                        if (pkg == null || !"com.agent.ultra".contentEquals(pkg)) { root = wRoot; break; }
                        wRoot.recycle();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SCROLL_DEEP: window scan failed: " + e.getMessage());
        }
        if (root == null) root = getRootInActiveWindow();
        if (root == null) { Log.i(TAG, "SCROLL_DEEP: no root"); return false; }

        AccessibilityNodeInfo best = findLargestScrollable(root, null);
        boolean result = false;
        if (best != null) {
            int action = ("up".equals(direction) || "backward".equals(direction))
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            result = best.performAction(action);
            Rect b = new Rect();
            best.getBoundsInScreen(b);
            Log.i(TAG, "SCROLL_DEEP: " + direction + " on " + best.getClassName()
                + " area=" + (b.width() * b.height()) + " result=" + result);
            best.recycle();
        } else {
            Log.i(TAG, "SCROLL_DEEP: nothing scrollable");
        }
        root.recycle();
        return result;
    }

    /** Depth-first search for the scrollable node covering the most screen area. */
    private AccessibilityNodeInfo findLargestScrollable(AccessibilityNodeInfo node, AccessibilityNodeInfo best) {
        if (node == null) return best;
        if (node.isScrollable()) {
            Rect nb = new Rect();
            node.getBoundsInScreen(nb);
            long area = (long) nb.width() * nb.height();
            long bestArea = 0;
            if (best != null) {
                Rect bb = new Rect();
                best.getBoundsInScreen(bb);
                bestArea = (long) bb.width() * bb.height();
            }
            if (area > bestArea) {
                if (best != null) best.recycle();
                best = AccessibilityNodeInfo.obtain(node);
            }
        }
        for (int i = 0; i < Math.min(node.getChildCount(), 200); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                best = findLargestScrollable(child, best);
                child.recycle();
            }
        }
        return best;
    }

    public boolean performImeAction() {
        // Pressing Enter submits: it sends the message, runs the search, places
        // the order. It reached here with no policy check at all, so an app the
        // user had never allowed could still have something committed in it —
        // the single most consequential keystroke was the one nothing guarded.
        if (!checkPackageAllowed()) {
            Log.i(TAG, "IME_ENTER: BLOCKED — not allowed in " + actionTargetPackage());
            return false;
        }
        Log.i(TAG, "IME_ENTER: firing");
        // Strategy 1: Find focused OR any editable field across all windows
        AccessibilityNodeInfo target = null;
        try {
            java.util.List<AccessibilityWindowInfo> windows = getWindows();
            // Pass 1: look for focused editable
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    AccessibilityNodeInfo wRoot = w.getRoot();
                    if (wRoot != null) {
                        target = findFocusedEditable(wRoot);
                        wRoot.recycle();
                        if (target != null) {
                            Log.i(TAG, "IME_ENTER: found focused editable in window pkg=" + (target.getPackageName() != null ? target.getPackageName() : "null"));
                            break;
                        }
                    }
                }
            }
            // Pass 2: if no focused editable, find ANY editable (focus may be on keyboard)
            if (target == null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        AccessibilityNodeInfo wRoot = w.getRoot();
                        if (wRoot != null) {
                            target = findAnyEditable(wRoot);
                            wRoot.recycle();
                            if (target != null) {
                                Log.i(TAG, "IME_ENTER: found non-focused editable in window pkg=" + (target.getPackageName() != null ? target.getPackageName() : "null"));
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "IME_ENTER: window scan failed: " + e.getMessage());
        }
        // Pass 3: fallback to getRootInActiveWindow
        if (target == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                target = findFocusedEditable(root);
                if (target == null) target = findAnyEditable(root);
                root.recycle();
            }
        }

        boolean result = false;
        if (target != null) {
            // Try ACTION_IME_ENTER first (API 30+)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                result = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                Log.i(TAG, "IME_ENTER: ACTION_IME_ENTER result=" + result);
            }
            // Fallback: try clicking the node itself (some search fields submit on click)
            if (!result) {
                result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "IME_ENTER: ACTION_CLICK fallback result=" + result);
            }
            target.recycle();
        } else {
            Log.i(TAG, "IME_ENTER: no editable found in any window");
        }

        // Strategy 2: If node-based approach failed, try KEYCODE_ENTER via instrumentation
        if (!result) {
            Log.i(TAG, "IME_ENTER: node approach failed, trying gesture tap on keyboard Enter");
            // Find the keyboard search/go/enter button via accessibility
            try {
                java.util.List<AccessibilityWindowInfo> windows = getWindows();
                for (AccessibilityWindowInfo w : windows) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        AccessibilityNodeInfo kbRoot = w.getRoot();
                        if (kbRoot != null) {
                            AccessibilityNodeInfo enterBtn = findKeyboardEnter(kbRoot);
                            if (enterBtn != null) {
                                android.graphics.Rect bounds = new android.graphics.Rect();
                                enterBtn.getBoundsInScreen(bounds);
                                int cx = bounds.centerX();
                                int cy = bounds.centerY();
                                Log.i(TAG, "IME_ENTER: tapping keyboard enter at " + cx + "," + cy);
                                android.accessibilityservice.GestureDescription.Builder gb = new android.accessibilityservice.GestureDescription.Builder();
                                android.graphics.Path p = new android.graphics.Path();
                                p.moveTo(cx, cy);
                                gb.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 50));
                                dispatchGesture(gb.build(), null, null);
                                result = true;
                                enterBtn.recycle();
                            }
                            kbRoot.recycle();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Log.i(TAG, "IME_ENTER: keyboard gesture failed: " + e.getMessage());
            }
        }

        Log.i(TAG, "IME_ENTER: final result=" + result);
        return result;
    }

    // Find any editable node (not necessarily focused) — keyboard may have stolen focus
    private AccessibilityNodeInfo findAnyEditable(AccessibilityNodeInfo root) {
        if (root.isEditable()) return AccessibilityNodeInfo.obtain(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findAnyEditable(child);
                if (found != null) { child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }

    // Find the Enter/Search/Go button on the keyboard
    private AccessibilityNodeInfo findKeyboardEnter(AccessibilityNodeInfo root) {
        String desc = root.getContentDescription() != null ? root.getContentDescription().toString().toLowerCase() : "";
        String text = root.getText() != null ? root.getText().toString().toLowerCase() : "";
        if (root.isClickable() && (desc.contains("enter") || desc.contains("search") || desc.contains("go") ||
            text.contains("enter") || text.contains("search") || text.contains("go") ||
            desc.contains("done") || text.contains("done"))) {
            return AccessibilityNodeInfo.obtain(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findKeyboardEnter(child);
                if (found != null) { child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }

    // Back and Home are deliberately NOT gated, and that is a decision rather
    // than an oversight. They act on the system, not on an app's content: they
    // change nothing, send nothing and commit nothing. More to the point they
    // are how the agent LEAVES somewhere it should not be — gating them on the
    // current app would mean that the moment it landed somewhere disallowed, it
    // would be forbidden from backing out, which is precisely backwards.
    public boolean performBack() { Log.i(TAG, "BACK: fired"); return performGlobalAction(GLOBAL_ACTION_BACK); }
    public boolean performHome() { Log.i(TAG, "HOME: fired"); return performGlobalAction(GLOBAL_ACTION_HOME); }
    public boolean performQuickSettings() { return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); }
    public boolean performNotifications() { return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); }
    public boolean performRecents() { return performGlobalAction(GLOBAL_ACTION_RECENTS); }
    public boolean takeScreenshot() {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        }
        return false;
    }

    // === UltraDevLog v4: System State Snapshot ===
    public String getSystemStateSnapshot() {
        try {
            org.json.JSONObject state = new org.json.JSONObject();
            // DND
            android.app.NotificationManager nm = (android.app.NotificationManager)
                getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int filter = nm.getCurrentInterruptionFilter();
                String dndLabel;
                switch (filter) {
                    case 1: dndLabel = "ALL"; break;
                    case 2: dndLabel = "PRIORITY"; break;
                    case 3: dndLabel = "NONE"; break;
                    case 4: dndLabel = "ALARMS"; break;
                    default: dndLabel = "UNKNOWN"; break;
                }
                state.put("dnd", dndLabel);
                state.put("dnd_raw", filter);
            }
            // Wi-Fi
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm != null) { state.put("wifi", wm.isWifiEnabled()); }
            // Bluetooth
            android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (bt != null) {
                int s = bt.getState();
                state.put("bluetooth", s == 12 ? "ON" : s == 10 ? "OFF" : s == 11 ? "TURNING_ON" : s == 13 ? "TURNING_OFF" : "UNKNOWN");
            }
            // Power saver
            android.os.PowerManager pm = (android.os.PowerManager)
                getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null) { state.put("powerSaver", pm.isPowerSaveMode()); }
            // Ringer
            android.media.AudioManager am = (android.media.AudioManager)
                getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am != null) {
                int r = am.getRingerMode();
                state.put("ringer", r == 0 ? "SILENT" : r == 1 ? "VIBRATE" : r == 2 ? "NORMAL" : "UNKNOWN");
                state.put("vol_media", am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
                state.put("vol_ring", am.getStreamVolume(android.media.AudioManager.STREAM_RING));
            }
            // Brightness
            try {
                state.put("brightness", android.provider.Settings.System.getInt(
                    getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS));
            } catch (Exception ignored) { state.put("brightness", -1); }
            return state.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public boolean tapQuickSettingsTile(String tileLabel) {
        allowPackage("com.android.systemui");
        Log.i(TAG, "QS_TAP: start tile=" + tileLabel);
        try {
            Thread.sleep(400);
            // Samsung One UI renders QS panel in a separate window from status bar.
            // getRootInActiveWindow() often returns the wrong SystemUI window (status bar with 4 children).
            // Scan ALL windows to find the one containing QS tiles.
            android.view.accessibility.AccessibilityNodeInfo root = null;
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                android.view.accessibility.AccessibilityNodeInfo bestRoot = null;
                int bestChildren = 0;
                for (android.view.accessibility.AccessibilityWindowInfo win : windows) {
                    android.view.accessibility.AccessibilityNodeInfo winRoot = win.getRoot();
                    if (winRoot == null) continue;
                    CharSequence pkg = winRoot.getPackageName();
                    if (pkg != null && "com.android.systemui".equals(pkg.toString())) {
                        int childCount = winRoot.getChildCount();
                        Log.i(TAG, "QS_TAP: systemui_window children=" + childCount + " type=" + win.getType());
                        if (childCount > bestChildren) {
                            if (bestRoot != null) bestRoot.recycle();
                            bestRoot = winRoot;
                            bestChildren = childCount;
                        } else {
                            winRoot.recycle();
                        }
                    } else {
                        winRoot.recycle();
                    }
                }
                root = bestRoot;
            }
            if (root == null) {
                // Fallback to getRootInActiveWindow if getWindows didn't find SystemUI
                root = getRootInActiveWindow();
            }
            if (root == null) {
                Log.i(TAG, "QS_TAP: no_root — no SystemUI window found");
                return false;
            }
            // Log what window we're reading
            CharSequence rootPkg = root.getPackageName();
            Log.i(TAG, "QS_TAP: root_pkg=" + (rootPkg != null ? rootPkg : "null") + " children=" + root.getChildCount());

            // Build search labels: include the original label + variant without hyphen (Wi-Fi -> WiFi)
            java.util.List<String> searchLabels = new java.util.ArrayList<>();
            searchLabels.add(tileLabel);
            if (tileLabel.contains("-")) searchLabels.add(tileLabel.replace("-", ""));
            if (!tileLabel.contains("-") && tileLabel.toLowerCase().startsWith("wifi")) searchLabels.add("Wi-Fi");

            java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
            int textMatches = 0;
            android.view.accessibility.AccessibilityNodeInfo byDesc = null;

            for (String label : searchLabels) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> found =
                    root.findAccessibilityNodeInfosByText(label);
                if (found != null) { nodes.addAll(found); textMatches += found.size(); }
                android.view.accessibility.AccessibilityNodeInfo descNode = findByContentDesc(root, label);
                if (descNode != null && byDesc == null) { byDesc = descNode; nodes.add(0, descNode); }
            }

            // Samsung QS tile labels may include state suffix ("Wi-Fi, Connected" or "Bluetooth, On")
            if (nodes.size() <= 1) {
                String[] suffixes = {", On", ", Off", ", Connected", ", Disconnected", ", Enabled", ", Disabled"};
                for (String label : searchLabels) {
                    for (String suffix : suffixes) {
                        java.util.List<android.view.accessibility.AccessibilityNodeInfo> extra =
                            root.findAccessibilityNodeInfosByText(label + suffix);
                        if (extra != null) nodes.addAll(extra);
                        android.view.accessibility.AccessibilityNodeInfo extraDesc = findByContentDesc(root, label + suffix);
                        if (extraDesc != null) nodes.add(0, extraDesc);
                    }
                }
            }
            Log.i(TAG, "QS_TAP: search tile=" + tileLabel + " text_matches=" + textMatches + " desc_match=" + (byDesc != null) + " total=" + nodes.size());

            // Filter out nodes in status bar / notification area (y < 500)
            // Samsung QS tiles start at y~500+ after full panel expansion
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> filteredNodes = new java.util.ArrayList<>();
            for (android.view.accessibility.AccessibilityNodeInfo candidate : nodes) {
                android.graphics.Rect cb2 = new android.graphics.Rect();
                candidate.getBoundsInScreen(cb2);
                int centerY2 = (cb2.top + cb2.bottom) / 2;
                CharSequence cText = candidate.getText();
                CharSequence cDesc = candidate.getContentDescription();
                String nodeInfo = "text=" + (cText != null ? cText : "null") + " desc=" + (cDesc != null ? cDesc : "null") + " y=" + centerY2 + " clickable=" + candidate.isClickable() + " bounds=" + cb2.toShortString();
                if (centerY2 < 500) {
                    Log.i(TAG, "QS_TAP: skip_node (above_qs_area) " + nodeInfo);
                } else {
                    Log.i(TAG, "QS_TAP: candidate " + nodeInfo);
                    filteredNodes.add(candidate);
                }
            }
            if (filteredNodes.isEmpty() && !nodes.isEmpty()) {
                // All filtered out — pick the node with HIGHEST y (furthest from status bar)
                android.view.accessibility.AccessibilityNodeInfo bestNode = null;
                int bestY = -1;
                for (android.view.accessibility.AccessibilityNodeInfo n : nodes) {
                    android.graphics.Rect nb = new android.graphics.Rect();
                    n.getBoundsInScreen(nb);
                    int cy = (nb.top + nb.bottom) / 2;
                    if (cy > bestY) { bestY = cy; bestNode = n; }
                }
                if (bestNode != null) filteredNodes.add(bestNode);
                Log.i(TAG, "QS_TAP: all nodes below y=500, using best_y=" + bestY);
            }
            if (filteredNodes.isEmpty()) {
                Log.i(TAG, "QS_TAP: no_match tile=" + tileLabel + " — zero candidates after filter");
                root.recycle();
                return false;
            }

            for (android.view.accessibility.AccessibilityNodeInfo node : filteredNodes) {
                // Get the node's own bounds for reference
                android.graphics.Rect nodeBounds = new android.graphics.Rect();
                node.getBoundsInScreen(nodeBounds);

                // Strategy 1: walk up to find the QS tile container
                // Samsung split tiles (Wi-Fi, BT, Mobile Data) have TWO clickable zones:
                //   - LEFT side: icon area — tapping toggles on/off
                //   - RIGHT side: text/label area — tapping opens settings
                // We want the ICON side (toggle), so we tap the LEFT quarter of the tile.
                android.view.accessibility.AccessibilityNodeInfo current = node;
                android.view.accessibility.AccessibilityNodeInfo clickableAncestor = null;
                int ancestorDepth = -1;
                for (int depth = 0; depth < 6; depth++) {
                    if (current == null) break;
                    if (current.isClickable()) {
                        clickableAncestor = current;
                        ancestorDepth = depth;
                        break;
                    }
                    current = current.getParent();
                }

                if (clickableAncestor != null) {
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    clickableAncestor.getBoundsInScreen(bounds);
                    // Check if this is a split tile: wide tile (width > 2x height) = likely split
                    boolean isSplitTile = bounds.width() > bounds.height() * 2;
                    int tapX, tapY;
                    if (isSplitTile) {
                        // Tap the LEFT quarter — icon/toggle area on Samsung
                        tapX = bounds.left + bounds.width() / 4;
                        tapY = (bounds.top + bounds.bottom) / 2;
                        Log.i(TAG, "QS_TAP: SPLIT_TILE detected — tapping icon side at " + tapX + "," + tapY + " bounds=" + bounds.toShortString());
                    } else {
                        tapX = (bounds.left + bounds.right) / 2;
                        tapY = (bounds.top + bounds.bottom) / 2;
                        Log.i(TAG, "QS_TAP: simple_tile — tapping center at " + tapX + "," + tapY + " bounds=" + bounds.toShortString());
                    }
                    Log.i(TAG, "QS_TAP: found_clickable_ancestor depth=" + ancestorDepth + " bounds=" + bounds.toShortString());
                    // Always use gesture tap for QS tiles — ACTION_CLICK often opens settings on Samsung
                    Log.i(TAG, "QS_TAP: gesture_tap at " + tapX + "," + tapY);
                    boolean g = tapAtPoint(tapX, tapY);
                    Log.i(TAG, "QS_TAP: gesture_tap result=" + g);
                    root.recycle();
                    return g;
                }

                // Strategy 2: no clickable ancestor — gesture tap on node bounds
                if (!nodeBounds.isEmpty()) {
                    int nx = (nodeBounds.left + nodeBounds.right) / 2;
                    int ny = nodeBounds.top + (nodeBounds.height() / 3);
                    Log.i(TAG, "QS_TAP: no_clickable_ancestor, gesture_tap at " + nx + "," + ny + " bounds=" + nodeBounds.toShortString());
                    root.recycle();
                    return tapAtPoint(nx, ny);
                }
            }
            Log.i(TAG, "QS_TAP: exhausted all candidates, no tap fired");
            root.recycle();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "QS_TAP: error: " + e.getMessage());
            return false;
        }
    }

    private boolean tapAtCenter(android.graphics.Rect bounds) {
        return tapAtPoint((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2);
    }

    private boolean tapAtPoint(int x, int y) {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        android.accessibilityservice.GestureDescription.Builder builder =
            new android.accessibilityservice.GestureDescription.Builder();
        builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150));
        final boolean[] done = {false};
        final boolean[] success = {false};
        dispatchGesture(builder.build(), new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(android.accessibilityservice.GestureDescription g) {
                success[0] = true; done[0] = true;
            }
            @Override
            public void onCancelled(android.accessibilityservice.GestureDescription g) {
                done[0] = true;
            }
        }, null);
        long waitStart = System.currentTimeMillis();
        while (!done[0] && System.currentTimeMillis() - waitStart < 1500) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        return success[0];
    }

    public boolean toggleQuickSetting(String tileLabel) {
        Log.i(TAG, "QS_TOGGLE: start tile=" + tileLabel);
        allowPackage("com.android.systemui");
        android.graphics.Point screenSize = new android.graphics.Point();
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getRealSize(screenSize);
        } catch (Exception ignored) { screenSize.set(1080, 2340); }
        int cx = screenSize.x / 2;
        int h = screenSize.y;
        Log.i(TAG, "QS_TOGGLE: screen=" + screenSize.x + "x" + h + " center_x=" + cx);

        // Swipe 1: pull down notification shade
        boolean swipe1 = swipeRaw(cx, 10, cx, h / 2, 300);
        Log.i(TAG, "QS_TOGGLE: swipe_shade result=" + swipe1);
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}

        // Swipe 2: expand to full QS tiles
        boolean swipe2 = swipeRaw(cx, h / 4, cx, h * 3 / 4, 300);
        Log.i(TAG, "QS_TOGGLE: swipe_expand result=" + swipe2);
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        boolean result = tapQuickSettingsTile(tileLabel);
        Log.i(TAG, "QS_TOGGLE: first_attempt tile=" + tileLabel + " result=" + result);
        if (!result) {
            // Scroll QS panel to find hidden tiles
            Log.i(TAG, "QS_TOGGLE: scrolling QS panel to find hidden tile");
            swipeRaw(cx, h / 2, cx, h / 4, 200);
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            result = tapQuickSettingsTile(tileLabel);
            Log.i(TAG, "QS_TOGGLE: second_attempt tile=" + tileLabel + " result=" + result);
        }

        // Always dismiss the shade
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        Log.i(TAG, "QS_TOGGLE: dismissing shade, toggle_result=" + result);
        swipeRaw(cx, h * 3 / 4, cx, 10, 250);
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        // Double-ensure shade is gone
        performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i(TAG, "QS_TOGGLE: complete tile=" + tileLabel + " result=" + result);
        return result;
    }

    // Swipe helper that bypasses checkPackageAllowed — needed for system UI swipes
    private boolean swipeRaw(int x1, int y1, int x2, int y2, int durationMs) {
        final boolean[] done = {false};
        final boolean[] success = {false};
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        android.accessibilityservice.GestureDescription gesture =
            new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, Math.max(durationMs, 100)))
                .build();
        dispatchGesture(gesture, new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(android.accessibilityservice.GestureDescription g) { success[0] = true; done[0] = true; }
            @Override
            public void onCancelled(android.accessibilityservice.GestureDescription g) { done[0] = true; }
        }, null);
        long ws = System.currentTimeMillis();
        while (!done[0] && System.currentTimeMillis() - ws < 3000) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        return success[0];
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo root) {
        if (root.isEditable() && root.isFocused()) return AccessibilityNodeInfo.obtain(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFocusedEditable(child);
                if (found != null) { child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, String selector) {
        List<AccessibilityNodeInfo> byText = root.findAccessibilityNodeInfosByText(selector);
        if (byText != null && !byText.isEmpty()) return byText.get(0);
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(selector);
        if (byId != null && !byId.isEmpty()) return byId.get(0);
        return findByContentDesc(root, selector);
    }

    private AccessibilityNodeInfo findByContentDesc(AccessibilityNodeInfo node, String desc) {
        if (node.getContentDescription() != null &&
            node.getContentDescription().toString().toLowerCase().contains(desc.toLowerCase())) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findByContentDesc(child, desc);
                if (found != null) { child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node.isScrollable()) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findScrollable(child);
                if (found != null) { child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }
}