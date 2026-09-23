package ie.onfoot.walklog

import android.Manifest
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
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
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
    private var keepScreenUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SLATE: huge clock to show to the camera; even bigger in landscape
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Same face as the channel's intro titles (JetBrains Mono, OFL — license bundled in assets)
        val mono = Typeface.createFromAsset(assets, "fonts/JetBrainsMono-ExtraBold.ttf")
        clock = TextView(this).apply {
            textSize = if (landscape) 120f else 52f
            typeface = mono
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, if (landscape) 10 else 40, 0, 0)
        }
        status = TextView(this).apply {
            textSize = if (landscape) 26f else 22f
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
        val shareBtn = big("⤴  SHARE TRACK") { pickTrack() }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(19, 21, 25))
            setPadding(48, 24, 48, 24)
            addView(clock)
            addView(status)
            addView(toggleBtn)
            addView(space())
            addView(shareBtn)
        })

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
        askBatteryExemption()
        val i = Intent(this, LogService::class.java).setAction(LogService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        // keep the screen on for 2 minutes — time to show the slate to the camera
        keepScreenUntil = System.currentTimeMillis() + 2 * 60 * 1000
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            if (keepScreenUntil > 0 && System.currentTimeMillis() > keepScreenUntil) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                keepScreenUntil = 0
            }
        } else {
            status.text = ""
            toggleBtn.text = "▶  START"
        }
        ui.postDelayed({ tick() }, 200)
    }
}
