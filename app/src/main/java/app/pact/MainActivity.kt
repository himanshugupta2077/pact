package app.pact

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pact.ui.apps.AppListScreen
import app.pact.ui.dashboard.DashboardScreen
import app.pact.ui.detail.AppDetailScreen
import app.pact.ui.groups.GroupEditScreen
import app.pact.ui.groups.GroupListScreen
import app.pact.ui.setup.SetupScreen
import app.pact.ui.theme.PactTheme
import app.pact.util.hasAccessibility
import app.pact.util.hasNotificationAccess
import app.pact.util.hasUsageAccess

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PactTheme {
                PactNav()
            }
        }
    }
}

@Composable
private fun PactNav() {
    val context = LocalContext.current
    val usageOk = remember { mutableStateOf(hasUsageAccess(context)) }
    val accessOk = remember { mutableStateOf(hasAccessibility(context)) }
    val notificationOk = remember { mutableStateOf(hasNotificationAccess(context)) }

    LifecycleResumeEffect(Unit) {
        usageOk.value = hasUsageAccess(context)
        accessOk.value = hasAccessibility(context)
        notificationOk.value = hasNotificationAccess(context)
        onPauseOrDispose { }
    }

    val navController = rememberNavController()
    val startDest = remember {
        if (usageOk.value && accessOk.value) "home" else "setup"
    }
    val fade = tween<Float>(160)

    NavHost(navController = navController, startDestination = startDest) {
        composable(
            route = "setup",
            enterTransition = { fadeIn(fade) },
            exitTransition = { fadeOut(fade) },
            popEnterTransition = { fadeIn(fade) },
            popExitTransition = { fadeOut(fade) },
        ) {
            SetupScreen(
                usageOk = usageOk.value,
                accessOk = accessOk.value,
                notificationOk = notificationOk.value,
                onContinue = {
                    if (!navController.popBackStack("home", inclusive = false)) {
                        navController.navigate("home") {
                            popUpTo("setup") { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(
            route = "home",
            enterTransition = { fadeIn(fade) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(fade) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            DashboardScreen(
                usageOk = usageOk.value,
                accessOk = accessOk.value,
                notificationOk = notificationOk.value,
                onOpenSetup = { navController.navigate("setup") },
                onOpenApps = {
                    navController.navigate("apps") { launchSingleTop = true }
                },
                onOpenGroups = {
                    navController.navigate("groups") { launchSingleTop = true }
                },
                onOpenGroup = { id ->
                    navController.navigate("group/${Uri.encode(id)}")
                },
            )
        }
        composable(
            route = "apps",
            enterTransition = { fadeIn(fade) + slideInHorizontally(tween(200)) { it / 12 } },
            exitTransition = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(fade) },
            popExitTransition = { fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { it / 12 } },
        ) {
            AppListScreen(
                usageOk = usageOk.value,
                onBack = { navController.popBackStack() },
                onOpenApp = { pkg ->
                    navController.navigate("detail/${Uri.encode(pkg)}")
                },
            )
        }
        composable(
            route = "detail/{pkg}",
            arguments = listOf(navArgument("pkg") { type = NavType.StringType }),
            enterTransition = { fadeIn(fade) + slideInHorizontally(tween(200)) { it / 12 } },
            exitTransition = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(fade) },
            popExitTransition = { fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { it / 12 } },
        ) { entry ->
            val pkg = Uri.decode(entry.arguments?.getString("pkg") ?: return@composable)
            AppDetailScreen(
                packageName = pkg,
                onBack = { navController.popBackStack() },
                onOpenGroup = { id ->
                    navController.navigate("group/${Uri.encode(id)}")
                },
            )
        }
        composable(
            route = "groups",
            enterTransition = { fadeIn(fade) + slideInHorizontally(tween(200)) { it / 12 } },
            exitTransition = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(fade) },
            popExitTransition = { fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { it / 12 } },
        ) {
            GroupListScreen(
                onBack = { navController.popBackStack() },
                onNewGroup = { navController.navigate("group/new") },
                onOpenGroup = { id ->
                    navController.navigate("group/${Uri.encode(id)}")
                },
            )
        }
        composable(
            route = "group/{groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            enterTransition = { fadeIn(fade) + slideInHorizontally(tween(200)) { it / 12 } },
            exitTransition = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(fade) },
            popExitTransition = { fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { it / 12 } },
        ) { entry ->
            val raw = Uri.decode(entry.arguments?.getString("groupId") ?: return@composable)
            val id = raw.takeIf { it != "new" }
            GroupEditScreen(
                groupId = id,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
