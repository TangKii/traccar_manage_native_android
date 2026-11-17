package com.traccar.nativeapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.ManagedDevice
import com.traccar.nativeapp.data.Prefs
import kotlinx.coroutines.launch

/**
 * 设备管理列表（一期 P0）。
 *
 * 权限门禁：进入时 GET /api/session?token= 拉当前用户全字段，按
 * SessionUser.canEditDevices / canAddDevices 决定「新增」按钮与只读提示。
 * 真正的写操作仍由服务端 checkEdit 兜底，这里只做体验优化，不越权。
 * 布局与用户管理页共用 activity_device_manage.xml，仅标题/提示/按钮文字不同。
 */
class DeviceManageActivity : AppCompatActivity() {

    private lateinit var api: Api
    private lateinit var rv: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnAdd: Button
    private lateinit var adapter: Adapter

    private var all = listOf<ManagedDevice>()
    private var canEdit = false
    private var canAdd = false

    private val editLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 新增 / 编辑 / 删除返回后刷新列表
            load()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_manage)
        fitTopBar(findViewById(R.id.topBar))
        api = Api(Prefs(this))

        findViewById<TextView>(R.id.tvTitle).text = getString(R.string.manage_devices_title)
        etSearch = findViewById(R.id.etSearch)
        etSearch.hint = getString(R.string.device_search_hint)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvHint = findViewById(R.id.tvHint)
        rv = findViewById(R.id.rvList)
        btnAdd = findViewById(R.id.btnAdd)
        btnAdd.text = getString(R.string.manage_add_device)

        rv.layoutManager = LinearLayoutManager(this)
        adapter = Adapter { openEdit(it) }
        rv.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { load() }
        swipeRefresh.setOnRefreshListener { load() }
        btnAdd.setOnClickListener { openEdit(null) }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { applyFilter() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        checkPermissionThenLoad()
    }

    private fun checkPermissionThenLoad() {
        lifecycleScope.launch {
            val me = try {
                api.currentUser()
            } catch (e: Exception) {
                android.util.Log.w("DeviceManage", "获取当前用户权限失败，按只读降级", e)
                null
            }
            canEdit = me?.canEditDevices ?: false
            canAdd = me?.canAddDevices ?: false
            btnAdd.visibility = if (canAdd) View.VISIBLE else View.GONE
            if (!canEdit) {
                tvHint.text = getString(R.string.device_manage_readonly)
                tvHint.visibility = View.VISIBLE
            } else {
                tvHint.visibility = View.GONE
            }
            load()
        }
    }

    private fun load() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                all = api.managedDevices()
                applyFilter()
                tvEmpty.text = getString(R.string.manage_devices_empty)
                tvEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                all = emptyList()
                adapter.update(emptyList())
                tvEmpty.text = getString(
                    R.string.common_load_failed_with_reason,
                    e.message ?: getString(R.string.common_network_error)
                )
                tvEmpty.visibility = View.VISIBLE
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun applyFilter() {
        val kw = etSearch.text.toString().trim()
        val list = if (kw.isEmpty()) all else all.filter {
            it.name.contains(kw, true) || it.uniqueId.contains(kw, true) ||
                    (it.model ?: "").contains(kw, true)
        }
        adapter.update(list)
    }

    private fun openEdit(device: ManagedDevice?) {
        val i = Intent(this, DeviceEditActivity::class.java)
        if (device != null) i.putExtra("deviceJson", device.raw.toString())
        editLauncher.launch(i)
    }

    class Adapter(
        private val onClick: (ManagedDevice) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        private var items = listOf<ManagedDevice>()

        fun update(list: List<ManagedDevice>) {
            // 精确刷新：只重绘变化的条目，避免整表闪烁与滚动位置丢失
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    items[oldPos].id == list[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                    items[oldPos] == list[newPos]
            })
            items = list
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_manage_device, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            val ctx = holder.itemView.context
            holder.tvName.text = d.name.ifEmpty { ctx.getString(R.string.common_unknown_device) }
            holder.tvMeta.text = buildString {
                append("ID: ").append(d.uniqueId.ifEmpty { "—" })
                if (!d.model.isNullOrEmpty()) append(" · ").append(d.model)
                if (!d.phone.isNullOrEmpty()) append(" · ").append(d.phone)
            }
            val online = d.isOnline
            holder.tvStatus.text = ctx.getString(if (online) R.string.status_online else R.string.status_offline)
            holder.tvStatus.setTextColor(ctx.getColor(if (online) R.color.online else R.color.offline))
            holder.tvStatus.setBackgroundResource(
                if (online) R.drawable.bg_badge_online else R.drawable.bg_badge_offline
            )
            holder.tvDisabled.visibility = if (d.disabled) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onClick(d) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvMeta: TextView = view.findViewById(R.id.tvMeta)
            val tvStatus: TextView = view.findViewById(R.id.tvStatus)
            val tvDisabled: TextView = view.findViewById(R.id.tvDisabled)
        }
    }
}
