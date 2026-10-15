package smartbin

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import smartbin.data.Hub
import smartbin.notify.Alerts
import smartbin.notify.Haptics
import smartbin.notify.Sounds
import smartbin.notify.WatchService
import smartbin.ui.AppViewModel
import smartbin.ui.SmartBinUi
import smartbin.ui.ThemeMode

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent { SmartBinUi(vm, ::setOrientation) }
        // The opening chime and its rising buzz. Not on a rotation.
        if (savedInstanceState == null) {
            Sounds.init(this)
            Sounds.boot()
            Haptics.init(this)
            Haptics.play(Haptics.Buzz.BOOT)
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A tap on a notification opens the Overview. */
    private fun handle(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) != null) vm.openFromNotification()
    }

    /**
     * Phones stay upright; the fullscreen chart is the one view drawn sideways.
     * Tablets may be held either way.
     */
    private fun setOrientation(landscape: Boolean) {
        val phone = resources.configuration.smallestScreenWidthDp < 600
        requestedOrientation = when {
            landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            phone -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onStart() {
        super.onStart()
        Hub.foreground = true
        vm.foregrounded()
        WatchService.stop(this)
    }

    override fun onResume() {
        super.onResume()
        // Picks up a change made in Android's own notification settings.
        vm.refreshSystemState()
    }

    override fun onStop() {
        Hub.foreground = false
        if (!isChangingConfigurations) vm.backgrounded()
        // With push alerts on, the bins keep being read while the app is in the
        // background, but only once the user is inside the main screens.
        if (Hub.inMain && Alerts.allowed(this) && !isChangingConfigurations) WatchService.start(this)
        syncIcon()
        super.onStop()
    }

    /**
     * Makes the home-screen logo match the theme the app was left in: the dark
     * logo for dark, the light logo for light. It runs as the app leaves the
     * screen, because some launchers redraw the icon then, and a change made
     * while the app is open can close it.
     */
    private fun syncIcon() {
        val dark = when (vm.theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.AUTO -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        fun switch(name: String, on: Boolean) {
            val entry = ComponentName(packageName, "smartbin.$name")
            val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (packageManager.getComponentEnabledSetting(entry) != want) {
                packageManager.setComponentEnabledSetting(entry, want, PackageManager.DONT_KILL_APP)
            }
        }
        runCatching {
            // The new entry goes on before the old one goes off, so there is always one.
            switch(if (dark) "LauncherDark" else "LauncherLight", true)
            switch(if (dark) "LauncherLight" else "LauncherDark", false)
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}
