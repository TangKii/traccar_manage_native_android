package com.traccar.nativeapp.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import com.traccar.nativeapp.R
import java.util.Calendar

/**
 * 沉浸式日期时间选择页：同页选日期 + 小时（分钟/秒/毫秒归零），
 * 替代系统 DatePickerDialog → TimePickerDialog 两步弹窗。
 *
 * extras:
 *  - initialMillis 初始时间的 epoch 毫秒（缺省=当前时间）
 * 返回:
 *  - RESULT_OK + resultMillis 所选时间毫秒；取消返回 RESULT_CANCELED
 */
class DateTimePickerActivity : AppCompatActivity() {

    private lateinit var datePicker: DatePicker
    private lateinit var timePicker: TimePicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_datetime_picker)
        fitTopBar(findViewById(R.id.topBar))

        datePicker = findViewById(R.id.datePicker)
        timePicker = findViewById(R.id.timePicker)
        timePicker.setIs24HourView(true)

        val initial = Calendar.getInstance().apply {
            timeInMillis = intent.getLongExtra(EXTRA_INITIAL_MILLIS, System.currentTimeMillis())
        }
        datePicker.updateDate(initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH))
        @Suppress("DEPRECATION")
        timePicker.currentHour = initial.get(Calendar.HOUR_OF_DAY)

        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            val cal = Calendar.getInstance().apply {
                set(datePicker.year, datePicker.month, datePicker.dayOfMonth,
                    @Suppress("DEPRECATION") timePicker.currentHour, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_MILLIS, cal.timeInMillis))
            finish()
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    companion object {
        const val EXTRA_INITIAL_MILLIS = "initialMillis"
        const val EXTRA_RESULT_MILLIS = "resultMillis"

        fun intent(host: Activity, initialMillis: Long) =
            Intent(host, DateTimePickerActivity::class.java).putExtra(EXTRA_INITIAL_MILLIS, initialMillis)
    }
}
