package com.sophy.admin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var device: TextView
    private lateinit var stats: TextView
    private lateinit var broadcastButton: Button
    private lateinit var autoSwitch: Switch
    private val requestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        startAdminServiceIfAllowed()
        refreshUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Deliberately do not stop the service. Closing the UI must not stop audio.
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), requestCode)
        else startAdminServiceIfAllowed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startAdminServiceIfAllowed()
            } else {
                Toast.makeText(this, "يجب السماح بالميكروفون لإرسال الصوت", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAdminServiceIfAllowed() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, AdminService::class.java).setAction(AdminService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "تعذر تشغيل خدمة الصوت: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 42, 32, 32)
            setBackgroundColor(getColor(R.color.sophy_bg))
        }

        val title = TextView(this).apply {
            text = "SOPHY ADMIN"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.sophy_text))
        }
        root.addView(title, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            textSize = 21f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
            setTextColor(getColor(R.color.sophy_accent))
        }
        root.addView(status, lp(LinearLayout.LayoutParams.MATCH_PARENT, -2))

        device = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.sophy_muted))
            setPadding(0, 0, 0, 20)
        }
        root.addView(device, lp(LinearLayout.LayoutParams.MATCH_PARENT, -2))

        broadcastButton = Button(this).apply {
            text = "بدء البث الصوتي"
            textSize = 19f
            isAllCaps = false
            setOnClickListener { toggleBroadcast() }
        }
        root.addView(broadcastButton, lp(LinearLayout.LayoutParams.MATCH_PARENT, 60))

        autoSwitch = Switch(this).apply {
            text = "البث تلقائياً عند العثور على الشاشة"
            textSize = 16f
            setTextColor(getColor(R.color.sophy_text))
            isChecked = AutoBroadcastPrefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                AutoBroadcastPrefs.setEnabled(this@MainActivity, checked)
                if (checked && AdminState.receiverFound.get() && !AdminState.broadcasting.get()) {
                    sendServiceAction(AdminService.ACTION_START_BROADCAST)
                }
                if (!checked && AdminState.broadcasting.get()) {
                    sendServiceAction(AdminService.ACTION_STOP_BROADCAST)
                }
            }
        }
        root.addView(autoSwitch, lp(LinearLayout.LayoutParams.MATCH_PARENT, -2))

        stats = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
            setTextColor(getColor(R.color.sophy_muted))
        }
        root.addView(stats, lp(LinearLayout.LayoutParams.MATCH_PARENT, -2))

        val hint = TextView(this).apply {
            text = "يكتشف الشاشة تلقائياً عبر Wi‑Fi.\nيمكنك إغلاق الواجهة وسيستمر البث في الخلفية."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
            setTextColor(getColor(R.color.sophy_muted))
        }
        root.addView(hint, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun toggleBroadcast() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        if (AdminState.broadcasting.get()) {
            AutoBroadcastPrefs.setEnabled(this, false)
            autoSwitch.isChecked = false
            sendServiceAction(AdminService.ACTION_STOP_BROADCAST)
        } else {
            sendServiceAction(AdminService.ACTION_START_BROADCAST)
        }
        rootRefreshLater()
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, AdminService::class.java).setAction(action)
        try { startService(intent) } catch (t: Throwable) {
            Toast.makeText(this, "تعذر تنفيذ الأمر: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshUi() {
        if (!::status.isInitialized) return
        when {
            AdminState.broadcasting.get() -> {
                status.text = "● البث يعمل"
                status.setTextColor(getColor(R.color.sophy_green))
                broadcastButton.text = "إيقاف البث"
            }
            AdminState.receiverFound.get() -> {
                status.text = "● الشاشة متصلة"
                status.setTextColor(getColor(R.color.sophy_accent))
                broadcastButton.text = "بدء البث الصوتي"
            }
            else -> {
                status.text = "● البحث عن الشاشة…"
                status.setTextColor(getColor(R.color.sophy_accent))
                broadcastButton.text = "بدء البث الصوتي"
            }
        }
        device.text = if (AdminState.receiverFound.get()) {
            "${AdminState.receiverName}\n${AdminState.receiverHost}:${AdminState.receiverPort}"
        } else AdminState.status
        stats.text = "الحزم المرسلة: ${AdminState.packets.get()}\n${AdminState.error}"
    }

    private fun rootRefreshLater() {
        window.decorView.postDelayed(object : Runnable {
            override fun run() {
                refreshUi()
                if (!isFinishing) window.decorView.postDelayed(this, 500)
            }
        }, 100)
    }

    private fun lp(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height).apply { this.weight = weight }
}
