package com.traccar.nativeapp.data

import android.os.Handler
import android.os.Looper
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 实时位置通道：WebSocket /api/socket?token=...
 *
 * Traccar 服务端推送两类 JSON：
 *   - {"devices":[...]}  设备状态变化
 *   - {"positions":[...]} 新位置
 * 与 Web 版 SocketController 一致，收到后回调到主线程刷新地图/列表。
 */
class SocketController(
    private val api: Api,
    private val onDevices: (List<Device>) -> Unit,
    private val onPositions: (List<Position>) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        // WebSocket 与 REST 使用同一套明文策略：公网 ws:// 直接拒绝，wss/内网放行
        .addInterceptor(Interceptor { chain ->
            NetworkPolicy.check(chain.request().url)
            chain.proceed(chain.request())
        })
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 需要长连接
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var running = false

    private var retry = 0

    fun connect() {
        if (running) return
        running = true
        open()
    }

    private fun open() {
        if (!running) return
        val request = Request.Builder().url(api.socketUrl()).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retry = 0
                handler.post { onState(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    if (obj.has("devices")) {
                        val arr = obj.getJSONArray("devices")
                        val list = (0 until arr.length()).map { Device.from(arr.getJSONObject(it)) }
                        handler.post { onDevices(list) }
                    }
                    if (obj.has("positions")) {
                        val arr = obj.getJSONArray("positions")
                        val list = (0 until arr.length()).map { Position.from(arr.getJSONObject(it)) }
                        handler.post { onPositions(list) }
                    }
                } catch (e: Exception) {
                    // 单条推送解析失败不应中断长连接，但要留下原始错误便于定位协议变更
                    android.util.Log.w("Socket", "忽略无法解析的推送", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 被网络策略拒绝属于配置问题，重连也不会成功；停止并提示用户改地址
                if (t is NetworkPolicy.InsecureHttpException) {
                    android.util.Log.w("Socket", "WebSocket 地址被网络策略拒绝，停止重连", t)
                    running = false
                    handler.post { onState(false) }
                    return
                }
                android.util.Log.w("Socket", "实时位置连接失败，准备重连", t)
                handler.post { onState(false) }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.i("Socket", "实时位置连接关闭：code=$code reason=$reason")
                handler.post { onState(false) }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running) return
        retry += 1
        val delay = (1000L * retry).coerceAtMost(15_000L)
        handler.postDelayed({
            socket?.cancel()
            open()
        }, delay)
    }

    fun disconnect() {
        running = false
        socket?.close(1000, "logout")
        socket?.cancel()
        socket = null
        handler.removeCallbacksAndMessages(null)
    }
}
