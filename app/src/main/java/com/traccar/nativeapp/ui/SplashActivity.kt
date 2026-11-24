package com.traccar.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import androidx.activity.result.contract.ActivityResultContracts
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
 * 品牌开屏 + 启动分流：
 *   未配置服务器 → SetupActivity
 *   已配置但未登录 → LoginActivity
 *   已登录（且勾选自动登录）→ 校验 token 后直接进 MainActivity，否则回登录页
 *
 * 开屏画面（[activity_splash]）先展示，路由校验（含 token 网络探测）与「最短展示时长」
 * 并行进行，两者都完成后才跳转——既不会因校验慢而黑屏干等，也不会一闪而过。
 * 若存在上次崩溃日志，先进提示，关闭后照常分流。
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }
    private var crashFile: File? = null

    /** 崩溃日志确认页：关闭后删除日志并继续启动分流 */
    private val crashConfirmLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        crashFile?.delete()
        crashFile = null
        startRouting()
    }
    /** 开屏最短展示时长：让品牌画面有存在感，同时不明显拖慢启动 */
    private val minShowMs = 900L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        playLogoAnimation()

        val crash = File(filesDir, "crash.log")
        if (crash.exists()) {
            val text = try {
                crash.readText().takeLast(4000)
            } catch (e: Exception) {
                android.util.Log.w("Splash", "读取崩溃日志失败", e)
                ""
            }
            if (text.isNotEmpty()) {
                crashFile = crash
                crashConfirmLauncher.launch(
                    ConfirmActivity.intent(
                        host = this,
                        title = getString(R.string.crash_log_title),
                        heading = getString(R.string.crash_log_title),
                        message = text,
                        positive = getString(android.R.string.ok),
                        single = true,
                        iconRes = R.drawable.ic_warning,
                    )
                )
                return
            }
        }
        startRouting()
    }

    /** Logo 轻微放大淡入，让开屏不是一张死图 */
    private fun playLogoAnimation() {
        val logo = findViewById<android.view.View>(R.id.splashLogo) ?: return
        logo.alpha = 0f
        val set = AnimationSet(true).apply {
            fillAfter = true
            addAnimation(AlphaAnimation(0f, 1f).apply { duration = 500 })
            addAnimation(ScaleAnimation(0.86f, 1f, 0.86f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply { duration = 600 })
        }
        logo.startAnimation(set)
    }

    /**
     * 路由校验与最短展示时长并行：谁慢等谁。
     * 无网/慢网时 validateToken 最多重试一次（约 800ms），不会无限等待。
     */
    private fun startRouting() {
        val startedAt = System.currentTimeMillis()
        lifecycleScope.launch {
            val decision = decideRoute()
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < minShowMs) delay(minShowMs - elapsed)
            mainHandler.post { applyRoute(decision) }
        }
    }

    private sealed interface Route {
        data class Go(val target: Class<*>, val clear: Boolean = true, val logout: Boolean = false) : Route
    }

    private suspend fun decideRoute(): Route = when {
        !prefs.isConfigured -> Route.Go(SetupActivity::class.java)
        !prefs.isLoggedIn -> Route.Go(LoginActivity::class.java)
        !prefs.autoLogin -> Route.Go(LoginActivity::class.java)
        else -> when (validateToken(prefs)) {
            TokenState.VALID, TokenState.NETWORK_ERROR -> Route.Go(MainActivity::class.java)
            TokenState.INVALID -> Route.Go(LoginActivity::class.java, logout = true)
        }
    }

    private fun applyRoute(route: Route) {
        when (route) {
            is Route.Go -> {
                if (route.logout) prefs.logout()
                go(route.target, route.clear)
            }
        }
    }

    private fun go(target: Class<*>, clear: Boolean) {
        val intent = Intent(this, target)
        if (clear) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        // 交叉淡入淡出，避免开屏与目标页之间闪一下黑底
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private enum class TokenState { VALID, INVALID, NETWORK_ERROR }

    /**
     * 探测 token 是否有效：
     * - 成功 → VALID
     * - HTTP 401/404（鉴权失败或会话不存在）→ INVALID，需重新登录
     * - 其它异常（网络/服务器）→ NETWORK_ERROR，不视为登录失效
     * 冷启动时第一次网络请求常因网络未就绪失败，这里重试一次以降噪。
     */
    private suspend fun validateToken(prefs: Prefs): TokenState {
        repeat(2) { attempt ->
            try {
                Api(prefs).currentUser()
                return TokenState.VALID
            } catch (e: ApiException) {
                if (e.code == 401 || e.code == 404) return TokenState.INVALID
            } catch (e: Exception) {
                android.util.Log.d("Splash", "token 校验网络异常（第 ${attempt + 1} 次）", e)
            }
            if (attempt == 0) delay(800)
        }
        return TokenState.NETWORK_ERROR
    }
}
