package com.traccar.nativeapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Event
import com.traccar.nativeapp.data.Prefs
import com.traccar.nativeapp.ui.glass.Glass
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 告警/事件中心：拉取最近 90 天的事件，支持类型筛选、下拉刷新、空态。
 * 进入即把“已读时间”更新为最新事件，使首页铃铛未读红点归零。
 */
class EventsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvEvents: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var filterChips: LinearLayout

    private var allEvents = listOf<Event>()
    private var deviceNameMap = mapOf<Long, String>()
    private var activeFilter = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_events)
        fitTopBar(findViewById(R.id.topBar))
        prefs = Prefs(this)
        api = Api(prefs)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        rvEvents = findViewById(R.id.rvEvents)
        tvEmpty = findViewById(R.id.tvEmpty)
        filterChips = findViewById(R.id.filterChips)

        rvEvents.layoutManager = LinearLayoutManager(this)
        rvEvents.adapter = Adapter { showDetail(it) }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        swipeRefresh.setOnRefreshListener { load() }

        load()
    }

    private fun load() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val (from, to) = last90DaysRange()
                // /api/reports/events 官方要求至少一个 deviceId；设备列表获取失败必须暴露，
                // 不能吞成空列表造成"查不到事件却显示成功/空数据"的假象。
                val devices = api.devices()
                deviceNameMap = devices.associate { it.id to it.name }
                if (devices.isEmpty()) {
                    // 文档要求至少一个 deviceId；没有设备时不发请求，直接展示空态
                    allEvents = emptyList()
                    buildFilterChips(emptyList())
                    (rvEvents.adapter as Adapter).update(emptyList(), emptyMap())
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = getString(R.string.events_no_device)
                    return@launch
                }
                val events = api.events(deviceIds = devices.map { it.id }, from = from, to = to)
                    .sortedByDescending { it.serverTime ?: "" }
                allEvents = events
                // 进入即标记已读：把已读时间更新为最新事件时间
                events.firstOrNull()?.serverTime?.let { prefs.lastEventSeenTime = it }
                buildFilterChips(events)
                applyFilter()
            } catch (e: Exception) {
                allEvents = emptyList()
                tvEmpty.visibility = View.VISIBLE
                // 直接显示接口返回的真实错误（含 HTTP 码、接口路径、异常类型与服务端原文）。
                // 即便异常没有 message 也要带上类名，杜绝无信息量的「未知错误」。
                val reason = e.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.event_exception_without_message, e.javaClass.simpleName)
                tvEmpty.text = getString(R.string.event_load_failed, reason)
                android.util.Log.e("EventsActivity", getString(R.string.event_load_failed_short), e)
                (rvEvents.adapter as Adapter).update(emptyList(), deviceNameMap)
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    /** 构建“全部 + 各类型”筛选标签 */
    private fun buildFilterChips(events: List<Event>) {
        filterChips.removeAllViews()
        val types = listOf("all") + events.map { it.type }.distinct()
        types.forEach { type ->
            val isSel = type == activeFilter
            val chip = TextView(this).apply {
                text = if (type == "all") getString(R.string.events_all) else Event.typeTitle(type)
                textSize = 13f
                setPadding(28, 16, 28, 16)
                background = ContextCompat.getDrawable(
                    this@EventsActivity,
                    if (isSel) R.drawable.bg_brand_button else R.drawable.bg_date_outline
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@EventsActivity,
                        if (isSel) android.R.color.white else R.color.brand
                    )
                )
                setOnClickListener {
                    activeFilter = type
                    buildFilterChips(allEvents)
                    applyFilter()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 12, 0)
            chip.layoutParams = lp
            filterChips.addView(chip)
        }
    }

    private fun applyFilter() {
        val list = if (activeFilter == "all") allEvents else allEvents.filter { it.type == activeFilter }
        (rvEvents.adapter as Adapter).update(list, deviceNameMap)
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) tvEmpty.text = getString(R.string.events_empty)
    }

    private fun showDetail(event: Event) {
        val deviceName = deviceNameMap[event.deviceId]
            ?: getString(R.string.event_device_label, event.deviceId)
        val urgent = Event.isAlarm(event.type, event.attributes)

        val dialog = android.app.Dialog(this).apply {
            setContentView(R.layout.dialog_event_detail)
            // 弹窗：窗口级调暗遮罩（不再做背后模糊），对话框自身为实色圆角卡片
            Glass.setupDialogGlass(this)
            // 宽度约 88%，保留两侧留白
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // 玻璃卡片圆角裁切（与 bg_glass 圆角一致）
            findViewById<View>(R.id.glassRoot)?.let { Glass.clipRound(it, 22f) }
            // 入场动画：缩放回弹 + 淡入（220–320ms），符合液态玻璃面板入场
            setOnShowListener {
                val root = findViewById<View>(R.id.glassRoot) ?: return@setOnShowListener
                // 减少动态效果：直接以终态呈现，不做缩放回弹
                if (Glass.isReduceMotion(this@EventsActivity)) {
                    root.alpha = 1f
                    root.scaleX = 1f
                    root.scaleY = 1f
                    return@setOnShowListener
                }
                root.alpha = 0f
                root.scaleX = 0.92f
                root.scaleY = 0.92f
                root.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f))
                    .start()
            }
        }

        // 头部：按类型取色 + 图标 + 标题 + 紧急/普通副标
        val colorRes = Event.typeColor(event.type)
        val headerColor = ContextCompat.getColor(this, colorRes)
        // 无障碍：彩色头部叠加 20% 黑遮罩，保证白字标题/副标题在红/蓝/绿/灰各类型色头上
        // 均满足 WCAG 4.5:1（原浅蓝副标题压红底仅 3.1:1，不达标）。
        val header = dialog.findViewById<View>(R.id.header)
        header.background = android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.ColorDrawable(headerColor),
                android.graphics.drawable.ColorDrawable(0x33000000.toInt())
            )
        )
        dialog.findViewById<TextView>(R.id.ivIcon).text = Event.typeIcon(event.type)
        dialog.findViewById<TextView>(R.id.tvTitle).text = event.title
        dialog.findViewById<TextView>(R.id.tvTypeLabel).text =
            getString(if (urgent) R.string.events_urgent else R.string.events_notice)

        // 键值正文（详情为空则隐藏该行）
        bindRow(dialog, R.id.rowType, getString(R.string.label_type), event.title)
        bindRow(dialog, R.id.rowDevice, getString(R.string.label_device), deviceName)
        bindRow(dialog, R.id.rowTime, getString(R.string.label_time), formatTime(event.serverTime))
        bindRow(dialog, R.id.rowDetail, getString(R.string.label_detail), event.detail)

        // 紧急类（SOS/报警）额外提供“定位设备”，跳转设备详情地图页
        val btnPrimary = dialog.findViewById<android.widget.Button>(R.id.btnPrimary)
        if (urgent) {
            btnPrimary.visibility = View.VISIBLE
            btnPrimary.setOnClickListener {
                startActivity(
                    android.content.Intent(this, DeviceDetailActivity::class.java)
                        .putExtra("deviceId", event.deviceId)
                )
                dialog.dismiss()
            }
        } else {
            btnPrimary.visibility = View.GONE
        }

        dialog.findViewById<android.widget.Button>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 给自定义弹窗中的一行包含布局绑定标签与值；值为空则整行隐藏 */
    private fun bindRow(dialog: android.app.Dialog, rowRootId: Int, label: String, value: String) {
        val root = dialog.findViewById<ViewGroup>(rowRootId)
        root.findViewById<TextView>(R.id.tvLabel).text = label
        root.findViewById<TextView>(R.id.tvValue).text = value
        root.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    private fun formatTime(iso: String?) = fmtLocal(iso)

    /** 最近 90 天时间范围（UTC，ISO-8601，与 Traccar 服务端一致） */
    private fun last90DaysRange(): Pair<String, String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val cal = Calendar.getInstance()
        val to = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_MONTH, -90)
        val from = fmt.format(cal.time)
        return from to to
    }

    class Adapter(
        private val onClick: (Event) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        private var items = listOf<Event>()
        private var names = mapOf<Long, String>()

        fun update(list: List<Event>, nameMap: Map<Long, String>) {
            val old = items
            val oldNames = names
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    old[oldPos].id == list[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = old[oldPos]
                    val b = list[newPos]
                    return a == b && oldNames[a.deviceId] == nameMap[b.deviceId]
                }
            })
            items = list
            names = nameMap
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.tvTitle.text = e.title
            holder.tvTime.text = fmtLocal(e.serverTime)
            holder.tvDevice.text = names[e.deviceId]
                ?: holder.itemView.context.getString(R.string.event_device_label, e.deviceId)
            if (e.detail.isNotEmpty()) {
                holder.tvDetail.visibility = View.VISIBLE
                holder.tvDetail.text = e.detail
            } else {
                holder.tvDetail.visibility = View.GONE
            }
            val colorRes = if (Event.isAlarm(e.type, e.attributes)) R.color.danger else Event.typeColor(e.type)
            holder.vTypeBar.setBackgroundColor(
                ContextCompat.getColor(holder.vTypeBar.context, colorRes)
            )
            holder.itemView.setOnClickListener { onClick(e) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val tvDevice: TextView = view.findViewById(R.id.tvDevice)
            val tvDetail: TextView = view.findViewById(R.id.tvDetail)
            val vTypeBar: View = view.findViewById(R.id.vTypeBar)
        }
    }
}
