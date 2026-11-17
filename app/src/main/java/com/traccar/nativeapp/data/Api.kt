package com.traccar.nativeapp.data

import com.traccar.nativeapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Traccar REST 客户端。
 *
 * 认证链路与已验证的 Web/Capacitor 版完全一致：
 *   1) 登录时临时保存 email:password，用 Basic 认证 POST /api/session 校验账号；
 *   2) 成功后 POST /api/session/token 换取长期 token（纯文本返回）；
 *   3) 之后所有 API 用 Authorization: Bearer <token>，WebSocket 用 ?token=。
 * 这样彻底避开 WebView 跨站 Cookie 的坑，服务端 SecurityRequestFilter 原生支持。
 */
class Api(private val prefs: Prefs) {

    // 仅登录阶段使用的 Basic 凭证，换到 token 后立即清空
    @Volatile
    private var basicCredentials: String? = null

    /** 登录期间强制使用 Basic，避免旧 token 抢先生效（否则可能换到旧账号的 token） */
    @Volatile
    private var forceBasicAuth: Boolean = false

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            // 运行期收紧明文 HTTP：https 与局域网 http 放行，公网 http 直接拒绝
            NetworkPolicy.check(chain.request().url)
            val builder = chain.request().newBuilder()
            if (forceBasicAuth) {
                basicCredentials?.let { builder.header("Authorization", it) }
            } else {
                val token = prefs.token
                if (token.isNotEmpty()) {
                    builder.header("Authorization", "Bearer $token")
                } else {
                    basicCredentials?.let { builder.header("Authorization", it) }
                }
            }
            // 默认只声明 JSON；纯文本接口（/session/token、/server/geocode）在请求上显式覆盖。
            // 不能全局强制 application/json，否则与 OpenAPI 的 text/plain 响应不一致。
            if (chain.request().header("Accept") == null) {
                builder.header("Accept", "application/json")
            }
            chain.proceed(builder.build())
        })
        .build()

    private fun url(path: String) = "${prefs.serverUrl}/api/${path.trimStart('/')}"

    fun setBasicCredentials(email: String, password: String) {
        basicCredentials = Credentials.basic(email, password, Charsets.UTF_8)
    }

    fun clearBasicCredentials() {
        basicCredentials = null
    }

    /** 底层同步请求，统一处理错误码（带上真实接口路径与服务端原始错误体，便于排查） */
    private fun execute(request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            val errBody = try {
                response.body?.string()
            } catch (e: Exception) {
                android.util.Log.w("Api", "读取错误响应体失败：${request.url.encodedPath}", e)
                null
            }
            response.close()
            val api = request.url.encodedPath
            val tail = if (!errBody.isNullOrBlank()) "：${errBody.take(300)}" else ""
            // 端点语义区分：POST /session 的 401 是账号密码错误；GET /session 的 404 是会话失效，
            // 不能都落到“登录已过期/接口不存在”的笼统文案。
            val message = when {
                code == 401 && request.method == "POST" && api.endsWith("/api/session") ->
                    AppText.get(R.string.api_error_login_401, tail)
                code == 404 && request.method == "GET" && api.endsWith("/api/session") ->
                    AppText.get(R.string.api_error_session_404, tail)
                else -> when (code) {
                    401 -> AppText.get(R.string.api_error_401, api, tail)
                    403 -> AppText.get(R.string.api_error_403, api, tail)
                    400 -> AppText.get(R.string.api_error_400, api, tail)
                    404 -> AppText.get(R.string.api_error_404, api, tail)
                    else -> AppText.get(R.string.api_error_other, api, code, tail)
                }
            }
            throw ApiException(message, code)
        }
        return response
    }

    private fun bodyOf(response: Response): String =
        response.body?.string().orEmpty().also { response.close() }

    // ---------------- 会话 ----------------

    /** 探测服务器是否可达（GET /api/server，未登录时也可用 Basic） */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url("server")).get().build()
            bodyOf(execute(req))
            true
        } catch (e: Exception) {
            throw ApiException(
                AppText.get(
                    R.string.api_error_connect,
                    e.message ?: AppText.get(R.string.common_network_error)
                )
            )
        }
    }

    /**
     * 登录：Basic 认证请求 /api/session 拿到当前用户，随后换取长期 token。
     * 成功返回用户信息；token 已写入 Prefs。
     */
    suspend fun login(email: String, password: String): SessionUser = withContext(Dispatchers.IO) {
        setBasicCredentials(email, password)
        // 关键：即使本地还留有旧 token，登录/换 token 两个请求也必须走 Basic，
        // 否则会以旧账号身份换发 token，或直接被旧 token 的 401 打断。
        forceBasicAuth = true
        try {
            // 1) 校验账号并拿用户信息
            val sessionReq = Request.Builder()
                .url(url("session"))
                .post(FormBody.Builder().add("email", email).add("password", password).build())
                .build()
            val user = SessionUser.from(JSONObject(bodyOf(execute(sessionReq))))

            // 2) 申请长期 token（6 个月有效期）
            val tokenReq = Request.Builder()
                .url(url("session/token"))
                .header("Accept", "text/plain")
                .post(FormBody.Builder().add("expiration", prefs.tokenExpiration()).build())
                .build()
            val token = bodyOf(execute(tokenReq)).trim().trim('"')
            if (token.isEmpty()) throw ApiException(AppText.get(R.string.api_error_no_token))
            prefs.token = token
            prefs.email = email
            user
        } finally {
            forceBasicAuth = false
            clearBasicCredentials()
        }
    }

    /** 退出：通知服务端注销 session，并清本地 token */
    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url("session")).delete().build()
            bodyOf(execute(req))
        } catch (e: Exception) {
            // 网络异常也要清本地，但保留日志便于排查注销接口异常
            android.util.Log.w("Api", "注销会话失败（本地 token 仍会清除）", e)
        } finally {
            prefs.logout()
        }
    }

    suspend fun currentUser(): SessionUser = withContext(Dispatchers.IO) {
        // 实测：Bearer 直接 GET /api/session 会因服务端无 HttpSession 返回 404。
        // 官方 GET /api/session 支持 ?token= 查询参数换取会话，故显式携带 token。
        val req = Request.Builder().url(url("session?token=${enc(prefs.token)}")).get().build()
        SessionUser.from(JSONObject(bodyOf(execute(req))))
    }

    // ---------------- 设备与位置 ----------------

    suspend fun devices(): List<Device> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("devices")).get().build()
        val arr = JSONArray(bodyOf(execute(req)))
        (0 until arr.length()).map { Device.from(arr.getJSONObject(it)) }
    }

    suspend fun groups(): List<Group> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("groups")).get().build()
        val arr = JSONArray(bodyOf(execute(req)))
        (0 until arr.length()).map { Group.from(arr.getJSONObject(it)) }
    }

    /** 最新位置（可带 deviceId，也可一次取全部） */
    suspend fun positions(deviceId: Long? = null): List<Position> = withContext(Dispatchers.IO) {
        val path = if (deviceId != null) "positions?deviceId=$deviceId" else "positions"
        val req = Request.Builder().url(url(path)).get().build()
        val arr = JSONArray(bodyOf(execute(req)))
        (0 until arr.length()).map { Position.from(arr.getJSONObject(it)) }
    }

    /**
     * 历史轨迹：GET /api/positions?deviceId=&from=&to=
     *
     * OpenAPI 6.17 已将 /api/reports/route 标记为 Deprecated，并建议改用 /api/positions
     * 携带 deviceId / from / to（可选 limit）。两者响应均为 Position 数组。
     */
    suspend fun route(deviceId: Long, from: String, to: String): List<Position> =
        withContext(Dispatchers.IO) {
            val q = "deviceId=$deviceId&from=${java.net.URLEncoder.encode(from, "UTF-8")}" +
                    "&to=${java.net.URLEncoder.encode(to, "UTF-8")}"
            val req = Request.Builder().url(url("positions?$q")).get().build()
            val arr = JSONArray(bodyOf(execute(req)))
            (0 until arr.length()).map { Position.from(arr.getJSONObject(it)) }
        }

    /** 逆地理编码（地址文字）；服务端需配置 geocoder，失败时返回 null 由 UI 降级显示坐标 */
    suspend fun geocode(latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                val q = "latitude=${java.net.URLEncoder.encode(latitude.toString(), "UTF-8")}" +
                        "&longitude=${java.net.URLEncoder.encode(longitude.toString(), "UTF-8")}"
                val req = Request.Builder()
                    .url(url("server/geocode?$q"))
                    .header("Accept", "text/plain")
                    .get()
                    .build()
                bodyOf(execute(req)).trim().trim('"').ifEmpty { null }
            } catch (e: Exception) {
                // 服务端未配置 geocoder 时返回 404/空，属预期降级；记录为调试级避免刷屏
                android.util.Log.d("Api", "逆地理编码不可用，界面降级显示坐标", e)
                null
            }
        }

    /**
     * 告警/事件列表：GET /api/reports/events（Traccar 官方唯一的时间段事件列表接口）。
     *
     * 官方 6.17 规范：
     *  - from / to 为必填，ISO-8601 UTC（如 2026-11-14T00:00:00Z）；缺失时服务端返回 400。
     *  - deviceId / groupId 为数组参数，官方说明"至少一个 deviceId 或 groupId"；
     *    实测不传时服务端返回空数组（不报错），但会查不到任何事件。
     *  - type 可选，不传即返回全部类型（源码 types.isEmpty() -> all=true），无需显式传 %。
     *  - 响应为 Event 数组，字段为 id/type/eventTime/deviceId/positionId/geofenceId/attributes。
     *
     * 重要：官方并不存在 GET /api/events 列表接口，只有 GET /api/events/{id} 按 ID 取单条
     * （实测 GET /api/events 返回 404）。旧实现回退到该路径，会把 /api/reports/events 的
     * 真实错误（401 登录过期 / 403 禁用报表 / 400 参数 / 500）覆盖成误导性的"接口不存在"，
     * 因此移除回退，只请求唯一正确的路径并把真实错误透传给界面。
     */
    suspend fun events(deviceIds: List<Long> = emptyList(), from: String? = null, to: String? = null): List<Event> =
        withContext(Dispatchers.IO) {
            if (from.isNullOrEmpty() || to.isNullOrEmpty()) {
                throw ApiException(AppText.get(R.string.api_error_events_range))
            }
            // OpenAPI：/reports/events 要求至少一个 deviceId 或 groupId。
            // 空列表时直接拒绝，避免发出必然无效的请求（调用方应提前展示空态）。
            if (deviceIds.isEmpty()) {
                throw ApiException(AppText.get(R.string.api_error_events_no_device))
            }
            val params = mutableListOf<String>()
            deviceIds.forEach { params += "deviceId=$it" }
            params += "from=${enc(from)}"
            params += "to=${enc(to)}"
            val q = "?${params.joinToString("&")}"
            val req = Request.Builder().url(url("reports/events$q")).get().build()
            val body = bodyOf(execute(req))
            try {
                val arr = JSONArray(body)
                (0 until arr.length()).map { Event.from(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                // 兜住所有解析异常（含模型解析期的运行时异常），并带上异常类型与响应片段。
                // 早期只捕获 JSONException，导致 Event.from() 的空指针异常裸奔到界面，
                // 又因 NPE 没有 message 而只显示「未知错误」，无法定位。
                val kind = e.javaClass.simpleName
                android.util.Log.e("Api", "事件解析失败（/api/reports/events，kind=$kind）", e)
                val detail = e.message?.takeIf { it.isNotBlank() }
                    ?: AppText.get(R.string.api_error_no_detail)
                val preview = body.take(160)
                    .ifEmpty { AppText.get(R.string.api_error_empty_body) }
                throw ApiException(AppText.get(R.string.api_error_parse, kind, detail, preview))
            }
        }

    /** 电子围栏列表：GET /api/geofences（服务端零改动） */
    suspend fun geofences(): List<Geofence> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("geofences")).get().build()
        val arr = JSONArray(bodyOf(execute(req)))
        (0 until arr.length()).map { Geofence.from(arr.getJSONObject(it)) }
    }

    /** 行程报表：GET /api/reports/trips（参数 deviceId + from + to，ISO-8601 UTC） */
    suspend fun trips(deviceId: Long, from: String, to: String): List<Trip> =
        withContext(Dispatchers.IO) {
            val q = "deviceId=$deviceId&from=${enc(from)}&to=${enc(to)}"
            val req = Request.Builder().url(url("reports/trips?$q")).get().build()
            val arr = JSONArray(bodyOf(execute(req)))
            (0 until arr.length()).map { Trip.from(arr.getJSONObject(it)) }
        }

    /** 停留报表：GET /api/reports/stops */
    suspend fun stops(deviceId: Long, from: String, to: String): List<Stop> =
        withContext(Dispatchers.IO) {
            val q = "deviceId=$deviceId&from=${enc(from)}&to=${enc(to)}"
            val req = Request.Builder().url(url("reports/stops?$q")).get().build()
            val arr = JSONArray(bodyOf(execute(req)))
            (0 until arr.length()).map { Stop.from(arr.getJSONObject(it)) }
        }

    /** 汇总报表：GET /api/reports/summary（服务端用数组包裹，即使是单设备，取首元素） */
    suspend fun summary(deviceId: Long, from: String, to: String): Summary? =
        withContext(Dispatchers.IO) {
            val q = "deviceId=$deviceId&from=${enc(from)}&to=${enc(to)}"
            val req = Request.Builder().url(url("reports/summary?$q")).get().build()
            val arr = JSONArray(bodyOf(execute(req)))
            if (arr.length() == 0) null else Summary.from(arr.getJSONObject(0))
        }

    // ---------------- 用户与设备管理（一期 P0，服务端零改动） ----------------

    /** JSON 请求体：Traccar 的 POST/PUT 对象接口均要求 application/json */
    private fun jsonBody(json: JSONObject) =
        json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    /** 读取单对象响应；空 body（个别版本 PUT 返回 204）时回退为传入对象 */
    private fun objectOr(body: String, fallback: JSONObject): JSONObject =
        if (body.isBlank()) fallback else JSONObject(body)

    /**
     * 设备列表（管理用）。GET /api/devices 返回当前用户可见的全部设备；
     * 与监控页共用同一路径，但解析成保留 raw 的 ManagedDevice 以便安全回写。
     */
    suspend fun managedDevices(): List<ManagedDevice> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("devices")).get().build()
        val arr = JSONArray(bodyOf(execute(req)))
        (0 until arr.length()).map { ManagedDevice.from(arr.getJSONObject(it)) }
    }

    /** 新建设备：POST /api/devices。uniqueId 全局唯一，重复时服务端返回 400 */
    suspend fun createDevice(device: ManagedDevice): ManagedDevice = withContext(Dispatchers.IO) {
        val body = device.toJson()
        val req = Request.Builder().url(url("devices")).post(jsonBody(body)).build()
        ManagedDevice.from(objectOr(bodyOf(execute(req)), body))
    }

    /** 编辑设备：PUT /api/devices/{id}（必须回带 id 与 uniqueId，见 toJson 以 raw 为底） */
    suspend fun updateDevice(device: ManagedDevice): ManagedDevice = withContext(Dispatchers.IO) {
        val body = device.toJson()
        val req = Request.Builder().url(url("devices/${device.id}")).put(jsonBody(body)).build()
        ManagedDevice.from(objectOr(bodyOf(execute(req)), body))
    }

    /** 删除设备：DELETE /api/devices/{id}，成功为 204 无响应体 */
    suspend fun deleteDevice(id: Long): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("devices/$id")).delete().build()
        bodyOf(execute(req))
        Unit
    }

    /**
     * 用户列表（管理用）：GET /api/users。
     * 实测非管理员且无子用户时返回 []（不报错）；管理员返回全部。
     */
    suspend fun managedUsers(): List<ManagedUser> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("users")).get().build()
        val arr = JSONArray(bodyOf(execute(req)))
        (0 until arr.length()).map { ManagedUser.from(arr.getJSONObject(it)) }
    }

    /** 新建用户：POST /api/users。password 必填；adminFields 决定是否提交敏感字段 */
    suspend fun createUser(user: ManagedUser, password: String, adminFields: Boolean): ManagedUser =
        withContext(Dispatchers.IO) {
            val body = user.toJson(password, adminFields)
            val req = Request.Builder().url(url("users")).post(jsonBody(body)).build()
            ManagedUser.from(objectOr(bodyOf(execute(req)), body))
        }

    /** 编辑用户：PUT /api/users/{id}。password 为空表示不改密码 */
    suspend fun updateUser(user: ManagedUser, password: String?, adminFields: Boolean): ManagedUser =
        withContext(Dispatchers.IO) {
            val body = user.toJson(password, adminFields)
            val req = Request.Builder().url(url("users/${user.id}")).put(jsonBody(body)).build()
            ManagedUser.from(objectOr(bodyOf(execute(req)), body))
        }

    /** 删除用户：DELETE /api/users/{id}，成功为 204 无响应体 */
    suspend fun deleteUser(id: Long): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("users/$id")).delete().build()
        bodyOf(execute(req))
        Unit
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** 供 WebSocket 构造连接地址：http→ws，https→wss */
    fun socketUrl(): String {
        val base = prefs.serverUrl
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "ws://$base"
        }
        return "$wsBase/api/socket?token=${java.net.URLEncoder.encode(prefs.token, "UTF-8")}"
    }
}

class ApiException(message: String, val code: Int = 0) : Exception(message)
