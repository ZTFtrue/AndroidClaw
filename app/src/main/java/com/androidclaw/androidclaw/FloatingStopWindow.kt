package com.androidclaw.androidclaw

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button

class FloatingStopWindow(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var stopButton: View? = null

    fun show(onStop: () -> Unit) {
        if (stopButton != null) return

        // 1. 先创建按钮
        val btn = Button(context)
        btn.text = "急停 (STOP)"
        btn.setBackgroundColor(Color.RED)
        btn.setTextColor(Color.WHITE)
        btn.setOnClickListener { onStop() }
        stopButton = btn

        // 2. 显式创建 LayoutParams (避免在构造函数里写一长串参数)
        // 避开 K2 编译器对复杂构造函数的解析 Bug
        val params = WindowManager.LayoutParams()

        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT

        // 设置层级：必须使用 TYPE_APPLICATION_OVERLAY
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // 设置标志：不拦截焦点
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        // 设置格式：透明
        params.format = PixelFormat.TRANSLUCENT

        // 设置位置
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 20
        params.y = 200

        // 3. 添加到窗口
        try {
            windowManager.addView(btn, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun dismiss() {
        stopButton?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            stopButton = null
        }
    }
}