package com.traccar.nativeapp.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Group
import com.traccar.nativeapp.data.ManagedDevice
import com.traccar.nativeapp.data.Prefs
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 设备新增 / 编辑（一期 P0）。
 *
 * - 新增：POST /api/devices；编辑：PUT /api/devices/{id}（回写以服务端 raw 为底，见 ManagedDevice.toJson）
 * - 只读账号（canEditDevices=false）或超出 deviceLimit（canAddDevices=false）时表单禁用、隐藏保存/删除
 * - 删除二次确认并说明影响范围（会连带删除该设备的历史位置与事件）
 */
class DeviceEditActivity : AppCompatActivity() {

    private lateinit var api: Api
    private lateinit var etName: EditText
    private lateinit var etUniqueId: EditText
    private lateinit var spGroup: Spinner
    private lateinit var etPhone: EditText
    private lateinit var etModel: EditText
    private lateinit var etContact: EditText
    private lateinit var etCategory: EditText
    private lateinit var cbDisabled: CheckBox
    private lateinit var tvError: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button

    private var original: ManagedDevice? = null
    private var isNew = true
    private var groups = listOf<Group>()
    private var groupIds = listOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_edit)
        fitTopBar(findViewById(R.id.topBar))
        api = Api(Prefs(this))

        etName = findViewById(R.id.etName)
        etUniqueId = findViewById(R.id.etUniqueId)
        spGroup = findViewById(R.id.spGroup)
        etPhone = findViewById(R.id.etPhone)
        etModel = findViewById(R.id.etModel)
        etContact = findViewById(R.id.etContact)
        etCategory = findViewById(R.id.etCategory)
        cbDisabled = findViewById(R.id.cbDisabled)
        tvError = findViewById(R.id.tvError)
        tvHint = findViewById(R.id.tvHint)
        btnSave = findViewById(R.id.btnSave)
        btnDelete = findViewById(R.id.btnDelete)

        intent.getStringExtra("deviceJson")?.let {
            try {
                original = ManagedDevice.from(JSONObject(it))
                isNew = false
            } catch (e: Exception) {
                android.util.Log.w("DeviceEdit", "解析传入的设备 JSON 失败，按新增处理", e)
            }
        }
        findViewById<TextView>(R.id.tvTitle).text =
            getString(if (isNew) R.string.manage_add_device else R.string.manage_edit_device)
        btnDelete.visibility = if (isNew) View.GONE else View.VISIBLE

        original?.let { fillForm(it) }
        setFormEnabled(false) // 权限确认前先禁用，避免抢跑

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnSave.setOnClickListener { save() }
        btnDelete.setOnClickListener { confirmDelete() }

        checkPermission()
        loadGroups()
    }

    private fun fillForm(d: ManagedDevice) {
        etName.setText(d.name)
        etUniqueId.setText(d.uniqueId)
        etPhone.setText(d.phone ?: "")
        etModel.setText(d.model ?: "")
        etContact.setText(d.contact ?: "")
        etCategory.setText(d.category ?: "")
        cbDisabled.isChecked = d.disabled
    }

    private fun checkPermission() {
        lifecycleScope.launch {
            val me = try {
                api.currentUser()
            } catch (e: Exception) {
                android.util.Log.w("DeviceEdit", "获取当前用户权限失败，按只读降级", e)
                null
            }
            val allowed = if (isNew) (me?.canAddDevices ?: false) else (me?.canEditDevices ?: false)
            setFormEnabled(allowed)
            btnSave.visibility = if (allowed) View.VISIBLE else View.GONE
            btnDelete.visibility = if (!isNew && (me?.canEditDevices ?: false)) View.VISIBLE else View.GONE
            if (!allowed) {
                tvHint.text = if (isNew) {
                    getString(R.string.device_no_add_permission)
                } else {
                    getString(R.string.device_readonly_hint)
                }
                tvHint.visibility = View.VISIBLE
            } else {
                tvHint.visibility = View.GONE
            }
        }
    }

    private fun setFormEnabled(enabled: Boolean) {
        etName.isEnabled = enabled
        etUniqueId.isEnabled = enabled
        spGroup.isEnabled = enabled
        etPhone.isEnabled = enabled
        etModel.isEnabled = enabled
        etContact.isEnabled = enabled
        etCategory.isEnabled = enabled
        cbDisabled.isEnabled = enabled
    }

    private fun loadGroups() {
        lifecycleScope.launch {
            try {
                groups = api.groups()
            } catch (e: Exception) {
                android.util.Log.w("DeviceEdit", "加载分组失败，仅保留未分组选项", e)
                groups = emptyList()
            }
            val names = mutableListOf(getString(R.string.common_unknown_group))
            val ids = mutableListOf(0L)
            groups.forEach { names += it.name; ids += it.id }
            groupIds = ids
            spGroup.adapter = ArrayAdapter(this@DeviceEditActivity, android.R.layout.simple_spinner_item, names).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val target = original?.groupId ?: 0L
            val idx = groupIds.indexOf(target).takeIf { it >= 0 } ?: 0
            spGroup.setSelection(idx)
        }
    }

    private fun currentGroupId(): Long {
        val pos = spGroup.selectedItemPosition
        return groupIds.getOrNull(pos) ?: 0L
    }

    private fun save() {
        val name = etName.text.toString().trim()
        val uniqueId = etUniqueId.text.toString().trim()
        if (name.isEmpty()) {
            showError(getString(R.string.device_name_required))
            return
        }
        if (uniqueId.isEmpty()) {
            showError(getString(R.string.device_unique_id_required))
            return
        }
        val base = original ?: ManagedDevice()
        val updated = base.copy(
            name = name,
            uniqueId = uniqueId,
            groupId = currentGroupId(),
            phone = etPhone.text.toString().trim().ifEmpty { null },
            model = etModel.text.toString().trim().ifEmpty { null },
            contact = etContact.text.toString().trim().ifEmpty { null },
            category = etCategory.text.toString().trim().ifEmpty { null },
            disabled = cbDisabled.isChecked,
        )
        btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                if (isNew) api.createDevice(updated) else api.updateDevice(updated)
                tvError.visibility = View.GONE
                Toast.makeText(this@DeviceEditActivity, getString(R.string.common_saved), Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.common_save_failed))
                btnSave.isEnabled = true
            }
        }
    }

    private fun confirmDelete() {
        val d = original ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_delete_title))
            .setMessage(getString(R.string.device_delete_message, d.name))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_confirm_delete)) { _, _ -> doDelete(d.id) }
            .show()
    }

    private fun doDelete(id: Long) {
        lifecycleScope.launch {
            try {
                api.deleteDevice(id)
                Toast.makeText(this@DeviceEditActivity, getString(R.string.common_deleted), Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.common_delete_failed))
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }
}
