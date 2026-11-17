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
import com.traccar.nativeapp.data.ManagedUser
import com.traccar.nativeapp.data.Prefs
import kotlinx.coroutines.launch

/**
 * 用户管理列表（一期 P0，管理员 / 经理可见）。
 *
 * 入口本身由 MainActivity 按 SessionUser.canManageUsers 控制显隐；页面内再次拉取当前用户，
 * 决定是否显示「新增」与是否只读。非管理员提交编辑时不下发敏感字段（见 UserEditActivity）。
 * 布局与设备管理页共用 activity_device_manage.xml。
 */
class UserManageActivity : AppCompatActivity() {

    private lateinit var api: Api
    private lateinit var rv: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnAdd: Button
    private lateinit var adapter: Adapter

    private var all = listOf<ManagedUser>()
    private var canEdit = false
    private var canAdd = false

    private val editLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_manage)
        fitTopBar(findViewById(R.id.topBar))
        api = Api(Prefs(this))

        findViewById<TextView>(R.id.tvTitle).text = getString(R.string.manage_users_title)
        etSearch = findViewById(R.id.etSearch)
        etSearch.hint = getString(R.string.user_search_hint)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvHint = findViewById(R.id.tvHint)
        rv = findViewById(R.id.rvList)
        btnAdd = findViewById(R.id.btnAdd)
        btnAdd.text = getString(R.string.manage_add_user)

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
                android.util.Log.w("UserManage", "获取当前用户权限失败，按只读降级", e)
                null
            }
            canEdit = me?.canManageUsers ?: false
            canAdd = me?.administrator == true || (me?.userLimit ?: 0) > 0
            btnAdd.visibility = if (canAdd) View.VISIBLE else View.GONE
            if (me?.administrator != true) {
                tvHint.text = getString(R.string.user_manage_readonly)
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
                all = api.managedUsers()
                applyFilter()
                tvEmpty.text = getString(R.string.manage_users_empty)
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
            it.name.contains(kw, true) || it.email.contains(kw, true) ||
                    (it.phone ?: "").contains(kw, true)
        }
        adapter.update(list)
    }

    private fun openEdit(user: ManagedUser?) {
        val i = Intent(this, UserEditActivity::class.java)
        if (user != null) i.putExtra("userJson", user.raw.toString())
        editLauncher.launch(i)
    }

    class Adapter(
        private val onClick: (ManagedUser) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        private var items = listOf<ManagedUser>()

        fun update(list: List<ManagedUser>) {
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
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_manage_user, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val u = items[position]
            val ctx = holder.itemView.context
            holder.tvName.text = u.name.ifEmpty { ctx.getString(R.string.common_unknown_user) }
            holder.tvMeta.text = buildString {
                append(u.email.ifEmpty { "—" })
                if (!u.phone.isNullOrEmpty()) append(" · ").append(u.phone)
            }
            holder.tvRole.text = u.roleLabel
            holder.tvRole.setTextColor(
                ctx.getColor(
                    when {
                        u.administrator -> R.color.brand
                        u.disabled || u.readonly -> R.color.danger
                        else -> R.color.online
                    }
                )
            )
            holder.tvDisabled.visibility = if (u.disabled) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onClick(u) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvMeta: TextView = view.findViewById(R.id.tvMeta)
            val tvRole: TextView = view.findViewById(R.id.tvRole)
            val tvDisabled: TextView = view.findViewById(R.id.tvDisabled)
        }
    }
}
