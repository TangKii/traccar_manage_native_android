package com.traccar.nativeapp.data

/**
 * 单位换算。
 *
 * OpenAPI 6.17 只声明 speed 为 number、duration/engineHours 为 integer，未定义单位。
 * 本工程按 Traccar 服务端实现约定：位置/报表速度以「节」(knots) 返回，时长以毫秒返回；
 * 所有界面统一换算为 km/h 与本地化时长。若真实服务端单位不同，只需调整本对象与
 * Report 的解析注释，不要在各页面散落硬编码换算。
 *
 * 1 kn = 1.852 km/h。
 */
object Units {

    /** 1 节 = 1.852 公里/小时 */
    const val KNOT_TO_KMH = 1.852

    /** 节 → 公里/小时（负值与 0 原样返回，避免出现 -0.0） */
    fun knotsToKmh(knots: Double): Double =
        if (knots.isNaN() || knots <= 0.0) 0.0 else knots * KNOT_TO_KMH

    /** 米 → 公里 */
    fun metersToKm(meters: Double): Double = meters / 1000.0
}