package com.traccar.nativeapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Prefs
import kotlinx.coroutines.launch

/** 登录页：账号密码 → Basic 校验 → 换 Bearer token → 进主页。 */
class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var cbRemember: CheckBox
    private lateinit var cbAuto: CheckBox
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var tvServerUrl: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        prefs = Prefs(this)

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        cbRemember = findViewById(R.id.cbRemember)
        cbAuto = findViewById(R.id.cbAuto)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        tvServerUrl = findViewById(R.id.tvServerUrl)

        // 仅回填记住的账号邮箱；密码不落盘，每次登录都需要用户重新输入
        if (prefs.rememberMe && prefs.email.isNotEmpty()) {
            etEmail.setText(prefs.email)
            cbRemember.isChecked = true
        }

        tvServerUrl.text = prefs.serverUrl

        btnLogin.setOnClickListener { attemptLogin() }
        findViewById<View>(R.id.tvServer).setOnClickListener {
            // 回到服务器配置页（保留已填地址），不跳转浏览器
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }

    private fun attemptLogin() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (email.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.login_empty_credentials))
            return
        }

        setLoading(true)
        val api = Api(prefs)
        lifecycleScope.launch {
            val error = try {
                api.login(email, password)
                prefs.rememberMe = cbRemember.isChecked
                prefs.autoLogin = cbAuto.isChecked
                // 只记住账号（邮箱），密码永不写入本地存储
                prefs.email = if (cbRemember.isChecked) email else ""
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.login_failed)
            }
            setLoading(false)
            if (error == null) {
                startActivity(
                    Intent(this@LoginActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } else {
                showError(error)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        btnLogin.text = if (loading) getString(R.string.loading) else getString(R.string.login_button)
        tvError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }
}
