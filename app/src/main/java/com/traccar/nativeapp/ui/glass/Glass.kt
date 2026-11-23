package com.traccar.nativeapp.ui.glass

import android.content.Context
import android.graphics.Outline
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider

/** Shared rounded clipping, elevation, and press feedback for floating surfaces. */
object Glass {

    /** 圆角裁切（rounded clipping）；半径按 dp 给出。 */
    fun clipRound(view: View, radiusDp: Float) {
        val r = radiusDp * view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
        }
        view.clipToOutline = true
    }

    /**
     * 保留布局中定义的表面背景，统一补上圆角裁切与投影。
     */
    fun apply(view: View, radiusDp: Float = 22f) {
        clipRound(view, radiusDp)
        // 配合 clipToOutline，阴影沿圆角轮廓投射。
        view.elevation = 10f * view.resources.displayMetrics.density
    }

    /**
     * 弹窗窗口：调暗遮罩 + 透明窗口背景（仅让对话框自身背景显示，不再做背后模糊）。
     */
    fun setupDialogGlass(dialog: android.app.Dialog, dimAmount: Float = 0.32f) {
        dialog.window?.let { w ->
            w.setDimAmount(dimAmount)
            w.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    /** 按压缩放反馈与轻微触觉反馈（90–140ms），保持点击仍可触发。系统开启「减少动态效果」时跳过缩放。 */
    fun pressScale(view: View, scale: Float = 0.94f, durationMs: Long = 110, haptic: Boolean = true) {
        val reduced = reduceMotion(view.context)
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (haptic) v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    if (!reduced) v.animate().scaleX(scale).scaleY(scale).setDuration(durationMs).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!reduced) v.animate().scaleX(1f).scaleY(1f).setDuration(durationMs).start()
                }
            }
            false
        }
    }

    /** 触发系统级轻微震动触觉反馈（Tab 切换、操作确认等） */
    fun hapticFeedback(view: View) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ---------------- 无障碍：减少动态效果 ----------------
    // Android 无 iOS 的 Reduce Transparency / Reduce Motion 同名公开 API。
    // 此处仅保留与动效相关的真实信号：系统动画缩放为 0（开发者选项「移除动画」）即代表减少动态效果。

    /** 是否应减少动态效果（跳过缩放回弹等动效，直接以终态呈现）。 */
    fun isReduceMotion(ctx: Context): Boolean = reduceMotion(ctx)

    private fun reduceMotion(ctx: Context): Boolean = try {
        Settings.Global.getFloat(
            ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (_: Exception) {
        false
    }
}
