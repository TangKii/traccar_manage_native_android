package com.traccar.nativeapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Geofence
import com.traccar.nativeapp.data.Prefs
import kotlinx.coroutines.launch

/**
 * 电子围栏列表：展示所有围栏（圆/多边形）、类型与半径/顶点数，点条目进入地图查看范围。
 */
class GeofencesActivity : AppCompatActivity() {

    private lateinit var api: Api
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_geofences)
        fitTopBar(findViewById(R.id.topBar))
        api = Api(Prefs(this))
        rv = findViewById(R.id.rvGeofences)
        tvEmpty = findViewById(R.id.tvEmpty)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = Adapter(emptyList()) { openMap(it) }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val list = api.geofences()
                (rv.adapter as Adapter).update(list)
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                android.util.Log.w("Geofences", "加载围栏列表失败", e)
                tvEmpty.text = getString(
                    R.string.common_load_failed_with_reason,
                    e.message ?: getString(R.string.common_network_error)
                )
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun openMap(g: Geofence) {
        startActivity(
            android.content.Intent(this, GeofenceMapActivity::class.java)
                .putExtra("geofenceId", g.id)
                .putExtra("name", g.name)
                .putExtra("area", g.area)
        )
    }

    class Adapter(
        private var items: List<Geofence>,
        private val onClick: (Geofence) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        fun update(list: List<Geofence>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    old[oldPos].id == list[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                    old[oldPos] == list[newPos]
            })
            items = list
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_geofence, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val g = items[position]
            val ctx = holder.itemView.context
            holder.tvName.text = g.name
            holder.tvType.text = if (g.kind == "circle") ctx.getString(R.string.geofence_circle) else ctx.getString(R.string.geofence_polygon)
            holder.tvMeta.text = when {
                g.kind == "circle" -> ctx.getString(R.string.geofence_radius, "%.0f".format(g.circle()?.third ?: 0.0))
                else -> ctx.getString(R.string.geofence_points, g.polygon()?.size ?: 0)
            }
            holder.itemView.setOnClickListener { onClick(g) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvMeta: TextView = view.findViewById(R.id.tvMeta)
            val tvType: TextView = view.findViewById(R.id.tvType)
        }
    }
}
