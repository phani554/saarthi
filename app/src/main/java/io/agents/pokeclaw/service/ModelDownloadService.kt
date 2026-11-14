package io.agents.pokeclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.ui.settings.LlmConfigActivity
import io.agents.pokeclaw.utils.XLog
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Downloads a local model in a foreground service, so it keeps going when the Settings screen is closed,
 * the app is in the background or the screen is off. Shows progress in a notification with a Stop button.
 *
 * The download itself is [LocalModelManager.downloadModel] (resume via Range, size check, cancel).
 * The finished model is saved as the local default here, not in the screen, because the screen may be gone.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val CHANNEL_ID = "saathi_model_download"
        private const val NOTIFICATION_ID = 4107
        private const val EXTRA_MODEL_ID = "model_id"
        private const val ACTION_START = "io.agents.pokeclaw.MODEL_DOWNLOAD_START"
        private const val ACTION_STOP = "io.agents.pokeclaw.MODEL_DOWNLOAD_STOP"

        /** The screen showing a download, if any. Weak so a closed screen is never kept alive. */
        private val listeners = ConcurrentHashMap<String, WeakReference<LocalModelManager.DownloadCallback>>()

        /** Lets a reopened screen show progress for a download that is already running. */
        fun attach(modelId: String, callback: LocalModelManager.DownloadCallback) {
            listeners[modelId] = WeakReference(callback)
        }

        fun detach(modelId: String, callback: LocalModelManager.DownloadCallback) {
            listeners.computeIfPresent(modelId) { _, ref -> if (ref.get() === callback) null else ref }
        }

        fun start(context: Context, model: LocalModelManager.ModelInfo, callback: LocalModelManager.DownloadCallback) {
            attach(model.id, callback)
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODEL_ID, model.id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun listenerFor(modelId: String) = listeners[modelId]?.get()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // startForeground first, every time: Android kills the app if a started foreground service doesn't.
        try {
            val n = buildNotification("Getting ready to download", 0, indeterminate = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "startForeground failed", e)
        }

        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        val model = LocalModelManager.AVAILABLE_MODELS.firstOrNull { it.id == modelId }
        when {
            model == null -> stopIfIdle()
            intent?.action == ACTION_STOP -> {
                LocalModelManager.cancelDownload(this, model)
                stopIfIdle()
            }
            running.add(model.id) -> {
                acquireLocks()
                executor.execute { runDownload(model) }
            }
            // Already downloading this model: nothing to do.
        }
        return START_NOT_STICKY
    }

    private fun runDownload(model: LocalModelManager.ModelInfo) {
        XLog.i(TAG, "Background download started: ${model.id}")
        LocalModelManager.downloadModel(this, model, object : LocalModelManager.DownloadCallback {
            override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt >= 1000) {
                    lastNotifyAt = now
                    val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                    notifyProgress(model, pct)
                }
                listenerFor(model.id)?.onProgress(bytesDownloaded, totalBytes, bytesPerSecond)
            }

            override fun onComplete(modelPath: String) {
                ModelConfigRepository.saveLocalDefault(modelPath = modelPath, modelId = model.id, activateNow = false)
                finish(model, "${model.displayName} downloaded")
                listenerFor(model.id)?.onComplete(modelPath)
            }

            override fun onError(error: String) {
                finish(model, "Download stopped: $error. Tap Download again to resume.")
                listenerFor(model.id)?.onError(error)
            }

            override fun onCancelled() {
                finish(model, null)
                listenerFor(model.id)?.onCancelled()
            }
        })
    }

    private fun finish(model: LocalModelManager.ModelInfo, message: String?) {
        running.remove(model.id)
        if (message != null) {
            // A separate, dismissable notification that outlives the foreground one.
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(model.displayName)
                .setContentText(message)
                .setContentIntent(openScreenIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID + 1, n)
        }
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (running.isNotEmpty()) return
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun notifyProgress(model: LocalModelManager.ModelInfo, pct: Int) {
        val n = buildNotification("Downloading ${model.displayName} · $pct%", pct, indeterminate = false, stopFor = model)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(
        text: String,
        pct: Int,
        indeterminate: Boolean,
        stopFor: LocalModelManager.ModelInfo? = null
    ): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Saathi")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(openScreenIntent())
        if (stopFor != null) {
            val stop = Intent(this, ModelDownloadService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_MODEL_ID, stopFor.id)
            val pi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, "Stop", pi)
        }
        return b.build()
    }

    private fun openScreenIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, LlmConfigActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Saathi::ModelDownload").apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L) // safety cap: 3 hours
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Saathi::ModelDownload").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Could not take wake/wifi lock", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        executor.shutdown()
        super.onDestroy()
    }
}
