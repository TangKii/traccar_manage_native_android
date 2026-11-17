package com.traccar.nativeapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Prefs
import kotlinx.coroutines.launch

/** 首次启动配置服务器地址。参考 Web 版 AppSetupPage：填地址 → 校验连通性 → 进登录页。 */
class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etServer: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        prefs = Prefs(this)

        etServer = findViewById(R.id.etServer)
        btnConnect = findViewById(R.id.btnConnect)
        tvError = findViewById(R.id.tvError)

        // 回显已有地址（从"我的→服务器设置"进来时）
        if (prefs.isConfigured) etServer.setText(prefs.serverUrl)

        btnConnect.setOnClickListener { attemptConnect() }
    }

    private fun attemptConnect() {
        var url = etServer.text.toString().trim().trimEnd('/')
        if (url.isEmpty()) {
            showError(getString(R.string.setup_enter_server))
            return
        }
        // 容错：用户只填了 IP:端口时自动补 http://
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
            etServer.setText(url)
        }
        if (url.count { it == ':' } < 1 && !url.startsWith("https://")) {
            showError(getString(R.string.setup_invalid_server))
            return
        }

        setLoading(true)
        prefs.serverUrl = url
        val api = Api(prefs)
        lifecycleScope.launch {
            val error = try {
                // 未登录时 /api/server 可能要求认证，任何"能连上"的响应都算成功
                try {
                    api.ping()
                } catch (e: com.traccar.nativeapp.data.ApiException) {
                    // 只有 401/403 才说明「服务器可达、只是需要登录」，视为连通；
                    // code=0 是网络/策略错误（如公网 http 被拒），必须如实报错，
                    // 否则填了不可达地址也会跳进登录页。
                    if (e.code !in setOf(401, 403)) throw e
                }
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.common_connect_failed)
            }
            setLoading(false)
            if (error == null) {
                startActivity(Intent(this@SetupActivity, LoginActivity::class.java))
                finish()
            } else {
                showError(error)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        btnConnect.isEnabled = !loading
        btnConnect.text = if (loading) getString(R.string.loading) else getString(R.string.setup_button)
        tvError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }
}
