package ie.onfoot.walklog

import android.Manifest
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.util.TypedValue
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.widget.TextViewCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen: START/STOP toggle, SHARE track picker, live status.
 * UI is built in code — no layout files, reads top to bottom.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var toggleBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Landscape = pure camera slate: giant clock + coords, no buttons,
        // screen never sleeps. Portrait = the control panel.
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Same face as the channel's intro titles (JetBrains Mono, OFL — license bundled in assets)
        val mono = Typeface.createFromAsset(assets, "fonts/JetBrainsMono-ExtraBold.ttf")
        clock = TextView(this).apply {
            typeface = mono
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, if (landscape) 10 else 40, 0, 0)
            if (landscape) {
                // fill the width with the biggest size that keeps HH:MM:SS on one line
                maxLines = 1
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 40, 300, 2, TypedValue.COMPLEX_UNIT_SP
                )
                text = "88:88:88" // placeholder so the first measure is full-width
            } else {
                textSize = 52f
            }
        }
        status = TextView(this).apply {
            textSize = if (landscape) 34f else 22f
            typeface = mono
            setTextColor(Color.rgb(70, 235, 90))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, if (landscape) 20 else 60)
        }
        // Single toggle: START while idle, STOP while recording.
        toggleBtn = big("▶  START") {
            if (LogService.running) {
                startService(Intent(this, LogService::class.java).setAction(LogService.ACTION_STOP))
            } else {
                startRec()
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(19, 21, 25))
            // landscape slate: no side margins — every pixel goes to the clock
            if (landscape) setPadding(0, 0, 0, 0) else setPadding(48, 24, 48, 24)
            gravity = if (landscape) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            if (landscape) {
                // autosize needs bounded height: the clock takes all space above the status
                addView(clock, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ))
            } else {
                addView(clock)
            }
            addView(status)
            if (!landscape) {
                addView(toggleBtn)
                addView(space())
                addView(big("⚙  SETTINGS") { showSettings() })
                addView(space())
                addView(big("⤴  SHARE TRACK") { pickTrack() })
            }
        })

        if (landscape) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        askPermissions()
        tick()
    }

    private fun big(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 20f
        setPadding(0, 36, 0, 36)
        setOnClickListener { onClick() }
    }

    private fun space() = TextView(this).apply { height = 24 }

    private fun startRec() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askPermissions()
            Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getSharedPreferences(LogService.PREFS, MODE_PRIVATE)
        val nm = getSystemService(NotificationManager::class.java)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, AdminReceiver::class.java)

        // One-time grants for the on-START behaviors (each can be unchecked in Settings)
        if (prefs.getBoolean(LogService.KEY_DND, true) && !nm.isNotificationPolicyAccessGranted) {
            Toast.makeText(this, "Allow Do Not Disturb access for WalkLog, then press START again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        if (prefs.getBoolean(LogService.KEY_LOCK, true) && !dpm.isAdminActive(admin)) {
            Toast.makeText(this, "Allow screen locking, then press START again", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "WalkLog locks the screen when recording starts, so the phone can go straight into a pocket."
                    )
            )
            return
        }
        askBatteryExemption()

        val i = Intent(this, LogService::class.java).setAction(LogService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)

        // The shoot must not be interrupted: total silence, app away, screen dark —
        // the phone is now a tracker in a pocket until the user unlocks it and taps STOP.
        if (prefs.getBoolean(LogService.KEY_DND, true)) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        }
        if (prefs.getBoolean(LogService.KEY_MINIMIZE, true)) moveTaskToBack(true)
        if (prefs.getBoolean(LogService.KEY_LOCK, true)) dpm.lockNow()
    }

    /** All tracks, newest first; tap one to share it. */
    private fun pickTrack() {
        val files = File(getExternalFilesDir(null), "tracks")
            .listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "No tracks yet", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Share track")
            .setItems(files.map { it.name }.toTypedArray()) { _, i -> shareFile(files[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** GPS interval + still-filter threshold; applied at the next START. */
    private fun showSettings() {
        val prefs = getSharedPreferences(LogService.PREFS, MODE_PRIVATE)

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setPadding(0, 24, 0, 4)
        }
        val interval = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getFloat(LogService.KEY_INTERVAL_S, LogService.DEF_INTERVAL_S).toString())
        }
        val minDist = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getFloat(LogService.KEY_MIN_DIST_M, LogService.DEF_MIN_DIST_M).toString())
        }
        fun check(key: String, text: String) = CheckBox(this).apply {
            this.text = text
            isChecked = prefs.getBoolean(key, true)
            setPadding(0, 16, 0, 0)
        }
        val dnd = check(LogService.KEY_DND, "Silence calls while recording (Do Not Disturb)")
        val lock = check(LogService.KEY_LOCK, "Lock the screen on START")
        val minimize = check(LogService.KEY_MINIMIZE, "Minimize the app on START")

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
                addView(label("GPS interval, seconds"))
                addView(interval)
                addView(label("Still filter: skip points closer than, meters"))
                addView(minDist)
                addView(dnd)
                addView(lock)
                addView(minimize)
                addView(label("Applied at the next START").apply { alpha = 0.6f })
            })
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putFloat(
                        LogService.KEY_INTERVAL_S,
                        interval.text.toString().toFloatOrNull() ?: LogService.DEF_INTERVAL_S
                    )
                    .putFloat(
                        LogService.KEY_MIN_DIST_M,
                        minDist.text.toString().toFloatOrNull() ?: LogService.DEF_MIN_DIST_M
                    )
                    .putBoolean(LogService.KEY_DND, dnd.isChecked)
                    .putBoolean(LogService.KEY_LOCK, lock.isChecked)
                    .putBoolean(LogService.KEY_MINIMIZE, minimize.isChecked)
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareFile(file: File) {
        val uri: Uri = FileProvider.getUriForFile(this, "ie.onfoot.walklog.files", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("application/gpx+xml")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share track"
            )
        )
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    /** Screen refresh 5×/s so the clock seconds never lag. */
    private fun tick() {
        clock.text = hms.format(Date())
        if (LogService.running) {
            status.text = "%.5f  %.5f\n● REC  %d pts  %.2f km  ±%.0f m".format(
                Locale.US, LogService.lastLat, LogService.lastLon,
                LogService.points, LogService.meters / 1000, LogService.lastAccuracy
            )
            toggleBtn.text = "■  STOP"
        } else {
            status.text = ""
            toggleBtn.text = "▶  START"
        }
        ui.postDelayed({ tick() }, 200)
    }
}
