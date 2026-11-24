package com.traccar.nativeapp.ui

import android.content.Intent
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
import kotlinx.coroutines.launch
import java.util.Calendar

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
        // 全屏详情页（Jiumi 风格，替代弹窗）
        startActivity(
            Intent(this, EventDetailActivity::class.java)
                .putExtra(EventDetailActivity.EXTRA_TYPE, event.type)
                .putExtra(EventDetailActivity.EXTRA_TITLE, event.title)
                .putExtra(EventDetailActivity.EXTRA_TIME, event.serverTime)
                .putExtra(EventDetailActivity.EXTRA_DETAIL, event.detail)
                .putExtra(EventDetailActivity.EXTRA_DEVICE, deviceName)
                .putExtra(EventDetailActivity.EXTRA_DEVICE_ID, event.deviceId)
                .putExtra(EventDetailActivity.EXTRA_URGENT, urgent)
        )
    }

    /** 最近 90 天时间范围（UTC，ISO-8601，与 Traccar 服务端一致） */
    private fun last90DaysRange(): Pair<String, String> {
        val to = isoUtc(Calendar.getInstance())
        val from = isoUtc(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -90) })
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
            holder.ivIcon.text = Event.typeIcon(e.type)
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
            // 紧急事件标题用危险红强调，普通事件用主墨色（Jiumi 状态语义色）
            holder.tvTitle.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (Event.isAlarm(e.type, e.attributes)) R.color.danger else R.color.text_primary
                )
            )
            holder.itemView.setOnClickListener { onClick(e) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: TextView = view.findViewById(R.id.ivIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val tvDevice: TextView = view.findViewById(R.id.tvDevice)
            val tvDetail: TextView = view.findViewById(R.id.tvDetail)
        }
    }
}
