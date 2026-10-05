package com.agent.ultra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.agent.ultra.agent.EventTrigger;

public class AgentBackgroundService extends Service {
    private static final String TAG = "AgentBgSvc";
    private static final String CHANNEL_ID = "agent_ultra_bg";
    private static final int NOTIFICATION_ID = 7001;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setNumber(0)
            .setBadgeIconType(android.app.Notification.BADGE_ICON_NONE)
            .build();
        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            // Android 12+ refuses a foreground start from the background (e.g. a sticky restart).
            // The accessibility service lives in this process too: stop, never crash it.
            Log.w(TAG, "cannot start in the foreground: " + e.getMessage());
            stopSelf();
            return;
        }
        Log.i(TAG, "Background service started");

        EventTrigger.INSTANCE.registerDefaults();
        Handler mainHandler = new Handler(getMainLooper());
        EventTrigger.INSTANCE.startSystemTriggers(this, (EventTrigger.ActionExecutor) action -> {
            if (action.getType() == EventTrigger.Action.Type.TOAST) {
                mainHandler.post(() ->
                    Toast.makeText(AgentBackgroundService.this, action.getLabel(), Toast.LENGTH_SHORT).show()
                );
            }
        });
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
        EventTrigger.INSTANCE.stopSystemTriggers(this);
        Log.i(TAG, "Background service destroyed");
        super.onDestroy();
    }

    // No restart in onTaskRemoved: stopWithTask="false" already keeps this service running when the
    // task is swiped away, and a foreground start from the background can throw on Android 12+.

    /**
     * Android 15 caps dataSync foreground services at ~6 h a day and calls this when the time is up.
     * Not stopping here gets the whole process killed, the accessibility service with it.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "foreground time limit reached; stopping");
        stopSelf();
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
            Notification n = b.setContentTitle("Jarvis")
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
                "Jarvis Background",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps Jarvis running in the background");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}