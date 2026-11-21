package com.turboboost.system

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "N'TurboBoost funcionou"
        tv.textSize = 22f
        tv.setPadding(48, 200, 48, 48)
        setContentView(tv)
    }
}
