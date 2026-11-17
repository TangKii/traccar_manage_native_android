package com.traccar.nativeapp.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.ManagedUser
import com.traccar.nativeapp.data.Prefs
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 用户新增 / 编辑（一期 P0）。
 *
 * 字段裁剪是安全关键：
 *  - 管理员：显示「权限与限额」高级区，提交时 adminFields=true；
 *  - 经理（userLimit>0 非管理员）：只能改基础资料，高级区隐藏，提交 adminFields=false，
 *    敏感字段完全沿用服务端 raw，避免 checkUserUpdate 返回 400；
 *  - 密码：新建必填；编辑留空表示不修改（服务端只写不读）。
 *  - 不允许删除当前登录账号，避免 token 立即失效导致掉线。
 */
class UserEditActivity : AppCompatActivity() {

    private lateinit var api: Api
    private lateinit var etName: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var tvPasswordLabel: TextView
    private lateinit var etPhone: EditText
    private lateinit var layoutAdmin: LinearLayout
    private lateinit var cbAdmin: CheckBox
    private lateinit var cbReadonly: CheckBox
    private lateinit var cbDeviceReadonly: CheckBox
    private lateinit var cbDisabled: CheckBox
    private lateinit var etDeviceLimit: EditText
    private lateinit var etUserLimit: EditText
    private lateinit var etExpiration: EditText
    private lateinit var tvError: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button

    private var original: ManagedUser? = null
    private var isNew = true
    private var isAdmin = false
    private var canEdit = false
    private var canAdd = false
    private var myUserId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_edit)
        fitTopBar(findViewById(R.id.topBar))
        api = Api(Prefs(this))

        etName = findViewById(R.id.etName)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        tvPasswordLabel = findViewById(R.id.tvPasswordLabel)
        etPhone = findViewById(R.id.etPhone)
        layoutAdmin = findViewById(R.id.layoutAdmin)
        cbAdmin = findViewById(R.id.cbAdmin)
        cbReadonly = findViewById(R.id.cbReadonly)
        cbDeviceReadonly = findViewById(R.id.cbDeviceReadonly)
        cbDisabled = findViewById(R.id.cbDisabled)
        etDeviceLimit = findViewById(R.id.etDeviceLimit)
        etUserLimit = findViewById(R.id.etUserLimit)
        etExpiration = findViewById(R.id.etExpiration)
        tvError = findViewById(R.id.tvError)
        tvHint = findViewById(R.id.tvHint)
        btnSave = findViewById(R.id.btnSave)
        btnDelete = findViewById(R.id.btnDelete)

        intent.getStringExtra("userJson")?.let {
            try {
                original = ManagedUser.from(JSONObject(it))
                isNew = false
            } catch (e: Exception) {
                android.util.Log.w("UserEdit", "解析传入的用户 JSON 失败，按新增处理", e)
            }
        }
        findViewById<TextView>(R.id.tvTitle).text =
            getString(if (isNew) R.string.manage_add_user else R.string.manage_edit_user)
        tvPasswordLabel.text = getString(
            if (isNew) R.string.user_password_new else R.string.user_password_edit
        )

        original?.let { fillForm(it) }
        setFormEnabled(false)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnSave.setOnClickListener { save() }
        btnDelete.setOnClickListener { confirmDelete() }

        checkPermission()
    }

    private fun fillForm(u: ManagedUser) {
        etName.setText(u.name)
        etEmail.setText(u.email)
        etPhone.setText(u.phone ?: "")
        cbAdmin.isChecked = u.administrator
        cbReadonly.isChecked = u.readonly
        cbDeviceReadonly.isChecked = u.deviceReadonly
        cbDisabled.isChecked = u.disabled
        etDeviceLimit.setText(u.deviceLimit.toString())
        etUserLimit.setText(u.userLimit.toString())
        etExpiration.setText(u.expirationTime ?: "")
    }

    private fun checkPermission() {
        lifecycleScope.launch {
            val me = try {
                api.currentUser()
            } catch (e: Exception) {
                android.util.Log.w("UserEdit", "获取当前用户权限失败，按只读降级", e)
                null
            }
            isAdmin = me?.administrator == true
            myUserId = me?.id ?: -1L
            canEdit = me?.canManageUsers ?: false
            canAdd = me?.administrator == true || (me?.userLimit ?: 0) > 0

            val allowed = if (isNew) canAdd else canEdit
            setFormEnabled(allowed)
            layoutAdmin.visibility = if (isAdmin) View.VISIBLE else View.GONE
            btnSave.visibility = if (allowed) View.VISIBLE else View.GONE

            // 不能删除自己：会让长期 token 立即失效
            val canDelete = !isNew && canEdit && original?.id != myUserId
            btnDelete.visibility = if (canDelete) View.VISIBLE else View.GONE

            // fixedEmail 账号不允许改邮箱（服务端 checkUserUpdate 保护）
            if (!isNew && original?.fixedEmail == true) {
                etEmail.isEnabled = false
                etEmail.hint = getString(R.string.user_email_fixed)
            }

            tvHint.text = when {
                !allowed -> getString(R.string.user_no_permission)
                !isAdmin -> getString(R.string.user_non_admin_hint)
                else -> ""
            }
            tvHint.visibility = if (tvHint.text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun setFormEnabled(enabled: Boolean) {
        etName.isEnabled = enabled
        etEmail.isEnabled = enabled
        etPassword.isEnabled = enabled
        etPhone.isEnabled = enabled
        cbAdmin.isEnabled = enabled
        cbReadonly.isEnabled = enabled
        cbDeviceReadonly.isEnabled = enabled
        cbDisabled.isEnabled = enabled
        etDeviceLimit.isEnabled = enabled
        etUserLimit.isEnabled = enabled
        etExpiration.isEnabled = enabled
    }

    private fun save() {
        val name = etName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (name.isEmpty()) { showError(getString(R.string.user_name_required)); return }
        if (email.isEmpty()) { showError(getString(R.string.user_email_required)); return }
        if (!email.contains("@")) { showError(getString(R.string.user_email_invalid)); return }
        if (isNew && password.isEmpty()) { showError(getString(R.string.user_password_required)); return }

        val expiration = if (isAdmin) etExpiration.text.toString().trim().ifEmpty { null } else original?.expirationTime
        if (isAdmin && expiration != null && !ISO_UTC.matches(expiration)) {
            showError(getString(R.string.user_expiration_format))
            return
        }

        val base = original ?: ManagedUser()
        val updated = base.copy(
            name = name,
            email = email,
            phone = etPhone.text.toString().trim().ifEmpty { null },
            // 非管理员沿用服务端原值；管理员按表单覆盖
            administrator = if (isAdmin) cbAdmin.isChecked else base.administrator,
            readonly = if (isAdmin) cbReadonly.isChecked else base.readonly,
            deviceReadonly = if (isAdmin) cbDeviceReadonly.isChecked else base.deviceReadonly,
            disabled = if (isAdmin) cbDisabled.isChecked else base.disabled,
            deviceLimit = if (isAdmin) (etDeviceLimit.text.toString().trim().toIntOrNull() ?: base.deviceLimit) else base.deviceLimit,
            userLimit = if (isAdmin) (etUserLimit.text.toString().trim().toIntOrNull() ?: base.userLimit) else base.userLimit,
            expirationTime = expiration,
        )

        btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                if (isNew) {
                    api.createUser(updated, password, isAdmin)
                } else {
                    api.updateUser(updated, password.ifEmpty { null }, isAdmin)
                }
                tvError.visibility = View.GONE
                Toast.makeText(this@UserEditActivity, getString(R.string.common_saved), Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.common_save_failed))
                btnSave.isEnabled = true
            }
        }
    }

    private fun confirmDelete() {
        val u = original ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.user_delete_title))
            .setMessage(getString(R.string.user_delete_message, u.name))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_confirm_delete)) { _, _ -> doDelete(u.id) }
            .show()
    }

    private fun doDelete(id: Long) {
        lifecycleScope.launch {
            try {
                api.deleteUser(id)
                Toast.makeText(this@UserEditActivity, getString(R.string.common_deleted), Toast.LENGTH_SHORT).show()
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

    companion object {
        private val ISO_UTC = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$""")
    }
}
