package com.sophy.receiver

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var peer: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startReceiverService()
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun startReceiverService() {
        try {
            val intent = Intent(this, ReceiverService::class.java).apply {
                action = ReceiverService.ACTION_START
            }
            startForegroundService(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "تعذر تشغيل خدمة المستقبل", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(android.graphics.Color.rgb(7, 17, 31))
        }

        val title = TextView(this).apply {
            text = "SOPHY RECEIVER"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 16)
            setTextColor(android.graphics.Color.rgb(101, 216, 255))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        peer = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.rgb(157, 176, 196))
        }
        root.addView(peer, LinearLayout.LayoutParams(-1, -2))

        val hint = TextView(this).apply {
            text = "التطبيق يعمل تلقائياً ويمكن إغلاق هذه الواجهة.\nستبقى خدمة استقبال الصوت فعالة في الخلفية."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
            setTextColor(android.graphics.Color.rgb(157, 176, 196))
        }
        root.addView(hint, LinearLayout.LayoutParams(-1, -2))

        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        updateUi()
    }

    private fun updateUi() {
        if (!::status.isInitialized) return
        if (ReceiverState.connected.get()) {
            status.text = "● متصل"
            status.setTextColor(android.graphics.Color.rgb(95, 230, 140))
        } else {
            status.text = "● جاهز"
            status.setTextColor(android.graphics.Color.rgb(101, 216, 255))
        }
        peer.text = ReceiverState.peer
    }
}
