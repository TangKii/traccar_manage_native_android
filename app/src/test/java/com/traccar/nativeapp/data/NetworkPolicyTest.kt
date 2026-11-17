package com.traccar.nativeapp.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun localhostAndLoopbackArePrivate() {
        assertTrue(NetworkPolicy.isPrivateHost("localhost"))
        assertTrue(NetworkPolicy.isPrivateHost("127.0.0.1"))
        assertTrue(NetworkPolicy.isPrivateHost("::1"))
        assertTrue(NetworkPolicy.isPrivateHost("[::1]"))
    }

    @Test
    fun rfc1918RangesArePrivate() {
        assertTrue(NetworkPolicy.isPrivateHost("10.0.0.5"))
        assertTrue(NetworkPolicy.isPrivateHost("10.0.2.2")) // Android 模拟器宿主机
        assertTrue(NetworkPolicy.isPrivateHost("192.168.1.10"))
        assertTrue(NetworkPolicy.isPrivateHost("172.16.0.1"))
        assertTrue(NetworkPolicy.isPrivateHost("172.31.255.254"))
        assertTrue(NetworkPolicy.isPrivateHost("169.254.10.10"))
    }

    @Test
    fun publicAddressesAreRejectedForCleartext() {
        assertFalse(NetworkPolicy.isPrivateHost("8.8.8.8"))
        assertFalse(NetworkPolicy.isPrivateHost("172.32.0.1"))
        assertFalse(NetworkPolicy.isPrivateHost("example.com"))
        assertFalse(NetworkPolicy.isPrivateHost("2001:db8::1"))
    }

    @Test
    fun singleLabelAndLocalDomainsAreAllowed() {
        assertTrue(NetworkPolicy.isPrivateHost("nas"))
        assertTrue(NetworkPolicy.isPrivateHost("traccar-server"))
        assertTrue(NetworkPolicy.isPrivateHost("traccar.local"))
        assertTrue(NetworkPolicy.isPrivateHost("traccar.lan"))
        assertTrue(NetworkPolicy.isPrivateHost("traccar.home"))
    }

    @Test
    fun ipv6UniqueLocalAndLinkLocalAreAllowed() {
        assertTrue(NetworkPolicy.isPrivateHost("fd00::1"))
        assertTrue(NetworkPolicy.isPrivateHost("fc00::1"))
        assertTrue(NetworkPolicy.isPrivateHost("fe80::1"))
    }

    @Test
    fun checkAllowsHttpsAndLanHttp() {
        // https 一律放行
        NetworkPolicy.check("https://example.com".toHttpUrl())
        // 局域网 http 必须保持可用（用户自建 Traccar 的典型部署）
        NetworkPolicy.check("http://192.168.1.10:8082".toHttpUrl())
        NetworkPolicy.check("http://10.0.2.2:8082".toHttpUrl())
        NetworkPolicy.check("http://nas:8082".toHttpUrl())
    }

    @Test
    fun checkRejectsPublicCleartextHttp() {
        try {
            NetworkPolicy.check("http://example.com".toHttpUrl())
            fail("公网 http 应当被拒绝")
        } catch (_: NetworkPolicy.InsecureHttpException) {
            // 期望路径：抛出策略异常（文案依赖 Android 资源，纯 JVM 测试不校验具体文字）
        }
    }
}