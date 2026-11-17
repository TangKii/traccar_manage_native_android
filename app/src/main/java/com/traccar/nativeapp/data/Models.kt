package com.traccar.nativeapp.data

import com.traccar.nativeapp.R
import org.json.JSONObject

/**
 * 安全读取可空字符串字段。
 *
 * 切勿写成 `optString(name, null).takeIf { it.isNotEmpty() }`：org.json 在字段缺失时返回
 * Java null，Kotlin 按平台类型 String! 处理、不插空检查，lambda 里的 `it.isNotEmpty()`
 * 会直接抛 NullPointerException。该异常既没有 message（界面只会显示「未知错误」），
 * 也不属于 JSONException，能绕过 API 层的解析兜底 —— 这正是告警列表加载失败的真正原因。
 * 统一走这里：先判 null，再转字符串，缺失 / JSON null / 空串 / 字面量 "null" 一律返回 null。
 */
internal fun JSONObject.optText(name: String): String? {
    val value = opt(name) ?: return null
    if (value === JSONObject.NULL) return null
    val text = value.toString()
    return text.takeIf { it.isNotEmpty() && it != "null" }
}

/**
 * 设备（对应 Traccar GET /api/devices 的单条记录）。
 * 只取 V1 用得到的字段，其他字段忽略，服务端返回多余字段不会报错。
 */
data class Device(
    val id: Long,
    val name: String,
    val uniqueId: String = "",
    val status: String = "offline",      // online / offline / unknown
    val lastUpdate: String? = null,
    val positionId: Long? = null,
    val groupId: Long = 0,
    val category: String? = null,
    val disabled: Boolean = false,
    val latitude: Double? = null,        // 由 WebSocket/positions 补全
    val longitude: Double? = null,
    val speed: Double = 0.0,            // 原始值，单位「节」（Traccar 服务端口径）
    val course: Double = 0.0,
    val address: String? = null,
    val attributes: JSONObject = JSONObject(),
) {
    val isOnline: Boolean get() = status == "online"

    /** 展示用速度，单位 km/h（已由节换算，禁止再用原始 speed 直接格式化） */
    val speedKmh: Double get() = Units.knotsToKmh(speed)

    companion object {
        fun from(json: JSONObject) = Device(
            id = json.optLong("id"),
            name = json.optString("name", AppText.get(R.string.common_unknown_device)),
            uniqueId = json.optString("uniqueId", ""),
            status = json.optString("status", "offline"),
            lastUpdate = json.optText("lastUpdate"),
            positionId = json.optLong("positionId", -1).takeIf { it >= 0 },
            groupId = json.optLong("groupId", 0),
            category = json.optText("category"),
            disabled = json.optBoolean("disabled", false),
            attributes = json.optJSONObject("attributes") ?: JSONObject(),
        )
    }
}

/**
 * 位置点（对应 GET /api/positions 与 /api/reports/route）。
 * 经纬度是 WGS84 —— 画到高德(GCJ02)前必须做坐标转换，见 CoordTransform。
 */
data class Position(
    val id: Long = 0,
    val deviceId: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val speed: Double = 0.0,             // 原始值，单位「节」（Traccar 服务端口径）
    val course: Double = 0.0,
    val altitude: Double = 0.0,
    val accuracy: Double = 0.0,
    val deviceTime: String? = null,
    val fixTime: String? = null,
    val address: String? = null,
    val attributes: JSONObject = JSONObject(),
) {
    /** 展示用速度，单位 km/h（已由节换算） */
    val speedKmh: Double get() = Units.knotsToKmh(speed)

    /** 电量百分比（若设备上报），attributes 里常见键为 battery / batteryLevel / power */
    val battery: Int?
        get() = listOf("battery", "batteryLevel", "power", "io200")
            .firstNotNullOfOrNull { key ->
                val v = attributes.opt(key) ?: return@firstNotNullOfOrNull null
                when (v) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                    else -> null
                }
            }

    /** ACC 状态（若设备上报） */
    val ignition: Boolean? get() = attributes.opt("ignition") as? Boolean

    companion object {
        fun from(json: JSONObject) = Position(
            id = json.optLong("id"),
            deviceId = json.optLong("deviceId"),
            latitude = json.optDouble("latitude"),
            longitude = json.optDouble("longitude"),
            speed = json.optDouble("speed", 0.0),
            course = json.optDouble("course", 0.0),
            altitude = json.optDouble("altitude", 0.0),
            accuracy = json.optDouble("accuracy", 0.0),
            deviceTime = json.optText("deviceTime"),
            fixTime = json.optText("fixTime"),
            address = json.optText("address"),
            attributes = json.optJSONObject("attributes") ?: JSONObject(),
        )
    }
}

/**
 * 登录用户信息（GET /api/session、POST /api/session）。
 *
 * 除基础资料外，额外解析 Traccar 的权限字段 —— 实测 6.17 的 session 响应会一并返回
 * readonly / deviceReadonly / deviceLimit / userLimit / disabled / expirationTime 等，
 * 无需额外接口即可做「我的」页管理入口的角色门禁。
 *
 * 关键：这些只是 UI 侧的显示控制，真正的写权限仍由服务端 checkEdit/checkUserUpdate 兜底。
 */
data class SessionUser(
    val id: Long,
    val name: String,
    val email: String,
    val administrator: Boolean,
    val readonly: Boolean = false,
    val deviceReadonly: Boolean = false,
    val deviceLimit: Int = -1,
    val userLimit: Int = 0,
    val disabled: Boolean = false,
    val expirationTime: String? = null,
    val limitCommands: Boolean = false,
    val fixedEmail: Boolean = false,
) {
    /** 是否可进入用户管理：管理员，或 userLimit > 0（有子用户配额的经理） */
    val canManageUsers: Boolean get() = administrator || userLimit > 0

    /** 是否可编辑/删除已有设备。服务端 checkEdit 只在 addition 时检查 deviceLimit，故编辑不受其限 */
    val canEditDevices: Boolean get() = administrator || (!readonly && !deviceReadonly)

    /** 是否可新增设备：deviceLimit == 0 时 checkEdit(..., addition=true) 会直接拒绝 */
    val canAddDevices: Boolean get() = canEditDevices && (administrator || deviceLimit != 0)

    /** 是否可分配权限（GET/POST /api/permissions 需管理员），二期使用 */
    val canManagePermissions: Boolean get() = administrator

    companion object {
        fun from(json: JSONObject) = SessionUser(
            id = json.optLong("id"),
            name = json.optString("name", ""),
            email = json.optString("email", ""),
            administrator = json.optBoolean("administrator", false),
            readonly = json.optBoolean("readonly", false),
            deviceReadonly = json.optBoolean("deviceReadonly", false),
            deviceLimit = json.optInt("deviceLimit", -1),
            userLimit = json.optInt("userLimit", 0),
            disabled = json.optBoolean("disabled", false),
            expirationTime = json.optText("expirationTime"),
            limitCommands = json.optBoolean("limitCommands", false),
            fixedEmail = json.optBoolean("fixedEmail", false),
        )
    }
}

/**
 * 设备管理模型（严格对齐官方 Device schema）。
 *
 * 与监控列表用的 [Device] 分开：Device 里混入 latitude/speed/address 等 WS 派生字段，
 * 且会被高频刷新；写请求若用它容易误提交派生字段。这里额外保存 raw（服务端原始 JSON），
 * [toJson] 以 raw 为底只覆盖表单字段，确保 attributes/lastUpdate/positionId/status 不丢。
 */
data class ManagedDevice(
    val id: Long = 0,
    val name: String = "",
    val uniqueId: String = "",
    val status: String = "offline",
    val lastUpdate: String? = null,
    val positionId: Long? = null,
    val groupId: Long = 0,
    val phone: String? = null,
    val model: String? = null,
    val contact: String? = null,
    val category: String? = null,
    val disabled: Boolean = false,
    val attributes: JSONObject = JSONObject(),
    val raw: JSONObject = JSONObject(),
) {
    val isOnline: Boolean get() = status == "online"

    companion object {
        fun from(json: JSONObject) = ManagedDevice(
            id = json.optLong("id"),
            name = json.optString("name", ""),
            uniqueId = json.optString("uniqueId", ""),
            status = json.optString("status", "offline"),
            lastUpdate = json.optText("lastUpdate"),
            positionId = json.optLong("positionId", -1).takeIf { it >= 0 },
            groupId = json.optLong("groupId", 0),
            phone = json.optText("phone"),
            model = json.optText("model"),
            contact = json.optText("contact"),
            category = json.optText("category"),
            disabled = json.optBoolean("disabled", false),
            attributes = json.optJSONObject("attributes") ?: JSONObject(),
            raw = json,
        )
    }

    /** POST /api/devices 与 PUT /api/devices/{id} 的请求体 */
    fun toJson(): JSONObject = JSONObject(raw.toString()).apply {
        put("name", name.trim())
        put("uniqueId", uniqueId.trim())
        put("groupId", groupId)
        put("disabled", disabled)
        putNullable(this, "phone", phone)
        putNullable(this, "model", model)
        putNullable(this, "contact", contact)
        putNullable(this, "category", category)
        // status / lastUpdate / positionId / attributes 等沿用 raw，不覆盖
    }
}

/**
 * 用户管理模型（严格对齐官方 User schema）。
 *
 * [toJson] 的字段裁剪是安全关键：服务端 checkUserUpdate 会比对敏感字段，
 * 非管理员若提交 administrator/deviceLimit/userLimit/readonly/disabled 等变化会返回 400。
 * 因此非管理员编辑时只提交基础字段，敏感字段完全沿用 raw 原值。
 */
data class ManagedUser(
    val id: Long = 0,
    val name: String = "",
    val email: String = "",
    val phone: String? = null,
    val readonly: Boolean = false,
    val administrator: Boolean = false,
    val map: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val zoom: Int = 0,
    val coordinateFormat: String? = null,
    val disabled: Boolean = false,
    val expirationTime: String? = null,
    val deviceLimit: Int = -1,
    val userLimit: Int = 0,
    val deviceReadonly: Boolean = false,
    val limitCommands: Boolean = false,
    val fixedEmail: Boolean = false,
    val poiLayer: String? = null,
    val attributes: JSONObject = JSONObject(),
    val raw: JSONObject = JSONObject(),
) {
    /** 列表用的角色标签（文案来自 strings_fixes.xml） */
    val roleLabel: String
        get() = AppText.get(
            when {
                administrator -> R.string.role_admin
                readonly -> R.string.role_readonly
                disabled -> R.string.role_disabled
                userLimit > 0 -> R.string.role_manager
                else -> R.string.role_user
            }
        )

    companion object {
        fun from(json: JSONObject) = ManagedUser(
            id = json.optLong("id"),
            name = json.optString("name", ""),
            email = json.optString("email", ""),
            phone = json.optText("phone"),
            readonly = json.optBoolean("readonly", false),
            administrator = json.optBoolean("administrator", false),
            map = json.optText("map"),
            latitude = json.optDouble("latitude", 0.0),
            longitude = json.optDouble("longitude", 0.0),
            zoom = json.optInt("zoom", 0),
            coordinateFormat = json.optText("coordinateFormat"),
            disabled = json.optBoolean("disabled", false),
            expirationTime = json.optText("expirationTime"),
            deviceLimit = json.optInt("deviceLimit", -1),
            userLimit = json.optInt("userLimit", 0),
            deviceReadonly = json.optBoolean("deviceReadonly", false),
            limitCommands = json.optBoolean("limitCommands", false),
            fixedEmail = json.optBoolean("fixedEmail", false),
            poiLayer = json.optText("poiLayer"),
            attributes = json.optJSONObject("attributes") ?: JSONObject(),
            raw = json,
        )
    }

    /**
     * @param password 明文密码；为空表示「不修改密码」（服务端只写不读，回显为空）
     * @param adminFields 是否提交敏感字段。仅管理员传 true；非管理员沿用 raw 避免 checkUserUpdate 拒绝
     */
    fun toJson(password: String? = null, adminFields: Boolean = false): JSONObject =
        JSONObject(raw.toString()).apply {
            put("name", name.trim())
            put("email", email.trim())
            putNullable(this, "phone", phone)
            if (!password.isNullOrEmpty()) put("password", password) else remove("password")

            if (adminFields) {
                put("administrator", administrator)
                put("readonly", readonly)
                put("deviceReadonly", deviceReadonly)
                put("disabled", disabled)
                put("deviceLimit", deviceLimit)
                put("userLimit", userLimit)
                put("limitCommands", limitCommands)
                put("fixedEmail", fixedEmail)
                putNullable(this, "expirationTime", expirationTime)
            }
            // map/latitude/longitude/zoom/coordinateFormat/poiLayer/attributes 沿用 raw
        }
}

/** 向 JSONObject 写可空字符串：null/空时移除该键（服务端按 null 处理，等价于清空） */
private fun putNullable(json: JSONObject, key: String, value: String?) {
    if (value.isNullOrBlank()) json.remove(key) else json.put(key, value.trim())
}

/** 分组（GET /api/groups），设备列表按分组筛选用 */
data class Group(val id: Long, val name: String) {
    companion object {
        fun from(json: JSONObject) = Group(json.optLong("id"), json.optString("name", AppText.get(R.string.common_unknown_group)))
    }
}

/**
 * 告警/事件（对应 Traccar GET /api/events 的单条记录）。
 * Traccar 的事件 type 是英文枚举，这里做中文映射与配色，便于列表展示。
 */
data class Event(
    val id: Long,
    val type: String,
    val serverTime: String?,
    val deviceId: Long,
    val attributes: JSONObject = JSONObject(),
) {
    /** 中文标题（按 type 映射，未知类型回退为原字符串） */
    val title: String
        get() {
            // 部分协议/版本直接上报 type=sos；Traccar 标准做法是 type=alarm + attributes.alarm=sos
            if (type.equals("sos", true)) return AppText.get(R.string.event_sos)
            val a = attributes.optString("alarm", "").takeIf { it.isNotEmpty() && it != "null" }
            if (a != null) {
                if (a.contains("sos", ignoreCase = true)) return AppText.get(R.string.event_sos)
                if (type == "alarm") return AppText.get(R.string.event_alarm_with, a)
            }
            return typeTitle(type)
        }

    /** 详细描述：优先取 attributes.message，其次 alarm，都没有则为空 */
    val detail: String
        get() {
            val msg = attributes.optString("message", "").takeIf { it.isNotEmpty() && it != "null" }
            val alarm = attributes.optString("alarm", "").takeIf { it.isNotEmpty() && it != "null" }
            return (msg ?: alarm) ?: ""
        }

    companion object {
        fun from(json: JSONObject) = Event(
            id = json.optLong("id"),
            type = json.optString("type", "unknown"),
            // 官方 6.17 Event schema 只有 eventTime（没有 serverTime）；
            // serverTime 仅作为旧版本 / 网关改写字段的兼容回退。
            serverTime = json.optText("eventTime") ?: json.optText("serverTime"),
            deviceId = json.optLong("deviceId"),
            attributes = json.optJSONObject("attributes") ?: JSONObject(),
        )

        fun typeTitle(type: String): String {
            val res = typeTitleRes(type)
            // 未知类型保留服务端原字符串，避免显示空标题或 0x0 资源异常
            return if (res == 0) type else AppText.get(res)
        }

        /** 事件类型 → 字符串资源（供 UI 在非 Activity 上下文复用） */
        @androidx.annotation.StringRes
        fun typeTitleRes(type: String): Int = when (type) {
            "alarm" -> R.string.event_alarm
            "sos" -> R.string.event_sos
            "deviceOnline" -> R.string.event_online
            "deviceOffline" -> R.string.event_offline
            "deviceUnknown" -> R.string.event_unknown
            "deviceInactive" -> R.string.event_inactive
            "deviceMoving" -> R.string.event_moving
            "deviceStopped" -> R.string.event_stopped
            "deviceOverspeed" -> R.string.event_overspeed
            "deviceFuelDrop" -> R.string.event_fuel_drop
            "geofenceEnter" -> R.string.event_geofence_enter
            "geofenceExit" -> R.string.event_geofence_exit
            "ignitionOn" -> R.string.event_ignition_on
            "ignitionOff" -> R.string.event_ignition_off
            "driverChanged" -> R.string.event_driver_changed
            "maintenance" -> R.string.event_maintenance
            "textMessage" -> R.string.event_text_message
            "commandResult" -> R.string.event_command_result
            "queuedCommandSent" -> R.string.event_command_queued
            "media" -> R.string.event_media
            else -> 0
        }

        /** 类型图标（emoji），用于告警详情弹窗头部强调 */
        fun typeIcon(type: String): String = when (type) {
            "sos" -> "🆘"
            "alarm" -> "⚠️"
            "deviceOverspeed" -> "💨"
            "deviceFuelDrop" -> "⛽"
            "geofenceEnter" -> "📍"
            "geofenceExit" -> "🚪"
            "deviceOnline" -> "🟢"
            "deviceOffline", "deviceInactive" -> "⚫"
            "deviceMoving" -> "🚗"
            "deviceStopped" -> "⏹️"
            "ignitionOn" -> "🔑"
            "ignitionOff" -> "🔌"
            "maintenance" -> "🔧"
            "textMessage" -> "💬"
            "commandResult", "queuedCommandSent" -> "📧"
            else -> "🔔"
        }

        /** 按类型给一个语义色（资源 id） */
        fun typeColor(type: String): Int = when (type) {
            "alarm", "sos", "deviceOverspeed", "deviceFuelDrop" -> R.color.danger
            "geofenceEnter", "geofenceExit" -> R.color.brand
            "deviceOnline" -> R.color.online
            "deviceOffline", "deviceInactive" -> R.color.offline
            else -> R.color.text_secondary
        }

        /** SOS / alarm 类型单独判断（用于配色） */
        fun isAlarm(type: String, attributes: JSONObject): Boolean {
            if (type.equals("sos", true)) return true
            if (type == "alarm") {
                val a = attributes.optString("alarm", "")
                return a.isNotEmpty() && a != "null"
            }
            return false
        }
    }
}
