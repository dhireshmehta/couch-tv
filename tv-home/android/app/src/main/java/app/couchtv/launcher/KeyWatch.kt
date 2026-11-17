package app.couchtv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The remote's gear (Settings) button. On some TVs (seen on an ONVO / MediaTek Google TV) that
 * button never reaches any app: Android's global-key table hands it to the maker's hot-key agent or
 * to the Google TV home, and with the Google TV home switched off nothing happens. An
 * accessibility service sees keys before that hand-off, so this one catches the gear button (it
 * opens couch-tv's own gear menu: Home settings or TV settings) and the remote buttons of Button
 * Mapping.md (an app, the Person switcher, Smart Home, notifications, the app switcher). Every other
 * key is passed on untouched, and it reads no screen content.
 *
 * Off until the person at the TV switches it on: Settings, System (or Device Preferences),
 * Accessibility, "Home gear button". Android switches it off again whenever the app is reinstalled.
 * undo.sh takes it away with couch-tv's other accessibility services.
 */
class KeyWatch : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        RemoteButtons.find(event)?.let { b -> return remote(b, event) }
        if (!isGear(event)) return false   // not ours: Android gets it as usual
        Log.i("couchtv-keywatch", "gear keyCode=${event.keyCode} scan=${event.scanCode} action=${event.action}")
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("gear", true))
            }
        }
        return true   // both the press and the release, so the old (dead) route never sees it
    }

    /** A remote button from Button Mapping.md, acted on at the press (not the release, not a repeat). */
    private fun remote(b: RemoteButtons.Button, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i("couchtv-keywatch", "remote ${b.label} keyCode=${event.keyCode} scan=${event.scanCode}")
            runCatching {
                when (val a = b.action) {
                    is RemoteButtons.Action.System -> performGlobalAction(a.global)
                    is RemoteButtons.Action.Launch, is RemoteButtons.Action.Component ->
                        RemoteButtons.intentFor(this, a)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    is RemoteButtons.Action.SmartHome -> SmartHomeOverlay.toggle(applicationContext)
                    is RemoteButtons.Action.Page -> startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("remote", a.name))
                }
            }
        }
        return true   // the press and the release both, so no app gets a button that is ours
    }

    companion object {
        /** The ONVO remote's gear: KEYCODE_BUTTON_14 (201), raw Linux KEY_TAPE (0x180 = 384), which
         *  the global-key table hands to MediaTek's hot-key agent. 176 is Android's own Settings key,
         *  kept for remotes that send it. */
        const val GEAR_SCAN = 384

        fun isGear(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_BUTTON_14 ||
            e.scanCode == GEAR_SCAN || e.keyCode == KeyEvent.KEYCODE_SETTINGS
    }
}
