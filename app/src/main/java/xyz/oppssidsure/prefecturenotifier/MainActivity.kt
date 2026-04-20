package xyz.oppssidsure.prefecturenotifier

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvLocation: TextView
    private lateinit var statusText: TextView
    private lateinit var mainLayout: LinearLayout
    private lateinit var tvHistory: TextView
    private val historyList = mutableListOf<String>()
    private val requestCode = 100

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_EXIT_APP" -> finishAffinity()
                "HISTORY_UPDATE" -> {
                    val newItem = intent.getStringExtra("history_item") ?: ""
                    historyList.add(0, newItem)
                    tvHistory.text = historyList.joinToString("\n")
                }
                "LOCATION_UPDATE" -> {
                    val isRunning = intent.getBooleanExtra("is_running", false)
                    if (isRunning) {
                        val lat = intent.getDoubleExtra("lat", 0.0)
                        val lng = intent.getDoubleExtra("lng", 0.0)
                        val pref = intent.getStringExtra("pref") ?: "判定中..."
                        val time = intent.getStringExtra("time") ?: "--:--:--"

                        statusText.text = "● 監視中"
                        statusText.setTextColor("#4CAF50".toColorInt())

                        val activeBg = if (isDarkTheme()) "#1B3320" else "#E8F5E9"
                        mainLayout.setBackgroundColor(activeBg.toColorInt())

                        tvLocation.text = getString(R.string.location_format, pref, lat, lng, time)
                    } else {
                        updateToStoppedState()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        tvLocation = findViewById(R.id.tvLocation)
        statusText = findViewById(R.id.statusText)
        mainLayout = findViewById(R.id.mainLayout)
        tvHistory = findViewById(R.id.tvHistory)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val sbVolume = findViewById<SeekBar>(R.id.sbVolume)
        val tvVolumeLabel = findViewById<TextView>(R.id.tvVolumeLabel)
        val etLat = findViewById<EditText>(R.id.etLat)
        val etLng = findViewById<EditText>(R.id.etLng)
        val btnTest = findViewById<Button>(R.id.btnTestLocation)

        val pref = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedVol = pref.getFloat("last_volume", 1.0f)
        sbVolume.progress = (savedVol * 100).toInt()
        tvVolumeLabel.text = getString(R.string.volume_label_format, sbVolume.progress)

        requestPermissions()

        btnStart.setOnClickListener {
            if (hasRequiredPermissions()) {
                val intent = Intent(this, LocationService::class.java)
                ContextCompat.startForegroundService(this, intent)
            } else { requestPermissions() }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            updateToStoppedState()
        }

        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvVolumeLabel.text = getString(R.string.volume_label_format, p)
                val intent = Intent(this@MainActivity, LocationService::class.java).apply {
                    action = "ACTION_UPDATE_VOLUME"
                    putExtra("volume_value", p / 100f)
                }
                startService(intent)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btnTest.setOnClickListener {
            val intent = Intent(this, LocationService::class.java).apply {
                action = "ACTION_TEST_LOCATION"
                putExtra("test_lat", etLat.text.toString().toDoubleOrNull() ?: 0.0)
                putExtra("test_lng", etLng.text.toString().toDoubleOrNull() ?: 0.0)
            }
            startService(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.about_message)
            .setPositiveButton("閉じる", null)
            .create()

        dialog.show()

        val messageView = dialog.findViewById<TextView>(android.R.id.message)
        if (messageView != null) {
            messageView.movementMethod = LinkMovementMethod.getInstance()
            Linkify.addLinks(messageView, Linkify.WEB_URLS)
        }
    }

    private fun isDarkTheme(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun hasRequiredPermissions(): Boolean {
        val fineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return fineLoc && postNotif
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), requestCode)
    }

    private fun updateToStoppedState() {
        statusText.text = "● 停止中"
        statusText.setTextColor("#888888".toColorInt())
        mainLayout.setBackgroundColor(Color.TRANSPARENT)
        tvLocation.text = getString(R.string.location_default)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("LOCATION_UPDATE")
            addAction("HISTORY_UPDATE")
            addAction("ACTION_EXIT_APP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(locationReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(locationReceiver) } catch (_: Exception) {}
    }
}