package app.pact.enforce

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.view.KeyEvent

class MediaSilencer(private val context: Context) {

    private val listenerComponent = ComponentName(context, PactNotificationListener::class.java)
    private var lastKillElapsed = 0L

    fun silence(packages: Set<String>, overlayVisible: Boolean) {
        if (packages.isEmpty()) return
        // Session-scoped only. Global media keys, STREAM_MUSIC mute, and
        // AUDIOFOCUS_GAIN also stop Spotify / YouTube Music in the background.
        pauseSessions(packages)
        PactNotificationListener.instance?.cancelPackages(packages)
        if (!overlayVisible) {
            killBackground(packages)
        }
    }

    fun release() = Unit

    private fun pauseSessions(packages: Set<String>) {
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return
        val sessions: List<MediaController> = runCatching {
            msm.getActiveSessions(listenerComponent)
        }.getOrDefault(emptyList())
        for (controller in sessions) {
            val pkg = controller.packageName ?: continue
            if (pkg !in packages) continue
            runCatching { controller.transportControls.pause() }
            runCatching { controller.transportControls.stop() }
            for (code in intArrayOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP)) {
                runCatching {
                    controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
            }
        }
    }

    private fun killBackground(packages: Set<String>) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKillElapsed < 4_000L) return
        lastKillElapsed = now
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        for (pkg in packages) {
            runCatching { am.killBackgroundProcesses(pkg) }
        }
    }
}
