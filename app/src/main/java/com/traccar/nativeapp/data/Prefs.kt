package com.traccar.nativeapp.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * 本地持久化：服务器地址、Bearer token、记住的账号邮箱。
 * 对应 Web 版 appServer.js 里的 localStorage(appServerUrl/appAuthToken)。
 *
 * 安全约束：密码绝不落盘。旧版本曾把密码明文写入同一 SharedPreferences，
 * 升级后由 init 中的一次性迁移清除，避免历史明文长期残留。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("traccar_native", Context.MODE_PRIVATE)

    init {
        // 一次性清理旧版本遗留的明文密码（不读取、不回填、不再写入）
        if (sp.contains(KEY_LEGACY_PASSWORD)) {
            sp.edit().remove(KEY_LEGACY_PASSWORD).apply()
        }
    }

    /** 服务器根地址，例如 http://192.168.1.10:8082（不带尾斜杠） */
    var serverUrl: String
        get() = sp.getString(KEY_SERVER, "") ?: ""
        set(value) = sp.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()

    /** 登录后拿到的长期 token，之后所有 API 用 Bearer 携带 */
    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    /** 记住的邮箱（勾选“记住账号”时持久化，方便下次登录预填；密码不保存） */
    var email: String
        get() = sp.getString(KEY_EMAIL, "") ?: ""
        set(value) = sp.edit().putString(KEY_EMAIL, value).apply()

    var rememberMe: Boolean
        get() = sp.getBoolean(KEY_REMEMBER, false)
        set(value) = sp.edit().putBoolean(KEY_REMEMBER, value).apply()

    var autoLogin: Boolean
        get() = sp.getBoolean(KEY_AUTO, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO, value).apply()

    /** 选中的地图样式 id：autoNavi（高德道路）/ autoNaviSatellite（高德卫星） */
    var mapStyle: String
        get() = sp.getString(KEY_MAP, "autoNavi") ?: "autoNavi"
        set(value) = sp.edit().putString(KEY_MAP, value).apply()

    /** 上次查看告警的时间戳（官方 Event 的 eventTime），用于首页铃铛未读红点 */
    var lastEventSeenTime: String
        get() = sp.getString(KEY_EVENT_SEEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_EVENT_SEEN, value).apply()

    val isConfigured: Boolean get() = serverUrl.startsWith("http://") || serverUrl.startsWith("https://")
    val isLoggedIn: Boolean get() = token.isNotEmpty()

    /** 退出登录：清 token 与自动登录，但保留服务器地址与“记住账号”邮箱，方便重新登录 */
    fun logout() {
        sp.edit()
            .remove(KEY_TOKEN)
            .putBoolean(KEY_AUTO, false)
            .apply()
    }

    /** 清除全部（切换服务器时用） */
    fun clearAll() = sp.edit().clear().apply()

    /** 生成 6 个月后的 ISO 时间，用于 POST /api/session/token 的 expiration 参数 */
    fun tokenExpiration(): String {
        val cal = Calendar.getInstance().apply { add(Calendar.MONTH, 6) }
        // 服务端接受 ISO-8601；用简单格式即可（与 Web 版 dayjs().toISOString() 对齐）
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(cal.time)
    }

    companion object {
        private const val KEY_SERVER = "serverUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_EMAIL = "email"
        /** 旧版本明文密码键，仅用于启动时清理，不再读写 */
        private const val KEY_LEGACY_PASSWORD = "password"
        private const val KEY_REMEMBER = "rememberMe"
        private const val KEY_AUTO = "autoLogin"
        private const val KEY_MAP = "mapStyle"
        private const val KEY_EVENT_SEEN = "lastEventSeenTime"
    }
}
