package app.pact.data

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import androidx.core.graphics.createBitmap
import java.util.concurrent.ConcurrentHashMap

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val isLauncher: Boolean,
)

class InstalledApps(private val context: Context) {

    private val iconCache = ConcurrentHashMap<String, Bitmap>()
    private val labelCache = ConcurrentHashMap<String, String>()
    private val catalogLock = Any()

    @Volatile
    private var catalog: List<InstalledApp>? = null

    @Volatile
    private var catalogAt = 0L

    @Volatile
    private var launcherPkg: String? = null

    fun launcherPackage(): String? {
        launcherPkg?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val found = res?.activityInfo?.packageName
        launcherPkg = found
        return found
    }

    fun userApps(
        extraPackages: Collection<String> = emptyList(),
        forceReload: Boolean = false,
    ): List<InstalledApp> {
        synchronized(catalogLock) {
            val now = SystemClock.elapsedRealtime()
            if (forceReload || catalog == null || now - catalogAt > 60_000L) {
                launcherPkg = null
                catalog = loadLaunchers()
                catalogAt = now
            }
            val base = catalog ?: emptyList()
            if (extraPackages.isEmpty()) return base
            val seen = HashSet<String>(base.size + extraPackages.size)
            base.forEach { seen.add(it.packageName) }
            val extra = ArrayList<InstalledApp>()
            for (pkg in extraPackages) {
                if (!seen.add(pkg)) continue
                if (isListHidden(pkg)) continue
                extra += InstalledApp(
                    packageName = pkg,
                    label = labelOf(pkg),
                    icon = iconOf(pkg),
                    isLauncher = pkg == launcherPackage(),
                )
            }
            if (extra.isEmpty()) return base
            return (base + extra).sortedBy { it.label.lowercase() }
        }
    }

    fun isNoise(packageName: String, label: String? = null): Boolean {
        if (packageName in HIDDEN_PACKAGES) return true
        if (HIDDEN_PREFIXES.any { packageName.startsWith(it) }) return true
        if ("permissioncontroller" in packageName) return true
        if ("systemui" in packageName) return true
        if ("settings.intelligence" in packageName || packageName.endsWith("settingsintelligence")) return true
        val resolved = (label ?: labelOf(packageName)).lowercase()
        if (resolved in HIDDEN_LABELS_EXACT) return true
        return HIDDEN_LABELS_CONTAIN.any { it in resolved }
    }

    fun isListHidden(packageName: String, label: String? = null): Boolean {
        if (packageName == context.packageName) return true
        if (packageName == launcherPackage()) return true
        return isNoise(packageName, label)
    }

    fun labelOf(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val label = runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        labelCache[packageName] = label
        return label
    }

    fun iconOf(packageName: String): Bitmap? {
        iconCache[packageName]?.let { if (!it.isRecycled) return it }
        val bmp = runCatching {
            context.packageManager.getApplicationIcon(packageName).toSmallBitmap()
        }.getOrNull() ?: return null
        iconCache[packageName] = bmp
        return bmp
    }

    private fun loadLaunchers(): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = launcherPackage()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<InstalledApp>(80)

        fun add(pkg: String, labelHint: String? = null, iconHint: Drawable? = null) {
            if (pkg.isBlank()) return
            if (!seen.add(pkg)) return
            val label = labelHint?.takeIf { it.isNotBlank() } ?: labelOf(pkg)
            if (isListHidden(pkg, label)) return
            labelCache[pkg] = label
            val icon = iconCache[pkg]?.takeUnless { it.isRecycled } ?: runCatching {
                (iconHint ?: pm.getApplicationIcon(pkg)).toSmallBitmap()
            }.getOrNull()?.also { iconCache[pkg] = it }
            out += InstalledApp(
                packageName = pkg,
                label = label,
                icon = icon,
                isLauncher = pkg == launcher,
            )
        }

        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val profiles = userManager?.userProfiles ?: listOf(Process.myUserHandle())
        if (launcherApps != null) {
            for (profile in profiles) {
                val activities = runCatching { launcherApps.getActivityList(null, profile) }
                    .getOrDefault(emptyList())
                for (info in activities) {
                    val pkg = info.applicationInfo?.packageName ?: continue
                    val drawable = runCatching { info.getBadgedIcon(0) ?: info.getIcon(0) }.getOrNull()
                    add(pkg, info.label?.toString(), drawable)
                }
            }
        }

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = queryLaunchers(pm, intent)
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            add(pkg, ri.loadLabel(pm)?.toString(), runCatching { ri.loadIcon(pm) }.getOrNull())
        }

        return out.sortedBy { it.label.lowercase() }
    }

    private fun queryLaunchers(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

    companion object {
        private val HIDDEN_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.android.providers.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.ext.services",
            "com.google.android.ext.shared",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.shell",
            "com.android.stk",
            "com.android.traceur",
            "com.android.vpndialogs",
            "com.android.printspooler",
            "com.android.bips",
            "com.android.nfc",
            "com.android.bluetooth",
            "com.google.android.setupwizard",
            "com.google.android.apps.restore",
            "com.google.android.gms.location.history",
        )

        private val HIDDEN_PREFIXES = listOf(
            "com.android.providers.",
            "com.android.internal.",
            "com.android.settings.",
            "com.google.android.gms.",
            "com.google.android.overlay.",
            "com.android.overlay.",
        )

        private val HIDDEN_LABELS_EXACT = setOf(
            "suggestions",
            "settings suggestions",
            "system ui",
            "systemui",
            "permission controller",
            "google pay services",
            "google play services",
            "package installer",
            "android system",
        )

        private val HIDDEN_LABELS_CONTAIN = listOf(
            "permission controller",
            "settings suggestions",
            "google pay service",
            "system ui",
        )
    }
}

private fun Drawable.toSmallBitmap(size: Int = 96): Bitmap {
    val bmp = createBitmap(size, size)
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
    bmp.prepareToDraw()
    return bmp
}
