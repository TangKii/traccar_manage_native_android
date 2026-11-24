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
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Event
import com.traccar.nativeapp.data.Prefs
import com.traccar.nativeapp.data.SocketController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 告警推送（前台服务），双通道：
 *   1) 实时：WebSocket /api/socket 推 events，事件到达即刻发系统通知（秒级）；
 *   2) 兜底：每 60s 轮询 /api/reports/events，防止 WebSocket 断线期间漏告警。
 * 两条通道共用「通知去重基线」（已通知的最大 event id），同一事件只通知一次。
 *
 * 去重基线（prefs.lastNotifiedEventId，服务端自增 id）与首页铃铛未读（prefs.lastEventSeenTime）
 * 刻意分开：通知推送只代表"系统通知过"，不能清掉用户尚未点开的未读红点。
 *
 * 首次运行：以服务启动时刻为界，只压制启动前的历史事件，保留启动后新发生的告警（便于用户测试推送）。
 */
class NotificationService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var socket: SocketController? = null

    private val channelId = "traccar_alerts"
    private val foregroundId = 1
    private val alertId = 2
    /** 兜底轮询间隔：实时通道正常时几乎用不到，取保守值省流量 */
    private val intervalMs = 60_000L
    private val deviceCacheTtlMs = 10 * 60_000L

    private var cachedDeviceIds = emptyList<Long>()
    private var deviceCacheAtMs = 0L
    /** 服务本次启动时刻：首次建基线时用来区分"历史"与"启动后新发生"的告警 */
    private var startedAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs)
        startedAtMs = System.currentTimeMillis()
        createChannel()
        startForeground(foregroundId, foregroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.isLoggedIn) {
            stopSelf()
            return START_NOT_STICKY
        }
        connectRealtime()
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        socket?.disconnect()
        socket = null
        scope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15（API 35）起，dataSync 类型前台服务在后台累计运行超过时限后，
     * 系统会回调本方法；必须主动停止服务，否则会被判定为无响应。
     * 服务停止后，用户下次打开 App（MainActivity）会重新拉起。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, getString(R.string.notif_timeout_log))
        stopSelf(startId)
    }

    // ---------------- 实时通道 ----------------

    private fun connectRealtime() {
        socket?.disconnect()
        socket = SocketController(
            api = api,
            onDevices = { },
            onPositions = { },
            onState = { connected ->
                Log.i(TAG, if (connected) "告警实时通道已连接" else "告警实时通道断开，等待重连")
            },
            onEvents = { events -> handleIncomingEvents(events, fromRealtime = true) }
        )
        socket?.connect()
    }

    /**
     * 统一处理新事件（实时 / 轮询都走这里）。
     *
     * 去重基准用服务端单调递增的 [Event.id]，绝不用 eventTime：
     * 告警事件的 eventTime 取自设备上报的 GPS 时间，设备时钟不准时会固定不变甚至倒退，
     * 用时间判断先后会把新事件全部误判为旧事件，导致完全收不到推送。event id 由服务端
     * 数据库自增分配，与设备时钟无关，是唯一可靠的先后依据。两条通道共享同一 id 基线。
     *
     * @param fromRealtime true=WebSocket 实时推送；false=轮询兜底
     */
    private fun handleIncomingEvents(events: List<Event>, fromRealtime: Boolean) {
        if (events.isEmpty()) return
        val baseline = prefs.lastNotifiedEventId

        // 按 id 过滤 + 批内去重（id 单调递增，可可靠判先后）
        val fresh = events.filter { it.id > baseline }.distinctBy { it.id }
        if (fresh.isEmpty()) return

        val newest = fresh.maxOf { it.id }

        // 首次运行：建立基线。只压制"服务启动前"的历史告警；启动后新发生的照常推送，
        // 这样用户装好后立刻触发一条测试告警就能收到，不会以为推送坏了。
        if (baseline == 0L) {
            val afterStart = fresh.filter { e ->
                val ms = isoMillis(e.serverTime)
                ms == null || ms >= startedAtMs - 2000L  // 时间缺失时保守按新事件处理
            }
            prefs.lastNotifiedEventId = newest
            if (afterStart.isEmpty()) {
                Log.i(TAG, "首次建立通知基线，历史事件数=${fresh.size}（不推送历史）")
                return
            }
            Log.i(TAG, "首次建立通知基线，推送启动后新告警 ${afterStart.size} 条")
            postAlert(afterStart.sortedByDescending { it.id })
            return
        }

        prefs.lastNotifiedEventId = newest
        postAlert(fresh.sortedByDescending { it.id })
        Log.i(TAG, getString(R.string.notif_pushed, fresh.size) +
            (if (fromRealtime) "（实时）" else "（轮询）"))
    }

    // ---------------- 兜底轮询 ----------------

    private suspend fun pollLoop() {
        while (scope.isActive) {
            try {
                if (prefs.isLoggedIn) {
                    pollOnce()
                } else {
                    stopSelf()
                    break
                }
            } catch (e: Exception) {
                // 网络异常下一轮继续；同时输出真实错误便于用 logcat 排查
                Log.w(TAG, getString(R.string.notif_poll_failed), e)
            }
            delay(intervalMs)
        }
    }

    private suspend fun pollOnce() {
        val nowMs = System.currentTimeMillis()
        if (cachedDeviceIds.isEmpty() || nowMs - deviceCacheAtMs >= deviceCacheTtlMs) {
            cachedDeviceIds = api.devices().map { it.id }
            deviceCacheAtMs = nowMs
            Log.d(TAG, getString(R.string.notif_cache_refreshed, cachedDeviceIds.size))
        }
        if (cachedDeviceIds.isEmpty()) return

        // 查询窗口最多回溯 7 天（仅用于缩小 /reports/events 的查询范围）。
        // 是否"新事件"完全交给 handleIncomingEvents 按自增 id 判定，与设备时钟无关。
        val (from, to) = pollRange()
        val fetched = api.events(deviceIds = cachedDeviceIds, from = from, to = to)
        if (fetched.isEmpty()) return
        handleIncomingEvents(fetched, fromRealtime = false)
    }

    // ---------------- 通知发布 ----------------

    private fun postAlert(events: List<Event>) {
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
        // 通知栏时间戳用「推送到达的真实时刻」（系统时间）。
        // 不用 eventTime：告警的 eventTime 取自设备上报的 GPS 时间，设备时钟不准时
        // 会固定在某个错误值（如 18:44），通知栏跟着显示就会误导用户。
        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
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

    private val isoInstantFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * 轮询查询区间：最近 7 天到「现在」。
     * 去重由 [handleIncomingEvents] 按自增 event id 完成，所以这里不需要按上次通知时间收紧窗口——
     * 固定回溯窗口反而能兜住"迟到"的事件，不会因设备时钟异常而漏掉。
     */
    private fun pollRange(): Pair<String, String> {
        val now = Instant.now()
        val to = isoInstantFormatter.format(now)
        val from = isoInstantFormatter.format(now.minus(7, ChronoUnit.DAYS))
        return from to to
    }

    companion object {
        private const val TAG = "NotificationService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotificationService::class.java))
        }
    }
}
