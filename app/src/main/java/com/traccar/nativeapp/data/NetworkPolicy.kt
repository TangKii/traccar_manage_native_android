package com.traccar.nativeapp.data

import com.traccar.nativeapp.BuildConfig
import com.traccar.nativeapp.R
import okhttp3.HttpUrl
import java.io.IOException
import java.util.Locale

/**
 * 明文 HTTP 访问策略。
 *
 * 背景：很多用户把 Traccar 部署在局域网并只开 http://192.168.x.x:8082，因此不能简单
 * 关闭明文；但 `usesCleartextTraffic=true` 是全局开关，若不加限制，用户把地址误填成
 * 公网域名时，登录 Basic 凭据与 Bearer token 会以明文经过运营商/代理。
 *
 * 这里在 OkHttp 请求前做一次运行期校验：
 *   - https 一律放行；
 *   - http 只允许 localhost、私有网段（10/8、172.16/12、192.168/16、169.254/16）、
 *     IPv6 回环 / ULA / 链路本地，以及单段主机名与 .local/.lan/.home 内网域名；
 *   - 其它公网 http 直接拒绝，并给出可操作的错误提示。
 *
 * 注意：本类只收紧「运行时能用哪些地址」，不改变 Manifest 的全局明文开关，
 * 因此局域网 http 部署仍然可用。
 */
object NetworkPolicy {

    class InsecureHttpException(message: String) : IOException(message)

    /** 判断主机是否属于本机 / 局域网 / 内网域名 */
    fun isPrivateHost(hostRaw: String): Boolean {
        val host = hostRaw.trim().trim('[', ']').lowercase(Locale.US)
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "::1" || host == "0.0.0.0") return true

        // IPv6 必须先于「单段主机名」判断：IPv6 字面量不含点，否则会被误判为内网主机名。
        // 只放行回环 / 唯一本地地址 fc00::/7 / 链路本地 fe80::/10。
        if (host.contains(':')) {
            return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
        }

        // 单段主机名（如 nas、traccar-server）视为内网名称；公网域名一定含点
        if (!host.contains('.')) return true
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home")) return true

        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host)
        if (ipv4 != null) {
            val parts = ipv4.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
            if (parts.any { it !in 0..255 }) return false
            val (a, b) = parts[0] to parts[1]
            return a == 10 || a == 127 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254)
        }
        return false
    }

    /** OkHttp 拦截器调用：不合规时抛出带中文说明的 IOException */
    fun check(url: HttpUrl) {
        // Debug 构建：为便于调试自建服务，放行任意地址的明文 http/ws（含公网 IP），
        // 因此调试版可以直接用 IP 登录；Release 构建仍执行下面的安全策略，
        // 公网地址必须使用 https（域名 + 证书），内网/本机 IP 的 http 依旧允许。
        if (BuildConfig.DEBUG) return
        // wss 与 https 同级放行；ws 与 http 一样只允许本机/内网
        if (url.isHttps || url.scheme.equals("wss", ignoreCase = true)) return
        if (isPrivateHost(url.host)) return
        throw InsecureHttpException(AppText.get(R.string.error_public_http, url.host))
    }
}