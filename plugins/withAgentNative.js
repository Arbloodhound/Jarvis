const { withDangerousMod, withAndroidManifest } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

const NATIVE_MODULE_JAVA = `package com.agent.ultra;

import com.facebook.react.bridge.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import androidx.core.content.FileProvider;

public class AgentNativeModule extends ReactContextBaseJavaModule {
    private static final String TAG = "AgentNative";
    private static final String[] ALLOWED_COMMANDS = {
        "dalvikvm", "keytool", "ls", "mkdir", "cp", "cat", "chmod", "find"
    };
    private static final String[] BLOCKED_METACHAR = {
        ";", "|", "&&", "||", "$(", "\`"
    };
    private final ReactApplicationContext ctx;

    public AgentNativeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.ctx = reactContext;
    }

    @Override
    public String getName() {
        return "AgentNative";
    }

    @ReactMethod
    public void writeFile(String filePath, String content, Promise promise) {
        try {
            File f = new File(filePath);
            String canonicalPath = f.getCanonicalPath();
            String dataDir = ctx.getFilesDir().getCanonicalPath();
            String cacheDir = ctx.getCacheDir().getCanonicalPath();
            if (!canonicalPath.startsWith(dataDir) && !canonicalPath.startsWith(cacheDir)) {
                promise.reject("WRITE_ERROR", "Path traversal detected: " + filePath);
                return;
            }
            f.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.close();
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("WRITE_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void compileJava(ReadableArray sourcePaths, String outputDir, String classpath, Promise promise) {
        try {
            File out = new File(outputDir);
            out.mkdirs();
            String ecj = ctx.getFilesDir() + "/build-tools/ecj.jar";

            if (!new File(ecj).exists()) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("success", false);
                map.putString("error", "ECJ compiler not found at " + ecj);
                promise.resolve(map);
                return;
            }

            List<String> allSources = new ArrayList<>();
            for (int i = 0; i < sourcePaths.size(); i++) {
                String sp = sourcePaths.getString(i);
                File sf = new File(sp);
                if (sf.isDirectory()) {
                    findJavaFiles(sf, allSources);
                } else if (sf.getName().endsWith(".java")) {
                    allSources.add(sf.getAbsolutePath());
                }
            }

            if (allSources.isEmpty()) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("success", false);
                map.putString("output", "");
                map.putString("errors", "No .java source files found");
                promise.resolve(map);
                return;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("dalvikvm");
            cmd.add("-Xmx256m");
            cmd.add("-cp");
            cmd.add(ecj);
            cmd.add("org.eclipse.jdt.internal.compiler.batch.Main");
            cmd.add("-source");
            cmd.add("1.8");
            cmd.add("-target");
            cmd.add("1.8");
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add("-d");
            cmd.add(outputDir);
            cmd.addAll(allSources);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\\n");
            int exitCode = p.waitFor();

            WritableMap map = Arguments.createMap();
            map.putBoolean("success", exitCode == 0);
            map.putString("output", exitCode == 0 ? sb.toString() : "");
            map.putString("errors", exitCode != 0 ? sb.toString() : "");
            promise.resolve(map);
        } catch (Exception e) {
            promise.reject("COMPILE_ERROR", e.getMessage(), e);
        }
    }

    private void findJavaFiles(File dir, List<String> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) findJavaFiles(f, result);
            else if (f.getName().endsWith(".java")) result.add(f.getAbsolutePath());
        }
    }

    @ReactMethod
    public void convertToDex(String classDir, String outputDir, String extraClasspath, Promise promise) {
        try {
            File outDir = new File(outputDir);
            outDir.mkdirs();
            String d8 = ctx.getFilesDir() + "/build-tools/d8.jar";

            if (!new File(d8).exists()) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("success", false);
                map.putString("error", "D8 compiler not found at " + d8);
                promise.resolve(map);
                return;
            }

            File dir = new File(classDir);
            List<String> classFiles = new ArrayList<>();
            findClassFiles(dir, classFiles);

            if (classFiles.isEmpty()) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("success", false);
                map.putString("error", "No .class files found in " + classDir);
                promise.resolve(map);
                return;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("dalvikvm");
            cmd.add("-Xmx256m");
            cmd.add("-cp");
            cmd.add(d8);
            cmd.add("com.android.tools.r8.D8");
            cmd.add("--min-api");
            cmd.add("26");
            cmd.add("--output");
            cmd.add(outputDir);
            if (extraClasspath != null && !extraClasspath.isEmpty()) {
                for (String cp : extraClasspath.split(":")) {
                    if (!cp.isEmpty()) {
                        cmd.add("--lib");
                        cmd.add(cp);
                    }
                }
            }
            cmd.addAll(classFiles);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String ln;
            while ((ln = reader.readLine()) != null) out.append(ln).append("\\n");
            int exitCode = p.waitFor();

            WritableMap map = Arguments.createMap();
            if (exitCode == 0) {
                map.putBoolean("success", true);
                map.putString("dexPath", outputDir + "/classes.dex");
            } else {
                map.putBoolean("success", false);
                map.putString("error", out.toString());
            }
            promise.resolve(map);
        } catch (Exception e) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("success", false);
            map.putString("error", e.getMessage());
            promise.resolve(map);
        }
    }

    private void findClassFiles(File dir, List<String> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) findClassFiles(f, result);
            else if (f.getName().endsWith(".class")) result.add(f.getAbsolutePath());
        }
    }

    @ReactMethod
    public void packageApk(
        String projectDir,
        String packageName,
        String appName,
        int versionCode,
        String versionName,
        int minSdk,
        int targetSdk,
        ReadableArray permissions,
        ReadableArray activities,
        String outputPath,
        Promise promise
    ) {
        try {
            List<String> permList = new ArrayList<>();
            for (int i = 0; i < permissions.size(); i++) permList.add(permissions.getString(i));
            List<String[]> actList = new ArrayList<>();
            for (int i = 0; i < activities.size(); i++) {
                ReadableMap act = activities.getMap(i);
                actList.add(new String[]{
                    act.getString("name"),
                    act.getBoolean("exported") ? "true" : "false",
                    act.getBoolean("launcher") ? "true" : "false"
                });
            }

            byte[] manifestBytes = BinaryManifestWriter.encode(
                packageName, versionCode, versionName, minSdk, targetSdk,
                appName, permList, actList
            );

            File dexFile = new File(projectDir, "build/dex/classes.dex");

            if (!dexFile.exists()) {
                promise.reject("PACKAGE_ERROR", "classes.dex not found at " + dexFile.getAbsolutePath());
                return;
            }

            File output = new File(outputPath);
            output.getParentFile().mkdirs();

            Map<String, File> extras = new HashMap<>();
            File assetsDir = new File(projectDir, "assets");
            if (assetsDir.isDirectory()) collectFiles(assetsDir, "assets", extras);

            ApkPackager.packageApk(manifestBytes, dexFile, output, extras);
            promise.resolve(output.getAbsolutePath());
        } catch (Exception e) {
            promise.reject("PACKAGE_ERROR", e.getMessage(), e);
        }
    }

    private void collectFiles(File dir, String prefix, Map<String, File> map) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String p = prefix + "/" + f.getName();
            if (f.isDirectory()) collectFiles(f, p, map);
            else map.put(p, f);
        }
    }

    @ReactMethod
    public void signApk(String unsignedPath, Promise promise) {
        try {
            if (!new File(unsignedPath).exists()) {
                promise.reject("SIGN_ERROR", "APK not found: " + unsignedPath);
                return;
            }
            ApkSignerV1 signer = new ApkSignerV1(ctx);
            String signedPath = signer.sign(unsignedPath);
            promise.resolve(signedPath);
        } catch (Exception e) {
            promise.reject("SIGN_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void installApk(String apkPath, Promise promise) {
        try {
            File apk = new File(apkPath);
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
            promise.resolve("Install prompt shown");
        } catch (Exception e) {
            promise.reject("INSTALL_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void exec(String command, String workDir, Promise promise) {
        try {
            if (!isCommandAllowed(command)) {
                logExec(command, "BLOCKED");
                promise.reject("EXEC_BLOCKED", "Command not in allowlist: " + command.split(" ")[0]);
                return;
            }
            logExec(command, "ALLOWED");
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            if (workDir != null && !workDir.isEmpty()) pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\\n");
            int exitCode = p.waitFor();
            if (exitCode == 0) promise.resolve(sb.toString());
            else promise.reject("EXEC_ERROR", "Exit " + exitCode + ": " + sb.toString());
        } catch (Exception e) {
            promise.reject("EXEC_ERROR", e.getMessage(), e);
        }
    }

    private boolean isCommandAllowed(String command) {
        String trimmed = command.trim();
        boolean prefixOk = false;
        for (String allowed : ALLOWED_COMMANDS) {
            if (trimmed.startsWith(allowed + " ") || trimmed.equals(allowed)) {
                prefixOk = true;
                break;
            }
        }
        if (!prefixOk) return false;
        for (String meta : BLOCKED_METACHAR) {
            if (trimmed.contains(meta)) return false;
        }
        return true;
    }

    private void logExec(String command, String status) {
        try {
            File logFile = new File(ctx.getFilesDir(), "exec_audit.log");
            FileOutputStream fos = new FileOutputStream(logFile, true);
            String entry = System.currentTimeMillis() + " [" + status + "] " + command + "\\n";
            fos.write(entry.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) {}
    }

    @ReactMethod
    public void getStorageInfo(Promise promise) {
        try {
            File data = ctx.getFilesDir();
            long total = data.getTotalSpace();
            long free = data.getFreeSpace();
            long used = total - free;
            WritableMap map = Arguments.createMap();
            map.putDouble("total", total);
            map.putDouble("free", free);
            map.putDouble("used", used);
            promise.resolve(map);
        } catch (Exception e) {
            promise.reject("STORAGE_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void isAllFilesAccessGranted(Promise promise) {
        try {
            boolean granted = true;
            if (Build.VERSION.SDK_INT >= 30) {
                granted = android.os.Environment.isExternalStorageManager();
            }
            promise.resolve(granted);
        } catch (Exception e) {
            promise.reject("STORAGE_CHECK_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void getInstalledApps(Promise promise) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.Intent launcherIntent = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            launcherIntent.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> activities = pm.queryIntentActivities(launcherIntent, 0);
            WritableArray result = Arguments.createArray();
            for (android.content.pm.ResolveInfo info : activities) {
                WritableMap app = Arguments.createMap();
                app.putString("packageName", info.activityInfo.packageName);
                app.putString("appName", info.loadLabel(pm).toString());
                result.pushMap(app);
            }
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("GET_APPS_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void launchApp(String packageName, Promise promise) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                WritableMap map = Arguments.createMap();
                map.putBoolean("success", false);
                map.putString("error", "Package not found or not launchable: " + packageName);
                promise.resolve(map);
                return;
            }
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launchIntent);
            WritableMap map = Arguments.createMap();
            map.putBoolean("success", true);
            map.putString("packageName", packageName);
            promise.resolve(map);
        } catch (Exception e) {
            WritableMap map = Arguments.createMap();
            map.putBoolean("success", false);
            map.putString("error", e.getMessage());
            promise.resolve(map);
        }
    }

    @ReactMethod
    public void sendMediaKey(int keyCode, Promise promise) {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE);
            long now = android.os.SystemClock.uptimeMillis();
            android.view.KeyEvent down = new android.view.KeyEvent(
                now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
            android.view.KeyEvent up = new android.view.KeyEvent(
                now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0);
            am.dispatchMediaKeyEvent(down);
            am.dispatchMediaKeyEvent(up);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("MEDIA_KEY_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void setFlashlight(boolean on, Promise promise) {
        try {
            android.hardware.camera2.CameraManager cm =
                (android.hardware.camera2.CameraManager) ctx.getSystemService(android.content.Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            for (String id : ids) {
                android.hardware.camera2.CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                Boolean hasFlash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    cm.setTorchMode(id, on);
                    promise.resolve(true);
                    return;
                }
            }
            promise.reject("NO_FLASH", "No flash available");
        } catch (Exception e) {
            promise.reject("FLASH_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void sendSms(String phoneNumber, String message, Promise promise) {
        try {
            android.telephony.SmsManager sms;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                sms = ctx.getSystemService(android.telephony.SmsManager.class);
            } else {
                sms = android.telephony.SmsManager.getDefault();
            }
            if (sms == null) {
                promise.reject("SMS_ERROR", "SmsManager unavailable");
                return;
            }
            java.util.ArrayList<String> parts = sms.divideMessage(message);
            if (parts.size() == 1) {
                sms.sendTextMessage(phoneNumber, null, message, null, null);
            } else {
                sms.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
            }
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("SMS_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void readSms(int limit, String filter, Promise promise) {
        try {
            android.database.Cursor cursor = ctx.getContentResolver().query(
                android.net.Uri.parse("content://sms/inbox"),
                new String[]{"_id", "address", "body", "date", "read"},
                null, null,
                "date DESC LIMIT " + Math.min(limit, 50)
            );
            if (cursor == null) {
                promise.reject("SMS_READ_ERROR", "SMS cursor null — permission may be denied");
                return;
            }
            com.facebook.react.bridge.WritableArray result = com.facebook.react.bridge.Arguments.createArray();
            while (cursor.moveToNext()) {
                com.facebook.react.bridge.WritableMap msg = com.facebook.react.bridge.Arguments.createMap();
                msg.putString("id", cursor.getString(0));
                msg.putString("address", cursor.getString(1));
                String body = cursor.getString(2);
                msg.putString("body", body != null && body.length() > 500 ? body.substring(0, 500) + "..." : (body != null ? body : ""));
                msg.putDouble("date", cursor.getLong(3));
                msg.putBoolean("read", cursor.getInt(4) == 1);
                result.pushMap(msg);
            }
            cursor.close();
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("SMS_READ_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void readSmsConversation(String address, int limit, Promise promise) {
        try {
            android.database.Cursor cursor = ctx.getContentResolver().query(
                android.net.Uri.parse("content://sms"),
                new String[]{"_id", "address", "body", "date", "type"},
                "address LIKE ?",
                new String[]{"%" + address.replaceAll("[^0-9+]", "") + "%"},
                "date DESC LIMIT " + Math.min(limit, 30)
            );
            if (cursor == null) {
                promise.reject("SMS_READ_ERROR", "SMS cursor null");
                return;
            }
            com.facebook.react.bridge.WritableArray result = com.facebook.react.bridge.Arguments.createArray();
            while (cursor.moveToNext()) {
                com.facebook.react.bridge.WritableMap msg = com.facebook.react.bridge.Arguments.createMap();
                msg.putString("id", cursor.getString(0));
                msg.putString("address", cursor.getString(1));
                String body = cursor.getString(2);
                msg.putString("body", body != null && body.length() > 500 ? body.substring(0, 500) + "..." : (body != null ? body : ""));
                msg.putDouble("date", cursor.getLong(3));
                msg.putString("direction", cursor.getInt(4) == 1 ? "received" : "sent");
                result.pushMap(msg);
            }
            cursor.close();
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("SMS_READ_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void readCalendarEvents(double startMs, double endMs, int limit, Promise promise) {
        try {
            android.database.Cursor cursor = ctx.getContentResolver().query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                new String[]{
                    android.provider.CalendarContract.Events._ID,
                    android.provider.CalendarContract.Events.TITLE,
                    android.provider.CalendarContract.Events.DTSTART,
                    android.provider.CalendarContract.Events.DTEND,
                    android.provider.CalendarContract.Events.ALL_DAY,
                    android.provider.CalendarContract.Events.EVENT_LOCATION,
                    android.provider.CalendarContract.Events.DESCRIPTION,
                    android.provider.CalendarContract.Events.CALENDAR_DISPLAY_NAME
                },
                android.provider.CalendarContract.Events.DTSTART + " >= ? AND " +
                android.provider.CalendarContract.Events.DTSTART + " <= ?",
                new String[]{String.valueOf((long)startMs), String.valueOf((long)endMs)},
                android.provider.CalendarContract.Events.DTSTART + " ASC"
            );
            com.facebook.react.bridge.WritableArray result = com.facebook.react.bridge.Arguments.createArray();
            int count = 0;
            if (cursor != null) {
                while (cursor.moveToNext() && count < Math.min(limit, 50)) {
                    com.facebook.react.bridge.WritableMap ev = com.facebook.react.bridge.Arguments.createMap();
                    ev.putString("id", cursor.getString(0));
                    ev.putString("title", cursor.getString(1) != null ? cursor.getString(1) : "");
                    ev.putDouble("startDate", cursor.getLong(2));
                    ev.putDouble("endDate", cursor.getLong(3));
                    ev.putBoolean("allDay", cursor.getInt(4) == 1);
                    ev.putString("location", cursor.getString(5) != null ? cursor.getString(5) : "");
                    ev.putString("description", cursor.getString(6) != null ? cursor.getString(6) : "");
                    ev.putString("calendar", cursor.getString(7) != null ? cursor.getString(7) : "");
                    result.pushMap(ev);
                    count++;
                }
                cursor.close();
            }
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("CALENDAR_READ_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void readCallLog(int limit, Promise promise) {
        try {
            android.database.Cursor cursor = ctx.getContentResolver().query(
                android.provider.CallLog.Calls.CONTENT_URI,
                new String[]{
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.CACHED_NAME,
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.DURATION
                },
                null, null,
                android.provider.CallLog.Calls.DATE + " DESC LIMIT " + Math.min(limit, 50)
            );
            com.facebook.react.bridge.WritableArray result = com.facebook.react.bridge.Arguments.createArray();
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    com.facebook.react.bridge.WritableMap call = com.facebook.react.bridge.Arguments.createMap();
                    call.putString("number", cursor.getString(0) != null ? cursor.getString(0) : "");
                    call.putString("name", cursor.getString(1) != null ? cursor.getString(1) : "");
                    call.putInt("type", cursor.getInt(2));
                    call.putDouble("date", cursor.getLong(3));
                    call.putInt("duration", cursor.getInt(4));
                    result.pushMap(call);
                }
                cursor.close();
            }
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("CALL_LOG_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void getWifiSSID(Promise promise) {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) ctx.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            if (info == null) { promise.resolve(null); return; }
            String ssid = info.getSSID();
            if (ssid == null || ssid.equals("<unknown ssid>")) { promise.resolve(null); return; }
            if (ssid.length() >= 2 && ssid.charAt(0) == 0x22 && ssid.charAt(ssid.length() - 1) == 0x22) ssid = ssid.substring(1, ssid.length() - 1);
            promise.resolve(ssid);
        } catch (Exception e) {
            promise.resolve(null);
        }
    }

    @ReactMethod
    public void getConnectedBluetoothDevices(Promise promise) {
        try {
            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            com.facebook.react.bridge.WritableArray result = com.facebook.react.bridge.Arguments.createArray();
            if (adapter != null && adapter.isEnabled()) {
                java.util.Set<android.bluetooth.BluetoothDevice> bonded = adapter.getBondedDevices();
                for (android.bluetooth.BluetoothDevice device : bonded) {
                    if (device.getBondState() == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                        result.pushString(device.getName() != null ? device.getName() : device.getAddress());
                    }
                }
            }
            promise.resolve(result);
        } catch (Exception e) {
            promise.resolve(com.facebook.react.bridge.Arguments.createArray());
        }
    }

    @ReactMethod
    public void startBackgroundAgent(Promise promise) {
        try {
            Intent intent = new Intent(getReactApplicationContext(), AgentBackgroundService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                getReactApplicationContext().startForegroundService(intent);
            } else {
                getReactApplicationContext().startService(intent);
            }
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("BG_AGENT_START_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void startReActTask(String goal, String appHint, String taskId, Promise promise) {
        try {
            android.app.Activity activity = getCurrentActivity();
            if (activity != null) {
                activity.moveTaskToBack(true);
                Log.i("AgentNative", "startReActTask: moveTaskToBack fired from native");
            } else {
                Log.w("AgentNative", "startReActTask: no current activity for moveTaskToBack");
            }
            Intent intent = new Intent(getReactApplicationContext(), AgentHeadlessTaskService.class);
            Bundle extras = new Bundle();
            extras.putString("taskType", "react_navigate");
            extras.putString("goal", goal != null ? goal : "");
            extras.putString("appHint", appHint != null ? appHint : "");
            extras.putString("taskId", taskId != null ? taskId : "");
            intent.putExtras(extras);
            getReactApplicationContext().startService(intent);
            Log.i("AgentNative", "startReActTask: goal=" + goal + " appHint=" + appHint + " taskId=" + taskId);
            promise.resolve(true);
        } catch (Exception e) {
            Log.e("AgentNative", "startReActTask failed: " + e.getMessage());
            promise.reject("HEADLESS_START_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void stopBackgroundAgent(Promise promise) {
        try {
            Intent intent = new Intent(getReactApplicationContext(), AgentBackgroundService.class);
            getReactApplicationContext().stopService(intent);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("BG_AGENT_STOP_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void getContentUriForFile(String filePath, Promise promise) {
        try {
            String normalized = filePath;
            if (normalized.startsWith("file://")) {
                normalized = normalized.substring(7);
            }
            java.io.File file = new java.io.File(normalized);
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                getReactApplicationContext(),
                BuildConfig.APPLICATION_ID + ".fileprovider",
                file
            );
            promise.resolve(uri.toString());
        } catch (Exception e) {
            promise.reject("CONTENT_URI_ERROR", e.getMessage(), e);
        }
    }
}`;

const BINARY_MANIFEST_WRITER_JAVA = `package com.agent.ultra;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BinaryManifestWriter {

    private static final int CHUNK_AXML = 0x00080003;
    private static final int CHUNK_STRING_POOL = 0x001C0001;
    private static final int CHUNK_RESOURCE_IDS = 0x00080180;
    private static final int CHUNK_START_NS = 0x00100100;
    private static final int CHUNK_END_NS = 0x00100101;
    private static final int CHUNK_START_TAG = 0x00100102;
    private static final int CHUNK_END_TAG = 0x00100103;

    private static final int ANDROID_NS_IDX = 0;
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static final int RES_PACKAGE = 0x01010003;
    private static final int RES_VERSION_CODE = 0x0101021b;
    private static final int RES_VERSION_NAME = 0x0101021c;
    private static final int RES_MIN_SDK = 0x0101020c;
    private static final int RES_TARGET_SDK = 0x01010270;
    private static final int RES_NAME = 0x01010003;
    private static final int RES_LABEL = 0x01010001;
    private static final int RES_ALLOW_BACKUP = 0x01010280;
    private static final int RES_EXPORTED = 0x01010010;

    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT = 0x10;
    private static final int TYPE_BOOL = 0x12;
    private static final int TYPE_REFERENCE = 0x01;

    public static byte[] encode(
        String packageName, int versionCode, String versionName,
        int minSdk, int targetSdk, String appLabel,
        List<String> permissions, List<String[]> activities
    ) throws IOException {
        List<String> strings = new ArrayList<>();
        Map<String, Integer> stringIdx = new HashMap<>();
        List<Integer> resourceIds = new ArrayList<>();

        strings.add(ANDROID_NS);
        stringIdx.put(ANDROID_NS, 0);

        String[] baseStrings = {
            "", "manifest", "package", "versionCode", "versionName",
            "platformBuildVersionCode", "platformBuildVersionName",
            "uses-sdk", "minSdkVersion", "targetSdkVersion",
            "uses-permission", "name",
            "application", "label", "allowBackup",
            "activity", "exported",
            "intent-filter", "action", "category",
            "android.intent.action.MAIN", "android.intent.category.LAUNCHER",
            packageName, String.valueOf(versionCode), versionName,
            appLabel
        };
        for (String s : baseStrings) {
            if (!stringIdx.containsKey(s)) {
                stringIdx.put(s, strings.size());
                strings.add(s);
            }
        }
        for (String perm : permissions) {
            if (!stringIdx.containsKey(perm)) {
                stringIdx.put(perm, strings.size());
                strings.add(perm);
            }
        }
        for (String[] act : activities) {
            if (!stringIdx.containsKey(act[0])) {
                stringIdx.put(act[0], strings.size());
                strings.add(act[0]);
            }
        }

        resourceIds.add(RES_PACKAGE);
        resourceIds.add(RES_VERSION_CODE);
        resourceIds.add(RES_VERSION_NAME);
        resourceIds.add(RES_MIN_SDK);
        resourceIds.add(RES_TARGET_SDK);
        resourceIds.add(RES_NAME);
        resourceIds.add(RES_LABEL);
        resourceIds.add(RES_ALLOW_BACKUP);
        resourceIds.add(RES_EXPORTED);

        ByteArrayOutputStream body = new ByteArrayOutputStream();

        writeStringPool(body, strings);
        writeResourceIds(body, resourceIds);

        int nsIdx = stringIdx.get(ANDROID_NS);
        int androidStr = ensureString(strings, stringIdx, "android");
        writeNamespace(body, CHUNK_START_NS, androidStr, nsIdx);

        int manifestIdx = stringIdx.get("manifest");
        writeStartTag(body, -1, manifestIdx, new int[][]{
            {-1,     stringIdx.get("package"),     0, TYPE_STRING, stringIdx.get(packageName)},
            {nsIdx,  stringIdx.get("versionCode"), 0, TYPE_INT,    versionCode},
            {nsIdx,  stringIdx.get("versionName"), 0, TYPE_STRING, stringIdx.get(versionName)},
        });

        int usesSdkIdx = stringIdx.get("uses-sdk");
        writeStartTag(body, -1, usesSdkIdx, new int[][]{
            {nsIdx, stringIdx.get("minSdkVersion"),    0, TYPE_INT, minSdk},
            {nsIdx, stringIdx.get("targetSdkVersion"), 0, TYPE_INT, targetSdk},
        });
        writeEndTag(body, -1, usesSdkIdx);

        int usesPermIdx = stringIdx.get("uses-permission");
        int nameIdx2 = stringIdx.get("name");
        for (String perm : permissions) {
            writeStartTag(body, -1, usesPermIdx, new int[][]{
                {nsIdx, nameIdx2, 0, TYPE_STRING, stringIdx.get(perm)},
            });
            writeEndTag(body, -1, usesPermIdx);
        }

        int appIdx = stringIdx.get("application");
        int labelIdx = stringIdx.get("label");
        int allowBackupIdx = stringIdx.get("allowBackup");
        writeStartTag(body, -1, appIdx, new int[][]{
            {nsIdx, labelIdx,       0, TYPE_STRING, stringIdx.get(appLabel)},
            {nsIdx, allowBackupIdx, 0, TYPE_BOOL,   0xFFFFFFFF},
        });

        int actIdx = stringIdx.get("activity");
        int exportedIdx = stringIdx.get("exported");
        int ifIdx = stringIdx.get("intent-filter");
        int actionIdx = stringIdx.get("action");
        int catIdx = stringIdx.get("category");
        int mainActionIdx = stringIdx.get("android.intent.action.MAIN");
        int launcherCatIdx = stringIdx.get("android.intent.category.LAUNCHER");

        for (String[] act : activities) {
            boolean isExported = "true".equals(act[1]);
            boolean isLauncher = "true".equals(act[2]);
            writeStartTag(body, -1, actIdx, new int[][]{
                {nsIdx, nameIdx2,    0, TYPE_STRING, stringIdx.get(act[0])},
                {nsIdx, exportedIdx, 0, TYPE_BOOL,   isExported ? 0xFFFFFFFF : 0},
            });
            if (isLauncher) {
                writeStartTag(body, -1, ifIdx, new int[][]{});
                writeStartTag(body, -1, actionIdx, new int[][]{
                    {nsIdx, nameIdx2, 0, TYPE_STRING, mainActionIdx},
                });
                writeEndTag(body, -1, actionIdx);
                writeStartTag(body, -1, catIdx, new int[][]{
                    {nsIdx, nameIdx2, 0, TYPE_STRING, launcherCatIdx},
                });
                writeEndTag(body, -1, catIdx);
                writeEndTag(body, -1, ifIdx);
            }
            writeEndTag(body, -1, actIdx);
        }

        writeEndTag(body, -1, appIdx);
        writeEndTag(body, -1, manifestIdx);
        writeNamespace(body, CHUNK_END_NS, androidStr, nsIdx);

        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream full = new ByteArrayOutputStream();
        writeInt(full, CHUNK_AXML);
        writeInt(full, bodyBytes.length + 8);
        full.write(bodyBytes);
        return full.toByteArray();
    }

    private static int ensureString(List<String> strings, Map<String, Integer> idx, String s) {
        if (idx.containsKey(s)) return idx.get(s);
        int i = strings.size();
        strings.add(s);
        idx.put(s, i);
        return i;
    }

    private static void writeStringPool(ByteArrayOutputStream out, List<String> strings) throws IOException {
        int count = strings.size();
        byte[][] encoded = new byte[count][];
        int dataSize = 0;
        for (int i = 0; i < count; i++) {
            byte[] utf8 = strings.get(i).getBytes(StandardCharsets.UTF_8);
            int charLen = strings.get(i).length();
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            if (charLen > 0x7F) { entry.write((charLen >> 8) | 0x80); entry.write(charLen & 0xFF); }
            else entry.write(charLen);
            if (utf8.length > 0x7F) { entry.write((utf8.length >> 8) | 0x80); entry.write(utf8.length & 0xFF); }
            else entry.write(utf8.length);
            entry.write(utf8);
            entry.write(0);
            encoded[i] = entry.toByteArray();
            dataSize += encoded[i].length;
        }

        int offsetsSize = count * 4;
        int headerSize = 28;
        int stringsStart = headerSize + offsetsSize;
        int totalSize = stringsStart + dataSize;
        totalSize = (totalSize + 3) & ~3;

        writeInt(out, CHUNK_STRING_POOL);
        writeInt(out, totalSize);
        writeInt(out, count);
        writeInt(out, 0);
        writeInt(out, 0x100);
        writeInt(out, stringsStart);
        writeInt(out, 0);

        int offset = 0;
        for (int i = 0; i < count; i++) {
            writeInt(out, offset);
            offset += encoded[i].length;
        }
        for (byte[] e : encoded) out.write(e);
        int pad = totalSize - stringsStart - dataSize;
        for (int i = 0; i < pad; i++) out.write(0);
    }

    private static void writeResourceIds(ByteArrayOutputStream out, List<Integer> ids) throws IOException {
        writeInt(out, CHUNK_RESOURCE_IDS);
        writeInt(out, 8 + ids.size() * 4);
        for (int id : ids) writeInt(out, id);
    }

    private static void writeNamespace(ByteArrayOutputStream out, int type, int prefixIdx, int uriIdx) throws IOException {
        writeInt(out, type);
        writeInt(out, 24);
        writeInt(out, 0);
        writeInt(out, 0xFFFFFFFF);
        writeInt(out, prefixIdx);
        writeInt(out, uriIdx);
    }

    private static void writeStartTag(ByteArrayOutputStream out, int nsIdx, int nameIdx, int[][] attrs) throws IOException {
        int attrCount = attrs.length;
        int size = 36 + attrCount * 20;
        writeInt(out, CHUNK_START_TAG);
        writeInt(out, size);
        writeInt(out, 0);
        writeInt(out, 0xFFFFFFFF);
        writeInt(out, nsIdx);
        writeInt(out, nameIdx);
        writeInt(out, 0x00140014);
        writeShort(out, attrCount);
        writeShort(out, 0);

        for (int[] attr : attrs) {
            writeInt(out, attr[0]);
            writeInt(out, attr[1]);
            int rawValue = attr[3] == TYPE_STRING ? attr[4] : -1;
            writeInt(out, rawValue);
            writeShort(out, 8);
            out.write(0);
            out.write(attr[3]);
            writeInt(out, attr[4]);
        }
    }

    private static void writeEndTag(ByteArrayOutputStream out, int nsIdx, int nameIdx) throws IOException {
        writeInt(out, CHUNK_END_TAG);
        writeInt(out, 24);
        writeInt(out, 0);
        writeInt(out, 0xFFFFFFFF);
        writeInt(out, nsIdx);
        writeInt(out, nameIdx);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }
}`;

const APK_PACKAGER_JAVA = `package com.agent.ultra;

import java.io.*;
import java.util.*;
import java.util.zip.*;

public class ApkPackager {

    private static final int ALIGNMENT = 4;

    public static void packageApk(byte[] binaryManifest, File dexFile, File outputApk, Map<String, File> extras) throws IOException {
        outputApk.getParentFile().mkdirs();
        FileOutputStream fos = new FileOutputStream(outputApk);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos));

        addAlignedEntry(zos, "AndroidManifest.xml", binaryManifest);

        if (dexFile.exists()) {
            addAlignedEntry(zos, "classes.dex", readFile(dexFile));
        }

        if (extras != null) {
            for (Map.Entry<String, File> entry : extras.entrySet()) {
                if (entry.getValue().exists()) {
                    addAlignedEntry(zos, entry.getKey(), readFile(entry.getValue()));
                }
            }
        }

        zos.close();
        fos.close();
    }

    private static void addAlignedEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] readFile(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) bos.write(buf, 0, len);
        fis.close();
        return bos.toByteArray();
    }
}`;

const APK_SIGNER_V1_JAVA = `package com.agent.ultra;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.zip.*;

public class ApkSignerV1 {
    private static final String TAG = "ApkSignerV1";
    private static final String KEYSTORE_FILE = "ultra_keystore.bks";
    private static final String KEY_ALIAS = "ultra";
    private static final char[] KEY_PASSWORD = "ultrakey".toCharArray();

    private final Context ctx;
    private KeyStore keyStore;
    private PrivateKey privateKey;
    private java.security.cert.X509Certificate certificate;

    public ApkSignerV1(Context context) {
        this.ctx = context;
    }

    public String sign(String unsignedPath) throws Exception {
        ensureKeyStore();

        String signedPath = unsignedPath.replace(".apk", "-signed.apk").replace(".unsigned", "");
        if (signedPath.equals(unsignedPath)) signedPath = unsignedPath + ".signed";

        Map<String, byte[]> entries = readApkEntries(unsignedPath);

        StringBuilder manifest = new StringBuilder();
        manifest.append("Manifest-Version: 1.0\\r\\n");
        manifest.append("Created-By: Agent Ultra\\r\\n\\r\\n");

        Map<String, String> entryDigests = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("META-INF/")) continue;
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(entry.getValue());
            String b64 = Base64.encodeToString(digest, Base64.NO_WRAP);
            manifest.append("Name: ").append(name).append("\\r\\n");
            manifest.append("SHA-256-Digest: ").append(b64).append("\\r\\n\\r\\n");
            entryDigests.put(name, b64);
        }

        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);

        MessageDigest mainDigest = MessageDigest.getInstance("SHA-256");
        String manifestDigest = Base64.encodeToString(mainDigest.digest(manifestBytes), Base64.NO_WRAP);

        StringBuilder sigFile = new StringBuilder();
        sigFile.append("Signature-Version: 1.0\\r\\n");
        sigFile.append("SHA-256-Digest-Manifest: ").append(manifestDigest).append("\\r\\n");
        sigFile.append("Created-By: Agent Ultra\\r\\n\\r\\n");

        for (Map.Entry<String, String> entry : entryDigests.entrySet()) {
            String section = "Name: " + entry.getKey() + "\\r\\n" +
                           "SHA-256-Digest: " + entry.getValue() + "\\r\\n\\r\\n";
            MessageDigest sd = MessageDigest.getInstance("SHA-256");
            String sectionDigest = Base64.encodeToString(sd.digest(section.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            sigFile.append("Name: ").append(entry.getKey()).append("\\r\\n");
            sigFile.append("SHA-256-Digest: ").append(sectionDigest).append("\\r\\n\\r\\n");
        }

        byte[] sigFileBytes = sigFile.toString().getBytes(StandardCharsets.UTF_8);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(sigFileBytes);
        byte[] signatureBytes = sig.sign();

        byte[] pkcs7 = buildPkcs7(certificate, signatureBytes, sigFileBytes);

        FileOutputStream fos = new FileOutputStream(signedPath);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos));

        zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
        zos.write(manifestBytes);
        zos.closeEntry();

        zos.putNextEntry(new ZipEntry("META-INF/CERT.SF"));
        zos.write(sigFileBytes);
        zos.closeEntry();

        zos.putNextEntry(new ZipEntry("META-INF/CERT.RSA"));
        zos.write(pkcs7);
        zos.closeEntry();

        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().startsWith("META-INF/")) continue;
            ZipEntry ze = new ZipEntry(entry.getKey());
            ze.setMethod(ZipEntry.STORED);
            byte[] data = entry.getValue();
            ze.setSize(data.length);
            ze.setCompressedSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            ze.setCrc(crc.getValue());
            zos.putNextEntry(ze);
            zos.write(data);
            zos.closeEntry();
        }

        zos.close();
        fos.close();

        Log.i(TAG, "APK signed with V1: " + signedPath);
        return signedPath;
    }

    private void ensureKeyStore() throws Exception {
        File ksFile = new File(ctx.getFilesDir(), KEYSTORE_FILE);
        keyStore = KeyStore.getInstance("BKS");

        if (ksFile.exists()) {
            FileInputStream fis = new FileInputStream(ksFile);
            keyStore.load(fis, KEY_PASSWORD);
            fis.close();
        } else {
            keyStore.load(null, KEY_PASSWORD);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            java.security.cert.X509Certificate cert = generateSelfSignedCert(kp);

            keyStore.setKeyEntry(KEY_ALIAS, kp.getPrivate(), KEY_PASSWORD, new java.security.cert.Certificate[]{cert});

            FileOutputStream fos = new FileOutputStream(ksFile);
            keyStore.store(fos, KEY_PASSWORD);
            fos.close();
            Log.i(TAG, "Generated new signing keystore");
        }

        privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, KEY_PASSWORD);
        certificate = (java.security.cert.X509Certificate) keyStore.getCertificate(KEY_ALIAS);
    }

    private java.security.cert.X509Certificate generateSelfSignedCert(KeyPair kp) throws Exception {
        long now = System.currentTimeMillis();
        long tenYears = 10L * 365 * 24 * 3600 * 1000;

        byte[] encoded = buildSelfSignedCertDer(kp, now, now + tenYears);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (java.security.cert.X509Certificate) cf.generateCertificate(new ByteArrayInputStream(encoded));
    }

    private byte[] buildSelfSignedCertDer(KeyPair kp, long notBefore, long notAfter) throws Exception {
        byte[] subjectDer = derSequence(
            derSet(derSequence(
                derOid(new int[]{2,5,4,3}),
                derUtf8("Agent Ultra")
            ))
        );

        byte[] serialBytes = BigInteger.valueOf(System.currentTimeMillis()).toByteArray();
        byte[] serial = derInteger(serialBytes);

        byte[] algId = derSequence(derOid(new int[]{1,2,840,113549,1,1,11}), derNull());

        byte[] validity = derSequence(
            derUtcTime(new Date(notBefore)),
            derUtcTime(new Date(notAfter))
        );

        byte[] pubKeyInfo = kp.getPublic().getEncoded();

        byte[] tbsCert = derSequence(
            derExplicit(0, derInteger(new byte[]{2})),
            serial,
            algId,
            subjectDer,
            validity,
            subjectDer,
            pubKeyInfo
        );

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbsCert);
        byte[] signature = sig.sign();

        return derSequence(tbsCert, algId, derBitString(signature));
    }

    private byte[] buildPkcs7(java.security.cert.X509Certificate cert, byte[] signature, byte[] signedData) throws Exception {
        byte[] certDer = cert.getEncoded();
        byte[] issuerAndSerial = derSequence(
            extractIssuer(certDer),
            derInteger(cert.getSerialNumber().toByteArray())
        );
        byte[] digestAlgId = derSequence(derOid(new int[]{2,16,840,1,101,3,4,2,1}), derNull());
        byte[] sigAlgId = derSequence(derOid(new int[]{1,2,840,113549,1,1,11}), derNull());

        byte[] signerInfo = derSequence(
            derInteger(new byte[]{1}),
            issuerAndSerial,
            digestAlgId,
            sigAlgId,
            derOctetString(signature)
        );

        byte[] contentInfo = derSequence(derOid(new int[]{1,2,840,113549,1,7,1}));
        byte[] signedDataSeq = derSequence(
            derInteger(new byte[]{1}),
            derSet(digestAlgId),
            contentInfo,
            derExplicit(0, certDer),
            derSet(signerInfo)
        );

        return derSequence(
            derOid(new int[]{1,2,840,113549,1,7,2}),
            derExplicit(0, signedDataSeq)
        );
    }

    private byte[] extractIssuer(byte[] certDer) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate c = (java.security.cert.X509Certificate)
                cf.generateCertificate(new ByteArrayInputStream(certDer));
            return c.getIssuerX500Principal().getEncoded();
        } catch (Exception e) {
            return derSequence(derSet(derSequence(derOid(new int[]{2,5,4,3}), derUtf8("Agent Ultra"))));
        }
    }

    private Map<String, byte[]> readApkEntries(String apkPath) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath));
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = zis.read(buf)) > 0) bos.write(buf, 0, len);
            entries.put(entry.getName(), bos.toByteArray());
        }
        zis.close();
        return entries;
    }

    private static byte[] derSequence(byte[]... items) {
        return derConstructed(0x30, items);
    }
    private static byte[] derSet(byte[]... items) {
        return derConstructed(0x31, items);
    }
    private static byte[] derConstructed(int tag, byte[]... items) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] item : items) {
            try { content.write(item); } catch (IOException ignored) {}
        }
        byte[] body = content.toByteArray();
        return derTlv(tag, body);
    }
    private static byte[] derExplicit(int tagNum, byte[]... items) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] item : items) {
            try { content.write(item); } catch (IOException ignored) {}
        }
        return derTlv(0xA0 | tagNum, content.toByteArray());
    }
    private static byte[] derInteger(byte[] val) { return derTlv(0x02, val); }
    private static byte[] derBitString(byte[] val) {
        byte[] body = new byte[val.length + 1];
        body[0] = 0;
        System.arraycopy(val, 0, body, 1, val.length);
        return derTlv(0x03, body);
    }
    private static byte[] derOctetString(byte[] val) { return derTlv(0x04, val); }
    private static byte[] derNull() { return new byte[]{0x05, 0x00}; }
    private static byte[] derOid(int[] oid) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(oid[0] * 40 + oid[1]);
        for (int i = 2; i < oid.length; i++) {
            int v = oid[i];
            if (v < 128) b.write(v);
            else {
                Stack<Integer> stack = new Stack<>();
                while (v > 0) { stack.push(v & 0x7F); v >>= 7; }
                while (!stack.isEmpty()) {
                    int octet = stack.pop();
                    if (!stack.isEmpty()) octet |= 0x80;
                    b.write(octet);
                }
            }
        }
        return derTlv(0x06, b.toByteArray());
    }
    private static byte[] derUtf8(String s) { return derTlv(0x0C, s.getBytes(StandardCharsets.UTF_8)); }
    @SuppressWarnings("deprecation")
    private static byte[] derUtcTime(Date d) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return derTlv(0x17, sdf.format(d).getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] derTlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 128) out.write(len);
        else if (len < 256) { out.write(0x81); out.write(len); }
        else { out.write(0x82); out.write((len >> 8) & 0xFF); out.write(len & 0xFF); }
        try { out.write(value); } catch (IOException ignored) {}
        return out.toByteArray();
    }
}`;

const NATIVE_PACKAGE_JAVA = `package com.agent.ultra;

import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.ViewManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AgentNativePackage implements ReactPackage {
    @Override
    public List<NativeModule> createNativeModules(ReactApplicationContext reactContext) {
        List<NativeModule> modules = new ArrayList<>();
        modules.add(new AgentNativeModule(reactContext));
        modules.add(new AccessibilityBridgeModule(reactContext));
        return modules;
    }

    @Override
    public List<ViewManager> createViewManagers(ReactApplicationContext reactContext) {
        return Collections.emptyList();
    }
}`;

const FILE_PROVIDER_PATHS = `<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <files-path name="builds" path="builds/" />
    <files-path name="projects" path="projects/" />
    <files-path name="maven" path="maven_cache/" />
    <cache-path name="cache" path="." />
    <external-files-path name="external" path="." />
</paths>`;


const ACCESSIBILITY_SERVICE_JAVA = `package com.agent.ultra;

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
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
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
    private static ReactApplicationContext reactContext = null;

    public static void setReactContext(ReactApplicationContext ctx) { reactContext = ctx; }
    public static AgentAccessibilityService getInstance() {
        synchronized (instanceLock) { return instance; }
    }
    public static boolean isRunning() {
        synchronized (instanceLock) { return instance != null; }
    }
    public static void allowPackage(String pkg) { allowedPackages.add(pkg); }
    public static void revokePackage(String pkg) { allowedPackages.remove(pkg); }
    public static boolean isPackageAllowed(String pkg) { return allowedPackages.contains(pkg); }
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
        pendingA11yLogs.add("{\\"cat\\":\\"" + category + "\\",\\"t\\":" + ts + ",\\"data\\":" + jsonData + "}");
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
                    sb.append(e.toString()).append("\\n");
                    for (StackTraceElement el : e.getStackTrace()) { sb.append("  ").append(el.toString()).append("\\n"); }
                    Throwable cause = e.getCause();
                    if (cause != null) {
                        sb.append("Caused by: ").append(cause.toString()).append("\\n");
                        for (StackTraceElement el : cause.getStackTrace()) { sb.append("  ").append(el.toString()).append("\\n"); }
                    }
                    java.io.File f = new java.io.File(getFilesDir(), "ultra_crash.log");
                    java.io.FileWriter fw = new java.io.FileWriter(f, true);
                    fw.write("\\n=== CRASH " + new java.util.Date().toString() + " thread=" + t.getName() + " ===\\n");
                    fw.write(sb.toString());
                    fw.close();
                    emitA11yLog("CRASH_NATIVE", "{\\"thread\\":\\"" + t.getName() + "\\",\\"error\\":\\"" +
                        e.toString().replace("\\"", "'").replace("\\n", " ").replace("\\\\", "") + "\\"}");
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
            String prevPkg = currentPackage;
            currentPackage = event.getPackageName().toString();
            if (!currentPackage.equals(prevPkg) && !"com.android.systemui".equals(currentPackage) && !"com.samsung.android.honeyboard".equals(currentPackage)) {
                Log.i(TAG, "PKG_CHANGE: " + prevPkg + " -> " + currentPackage);
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
                        emitA11yLog("A11Y_WINDOW", "{\\"pkg\\":\\"" + pkg + "\\",\\"cls\\":\\"" + cls + "\\"}");
                    }
                    break;
                }
                case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED: {
                    String pkg = currentPackage;
                    java.util.List<CharSequence> tl = event.getText();
                    String txt = (tl != null && !tl.isEmpty()) ? tl.get(0).toString() : "";
                    if (txt.length() > 100) txt = txt.substring(0, 100);
                    if (txt.matches(".*[A-Za-z0-9_-]{20,}.*")) { txt = "[REDACTED_TOKEN]"; }
                    txt = txt.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"").replace("\\n", " ");
                    emitA11yLog("A11Y_NOTIF", "{\\"pkg\\":\\"" + pkg + "\\",\\"text\\":\\"" + txt + "\\"}");
                    break;
                }
                case AccessibilityEvent.TYPE_VIEW_CLICKED: {
                    String pkg = currentPackage;
                    boolean isOwnApp = "com.agent.ultra".equals(pkg);
                    boolean isSystemUi = "com.android.systemui".equals(pkg);
                    boolean isAllowed = isOwnApp || isSystemUi || isPackageAllowed(pkg);
                    String cls = event.getClassName() != null ? event.getClassName().toString() : "null";
                    if (isAllowed) {
                        java.util.List<CharSequence> tl = event.getText();
                        String txt = (tl != null && !tl.isEmpty()) ? tl.get(0).toString() : "";
                        if (txt.length() > 50) txt = txt.substring(0, 50);
                        if (txt.matches(".*[A-Za-z0-9_-]{20,}.*")) { txt = "[REDACTED_LONG_TOKEN]"; }
                        txt = txt.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"").replace("\\n", " ");
                        String desc = event.getContentDescription() != null ? event.getContentDescription().toString() : "";
                        if (desc.length() > 50) desc = desc.substring(0, 50);
                        if (desc.matches(".*[A-Za-z0-9_-]{20,}.*")) { desc = "[REDACTED]"; }
                        desc = desc.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"").replace("\\n", " ");
                        emitA11yLog("A11Y_CLICK", "{\\"pkg\\":\\"" + pkg + "\\",\\"cls\\":\\"" + cls + "\\",\\"text\\":\\"" + txt + "\\",\\"desc\\":\\"" + desc + "\\"}");
                    } else {
                        emitA11yLog("A11Y_CLICK", "{\\"pkg\\":\\"" + pkg + "\\",\\"cls\\":\\"" + cls + "\\",\\"text\\":\\"[external]\\",\\"desc\\":\\"[external]\\"}");
                    }
                    break;
                }
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                    long now = System.currentTimeMillis();
                    if (now - lastContentChangedLog > CONTENT_THROTTLE_MS) {
                        lastContentChangedLog = now;
                        emitA11yLog("A11Y_CONTENT", "{\\"pkg\\":\\"" + currentPackage + "\\"}");
                    }
                    break;
                }
            }
        } catch (Exception ignored) { }
    }

    private void emitUiTreeChanged() {
        if (reactContext == null || !reactContext.hasActiveCatalystInstance()) return;
        try {
            WritableMap payload = Arguments.createMap();
            payload.putString("packageName", currentPackage);
            payload.putDouble("timestamp", System.currentTimeMillis());
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("onUiTreeChanged", payload);
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

    public String getScreenContent() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "{}";
            JSONObject tree = nodeToJson(root, 0, 5);
            root.recycle();
            return tree.toString();
        } catch (Exception e) {
            Log.e(TAG, "getScreenContent error", e);
            return "{\\"error\\":\\"" + e.getMessage() + "\\"}";
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
                AccessibilityNodeInfo root = null;
                try {
                    java.util.List<AccessibilityWindowInfo> windows = getWindows();
                    // First pass: find type=1 (application) window that isn't Agent Ultra
                    for (AccessibilityWindowInfo w : windows) {
                        if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                            AccessibilityNodeInfo wRoot = w.getRoot();
                            if (wRoot != null) {
                                CharSequence pkg = wRoot.getPackageName();
                                if (pkg == null || !"com.agent.ultra".contentEquals(pkg)) {
                                    root = wRoot;
                                    Log.i(TAG, "SCREEN_FLAT: using_window pkg=" + pkg + " layer=" + w.getLayer());
                                    break;
                                }
                                wRoot.recycle();
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "SCREEN_FLAT: window scan failed: " + e.getMessage());
                }
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
                if (root != null) {
                    CharSequence rootPkg = root.getPackageName();
                    Log.i(TAG, "SCREEN_FLAT: root_pkg=" + (rootPkg != null ? rootPkg.toString() : "null"));
                    dumpWindowStack();
                    JSONArray flat = new JSONArray();
                    flattenNode(root, flat);
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

    private void flattenNode(AccessibilityNodeInfo node, JSONArray flat) {
        if (node == null) return;
        String text = node.getText() != null ? node.getText().toString().trim() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : "";
        boolean hasContent = !text.isEmpty() || !desc.isEmpty();
        boolean interactive = node.isClickable() || node.isScrollable() || node.isEditable();
        if (hasContent || interactive) {
            try {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() > 0 && bounds.height() > 0) {
                    JSONObject obj = new JSONObject();
                    obj.put("i", flat.length());
                    obj.put("t", text);
                    obj.put("d", desc);
                    obj.put("c", node.isClickable());
                    obj.put("e", node.isEditable());
                    obj.put("s", node.isScrollable());
                    obj.put("x", bounds.centerX());
                    obj.put("y", bounds.centerY());
                    flat.put(obj);
                }
            } catch (Exception ignored) {}
        }
        for (int i = 0; i < Math.min(node.getChildCount(), 60); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                flattenNode(child, flat);
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
    private boolean checkPackageAllowed() {
        // Never allow actions on Agent Ultra's own UI — prevents self-interaction
        if ("com.agent.ultra".equals(currentPackage)) {
            emitA11yLog("A11Y_GATE", "{\\"action\\":\\"BLOCKED_SELF\\",\\"pkg\\":\\"" + currentPackage + "\\"}");
            Log.i(TAG, "GATE: BLOCKED_SELF pkg=" + currentPackage);
            return false;
        }
        if (isPackageBlocked(currentPackage)) {
            emitA11yLog("A11Y_GATE", "{\\"action\\":\\"BLOCKED_USER\\",\\"pkg\\":\\"" + currentPackage + "\\"}");
            Log.i(TAG, "GATE: BLOCKED_USER pkg=" + currentPackage);
            return false;
        }
        if (!isPackageAllowed(currentPackage)) {
            allowPackage(currentPackage);
            emitA11yLog("A11Y_GATE", "{\\"action\\":\\"AUTO_ALLOWED\\",\\"pkg\\":\\"" + currentPackage + "\\"}");
            Log.i(TAG, "GATE: AUTO_ALLOWED pkg=" + currentPackage);
        }
        emitA11yLog("A11Y_GATE", "{\\"action\\":\\"PASSED\\",\\"pkg\\":\\"" + currentPackage + "\\"}");
        Log.i(TAG, "GATE: PASSED pkg=" + currentPackage);
        return true;
    }
    public boolean performTap(int x, int y) {
        if (!checkPackageAllowed()) {
            emitA11yLog("A11Y_TAP", "{\\"action\\":\\"BLOCKED\\",\\"x\\":" + x + ",\\"y\\":" + y + ",\\"pkg\\":\\"" + currentPackage + "\\"}");
            Log.i(TAG, "TAP: BLOCKED x=" + x + " y=" + y + " pkg=" + currentPackage);
            return false;
        }
        emitA11yLog("A11Y_TAP", "{\\"action\\":\\"DISPATCH\\",\\"x\\":" + x + ",\\"y\\":" + y + ",\\"pkg\\":\\"" + currentPackage + "\\"}");
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
                        emitA11yLog("A11Y_TAP", "{\\"action\\":\\"COMPLETED\\",\\"x\\":" + x + ",\\"y\\":" + y + "}");
                        Log.i(TAG, "TAP: COMPLETED x=" + x + " y=" + y);
                        success.set(true); latch.countDown();
                    }
                    @Override
                    public void onCancelled(GestureDescription g) {
                        emitA11yLog("A11Y_TAP", "{\\"action\\":\\"CANCELLED\\",\\"x\\":" + x + ",\\"y\\":" + y + "}");
                        Log.i(TAG, "TAP: CANCELLED x=" + x + " y=" + y);
                        latch.countDown();
                    }
                }, null);
            } catch (Exception e) {
                emitA11yLog("A11Y_TAP", "{\\"action\\":\\"ERROR\\",\\"x\\":" + x + ",\\"y\\":" + y + ",\\"error\\":\\"" + e.getMessage() + "\\"}");
                Log.i(TAG, "TAP: ERROR x=" + x + " y=" + y + " error=" + e.getMessage());
                Log.e(TAG, "performTap error", e); latch.countDown();
            }
        });
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        boolean result = success.get();
        if (!result) { emitA11yLog("A11Y_TAP", "{\\"action\\":\\"TIMEOUT_OR_FAIL\\",\\"x\\":" + x + ",\\"y\\":" + y + "}"); Log.i(TAG, "TAP: TIMEOUT_OR_FAIL x=" + x + " y=" + y); }
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
        Log.i(TAG, "TEXT: selector=" + selector + " text=" + text.substring(0, Math.min(text.length(), 30)) + " pkg=" + currentPackage);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = selector.isEmpty() ? findFocusedEditable(root) : findNode(root, selector);
        boolean result = false;
        if (target != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            target.recycle();
        }
        root.recycle();
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

    public boolean performImeAction() {
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
            return "{\\"error\\":\\"" + e.getMessage() + "\\"}";
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
}`;

const ACCESSIBILITY_BRIDGE_JAVA = `package com.agent.ultra;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import android.util.Log;

public class AccessibilityBridgeModule extends ReactContextBaseJavaModule {
    private static final String TAG = "A11yBridge";
    private final ReactApplicationContext reactContext;

    public AccessibilityBridgeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        AgentAccessibilityService.setReactContext(reactContext);
    }

    @Override
    public String getName() { return "AppController"; }

    @ReactMethod
    public void isServiceEnabled(Promise promise) {
        try {
            // Check 1: is the service registered in system settings?
            boolean settingsEnabled = false;
            try {
                String enabledServices = Settings.Secure.getString(
                    reactContext.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                );
                settingsEnabled = !TextUtils.isEmpty(enabledServices) &&
                    enabledServices.contains("com.agent.ultra");
            } catch (Exception e) {
                Log.w(TAG, "isServiceEnabled: Settings.Secure read failed: " + e.getMessage());
            }
            // Check 2: is the static instance alive? (may be null after process restart)
            boolean instanceAlive = AgentAccessibilityService.isRunning();
            // Settings.Secure is authoritative — instance may lag behind after restart
            boolean result = settingsEnabled || instanceAlive;
            if (settingsEnabled && !instanceAlive) {
                Log.i(TAG, "isServiceEnabled: service registered but instance not yet connected — returning true");
            }
            promise.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "isServiceEnabled: unexpected error: " + e.getMessage());
            // Last resort: check if instance is alive even if settings read failed
            promise.resolve(AgentAccessibilityService.isRunning());
        }
    }

    @ReactMethod
    public void openAccessibilitySettings(Promise promise) {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            reactContext.startActivity(intent);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getScreenContent(Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        try {
            promise.resolve(AgentAccessibilityService.getInstance().getScreenContent());
        } catch (Exception e) {
            promise.reject("ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getScreenContentFlat(Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        try {
            promise.resolve(AgentAccessibilityService.getInstance().getScreenContentFlat());
        } catch (Exception e) {
            promise.reject("ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void performTap(int x, int y, Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        try {
            promise.resolve(AgentAccessibilityService.getInstance().performTap(x, y));
        } catch (Exception e) {
            promise.reject("ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void performSwipe(int x1, int y1, int x2, int y2, int durationMs, Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        try {
            promise.resolve(AgentAccessibilityService.getInstance().performSwipe(x1, y1, x2, y2, durationMs));
        } catch (Exception e) {
            promise.reject("ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void waitForUiChange(int timeoutMs, Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        try {
            promise.resolve(AgentAccessibilityService.getInstance().waitForUiChange(timeoutMs));
        } catch (Exception e) {
            promise.reject("ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void performClick(String selector, Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        promise.resolve(AgentAccessibilityService.getInstance().performClick(selector));
    }

    @ReactMethod
    public void performText(String selector, String text, Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        promise.resolve(AgentAccessibilityService.getInstance().performText(selector, text));
    }

    @ReactMethod
    public void performImeAction(Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        promise.resolve(AgentAccessibilityService.getInstance().performImeAction());
    }

    @ReactMethod
    public void performScroll(String direction, Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        promise.resolve(AgentAccessibilityService.getInstance().performScroll(direction));
    }

    @ReactMethod
    public void getActivePackage(Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.resolve("");
            return;
        }
        promise.resolve(AgentAccessibilityService.getInstance().getActivePackage());
    }

    @ReactMethod
    public void performBack(Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        promise.resolve(AgentAccessibilityService.getInstance().performBack());
    }

    @ReactMethod
    public void performHome(Promise promise) {
        if (!AgentAccessibilityService.isRunning()) {
            promise.reject("NOT_RUNNING", "Accessibility service not running");
            return;
        }
        promise.resolve(AgentAccessibilityService.getInstance().performHome());
    }

    @ReactMethod
    public void allowPackage(String pkg, Promise promise) {
      AgentAccessibilityService.allowPackage(pkg);
      promise.resolve(true);
    }

    @ReactMethod
    public void revokePackage(String pkg, Promise promise) {
      AgentAccessibilityService.revokePackage(pkg);
      promise.resolve(true);
    }

    @ReactMethod
    public void performQuickSettings(Promise promise) {
        if (!AgentAccessibilityService.isRunning()) { promise.reject("NOT_RUNNING", "Service not running"); return; }
        promise.resolve(AgentAccessibilityService.getInstance().performQuickSettings());
    }

    @ReactMethod
    public void takeScreenshot(Promise promise) {
        if (!AgentAccessibilityService.isRunning()) { promise.reject("NOT_RUNNING", "Service not running"); return; }
        promise.resolve(AgentAccessibilityService.getInstance().takeScreenshot());
    }

    @ReactMethod
    public void toggleQuickSetting(String tileLabel, Promise promise) {
        if (!AgentAccessibilityService.isRunning()) { promise.reject("NOT_RUNNING", "Service not running"); return; }
        new Thread(() -> {
            boolean result = AgentAccessibilityService.getInstance().toggleQuickSetting(tileLabel);
            promise.resolve(result);
        }).start();
    }

    // P8: Direct system settings toggle via WRITE_SECURE_SETTINGS permission
    // Grant with: adb shell pm grant com.agent.ultra android.permission.WRITE_SECURE_SETTINGS
    @ReactMethod
    public void setSecureSetting(String namespace, String key, int value, Promise promise) {
        try {
            android.content.ContentResolver resolver = reactContext.getContentResolver();
            boolean success;
            if ("global".equals(namespace)) {
                success = android.provider.Settings.Global.putInt(resolver, key, value);
            } else if ("secure".equals(namespace)) {
                success = android.provider.Settings.Secure.putInt(resolver, key, value);
            } else if ("system".equals(namespace)) {
                success = android.provider.Settings.System.putInt(resolver, key, value);
            } else {
                promise.reject("INVALID_NAMESPACE", "Use 'global', 'secure', or 'system'");
                return;
            }
            Log.i(TAG, "setSecureSetting: " + namespace + "/" + key + "=" + value + " result=" + success);
            if (success) {
                // For airplane mode, broadcast the state change so radios actually respond
                if ("airplane_mode_on".equals(key)) {
                    Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                    intent.putExtra("state", value != 0);
                    reactContext.sendBroadcast(intent);
                    Log.i(TAG, "setSecureSetting: broadcast AIRPLANE_MODE_CHANGED state=" + (value != 0));
                }
                promise.resolve(true);
            } else {
                promise.reject("WRITE_FAILED", "Settings.putInt returned false — WRITE_SECURE_SETTINGS permission may not be granted");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "setSecureSetting: SecurityException — permission not granted: " + e.getMessage());
            promise.reject("PERMISSION_DENIED", "WRITE_SECURE_SETTINGS not granted. Run: adb shell pm grant com.agent.ultra android.permission.WRITE_SECURE_SETTINGS");
        } catch (Exception e) {
            Log.e(TAG, "setSecureSetting: " + e.getMessage());
            promise.reject("ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getSecureSetting(String namespace, String key, Promise promise) {
        try {
            android.content.ContentResolver resolver = reactContext.getContentResolver();
            int value;
            if ("global".equals(namespace)) {
                value = android.provider.Settings.Global.getInt(resolver, key, -1);
            } else if ("secure".equals(namespace)) {
                value = android.provider.Settings.Secure.getInt(resolver, key, -1);
            } else if ("system".equals(namespace)) {
                value = android.provider.Settings.System.getInt(resolver, key, -1);
            } else {
                promise.reject("INVALID_NAMESPACE", "Use 'global', 'secure', or 'system'");
                return;
            }
            promise.resolve(value);
        } catch (Exception e) {
            promise.reject("ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void startBackgroundService(Promise promise) {
        try {
            Intent intent = new Intent(getReactApplicationContext(), AgentBackgroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getReactApplicationContext().startForegroundService(intent);
            } else {
                getReactApplicationContext().startService(intent);
            }
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("BG_SERVICE_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void blockPackage(String pkg, Promise promise) {
        if (!AgentAccessibilityService.isRunning()) { promise.reject("NOT_RUNNING", "Service not running"); return; }
        AgentAccessibilityService.blockPackage(pkg);
        promise.resolve(true);
    }

    @ReactMethod
    public void unblockPackage(String pkg, Promise promise) {
        if (!AgentAccessibilityService.isRunning()) { promise.reject("NOT_RUNNING", "Service not running"); return; }
        AgentAccessibilityService.unblockPackage(pkg);
        promise.resolve(true);
    }

    @ReactMethod
    public void getBlockedPackages(Promise promise) {
        if (!AgentAccessibilityService.isRunning()) { promise.resolve(new com.facebook.react.bridge.WritableNativeArray()); return; }
        java.util.List<String> list = AgentAccessibilityService.getBlockedPackages();
        com.facebook.react.bridge.WritableArray arr = new com.facebook.react.bridge.WritableNativeArray();
        for (String pkg : list) arr.pushString(pkg);
        promise.resolve(arr);
    }

    @ReactMethod
    public void setVolume(String streamType, int level, Promise promise) {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                getReactApplicationContext().getSystemService(android.content.Context.AUDIO_SERVICE);
            int stream = android.media.AudioManager.STREAM_MUSIC;
            if ("ring".equalsIgnoreCase(streamType)) stream = android.media.AudioManager.STREAM_RING;
            else if ("alarm".equalsIgnoreCase(streamType)) stream = android.media.AudioManager.STREAM_ALARM;
            else if ("notification".equalsIgnoreCase(streamType)) stream = android.media.AudioManager.STREAM_NOTIFICATION;
            else if ("voice".equalsIgnoreCase(streamType) || "call".equalsIgnoreCase(streamType)) stream = android.media.AudioManager.STREAM_VOICE_CALL;
            int maxVol = am.getStreamMaxVolume(stream);
            int targetVol = (int) Math.round((level / 100.0) * maxVol);
            targetVol = Math.max(0, Math.min(targetVol, maxVol));
            am.setStreamVolume(stream, targetVol, android.media.AudioManager.FLAG_SHOW_UI);
            int actualPct = maxVol > 0 ? (int) Math.round((targetVol * 100.0) / maxVol) : 0;
            promise.resolve(actualPct);
        } catch (Exception e) {
            promise.reject("VOLUME_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getVolume(String streamType, Promise promise) {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                getReactApplicationContext().getSystemService(android.content.Context.AUDIO_SERVICE);
            int stream = android.media.AudioManager.STREAM_MUSIC;
            if ("ring".equalsIgnoreCase(streamType)) stream = android.media.AudioManager.STREAM_RING;
            else if ("alarm".equalsIgnoreCase(streamType)) stream = android.media.AudioManager.STREAM_ALARM;
            else if ("notification".equalsIgnoreCase(streamType)) stream = android.media.AudioManager.STREAM_NOTIFICATION;
            else if ("voice".equalsIgnoreCase(streamType) || "call".equalsIgnoreCase(streamType)) stream = android.media.AudioManager.STREAM_VOICE_CALL;
            int cur = am.getStreamVolume(stream);
            int max = am.getStreamMaxVolume(stream);
            int percent = max > 0 ? (int) Math.round((cur * 100.0) / max) : 0;
            promise.resolve(percent);
        } catch (Exception e) {
            promise.reject("VOLUME_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void adjustVolume(String direction, Promise promise) {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                getReactApplicationContext().getSystemService(android.content.Context.AUDIO_SERVICE);
            int adjust = "up".equalsIgnoreCase(direction)
                ? android.media.AudioManager.ADJUST_RAISE
                : android.media.AudioManager.ADJUST_LOWER;
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, adjust, android.media.AudioManager.FLAG_SHOW_UI);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int percent = max > 0 ? (int) Math.round((cur * 100.0) / max) : 0;
            promise.resolve(percent);
        } catch (Exception e) {
            promise.reject("VOLUME_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getSystemStateSnapshot(Promise promise) {
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        if (svc != null) {
            promise.resolve(svc.getSystemStateSnapshot());
        } else {
            promise.resolve("{\\"error\\":\\"service_not_running\\"}");
        }
    }

    @ReactMethod
    public void drainAccessibilityLogs(Promise promise) {
        java.util.List<String> logs = AgentAccessibilityService.drainPendingLogs();
        WritableArray arr = Arguments.createArray();
        for (String log : logs) { arr.pushString(log); }
        promise.resolve(arr);
    }

    @ReactMethod
    public void readCrashLog(Promise promise) {
        try {
            java.io.File f = new java.io.File(getReactApplicationContext().getFilesDir(), "ultra_crash.log");
            if (!f.exists()) { promise.resolve(""); return; }
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) { sb.append(line).append("\\n"); }
            br.close();
            promise.resolve(sb.toString());
        } catch (Exception e) { promise.resolve("read_error: " + e.getMessage()); }
    }

    @ReactMethod
    public void clearCrashLog(Promise promise) {
        try {
            java.io.File f = new java.io.File(getReactApplicationContext().getFilesDir(), "ultra_crash.log");
            if (f.exists()) f.delete();
            promise.resolve(true);
        } catch (Exception e) { promise.resolve(false); }
    }

    @ReactMethod
    public void heartbeatPing(Promise promise) {
        boolean alive = AgentAccessibilityService.isRunning();
        AgentAccessibilityService svc = AgentAccessibilityService.getInstance();
        String pkg = "unknown";
        if (svc != null) {
            try { pkg = svc.getCurrentPackage(); } catch (Exception ignored) {}
        }
        WritableMap map = Arguments.createMap();
        map.putBoolean("alive", alive);
        map.putString("foregroundPackage", pkg != null ? pkg : "null");
        map.putDouble("timestamp", System.currentTimeMillis());
        promise.resolve(map);
    }

    @ReactMethod
    public void updateBackgroundNotification(String text, Promise promise) {
        try {
            android.content.Intent intent = new android.content.Intent("com.agent.ultra.UPDATE_NOTIFICATION");
            intent.putExtra("text", text);
            intent.setPackage(reactContext.getPackageName());
            reactContext.sendBroadcast(intent);
            promise.resolve(true);
        } catch (Exception e) { promise.reject("NOTIF_ERR", e.getMessage()); }
    }

    @ReactMethod
    public void getA11yServiceState(Promise promise) {
        try {
            android.content.SharedPreferences prefs =
                reactContext.getSharedPreferences("ultra_a11y", android.content.Context.MODE_PRIVATE);
            WritableMap map = Arguments.createMap();
            map.putString("state", prefs.getString("state", "unknown"));
            map.putDouble("connectedAt", prefs.getLong("connected_at", 0));
            map.putDouble("interruptedAt", prefs.getLong("interrupted_at", 0));
            map.putDouble("destroyedAt", prefs.getLong("destroyed_at", 0));
            map.putDouble("lastEvent", prefs.getLong("last_event", 0));
            map.putString("lastEventPkg", prefs.getString("last_event_pkg", ""));
            long now = System.currentTimeMillis();
            long lastEvent = prefs.getLong("last_event", 0);
            map.putDouble("eventAgeSec", lastEvent > 0 ? (now - lastEvent) / 1000.0 : -1);
            promise.resolve(map);
        } catch (Exception e) {
            promise.reject("A11Y_STATE_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void moveTaskToBack(Promise promise) {
        try {
            android.app.Activity activity = getCurrentActivity();
            if (activity != null) {
                activity.moveTaskToBack(true);
                Log.i(TAG, "MOVE_TO_BACK: activity=true result=true");
                promise.resolve(true);
            } else {
                Log.i(TAG, "MOVE_TO_BACK: activity=false result=false");
                promise.resolve(false);
            }
        } catch (Exception e) {
            Log.i(TAG, "MOVE_TO_BACK: activity=unknown result=false error=" + e.getMessage());
            promise.resolve(false);
        }
    }
}`;

const BACKGROUND_SERVICE_JAVA = `package com.agent.ultra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class AgentBackgroundService extends Service {
    private static final String TAG = "AgentBgSvc";
    private static final String CHANNEL_ID = "agent_ultra_bg";
    private static final int NOTIFICATION_ID = 7001;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Agent Ultra")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setNumber(0)
            .setBadgeIconType(android.app.Notification.BADGE_ICON_NONE)
            .build();
        startForeground(NOTIFICATION_ID, notification);
        Log.i(TAG, "Background service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Background service destroyed");
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartIntent = new Intent(getApplicationContext(), AgentBackgroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(restartIntent);
        } else {
            getApplicationContext().startService(restartIntent);
        }
        super.onTaskRemoved(rootIntent);
    }

    public void updateNotification(String text) {
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                getPackageManager().getLaunchIntentForPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = b.setContentTitle("Agent Ultra")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        } catch (Exception e) {
            Log.e(TAG, "Notification update failed: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Agent Ultra Background",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps Agent Ultra running in the background");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}`;


const HEADLESS_TASK_SERVICE_JAVA = `package com.agent.ultra;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;
import javax.annotation.Nullable;

public class AgentHeadlessTaskService extends HeadlessJsTaskService {
    private static final String TAG = "AgentHeadlessTask";

    @Override
    protected HeadlessJsTaskConfig getTaskConfig(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras != null) {
            return new HeadlessJsTaskConfig(
                "AgentBackgroundTask",
                Arguments.fromBundle(extras),
                300000,
                true
            );
        }
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "AgentHeadlessTaskService destroyed");
        super.onDestroy();
    }
}`;

const ACCESSIBILITY_SERVICE_CONFIG = `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="50"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canRequestFilterKeyEvents="true"
    android:settingsActivity="com.agent.ultra.MainActivity"
    android:accessibilityFlags="flagDefault|flagReportViewIds|flagIncludeNotImportantViews|flagRetrieveInteractiveWindows" />`;

const STRINGS_XML_ADDITION = `    <string name="accessibility_service_description">Agent Ultra uses accessibility to interact with other apps on your behalf. Enable only if you want Ultra to control apps for you.</string>`;

function withAgentNative(config) {
  config = withDangerousMod(config, [
    'android',
    async (config) => {
      const projectRoot = config.modRequest.projectRoot;
      const androidDir = path.join(projectRoot, 'android');

      const javaDir = path.join(
        androidDir, 'app', 'src', 'main', 'java', 'com', 'agent', 'ultra'
      );
      fs.mkdirSync(javaDir, { recursive: true });
      fs.writeFileSync(path.join(javaDir, 'AgentNativeModule.java'), NATIVE_MODULE_JAVA);
      fs.writeFileSync(path.join(javaDir, 'AgentNativePackage.java'), NATIVE_PACKAGE_JAVA);
      fs.writeFileSync(path.join(javaDir, 'BinaryManifestWriter.java'), BINARY_MANIFEST_WRITER_JAVA);
      fs.writeFileSync(path.join(javaDir, 'ApkPackager.java'), APK_PACKAGER_JAVA);
      fs.writeFileSync(path.join(javaDir, 'ApkSignerV1.java'), APK_SIGNER_V1_JAVA);
      fs.writeFileSync(path.join(javaDir, 'AgentAccessibilityService.java'), ACCESSIBILITY_SERVICE_JAVA);
      fs.writeFileSync(path.join(javaDir, 'AccessibilityBridgeModule.java'), ACCESSIBILITY_BRIDGE_JAVA);
      fs.writeFileSync(path.join(javaDir, 'AgentBackgroundService.java'), BACKGROUND_SERVICE_JAVA);
      fs.writeFileSync(path.join(javaDir, 'AgentHeadlessTaskService.java'), HEADLESS_TASK_SERVICE_JAVA);
      const xmlDir = path.join(androidDir, 'app', 'src', 'main', 'res', 'xml');
      fs.mkdirSync(xmlDir, { recursive: true });
      fs.writeFileSync(path.join(xmlDir, 'file_paths.xml'), FILE_PROVIDER_PATHS);
      fs.writeFileSync(path.join(xmlDir, 'accessibility_service_config.xml'), ACCESSIBILITY_SERVICE_CONFIG);

      const stringsPath = path.join(androidDir, 'app', 'src', 'main', 'res', 'values', 'strings.xml');
      if (fs.existsSync(stringsPath)) {
        let stringsXml = fs.readFileSync(stringsPath, 'utf8');
        if (!stringsXml.includes('accessibility_service_description')) {
          stringsXml = stringsXml.replace('</resources>', STRINGS_XML_ADDITION + '\n</resources>');
          fs.writeFileSync(stringsPath, stringsXml);
        }
      }

      // Patch MainApplication.java or MainApplication.kt
      const mainAppPathJava = path.join(javaDir, 'MainApplication.java');
      const mainAppPathKotlin = path.join(javaDir, 'MainApplication.kt');
      const mainAppPath = fs.existsSync(mainAppPathJava) ? mainAppPathJava
        : fs.existsSync(mainAppPathKotlin) ? mainAppPathKotlin
        : null;

      if (mainAppPath) {
        let mainApp = fs.readFileSync(mainAppPath, 'utf8');
        const isKotlin = mainAppPath.endsWith('.kt');

        if (!mainApp.includes('AgentNativePackage')) {
          if (isKotlin) {
            // New Architecture Kotlin pattern:
            // override fun getPackages(): List<ReactPackage> =
            //   PackageList(this).packages.apply { add(AgentNativePackage()) }
            // OR the older override fun getPackages() block style
            const kotlinApplyPattern = /PackageList\(this\)\.packages\.apply\s*\{([\s\S]*?)\}/;
            const kotlinApplyMatch = mainApp.match(kotlinApplyPattern);
            if (kotlinApplyMatch) {
              mainApp = mainApp.replace(
                kotlinApplyPattern,
                `PackageList(this).packages.apply {$1  add(AgentNativePackage())\n          }`
              );
            } else {
              // Block-style getPackages override
              const kotlinBlockPattern = /(override fun getPackages\(\)[\s\S]*?PackageList\(this\)\.packages)([\s\S]*?)(return packages|return mutableListOf)/;
              const kotlinBlockMatch = mainApp.match(kotlinBlockPattern);
              if (kotlinBlockMatch) {
                mainApp = mainApp.replace(
                  kotlinBlockMatch[3],
                  'packages.add(AgentNativePackage())\n      ' + kotlinBlockMatch[3]
                );
              }
            }
          } else {
            // Java patterns (original logic)
            if (mainApp.includes('packages.add(new com.facebook.react.shell.MainReactPackage());')) {
              mainApp = mainApp.replace(
                'packages.add(new com.facebook.react.shell.MainReactPackage());',
                'packages.add(new com.facebook.react.shell.MainReactPackage());\n            packages.add(new AgentNativePackage());'
              );
            } else {
              const autolinkedMatch = mainApp.match(/(new\s+PackageList\(this\)\.getPackages\(\))/);
              if (autolinkedMatch) {
                mainApp = mainApp.replace(
                  autolinkedMatch[0],
                  autolinkedMatch[0] + ';\n            packages.add(new AgentNativePackage())'
                );
              }
              if (!mainApp.includes('AgentNativePackage')) {
                const addPackagesPattern = /(@Override\s+protected\s+List<ReactPackage>\s+getPackages\(\)\s*\{[\s\S]*?)(return\s+packages;)/;
                const addMatch = mainApp.match(addPackagesPattern);
                if (addMatch) {
                  mainApp = mainApp.replace(
                    addMatch[2],
                    'packages.add(new AgentNativePackage());\n            ' + addMatch[2]
                  );
                }
              }
            }
          }
          fs.writeFileSync(mainAppPath, mainApp);
        }
      }

      const genomeSrcDir = path.join(
        androidDir, 'app', 'src', 'main', 'assets', 'genome_sources'
      );
      fs.mkdirSync(genomeSrcDir, { recursive: true });

      const sourceMap = {
        'BinaryManifestWriter.java': BINARY_MANIFEST_WRITER_JAVA,
        'ApkPackager.java': APK_PACKAGER_JAVA,
        'ApkSignerV1.java': APK_SIGNER_V1_JAVA,
        'AgentNativeModule.java': NATIVE_MODULE_JAVA,
        'AgentAccessibilityService.java': ACCESSIBILITY_SERVICE_JAVA,
      };

      for (const [name, content] of Object.entries(sourceMap)) {
        fs.writeFileSync(path.join(genomeSrcDir, name), content);
      }

      return config;
    },
  ]);

  config = withAndroidManifest(config, async (config) => {
    const manifest = config.modResults;
    const app = manifest.manifest.application[0];

    const appPackage = config.android?.package || config.android?.packageName || 'com.agent.ultra';
    const fileProviderAuthority = `${appPackage}.fileprovider`;

    const hasProvider = (app.provider || []).some(
      (p) => p.$['android:authorities'] === fileProviderAuthority
    );
    if (!hasProvider) {
      if (!app.provider) app.provider = [];
      app.provider.push({
        $: {
          'android:name': 'androidx.core.content.FileProvider',
          'android:authorities': fileProviderAuthority,
          'android:exported': 'false',
          'android:grantUriPermissions': 'true',
        },
        'meta-data': [{
          $: {
            'android:name': 'android.support.FILE_PROVIDER_PATHS',
            'android:resource': '@xml/file_paths',
          },
        }],
      });
    }

    const hasA11y = (app.service || []).some(
      (s) => s.$['android:name'] === '.AgentAccessibilityService'
    );
    if (!hasA11y) {
      if (!app.service) app.service = [];
      app.service.push({
        $: {
          'android:name': '.AgentAccessibilityService',
          'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
          'android:exported': 'false',
          'android:stopWithTask': 'false',
        },
        'intent-filter': [{
          action: [{
            $: { 'android:name': 'android.accessibilityservice.AccessibilityService' },
          }],
        }],
        'meta-data': [{
          $: {
            'android:name': 'android.accessibilityservice',
            'android:resource': '@xml/accessibility_service_config',
          },
        }],
      });
    }

    const hasBgService = (app.service || []).some(
      (s) => s.$['android:name'] === '.AgentBackgroundService'
    );
    if (!hasBgService) {
      if (!app.service) app.service = [];
      app.service.push({
        $: {
          'android:name': '.AgentBackgroundService',
          'android:exported': 'false',
          'android:foregroundServiceType': 'dataSync',
          'android:stopWithTask': 'false',
        },
      });
    }

    const hasHeadlessService = (app.service || []).some(
      (s) => s.$['android:name'] === '.AgentHeadlessTaskService'
    );
    if (!hasHeadlessService) {
      if (!app.service) app.service = [];
      app.service.push({
        $: {
          'android:name': '.AgentHeadlessTaskService',
          'android:exported': 'false',
        },
      });
    }

    const perms = manifest.manifest['uses-permission'] || [];
    const requiredPermissions = [
      // Network
      'android.permission.INTERNET',
      'android.permission.ACCESS_NETWORK_STATE',
      'android.permission.ACCESS_WIFI_STATE',
      'android.permission.CHANGE_WIFI_STATE',
      'android.permission.CHANGE_NETWORK_STATE',
      'android.permission.NFC',

      // Phone & SMS
      'android.permission.CALL_PHONE',
      'android.permission.READ_PHONE_STATE',
      'android.permission.SEND_SMS',
      'android.permission.READ_SMS',
      'android.permission.RECEIVE_SMS',
      'android.permission.READ_CALL_LOG',

      // Contacts & Calendar
      'android.permission.READ_CONTACTS',
      'android.permission.WRITE_CONTACTS',
      'android.permission.READ_CALENDAR',
      'android.permission.WRITE_CALENDAR',

      // Location
      'android.permission.ACCESS_FINE_LOCATION',
      'android.permission.ACCESS_COARSE_LOCATION',
      'android.permission.ACCESS_BACKGROUND_LOCATION',

      // Camera & Microphone
      'android.permission.CAMERA',
      'android.permission.RECORD_AUDIO',

      // Storage (pre-Android 13)
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.WRITE_EXTERNAL_STORAGE',

      // Storage (Android 13+ / API 33+)
      'android.permission.READ_MEDIA_IMAGES',
      'android.permission.READ_MEDIA_VIDEO',
      'android.permission.READ_MEDIA_AUDIO',

      // Notifications (Android 13+)
      'android.permission.POST_NOTIFICATIONS',

      // Device control
      'android.permission.FLASHLIGHT',
      'android.permission.VIBRATE',
      'android.permission.MODIFY_AUDIO_SETTINGS',
      'android.permission.SET_ALARM',
      'android.permission.SCHEDULE_EXACT_ALARM',
      'android.permission.USE_EXACT_ALARM',

      // Bluetooth
      'android.permission.BLUETOOTH',
      'android.permission.BLUETOOTH_ADMIN',
      'android.permission.BLUETOOTH_CONNECT',
      'android.permission.BLUETOOTH_SCAN',

      // System
      'android.permission.FOREGROUND_SERVICE',
      'android.permission.FOREGROUND_SERVICE_LOCATION',
      'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
      'android.permission.RECEIVE_BOOT_COMPLETED',
      'android.permission.QUERY_ALL_PACKAGES',
      'android.permission.REQUEST_INSTALL_PACKAGES',
      'android.permission.SYSTEM_ALERT_WINDOW',
      'android.permission.WRITE_SETTINGS',
      'android.permission.ACCESS_NOTIFICATION_POLICY',

      // Biometric
      'android.permission.USE_BIOMETRIC',
      'android.permission.USE_FINGERPRINT',
    ];
    for (const perm of requiredPermissions) {
      if (!perms.some(p => p.$['android:name'] === perm)) {
        perms.push({ $: { 'android:name': perm } });
      }
    }
    manifest.manifest['uses-permission'] = perms;

    return config;
  });

  return config;
}

module.exports = withAgentNative;
