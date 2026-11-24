package com.traccar.nativeapp.ui.glass

import android.content.Context
import android.graphics.Outline
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.traccar.nativeapp.R

/**
 * 浮层工具：圆角裁切 + 实色背景 + 投影 + 按压缩放。
 *
 * 之前的「液态玻璃」材质（半透明体 / 高光 / 描边 / 动态模糊）已移除，改为干净的实色卡片：
 *   1) 实色圆角背景（默认纯白），不再透出底层；
 *   2) 圆角裁切由 View.clipToOutline 完成（保证四角都圆润）；
 *   3) 10dp 投影让浮层在地图 / 浅色背景上都清晰“浮起”；
 *   4) 按压缩放反馈；
 *   5) 减少动态效果（系统动画缩放为 0）时跳过入场/按压动效。
 */
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
     * 给 View 套上实色圆角背景 + 投影。radiusDp 传宽高一半即可得圆形（FAB）。
     * 不再使用任何半透明 / 模糊玻璃材质。
     */
    fun apply(
        view: View,
        radiusDp: Float = 22f,
        @ColorRes tint: Int = R.color.surface,
        elevationDp: Float = 10f,
    ) {
        val ctx = view.context
        view.background = android.graphics.drawable.ColorDrawable(
            ContextCompat.getColor(ctx, tint)
        )
        clipRound(view, radiusDp)
        // 投影：让浮层清晰浮起，文字更易读。配合 clipToOutline，阴影沿圆角轮廓投射。
        // 传 0f 可关闭（地图悬浮按钮在深色底图上不需要投影，避免溢出图标范围）。
        view.elevation = elevationDp * view.resources.displayMetrics.density
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
