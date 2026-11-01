package com.ettlinger.wearrecorder

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.view.View
import android.widget.*

class SettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        fun text(value: String) = TextView(this).apply {
            text = value; textSize = 14f; setTextColor(0xffeeeeee.toInt()); column.addView(this)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; column.addView(this); setOnClickListener { action() }
        }
        text("Rave AI settings • ${BuildConfig.VERSION_NAME}")
        text(BatteryMonitor.label)
        text("Recording mode (applies next start)")
        val modes = RadioGroup(this)
        val continuous = RadioButton(this).apply { id = View.generateViewId(); text = "Continuous" }
        val speech = RadioButton(this).apply { id = View.generateViewId(); text = "Speech only (beta)" }
        modes.addView(continuous); modes.addView(speech); column.addView(modes)
        modes.check(if (AppSettings.speechOnly(this)) speech.id else continuous.id)
        modes.setOnCheckedChangeListener { _, id ->
            AppSettings.set(this, AppSettings.MODE, id == speech.id)
            AppLog.i("Settings", "Recording mode changed; speech_only=${id == speech.id}")
            RecorderStatus.refreshTile(this)
        }
        text("Speech only saves 1 second before speech and 2 seconds after it. The microphone still listens. Quiet speech can be missed; use Continuous when completeness matters.")
        text("Speech sensitivity (next start)")
        val sensitivity = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("High sensitivity", "Balanced", "Noise rejection"))
            setSelection(AppSettings.sensitivity(this@SettingsActivity))
        }
        column.addView(sensitivity)
        sensitivity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != AppSettings.sensitivity(this@SettingsActivity)) {
                    AppSettings.setSensitivity(this@SettingsActivity, position)
                    AppLog.i("Settings", "Speech sensitivity changed to $position")
                }
            }
        }
        val logging = Switch(this).apply { text = "Enable logging"; isChecked = AppSettings.logging(this@SettingsActivity) }
        column.addView(logging)
        text("Opt-in action and battery logs upload to Rave AI/logs. No audio or sign-in secrets. Off pauses logging and log uploads; pending logs stay on watch. Limit: 20 MiB.")
        val diagnosticStatus = text("Diagnostics: ${if (AppSettings.logging(this)) AppLog.status else "Off"}")
        logging.setOnCheckedChangeListener { _, enabled ->
            if (!enabled) { AppLog.i("Settings", "Logging disabled"); AppLog.flush() }
            AppSettings.set(this, AppSettings.LOGGING, enabled)
            if (enabled) { AppLog.i("Settings", "Logging enabled"); AppLog.flush(); UploadWorker.kick(this) }
            diagnosticStatus.text = "Diagnostics: ${if (enabled) AppLog.status else "Off"}"
        }
        val unmetered = Switch(this).apply { text = "Unmetered uploads only"; isChecked = AppSettings.unmetered(this@SettingsActivity) }
        column.addView(unmetered)
        text("When enabled, recordings and logs wait for an unmetered connection, usually Wi-Fi.")
        unmetered.setOnCheckedChangeListener { _, enabled ->
            AppSettings.set(this, AppSettings.UNMETERED, enabled)
            AppLog.i("Settings", "Unmetered upload preference changed to $enabled")
            UploadWorker.schedule(this); UploadWorker.kick(this, true)
        }
        button("SYNC NOW") { AppLog.i("Settings", "Manual sync requested"); AppLog.flush(); UploadWorker.kick(this, true); Toast.makeText(this, "Sync queued", Toast.LENGTH_SHORT).show() }
        button("BACK") { finish() }
    }
}
