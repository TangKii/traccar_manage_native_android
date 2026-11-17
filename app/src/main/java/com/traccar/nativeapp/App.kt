package com.traccar.nativeapp

import android.app.Application
import android.util.Log
import com.traccar.nativeapp.data.AppText
import org.maplibre.android.MapLibre
import java.io.File

/**
 * 应用入口：
 *  1) 初始化 MapLibre（必须在任何 MapView 创建前调用一次）；
 *  2) 兜底崩溃捕获：把未捕获异常的堆栈写入 filesDir/crash.log，
 *     方便无法连接电脑抓 logcat 时，通过“下次启动弹窗”截图反馈。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 数据层（事件类型映射 / 接口错误文案）依赖字符串资源，必须先注入
        AppText.init(this)
        MapLibre.getInstance(this)

        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sb = StringBuilder()
                sb.append("time=").append(System.currentTimeMillis()).append("\n")
                sb.append("thread=").append(thread.name).append("\n")
                sb.append(Log.getStackTraceString(throwable)).append("\n----\n")
                File(filesDir, "crash.log").appendText(sb.toString())
            } catch (e: Exception) {
                // 记录失败也不影响系统默认崩溃处理，但必须留下可检索日志
                Log.w("App", "写入崩溃日志失败", e)
            }
            // 继续走系统默认处理（弹“应用已停止”并结束进程）
            default?.uncaughtException(thread, throwable)
        }
    }
}
