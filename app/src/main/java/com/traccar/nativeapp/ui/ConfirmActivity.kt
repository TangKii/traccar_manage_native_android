package com.traccar.nativeapp.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.traccar.nativeapp.R

/**
 * 通用沉浸式确认页：替代系统 AlertDialog 的模态确认/提示。
 *
 * 通过 extras 传入内容，确认后 setResult(RESULT_OK) 并返回，调用方在
 * onActivityResult / ActivityResultLauncher 回调里执行各自动作；取消返回 RESULT_CANCELED。
 *
 * extras:
 *  - title     顶栏标题
 *  - heading   居中大标题
 *  - message   正文
 *  - positive  确认按钮文字（缺省 R.string.confirm）
 *  - danger    是否危险色确认按钮（删除类操作）
 *  - single    单按钮模式（隐藏取消，崩溃日志提示用）
 *  - icon      图标 drawable 资源 id（缺省 ic_logout）
 */
class ConfirmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm)
        fitTopBar(findViewById(R.id.topBar))

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val heading = intent.getStringExtra(EXTRA_HEADING).orEmpty()
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        val positive = intent.getStringExtra(EXTRA_POSITIVE) ?: getString(R.string.confirm)
        val danger = intent.getBooleanExtra(EXTRA_DANGER, false)
        val single = intent.getBooleanExtra(EXTRA_SINGLE, false)
        val iconRes = intent.getIntExtra(EXTRA_ICON, R.drawable.ic_logout)

        findViewById<TextView>(R.id.tvTitle).text = title
        findViewById<TextView>(R.id.tvHeading).text = heading
        findViewById<TextView>(R.id.tvMessage).text = message
        findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        if (danger) {
            findViewById<ImageView>(R.id.ivIcon).setColorFilter(getColor(R.color.danger))
        }

        val btnConfirm = findViewById<Button>(R.id.btnConfirm)
        btnConfirm.text = positive
        if (danger) {
            btnConfirm.setBackgroundResource(R.drawable.selector_danger_outline)
            btnConfirm.setTextColor(getColor(R.color.danger))
        }
        btnConfirm.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }

        val btnCancel = findViewById<Button>(R.id.btnCancel)
        if (single) {
            btnCancel.visibility = View.GONE
        } else {
            btnCancel.setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        // 返回键等同取消；单按钮模式仍可返回（不强制卡住用户）
        findViewById<View>(R.id.btnBack).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_HEADING = "heading"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_POSITIVE = "positive"
        const val EXTRA_DANGER = "danger"
        const val EXTRA_SINGLE = "single"
        const val EXTRA_ICON = "icon"

        /** 构造一个跳往确认页的 Intent */
        fun intent(
            host: Activity,
            title: String,
            heading: String,
            message: String,
            positive: String? = null,
            danger: Boolean = false,
            single: Boolean = false,
            iconRes: Int = R.drawable.ic_logout,
        ) = Intent(host, ConfirmActivity::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_HEADING, heading)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_POSITIVE, positive)
            putExtra(EXTRA_DANGER, danger)
            putExtra(EXTRA_SINGLE, single)
            putExtra(EXTRA_ICON, iconRes)
        }
    }
}
