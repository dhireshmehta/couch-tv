package app.couchtv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the launcher when the TV starts. When couch-tv is the home screen, Android starts it at boot by
 * itself, and this only makes sure. Newer Android may refuse a start from a boot broadcast; then the
 * home app still starts at boot, as long as couch-tv is the home (HomeRole asks for that).
 */
class Boot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Log.i("couchtv.boot", "boot: ${i.action}")
        runCatching {
            c.startActivity(Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
