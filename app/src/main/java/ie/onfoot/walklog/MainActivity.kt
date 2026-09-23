package ie.onfoot.walklog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Один экран: СТАРТ / СТОП / ПОДЕЛИТЬСЯ, живой статус записи.
 * Интерфейс собран кодом — ни одного layout-файла, читается сверху вниз.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var keepScreenUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ХЛОПУШКА: огромные часы — показать камере для синхронизации
        clock = TextView(this).apply {
            textSize = 52f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 0)
        }
        status = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(70, 235, 90))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 60)
        }
        startBtn = big("▶  СТАРТ") { startRec() }
        stopBtn = big("■  СТОП") {
            startService(Intent(this, LogService::class.java).setAction(LogService.ACTION_STOP))
        }
        val shareBtn = big("⤴  ПОДЕЛИТЬСЯ ПОСЛЕДНИМ") { shareLast() }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(19, 21, 25))
            setPadding(48, 48, 48, 48)
            addView(clock)
            addView(status)
            addView(startBtn)
            addView(space())
            addView(stopBtn)
            addView(space())
            addView(shareBtn)
        })

        askPermissions()
        tick()
    }

    private fun big(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 20f
        setPadding(0, 44, 0, 44)
        setOnClickListener { onClick() }
    }

    private fun space() = TextView(this).apply { height = 32 }

    private fun startRec() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askPermissions()
            Toast.makeText(this, "Нужно разрешение на геолокацию", Toast.LENGTH_LONG).show()
            return
        }
        askBatteryExemption()
        val i = Intent(this, LogService::class.java).setAction(LogService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        // держим экран включённым 2 минуты — успеть показать хлопушку камере
        keepScreenUntil = System.currentTimeMillis() + 2 * 60 * 1000
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun shareLast() {
        val path = LogService.lastFile ?: newestTrack()?.absolutePath
        if (path == null) {
            Toast.makeText(this, "Треков ещё нет", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "ie.onfoot.walklog.files", File(path))
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("application/gpx+xml")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Отправить трек"
            )
        )
    }

    private fun newestTrack(): File? =
        File(getExternalFilesDir(null), "tracks").listFiles()?.maxByOrNull { it.lastModified() }

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

    /** Обновление экрана: часы — 5 раз в секунду, чтобы секунды не отставали. */
    private fun tick() {
        if (LogService.running) {
            clock.text = hms.format(Date())
            status.text = "%.5f  %.5f\n● REC  %d точек  %.2f км  ±%.0f м".format(
                Locale.US, LogService.lastLat, LogService.lastLon,
                LogService.points, LogService.meters / 1000, LogService.lastAccuracy
            )
            if (keepScreenUntil > 0 && System.currentTimeMillis() > keepScreenUntil) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                keepScreenUntil = 0
            }
        } else {
            clock.text = ""
            val n = File(getExternalFilesDir(null), "tracks").listFiles()?.size ?: 0
            status.text = "⏸ не пишем\nтреков на телефоне: $n"
        }
        ui.postDelayed({ tick() }, 200)
    }
}
