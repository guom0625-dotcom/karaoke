package com.guom.karaoke

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager

/**
 * 표시할 디스플레이 고르기. Tesor 처럼 차 화면을 별도(가상) 디스플레이로 띄우는 경우
 * 유튜브·크롬과 오버레이가 같은 화면에 있어야 한다.
 *  - 자동: 폰 기본 화면이 아닌, 켜져 있고 다른 앱 창을 허용하는(비공개 아님) 디스플레이. 없으면 기본 화면
 *  - 설정에서 특정 디스플레이를 고를 수도 있다
 */
object Displays {
    const val AUTO = -1

    private fun manager(context: Context) = context.getSystemService(DisplayManager::class.java)

    fun all(context: Context): List<Display> = manager(context).displays.toList()

    fun target(context: Context): Display? {
        val displays = all(context)
        val default = displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
        val chosen = Settings.displayId(context)
        if (chosen != AUTO) return displays.firstOrNull { it.displayId == chosen } ?: default
        return displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON && it.flags and Display.FLAG_PRIVATE == 0
        } ?: default
    }

    fun targetId(context: Context) = target(context)?.displayId ?: Display.DEFAULT_DISPLAY

    /** 오버레이 창을 목표 디스플레이에 붙이기 위한 컨텍스트 (크기·밀도도 그 화면 기준) */
    fun overlayContext(base: Context): Context {
        val d = target(base) ?: return base
        val dc = base.createDisplayContext(d)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dc.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            dc
        }
    }

    private fun launchOptions(context: Context): Bundle? {
        val id = targetId(context)
        if (id == Display.DEFAULT_DISPLAY) return null
        return ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
    }

    /** 목표 디스플레이에서 연다. 그 화면에 못 열면(권한 등) 기본 화면에서 연다. */
    fun startActivity(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = launchOptions(context)
        if (options != null) {
            try {
                context.startActivity(intent, options)
                return
            } catch (e: SecurityException) {
                // 다른 앱이 만든 비공개 화면 등 → 기본 화면으로
            } catch (e: IllegalArgumentException) {
            }
        }
        context.startActivity(intent)
    }

    fun changes(context: Context, listener: DisplayManager.DisplayListener) =
        manager(context).registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))

    fun removeListener(context: Context, listener: DisplayManager.DisplayListener) =
        manager(context).unregisterDisplayListener(listener)

    /** 설정 화면용: 디스플레이 목록 */
    fun describe(context: Context): String {
        val targetId = targetId(context)
        return all(context).joinToString("\n") { d ->
            val kind = buildList {
                if (d.displayId == Display.DEFAULT_DISPLAY) add("폰 화면")
                if (d.flags and Display.FLAG_PRIVATE != 0) add("비공개")
                if (d.flags and Display.FLAG_PRESENTATION != 0) add("외부 표시")
                if (d.state != Display.STATE_ON) add("꺼짐")
            }.joinToString(", ")
            val mark = if (d.displayId == targetId) "▶ " else "   "
            "$mark#${d.displayId} ${d.name}${if (kind.isNotEmpty()) " ($kind)" else ""}"
        }
    }
}
