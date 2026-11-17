package com.traccar.nativeapp.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 告警实时推送（前台服务）：
 * 以前台服务常驻，周期性轮询 /api/reports/events（官方唯一的时间段事件列表接口），
 * 发现比“已读时间”更新的事件就发系统通知。
 * （Traccar 的 WebSocket 只推位置/设备状态，不推 events，故用轮询更可靠；
 *  官方并不存在 GET /api/events 列表接口。）
 * 设备上报的告警 / 上线离线 / 进出围栏等都会进入事件，从而触发通知。
 *
 * 轮询策略（避免每 60 秒全量拉 7 天）：
 *   - 只拉「已读时间 ~ 现在」的增量区间，区间最多回溯 7 天；
 *   - 设备 ID 缓存 10 分钟刷新一次，设备增减无需重启服务；
 *   - 首次运行只写基线，不推送历史告警。
 */
class NotificationService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val channelId = "traccar_alerts"
    private val foregroundId = 1
    private val alertId = 2
    private val intervalMs = 60_000L
    private val deviceCacheTtlMs = 10 * 60_000L

    private var cachedDeviceIds = emptyList<Long>()
    private var deviceCacheAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs)
        createChannel()
        startForeground(foregroundId, foregroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15（API 35）起，dataSync 类型前台服务在后台累计运行超过时限后，
     * 系统会回调本方法；必须主动停止服务，否则会被判定为无响应。
     * 服务停止后，用户下次打开 App（MainActivity）会重新拉起轮询。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, getString(R.string.notif_timeout_log))
        pollJob?.cancel()
        stopSelf(startId)
    }

    private suspend fun pollLoop() {
        // 首次以较短间隔快速建立基线，之后按 intervalMs
        while (scope.isActive) {
            try {
                if (prefs.isLoggedIn) pollOnce()
            } catch (e: Exception) {
                // 网络异常下一轮继续；同时输出真实错误便于用 logcat 排查（token 过期/报表被禁用等）
                Log.w(TAG, getString(R.string.notif_poll_failed), e)
            }
            delay(intervalMs)
        }
    }

    private suspend fun pollOnce() {
        // 设备列表异常不再静默吞掉，交给 pollLoop 记录，避免"一直无通知却查不出原因"。
        // 缓存 10 分钟过期：设备新增/删除后无需重启前台服务即可生效。
        val nowMs = System.currentTimeMillis()
        if (cachedDeviceIds.isEmpty() || nowMs - deviceCacheAtMs >= deviceCacheTtlMs) {
            cachedDeviceIds = api.devices().map { it.id }
            deviceCacheAtMs = nowMs
            Log.d(TAG, getString(R.string.notif_cache_refreshed, cachedDeviceIds.size))
        }
        if (cachedDeviceIds.isEmpty()) return

        val seen = prefs.lastEventSeenTime
        if (seen.isEmpty()) {
            // 首次运行：只建立基线，不轰炸历史告警
            prefs.lastEventSeenTime = isoNow()
            return
        }

        val (from, to) = pollRange(seen)
        val newOnes = api.events(deviceIds = cachedDeviceIds, from = from, to = to)
            .filter { (it.serverTime ?: "") > seen }
            .sortedByDescending { it.serverTime ?: "" }
        if (newOnes.isEmpty()) {
            // 没有新事件也要把轮询窗口前移，否则已读时间长期不变，
            // 查询区间会逐日增长到 7 天上限，退化成每 60 秒全量拉取。
            prefs.lastEventSeenTime = to
            return
        }

        postAlert(newOnes)
        newOnes.firstOrNull()?.serverTime?.let { prefs.lastEventSeenTime = it }
        Log.i(TAG, getString(R.string.notif_pushed, newOnes.size))
    }

    private fun postAlert(events: List<com.traccar.nativeapp.data.Event>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val count = events.size
        val latest = events.first()
        val title = if (count == 1) latest.title else getString(R.string.notif_title_multi, count)
        val text = buildString {
            if (count == 1) {
                append(latest.detail.ifEmpty { latest.title })
            } else {
                append(latest.title)
                if (latest.detail.isNotEmpty()) append("：${latest.detail}")
                append(" ").append(getString(R.string.notif_suffix, count))
            }
        }
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, EventsActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(alertId, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                val ch = NotificationChannel(
                    channelId, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(true)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun foregroundNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_foreground_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    /**
     * 增量查询区间：从「已读时间」到「现在」，最多回溯 7 天。
     * Traccar 的时间参数要求 ISO-8601 带 'Z'；同一服务端返回的 eventTime 也是该格式，
     * 因此字符串比较与时间先后一致。
     */
    private fun pollRange(seen: String): Pair<String, String> {
        val fmt = isoFmt()
        val to = fmt.format(Calendar.getInstance().time)
        val earliest = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -7) }
        val floor = fmt.format(earliest.time)
        val from = if (seen > floor) seen else floor
        return from to to
    }

    private fun isoNow(): String = isoFmt().format(Calendar.getInstance().time)

    private fun isoFmt(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

    companion object {
        private const val TAG = "NotificationService"
    }
}