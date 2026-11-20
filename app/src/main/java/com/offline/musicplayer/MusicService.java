package com.offline.musicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class MusicService extends Service {
    public static final String CHANNEL_ID = "offline_music_channel";
    public static final int NOTIF_ID = 101;
    public static final String ACTION_CTRL = "com.offline.musicplayer.ACTION_CTRL";
    public static final String EXTRA_CMD = "cmd";

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfflineMusicPlayer::AudioWakeLock");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String cmd = intent.getStringExtra(EXTRA_CMD);
        if (cmd != null) {
            Intent b = new Intent(ACTION_CTRL);
            b.setPackage(getPackageName());
            b.putExtra(EXTRA_CMD, cmd);
            sendBroadcast(b);
            return START_STICKY;
        }

        String title = intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        boolean isPlaying = intent.getBooleanExtra("playing", false);
        if (title == null) title = "Music Player";
        if (artist == null) artist = "Offline";

        if (wakeLock != null) {
            if (isPlaying && !wakeLock.isHeld()) wakeLock.acquire();
            else if (!isPlaying && wakeLock.isHeld()) wakeLock.release();
        }

        Notification notif = buildNotification(title, artist, isPlaying);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        return START_STICKY;
    }

    private PendingIntent makeActionIntent(String cmd, int reqCode) {
        Intent i = new Intent(this, MusicService.class);
        i.putExtra(EXTRA_CMD, cmd);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, reqCode, i, flags);
    }

    private Notification buildNotification(String title, String artist, boolean isPlaying) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        nb.setContentTitle(title)
          .setContentText(artist)
          .setSmallIcon(isPlaying ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
          .setContentIntent(contentPi)
          .setOngoing(isPlaying)
          .setVisibility(Notification.VISIBILITY_PUBLIC)
          .addAction(android.R.drawable.ic_media_previous, "Prev", makeActionIntent("prev", 1))
          .addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                  isPlaying ? "Pause" : "Play", makeActionIntent("toggle", 2))
          .addAction(android.R.drawable.ic_media_next, "Next", makeActionIntent("next", 3));

        return nb.build();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
