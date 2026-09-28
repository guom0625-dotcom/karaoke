package com.guom.karaoke

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = packageManager.getPackageInfo(packageName, 0)
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            text = "${getString(R.string.app_name)}\nv${info.versionName}"
        })
    }
}
