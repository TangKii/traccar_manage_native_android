package com.traccar.nativeapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Event

/**
 * 事件详情页：Jiumi 风格全屏卡片（图标瓦片头部 + 分组键值卡片 + 底部操作），
 * 替代原先的 Dialog 弹窗。
 */
class EventDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_detail)
        fitTopBar(findViewById(R.id.topBar))

        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val time = intent.getStringExtra(EXTRA_TIME).orEmpty()
        val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
        val deviceName = intent.getStringExtra(EXTRA_DEVICE).orEmpty()
        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, 0L)
        val urgent = intent.getBooleanExtra(EXTRA_URGENT, false)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.ivIcon).text = Event.typeIcon(type)
        findViewById<TextView>(R.id.tvTitle).text = title
        findViewById<TextView>(R.id.tvTypeLabel).text =
            getString(if (urgent) R.string.events_urgent else R.string.events_notice)

        bindRow(R.id.rowType, getString(R.string.label_type), title)
        bindRow(R.id.rowDevice, getString(R.string.label_device), deviceName)
        bindRow(R.id.rowTime, getString(R.string.label_event_device_time), fmtLocal(time))
        bindRow(R.id.rowDetail, getString(R.string.label_detail), detail)

        val btnLocate = findViewById<Button>(R.id.btnLocate)
        if (urgent) {
            btnLocate.visibility = View.VISIBLE
            btnLocate.setOnClickListener {
                startActivity(
                    Intent(this, DeviceDetailActivity::class.java)
                        .putExtra("deviceId", deviceId)
                )
            }
        } else {
            btnLocate.visibility = View.GONE
        }
    }

    /** 给一行包含布局绑定标签与值；值为空则整行隐藏 */
    private fun bindRow(rowRootId: Int, label: String, value: String) {
        val root = findViewById<ViewGroup>(rowRootId)
        root.findViewById<TextView>(R.id.tvLabel).text = label
        root.findViewById<TextView>(R.id.tvValue).text = value
        root.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TIME = "time"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_URGENT = "urgent"
    }
}
