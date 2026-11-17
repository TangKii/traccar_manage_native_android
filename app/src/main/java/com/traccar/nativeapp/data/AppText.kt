package com.traccar.nativeapp.data

import android.content.Context
import androidx.annotation.StringRes
import com.traccar.nativeapp.R

/**
 * 数据层访问字符串资源的入口。
 *
 * 协议映射（事件类型、角色、默认名称）与接口错误文案原先硬编码在 Kotlin 里，
 * 无法随系统语言/深色模式统一维护。这里在 Application 启动时注入 applicationContext，
 * 数据层通过 @StringRes 取文案；界面层仍直接使用 Activity/Service 的 getString。
 *
 * 未初始化时（例如纯 JVM 单元测试直接调用模型）返回资源 id 占位，不抛异常。
 */
object AppText {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any): String {
        val ctx = appContext ?: return "(string#$id)"
        return ctx.getString(id, *args)
    }
}