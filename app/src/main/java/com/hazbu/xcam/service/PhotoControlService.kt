package com.hazbu.xcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants.KEY_MEDIA_PATH
import com.hazbu.xcam.data.Constants.PREFS_NAME
import com.hazbu.xcam.ui.MainActivity
import org.json.JSONArray
import java.io.File

class PhotoControlService : Service() {

    companion object {
        const val ACTION_START = "com.hazbu.xcam.action.PHOTO_CONTROL_START"
        const val ACTION_MEDIA_CLEARED = "com.hazbu.xcam.action.PHOTO_MEDIA_CLEARED"
        private const val ACTION_PREVIOUS = "com.hazbu.xcam.action.PHOTO_CONTROL_PREVIOUS"
        private const val ACTION_NEXT = "com.hazbu.xcam.action.PHOTO_CONTROL_NEXT"
        private const val CHANNEL_ID = "xcam_photo_controls"
        private const val NOTIFICATION_ID = 4102
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIOUS -> movePhoto(-1)
            ACTION_NEXT -> movePhoto(1)
        }

        val paths = readMediaPaths()
        if (paths.isEmpty()) {
            stopForegroundAndRemove()
            stopSelf()
            return START_NOT_STICKY
        }

        val index = readCurrentIndex(paths.size)
        val notification = buildNotification(paths, index)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Do not clear the selection here: normal service shutdowns can happen
        // while the app is merely backgrounded. Task removal is handled below.
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        clearSelectedMedia()
        notifyMediaCleared()
        stopForegroundAndRemove()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun clearSelectedMedia() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_MEDIA_PATH)
            .putString("media_paths", JSONArray().toString())
            .putInt("current_media_index", 0)
            .putBoolean("photo_control_stopped", true)
            .putBoolean(com.hazbu.xcam.data.Constants.KEY_IS_MIRRORED, false)
            .putInt(com.hazbu.xcam.data.Constants.KEY_ROTATION_ANGLE, 0)
            .commit()

        filesDir.listFiles { _, name -> name.startsWith("virtual.") }
            ?.forEach { it.delete() }
    }

    private fun notifyMediaCleared() {
        sendBroadcast(Intent(ACTION_MEDIA_CLEARED).setPackage(packageName))
    }

    private fun movePhoto(direction: Int) {
        val paths = readMediaPaths()
        if (paths.size < 2) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = readCurrentIndex(paths.size)
        val next = (current + direction + paths.size) % paths.size
        prefs.edit()
            .putInt("current_media_index", next)
            .putString(KEY_MEDIA_PATH, paths[next])
            .putBoolean("photo_control_stopped", false)
            .apply()
    }

    private fun readMediaPaths(): List<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString("media_paths", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length())
                .map { array.getString(it) }
                .filter { File(it).exists() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readCurrentIndex(size: Int): Int {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("current_media_index", 0)
            .coerceIn(0, size - 1)
    }

    private fun buildNotification(paths: List<String>, index: Int): Notification {
        val title = "Camx"
        val text = "第 ${index + 1} / ${paths.size} 张照片"
        val thumbnail = loadThumbnail(paths[index])
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previous = actionPendingIntent(ACTION_PREVIOUS, 1)
        val next = actionPendingIntent(ACTION_NEXT, 2)
        val previousIcon = android.R.drawable.ic_media_previous
        val nextIcon = android.R.drawable.ic_media_next

        return if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_photo)
                .setLargeIcon(thumbnail)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, previousIcon),
                        "◀",
                        previous
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, nextIcon),
                        "▶",
                        next
                    ).build()
                )
                .build()
                .apply {
                    extras.putBoolean("android.requestPromotedOngoing", true)
                }
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_photo)
                .setLargeIcon(thumbnail)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .addAction(previousIcon, "◀", previous)
                .addAction(nextIcon, "▶", next)
                .build()
        }
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PhotoControlService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun loadThumbnail(path: String): Bitmap? {
        return try {
            if (path.endsWith(".mp4", ignoreCase = true)) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            } else {
                BitmapFactory.decodeFile(path)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cam照片控制",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "在后台快速切换 xCam 当前使用的照片"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundAndRemove() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
