package com.traccar.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.ApiException
import com.traccar.nativeapp.data.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 启动分流（对应草案页面原型）：
 *   未配置服务器 → SetupActivity
 *   已配置但未登录 → LoginActivity
 *   已登录（且勾选自动登录）→ 校验 token 后直接进 MainActivity，否则回登录页
 * 并在启动前展示上一次崩溃日志（如有），便于无电脑抓 logcat 时反馈。
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, "crash.log")
        if (crashFile.exists()) {
            val text = try {
                crashFile.readText().takeLast(4000)
            } catch (e: Exception) {
                android.util.Log.w("Splash", "读取崩溃日志失败", e)
                ""
            }
            if (text.isNotEmpty()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.crash_log_title))
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        crashFile.delete()
                        proceedRouting()
                    }
                    .setCancelable(false)
                    .show()
                return
            }
        }
        proceedRouting()
    }

    private fun proceedRouting() {
        val prefs = Prefs(this)
        when {
            !prefs.isConfigured -> go(SetupActivity::class.java, clear = true)
            !prefs.isLoggedIn -> go(LoginActivity::class.java, clear = true)
            !prefs.autoLogin -> go(LoginActivity::class.java, clear = true)
            else -> {
                // 自动登录：用现有 token 探测会话。
                // 关键：只有“鉴权失败(401，token 确实过期/无效)”才清 token 回登录页；
                // 网络抖动、冷启动网络未就绪等其它异常不要清 token，直接进主页由主页重试，
                // 否则一次瞬时网络失败就会把“自动登录”永久废除（之前的根因）。
                lifecycleScope.launch {
                    when (val state = validateToken(prefs)) {
                        TokenState.VALID ->
                            Handler(Looper.getMainLooper()).post { go(MainActivity::class.java, clear = true) }
                        TokenState.INVALID ->
                            Handler(Looper.getMainLooper()).post {
                                prefs.logout()
                                go(LoginActivity::class.java, clear = true)
                            }
                        TokenState.NETWORK_ERROR ->
                            // token 可能仍有效，直接进主页，主页会重新加载/重试
                            Handler(Looper.getMainLooper()).post { go(MainActivity::class.java, clear = true) }
                    }
                }
            }
        }
    }

    private fun go(target: Class<*>, clear: Boolean) {
        val intent = Intent(this, target)
        if (clear) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private enum class TokenState { VALID, INVALID, NETWORK_ERROR }

    /**
     * 探测 token 是否有效：
     * - 成功 → VALID
     * - HTTP 401（鉴权失败）→ INVALID，需重新登录
     * - 其它异常（网络/服务器）→ NETWORK_ERROR，不视为登录失效
     * 冷启动时第一次网络请求常因网络未就绪失败，这里重试一次以降噪。
     */
    private suspend fun validateToken(prefs: Prefs): TokenState {
        repeat(2) { attempt ->
            try {
                Api(prefs).currentUser()
                return TokenState.VALID
            } catch (e: ApiException) {
                // 401：token 失效；404：OpenAPI 对 GET /session 定义的“会话不存在”，
                // 同样说明当前 token 不可用，必须清掉后回登录页，不能带病进主页。
                if (e.code == 401 || e.code == 404) return TokenState.INVALID
            } catch (e: Exception) {
                // 连接异常（超时/无网络/域名解析失败等）：保留 token，主页会重试
                android.util.Log.d("Splash", "token 校验网络异常（第 ${attempt + 1} 次）", e)
            }
            if (attempt == 0) delay(800)
        }
        return TokenState.NETWORK_ERROR
    }
}
