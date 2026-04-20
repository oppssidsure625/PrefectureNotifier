package xyz.oppssidsure.prefecturenotifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class LocationService : Service() {

    private val NOTIFICATION_ID = 888
    private val CHANNEL_ID = "pref_notifier_v7"
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val prefPolygons = mutableListOf<PrefData>()
    private var currentPrefName: String? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
    private var appVolume = 1.0f

    data class PrefData(val name: String, val polygons: List<List<LatLng>>)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        loadTopoJson()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                res.lastLocation?.let { processNewLocation(it.latitude, it.longitude) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_STOP" -> { killApplication(); return START_NOT_STICKY }
            "ACTION_TEST_LOCATION" -> {
                processNewLocation(intent.getDoubleExtra("test_lat", 0.0), intent.getDoubleExtra("test_lng", 0.0))
                return START_STICKY
            }
            "ACTION_UPDATE_VOLUME" -> {
                appVolume = intent.getFloatExtra("volume_value", 1.0f)
                getSharedPreferences("app_settings", MODE_PRIVATE).edit().putFloat("last_volume", appVolume).apply()
                return START_STICKY
            }
        }

        appVolume = getSharedPreferences("app_settings", MODE_PRIVATE).getFloat("last_volume", 1.0f)
        createNotificationChannel()
        val notification = createNotification("監視中", "位置情報を取得しています...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startLocationUpdates()
        return START_STICKY
    }

    private fun processNewLocation(lat: Double, lng: Double) {
        val newPref = findPrefecture(lat, lng)
        val currentTime = timeFormat.format(Date())
        if (newPref != null && newPref != currentPrefName) {
            playPrefectureVoice(newPref)
            triggerVibration()
            currentPrefName = newPref
            sendHistoryUpdate(newPref, currentTime)
        }
        val displayPref = newPref ?: "判定外"
        updateNotification("現在地: $displayPref", "最終更新: $currentTime\n緯度: $lat / 経度: $lng")
        sendUpdateToActivity(lat, lng, displayPref, currentTime, true)
    }

    private fun triggerVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(500)
            }
        } catch (e: Exception) { Log.e("PrefService", "Vibration failed") }
    }

    private fun sendHistoryUpdate(pref: String, time: String) {
        val intent = Intent("HISTORY_UPDATE")
        intent.putExtra("history_item", "$time : $pref に進入")
        sendBroadcast(intent)
    }

    private fun playPrefectureVoice(prefName: String) {
        val fileNameMap = mapOf(
            "北海道" to "hokkaido", "青森県" to "aomori", "岩手県" to "iwate",
            "宮城県" to "miyagi", "秋田県" to "akita", "山形県" to "yamagata",
            "福島県" to "fukushima", "茨城県" to "ibaraki", "栃木県" to "tochigi",
            "群馬県" to "gunma", "埼玉県" to "saitama", "千葉県" to "chiba",
            "東京都" to "tokyo", "神奈川県" to "kanagawa", "新潟県" to "niigata",
            "富山県" to "toyama", "石川県" to "ishikawa", "福井県" to "fukui",
            "山梨県" to "yamanashi", "長野県" to "nagano", "岐阜県" to "gifu",
            "静岡県" to "shizuoka", "愛知県" to "aichi", "三重県" to "mie",
            "滋賀県" to "shiga", "京都府" to "kyoto", "大阪府" to "osaka",
            "兵庫県" to "hyogo", "奈良県" to "nara", "和歌山県" to "wakayama",
            "鳥取県" to "tottori", "島根県" to "shimane", "岡山県" to "okayama",
            "広島県" to "hiroshima", "山口県" to "yamaguchi", "徳島県" to "tokushima",
            "香川県" to "kagawa", "愛媛県" to "ehime", "高知県" to "kochi",
            "福岡県" to "fukuoka", "佐賀県" to "saga", "長崎県" to "nagasaki",
            "熊本県" to "kumamoto", "大分県" to "oita", "宮崎県" to "miyazaki",
            "鹿児島県" to "kagoshima", "沖縄県" to "okinawa"
        )
        val fileName = fileNameMap[prefName] ?: return
        val resId = resources.getIdentifier(fileName, "raw", packageName)
        if (resId != 0) {
            try {
                val mp = MediaPlayer()
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                mp.setAudioAttributes(attr)
                val afd = resources.openRawResourceFd(resId)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mp.prepare()
                mp.setVolume(appVolume, appVolume)
                mp.setOnCompletionListener { it.release() }
                mp.start()
            } catch (e: Exception) { Log.e("PrefService", "Audio Error: ${e.message}") }
        }
    }

    private fun sendUpdateToActivity(lat: Double, lng: Double, pref: String, time: String, isRunning: Boolean) {
        val intent = Intent("LOCATION_UPDATE").apply {
            putExtra("lat", lat); putExtra("lng", lng); putExtra("pref", pref)
            putExtra("time", time); putExtra("is_running", isRunning)
        }
        sendBroadcast(intent)
    }

    private fun loadTopoJson() {
        try {
            val json = JSONObject(assets.open("japan.json").bufferedReader().use { it.readText() })
            val transform = json.getJSONObject("transform")
            val scale = transform.getJSONArray("scale")
            val translate = transform.getJSONArray("translate")
            val arcs = json.getJSONArray("arcs")
            val decodedArcs = mutableListOf<List<LatLng>>()
            for (i in 0 until arcs.length()) {
                val arc = arcs.getJSONArray(i); val points = mutableListOf<LatLng>()
                var x = 0.0; var y = 0.0
                for (j in 0 until arc.length()) {
                    val pt = arc.getJSONArray(j); x += pt.getDouble(0); y += pt.getDouble(1)
                    points.add(LatLng(y * scale.getDouble(1) + translate.getDouble(1), x * scale.getDouble(0) + translate.getDouble(0)))
                }
                decodedArcs.add(points)
            }
            val geometries = json.getJSONObject("objects").getJSONObject("prefectures").getJSONArray("geometries")
            for (i in 0 until geometries.length()) {
                val geo = geometries.getJSONObject(i); val prefName = geo.getJSONObject("properties").getString("N03_001")
                val prefArcs = geo.getJSONArray("arcs"); val polygons = mutableListOf<List<LatLng>>()
                if (geo.getString("type") == "Polygon") polygons.add(assemblePolygon(prefArcs.getJSONArray(0), decodedArcs))
                else for (j in 0 until prefArcs.length()) polygons.add(assemblePolygon(prefArcs.getJSONArray(j).getJSONArray(0), decodedArcs))
                prefPolygons.add(PrefData(prefName, polygons))
            }
        } catch (e: Exception) { Log.e("PrefService", "Load Error") }
    }

    private fun assemblePolygon(indices: JSONArray, decoded: List<List<LatLng>>): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        for (i in 0 until indices.length()) {
            var idx = indices.getInt(i); val rev = idx < 0; if (rev) idx = idx.inv()
            if (rev) poly.addAll(decoded[idx].reversed()) else poly.addAll(decoded[idx])
        }
        return poly
    }

    private fun findPrefecture(lat: Double, lng: Double): String? {
        val pos = LatLng(lat, lng)
        for (pref in prefPolygons) for (poly in pref.polygons) if (PolyUtil.containsLocation(pos, poly, true)) return pref.name
        return null
    }

    private fun killApplication() {
        sendBroadcast(Intent("ACTION_EXIT_APP"))
        stopSelf(); Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 300)
    }

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        try { fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper()) } catch (e: SecurityException) {}
    }

    override fun onDestroy() {
        sendUpdateToActivity(0.0, 0.0, "---", "--:--:--", false)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun createNotification(title: String, text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_HIGH)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "監視を停止して終了",
            PendingIntent.getService(this, 0, Intent(this, LocationService::class.java).apply { action = "ACTION_STOP" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(title, text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "県境監視", NotificationManager.IMPORTANCE_HIGH)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }
}