package com.guom.karaoke

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 코드로 만드는 화면의 공통 모양 (카드·섹션 제목·버튼) */
class UiKit(private val activity: Activity) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    /** 둥근 흰색 카드 */
    fun card(vararg children: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        children.forEach { addView(it) }
    }

    fun title(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#1c1c1e"))
        setPadding(0, 0, 0, dp(6))
    }

    fun text(text: String = "", size: Float = 14f, color: String = "#3a3a3c") = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor(color))
        setLineSpacing(0f, 1.15f)
    }

    fun hint(text: String) = text(text, 12f, "#8e8e93")

    fun button(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) =
        Button(activity).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) Color.WHITE else Color.parseColor(if (danger) "#d70015" else "#1c1c1e"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (primary) "#3478f6" else "#eeeef0"))
                cornerRadius = dp(12).toFloat()
            }
            stateListAnimator = null
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(if (primary) 56 else 48)
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    /** 화면 바탕 (옅은 회색) + 세로 목록 */
    fun page() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(24))
        setBackgroundColor(Color.parseColor("#f2f2f7"))
    }

    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
