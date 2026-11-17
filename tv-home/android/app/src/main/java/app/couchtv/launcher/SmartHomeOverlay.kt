package app.couchtv.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Smart Home over whatever is on the screen (Button Mapping.md: the Info key). A card drawn over other
 * apps lists the devices the household chose on the home (Home settings, Smart Home). Up and down pick
 * one, OK toggles it, and Back or Info closes the card. With no key pressed for a while it closes itself.
 *
 * It reads the settings the home page keeps ("tvhome.settings" in the page's store) and talks to Home
 * Assistant the way the native Smart Home client does. It needs "Display over other apps"
 * (SYSTEM_ALERT_WINDOW): without it, the press opens Android's screen for that instead.
 */
object SmartHomeOverlay {
    private const val TAG = "couchtv.smarthome"
    private const val IDLE_MS = 20_000L
    private const val MAX_ROWS = 12
    private const val SETTINGS_KEY = "tvhome.settings"
    private val CREAM = Color.parseColor("#F3EBDD")
    private val CREAM_2 = Color.parseColor("#B9B0A0")
    private val SELECTED = Color.parseColor("#33FFFFFF")

    private class Device(val entity: String, val name: String, val domain: String, var state: String) {
        var view: TextView? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val idleHide = Runnable { hide() }
    private var card: LinearLayout? = null
    private var wm: WindowManager? = null
    private var devices: List<Device> = emptyList()
    private var settings: HomeAssistant.Settings? = null
    private var sel = 0

    /** The Info key: opens the card, or closes it when it is up. */
    fun toggle(c: Context) {
        if (card != null) hide() else show(c)
    }

    fun show(c: Context) {
        if (card != null) return
        val app = c.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            runCatching {
                app.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Toast.makeText(app, "Allow display over other apps for couch-tv, then press Info again", Toast.LENGTH_LONG).show()
            return
        }
        val conf = readConfig(app)
        settings = conf?.first
        devices = conf?.second ?: emptyList()
        sel = 0

        val wmgr = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = app.resources.displayMetrics
        val dp = m.density
        val width = (m.widthPixels * 0.34f).toInt()

        val frame = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#EE131614")); cornerRadius = 18 * dp }
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 12 * dp
        }
        val wrap = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
        frame.addView(TextView(app).apply {
            text = "Smart Home"
            setTextColor(CREAM)
            textSize = 18f
        }, wrap)
        if (devices.isEmpty()) {
            frame.addView(TextView(app).apply {
                text = "No Smart Home devices chosen on the home yet"
                setTextColor(CREAM_2)
                textSize = 14f
            }, wrap)
        }
        for (d in devices) {
            val tv = TextView(app).apply {
                setTextColor(CREAM)
                textSize = 16f
                val p = (10 * dp).toInt()
                setPadding(p, p, p, p)
            }
            d.view = tv
            frame.addView(tv, LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * dp).toInt()
            })
        }
        paint()

        // It takes the remote while it is up: the arrows move, OK toggles, Back or Info closes.
        frame.isFocusable = true
        frame.isFocusableInTouchMode = true
        frame.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN) {
                touch()
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> move(-1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> press()
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_INFO -> hide()
                }
            }
            true
        }

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (m.widthPixels * 0.042f).toInt()   // inside the TV's safe area
        }
        try {
            wmgr.addView(frame, lp)
        } catch (e: Exception) {
            Log.w(TAG, "could not show the card: ${e.message}")
            return
        }
        frame.requestFocus()
        card = frame
        wm = wmgr
        touch()
        refreshStates()
    }

    fun hide() {
        main.removeCallbacks(idleHide)
        val v = card ?: return
        card = null
        devices = emptyList()
        settings = null
        try {
            wm?.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "could not hide the card: ${e.message}")
        }
        wm = null
    }

    private fun touch() {
        main.removeCallbacks(idleHide)
        main.postDelayed(idleHide, IDLE_MS)
    }

    private fun move(d: Int) {
        if (devices.isEmpty()) return
        sel = (sel + d).coerceIn(0, devices.size - 1)
        paint()
    }

    /** OK: toggles the picked device (scenes and scripts run). Off again if Home Assistant refuses. */
    private fun press() {
        val dev = devices.getOrNull(sel) ?: return
        val s = settings ?: return
        val runs = dev.domain == "scene" || dev.domain == "script"
        val service = if (runs) "turn_on" else "toggle"
        val before = dev.state
        if (!runs) {
            dev.state = if (before == "on") "off" else "on"
            paint()
        }
        Thread {
            val ok = HomeAssistant.service(s, dev.domain, service, JSONObject().put("entity_id", dev.entity))
            main.post {
                if (!ok && !runs) {
                    dev.state = before
                    paint()
                }
            }
        }.start()
    }

    /** The states as Home Assistant has them now, not as they were when the home last read them. */
    private fun refreshStates() {
        val s = settings ?: return
        val list = devices.toList()
        Thread {
            for (d in list) {
                val st = runCatching { HomeAssistant.state(s, d.entity)?.optString("state", "") }.getOrNull()
                if (st.isNullOrEmpty()) continue
                main.post {
                    d.state = st
                    paint()
                }
            }
        }.start()
    }

    private fun paint() {
        devices.forEachIndexed { i, d ->
            val v = d.view ?: return@forEachIndexed
            val picked = i == sel
            v.text = (if (picked) "›  " else "    ") + d.name + "   " + d.state.ifEmpty { "…" }
            v.background = if (picked) {
                GradientDrawable().apply {
                    setColor(SELECTED)
                    cornerRadius = 12 * v.resources.displayMetrics.density
                }
            } else {
                null
            }
        }
    }

    /** The Home Assistant address and token, and the devices chosen on the home (not the hidden ones). */
    private fun readConfig(c: Context): Pair<HomeAssistant.Settings, List<Device>>? {
        val raw = c.getSharedPreferences("page", Context.MODE_PRIVATE).getString(SETTINGS_KEY, null) ?: return null
        val s = runCatching { JSONObject(raw).optJSONObject("s") }.getOrNull() ?: return null
        val base = s.optString("ha_url", "").trim()
        if (base.isEmpty()) return null
        val token = s.optString("ha_token", "").trim()
        val hidden = s.optJSONArray("ha_hidden").toStrings().toSet()
        val arr = s.optJSONArray("ha_devices") ?: JSONArray()
        val list = mutableListOf<Device>()
        for (i in 0 until arr.length()) {
            if (list.size >= MAX_ROWS) break
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("entity_id", "")
            if (id.isEmpty() || id in hidden) continue
            list.add(Device(
                id,
                o.optString("name", "").ifEmpty { id },
                o.optString("domain", "").ifEmpty { id.substringBefore('.') },
                o.optString("state", "")
            ))
        }
        return Pair(HomeAssistant.Settings(base, token, emptyList(), emptyList(), null), list)
    }

    private fun JSONArray?.toStrings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
