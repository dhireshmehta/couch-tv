package app.couchtv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * The remote's buttons and what they open, as captured on the TV in ACTION_DOWN events (Button Mapping.md).
 *
 * A button is matched on its scan code when it has one (the remote's own button number, which is
 * what tells two keys with the same key code apart), else on its key code (the Guide key comes with
 * scan code 0). The launcher handles these in MainActivity while it is in front; KeyWatch, the
 * accessibility service, handles them while another app is, and it is the only one that can open the
 * notifications or the app switcher.
 */
object RemoteButtons {
    sealed class Action {
        /** Opens the first of these apps that is installed (YouTube is Google's on one TV and Amazon's on another). */
        class Launch(val packages: List<String>) : Action()
        /** Opens one activity by its name (TCL's TV app). */
        class Component(val pkg: String, val cls: String) : Action()
        /** A system action (notifications, app switcher). Only KeyWatch can perform it. */
        class System(val global: Int) : Action()
        /** Something couch-tv's own page does; the page names it (window.tv.remoteButton). */
        class Page(val name: String) : Action()
        /** The Smart Home card over whatever is on screen (SmartHomeOverlay). */
        object SmartHome : Action()
    }

    class Button(val label: String, val keyCode: Int, val scanCode: Int, val action: Action)

    val ALL: List<Button> = listOf(
        Button("Person", KeyEvent.KEYCODE_PROFILE_SWITCH, 653, Action.Page("person")),
        Button("Notifications", KeyEvent.KEYCODE_NOTIFICATION, 233, Action.System(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)),
        Button("App switcher", KeyEvent.KEYCODE_GUIDE, 0, Action.System(AccessibilityService.GLOBAL_ACTION_RECENTS)),
        Button("Smart Home", KeyEvent.KEYCODE_INFO, 195, Action.SmartHome),
        Button("Netflix", 4062, 242, Action.Launch(listOf("com.netflix.ninja"))),
        Button("Prime Video", 4099, 752, Action.Launch(listOf("com.amazon.amazonvideo.livingroom"))),
        Button("YouTube", 4065, 245, Action.Launch(listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube"))),
        Button("Apple TV", 4160, 652, Action.Launch(listOf("com.apple.atve.androidtv.appletv"))),
        Button("TCL TV", 4020, 227, Action.Component("com.tcl.tv", "com.tcl.player.TVActivity"))
    )

    /** The button this key event is, or null when it is not one of ours. */
    fun find(e: KeyEvent): Button? = ALL.firstOrNull { b ->
        if (b.scanCode != 0) e.scanCode == b.scanCode else e.keyCode == b.keyCode
    }

    /** The launch intent for a package, or null when it is not installed or has none. */
    fun launchIntent(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        return pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    }

    /** The first of these packages that is installed and can be opened, or null. */
    fun installed(ctx: Context, packages: List<String>): String? = packages.firstOrNull { launchIntent(ctx, it) != null }

    /** The intent an app button opens, or null when its app is not installed. */
    fun intentFor(ctx: Context, a: Action): Intent? = when (a) {
        is Action.Launch -> installed(ctx, a.packages)?.let { launchIntent(ctx, it) }
        is Action.Component -> try {
            ctx.packageManager.getPackageInfo(a.pkg, 0)
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(a.pkg, a.cls))
        } catch (e: Exception) {
            null
        }
        else -> null
    }
}
