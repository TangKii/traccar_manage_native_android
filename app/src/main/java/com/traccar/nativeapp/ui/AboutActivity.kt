package com.traccar.nativeapp.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.traccar.nativeapp.R

/** 关于页：Jiumi 风格全屏卡片，替代原先的 AlertDialog。 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        fitTopBar(findViewById(R.id.topBar))
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
        } catch (e: Exception) {
            "—"
        }
        findViewById<TextView>(R.id.tvVersion).text =
            getString(R.string.about_version_fmt, version)

        bindRow(R.id.rowServer, getString(R.string.about_label_server), getString(R.string.about_server_value))
        bindRow(R.id.rowMap, getString(R.string.about_label_map), getString(R.string.about_map_value))
        bindRow(R.id.rowClient, getString(R.string.about_label_client), getString(R.string.about_client_value))
    }

    /** 给一行包含布局绑定标签与值 */
    private fun bindRow(rowRootId: Int, label: String, value: String) {
        val root = findViewById<ViewGroup>(rowRootId)
        root.findViewById<TextView>(R.id.tvLabel).text = label
        root.findViewById<TextView>(R.id.tvValue).text = value
    }
}
