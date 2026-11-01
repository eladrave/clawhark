package com.ettlinger.wearrecorder

import android.app.Activity
import android.app.AlertDialog
import kotlinx.coroutines.*
import android.os.Bundle
import android.content.Context
import android.view.View
import android.widget.*

class SettingsActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
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
        fun select(labels: List<String>, selected: Int, action: (Int) -> Unit) {
            val picker = Spinner(this).apply {
                adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(selected)
            }
            column.addView(picker)
            picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
            }
        }
        var presetStatus: TextView? = null
        fun changed(message: String) {
            presetStatus?.text = "Selected preset: ${AppSettings.presetLabel(this)}"
            AppLog.i("Settings", "$message; ${AppSettings.profile(this)}")
            RecorderStatus.refreshTile(this)
        }
        fun syncNow() {
            AppLog.i("Settings", "Manual sync requested; ${AppSettings.profile(this)}")
            AppLog.flush()
            UploadWorker.syncNow(this)
            Toast.makeText(this, "Sync queued for selected content", Toast.LENGTH_SHORT).show()
        }
        text("Rave AI settings • ${BuildConfig.VERSION_NAME}")
        text(BatteryMonitor.label)
        text("Battery test presets")
        presetStatus = text("Selected preset: ${AppSettings.presetLabel(this)}")
        select(listOf("Choose a preset", "Baseline: 15-minute uploads", "Capture only: manual sync", "Logs only: 15-minute uploads"), 0) { position ->
            if (position != 0) {
                AppSettings.applyPreset(this, position)
                changed("Battery preset selected: $position")
                UploadWorker.reconfigure(this)
                Toast.makeText(this, "Selected: ${AppSettings.presetLabel(this)}", Toast.LENGTH_LONG).show()
                recreate()
            }
        }
        text("Presets change upload timing/content only. Recording mode, sensitivity, logging detail and tile preference stay as selected. Manual only keeps audio and logs on the watch until you sync.")
        val testStatus = text(if (AppSettings.testId(this) == "none") "No battery test marked" else "Battery test active")
        button("START BATTERY TEST") {
            if (AppSettings.testId(this) != "none") { AppLog.i("Test", "END reason=replaced; ${AppSettings.profile(this)}"); AppLog.flush() }
            AppSettings.set(this, AppSettings.LOGGING, true)
            AppSettings.startTest(this)
            UploadWorker.testStarted(this)
            AppLog.i("Test", "BEGIN; state=${RecorderStatus.state(this)}; active_mode=${RecorderStatus.mode(this)}; firmware=${android.os.Build.DISPLAY}; security_patch=${android.os.Build.VERSION.SECURITY_PATCH}; ${AppSettings.profile(this)}")
            AppLog.flush()
            testStatus.text = "Battery test active"
            Toast.makeText(this, "Test marked; logging enabled", Toast.LENGTH_SHORT).show()
            recreate()
        }
        lateinit var endTest: Button
        endTest = button("END TEST & SYNC") {
            AppLog.i("Test", "END; state=${RecorderStatus.state(this)}; active_mode=${RecorderStatus.mode(this)}; firmware=${android.os.Build.DISPLAY}; security_patch=${android.os.Build.VERSION.SECURITY_PATCH}; ${AppSettings.profile(this)}")
            AppLog.flush()
            AppSettings.finishTest(this)
            testStatus.text = "Test ended"
            endTest.isEnabled = false
            syncNow()
        }.apply { isEnabled = AppSettings.testId(this@SettingsActivity) != "none" }
        text("Start recording in the main app first and let any active sync finish before testing. START marks the current session and enables logging; it does not start the microphone. END marks the end and syncs selected content. Capture continues until you press STOP in the main app.")
        text("Recording mode (applies next start)")
        val modes = RadioGroup(this)
        val continuous = RadioButton(this).apply { id = View.generateViewId(); text = "Continuous" }
        val speech = RadioButton(this).apply { id = View.generateViewId(); text = "Speech only (beta)" }
        modes.addView(continuous); modes.addView(speech); column.addView(modes)
        modes.check(if (AppSettings.speechOnly(this)) speech.id else continuous.id)
        modes.setOnCheckedChangeListener { _, id ->
            AppSettings.set(this, AppSettings.MODE, id == speech.id)
            changed("Recording mode changed; speech_only=${id == speech.id}")
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
                    changed("Speech sensitivity changed to $position")
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
        text("Upload timing")
        val timing = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                UploadPolicy.timingLabels)
            setSelection(AppSettings.uploadTiming(this@SettingsActivity))
        }
        column.addView(timing)
        timing.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != AppSettings.uploadTiming(this@SettingsActivity)) {
                    AppSettings.setUploadTiming(this@SettingsActivity, position)
                    changed("Upload timing changed to ${UploadPolicy.timingLabels[position]}")
                    UploadWorker.reconfigure(this@SettingsActivity)
                }
            }
        }
        text("Manual only disables automatic audio/log uploads, including existing automatic jobs. SYNC NOW bypasses the interval/manual-only setting, but respects charging, unmetered and selected-content preferences.")
        text("Upload content")
        select(UploadPolicy.contentLabels, AppSettings.uploadContent(this)) { position ->
            if (position != AppSettings.uploadContent(this)) {
                AppSettings.setInt(this, AppSettings.UPLOAD_CONTENT, position)
                changed("Upload content changed to ${UploadPolicy.contentLabels[position]}")
                UploadWorker.reconfigure(this)
            }
        }
        text("Logs only keeps recordings local. Audio only keeps diagnostics local. Excluded files are retained; select Audio and logs when ready to upload both.")
        text("Logging detail")
        select(listOf("Normal", "Battery trial: fewer events"), AppSettings.logDetail(this)) { position ->
            if (position != AppSettings.logDetail(this)) {
                AppSettings.setInt(this, AppSettings.LOG_DETAIL, position)
                changed("Logging detail changed to $position")
            }
        }
        text("Battery trial detail keeps battery, settings, test markers, capture summaries and all warnings/errors. It omits routine speech transitions, per-file upload progress and screen events.")
        val tileUpdates = Switch(this).apply { text = "Live tile updates"; isChecked = AppSettings.tileUpdates(this@SettingsActivity) }
        column.addView(tileUpdates)
        tileUpdates.setOnCheckedChangeListener { _, enabled ->
            AppSettings.set(this, AppSettings.TILE_UPDATES, enabled)
            changed("Live tile updates changed to $enabled")
        }
        text("When off, the tile refreshes on opening and safety transitions. Cached active status can expire to CHECK RECORDER while left open.")
        val unmetered = Switch(this).apply { text = "Unmetered uploads only"; isChecked = AppSettings.unmetered(this@SettingsActivity) }
        column.addView(unmetered)
        text("When enabled, recordings and logs wait for an unmetered connection, usually Wi-Fi.")
        unmetered.setOnCheckedChangeListener { _, enabled ->
            AppSettings.set(this, AppSettings.UNMETERED, enabled)
            changed("Unmetered upload preference changed to $enabled")
            UploadWorker.reconfigure(this)
        }
        button("SYNC NOW") { syncNow() }
        text("Test recording cleanup")
        text("Delete local test audio without resetting your Drive login, settings or diagnostic logs. Cloud copies are not deleted. Stop recording first.")
        button("DELETE PENDING TEST RECORDINGS") {
            if (getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE).getBoolean(RecordingService.PREF_SHOULD_RECORD, false)) {
                Toast.makeText(this, "Stop recording first", Toast.LENGTH_LONG).show()
            } else AlertDialog.Builder(this).setTitle("Delete local test recordings?")
                .setMessage("Permanently delete pending audio on this watch, including interrupted test audio? Your Drive login, settings, logs and cloud files will stay.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE TEST AUDIO") { _, _ ->
                    UploadWorker.pauseForCleanup(this)
                    scope.launch {
                        try {
                            val count = withContext(Dispatchers.IO) { RecordingFiles.open(this@SettingsActivity).deletePendingTestRecordings() }
                            AppLog.i("Settings", "User deleted $count local test recordings; login/settings/logs preserved")
                            val prefs = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
                            prefs.edit().putString(UploadWorker.PREF_AUDIO_STATUS, "Local test audio deleted")
                                .putString(UploadWorker.PREF_STATUS, "Test audio deleted. Drive login preserved.").apply()
                            Toast.makeText(this@SettingsActivity, "Deleted $count test recordings", Toast.LENGTH_LONG).show()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            AppLog.e("Settings", "Test cleanup incomplete; retry after stopping capture/sync", e)
                            Toast.makeText(this@SettingsActivity, "Stop capture/sync, then retry cleanup", Toast.LENGTH_LONG).show()
                        } finally { UploadWorker.schedule(this@SettingsActivity) }
                    }
                }.show()
        }
        button("BACK") { finish() }
    }
}
