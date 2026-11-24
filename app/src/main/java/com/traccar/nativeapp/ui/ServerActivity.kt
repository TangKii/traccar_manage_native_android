package com.traccar.nativeapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Prefs

/** 服务器设置页：展示当前地址，「切换服务器」按钮去掉确认弹窗，直接跳配置页。 */
class ServerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)
        fitTopBar(findViewById(R.id.topBar))
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val prefs = Prefs(this)
        findViewById<TextView>(R.id.tvServer).text = prefs.serverUrl

        findViewById<Button>(R.id.btnSwitch).setOnClickListener {
            // 停止后台推送 + 清登录态，跳配置页（保留已填地址）
            NotificationService.stop(this)
            prefs.logout()
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }
}
