package ie.onfoot.walklog

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Foreground-сервис записи GPS-трека.
 *
 * Принципы надёжности:
 *  - foreground-сервис типа location с постоянной нотификацией — высший
 *    приоритет выживания, доступный обычному приложению;
 *  - partial wakelock — процессор не засыпает при выключенном экране;
 *  - каждая точка немедленно пишется в файл и flush() — при любой смерти
 *    процесса трек цел до последней секунды;
 *  - нотификация показывает живой счётчик точек и километров: один взгляд
 *    на шторку — и видно, что запись идёт.
 */
class LogService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL = "walklog"

        // живое состояние для MainActivity
        @Volatile var running = false
        @Volatile var points = 0
        @Volatile var meters = 0.0
        @Volatile var lastAccuracy = 0f
        @Volatile var lastFile: String? = null
        @Volatile var lastLat = 0.0
        @Volatile var lastLon = 0.0
    }

    private var writer: FileWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLoc: Location? = null
    private val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
        }
        // если система убьёт и пересоздаст сервис — не рестартуем запись сами,
        // файл уже сохранён; честнее показать пользователю, что запись прервана
        return START_NOT_STICKY
    }

    private fun start() {
        if (running) return

        // файл: Android/data/ie.onfoot.walklog/files/tracks/walk_ГГГГММДД_ЧЧММСС.gpx
        val dir = File(getExternalFilesDir(null), "tracks").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "walk_$name.gpx")
        writer = FileWriter(file, true).apply {
            write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            write("<gpx version=\"1.1\" creator=\"WalkLog\"><trk><name>walk_$name</name><trkseg>\n")
            flush()
        }
        lastFile = file.absolutePath
        points = 0
        meters = 0.0
        lastLoc = null

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walklog:rec")
            .apply { acquire(12 * 60 * 60 * 1000L) } // предохранитель: максимум 12 часов

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)

        running = true
        startInForeground()
    }

    private fun stop() {
        if (running) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(this)
            writer?.apply {
                write("</trkseg></trk></gpx>\n")
                flush()
                close()
            }
            writer = null
            wakeLock?.release()
            wakeLock = null
            running = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(loc: Location) {
        val w = writer ?: return
        w.write(
            "<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n"
                .format(Locale.US, loc.latitude, loc.longitude, loc.altitude, utc.format(Date(loc.time)))
        )
        w.flush() // точка на диске сразу — смерть процесса не теряет трек
        points++
        lastAccuracy = loc.accuracy
        lastLat = loc.latitude
        lastLon = loc.longitude
        lastLoc?.let { meters += it.distanceTo(loc) }
        lastLoc = loc
        if (points % 5 == 0) startInForeground() // обновляем счётчик в шторке
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Запись трека", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LogService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("REC ● %d точек • %.2f км".format(Locale.US, points, meters / 1000))
            .setContentText("точность ±%.0f м — нажми, чтобы открыть".format(lastAccuracy))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "СТОП", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        // страховка: если систему что-то заставило убить сервис — дописываем файл
        writer?.runCatching { flush(); close() }
        wakeLock?.runCatching { if (isHeld) release() }
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
