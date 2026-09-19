package com.filezen.files

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.filezen.files.ui.*
import com.filezen.files.ui.browse.BrowseScreen
import com.filezen.files.ui.common.OpProgressCard
import com.filezen.files.ui.common.PermissionGate
import com.filezen.files.ui.common.hasStorageAccess
import com.filezen.files.ui.home.HomeScreen
import com.filezen.files.ui.inbox.InboxScreen
import com.filezen.files.ui.preview.PreviewScreen
import com.filezen.files.ui.search.SearchScreen
import com.filezen.files.ui.settings.SettingsScreen
import com.filezen.files.ui.storage.StorageScreen
import com.filezen.files.ui.theme.FileZenTheme

object Routes {
    const val HOME = "home"
    const val INBOX = "inbox"
    const val BROWSE = "browse"
    const val STORAGE = "storage"
    const val FOLDER = "folder/{path}"
    const val SEARCH = "search"
    const val PREVIEW = "preview/{path}"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val RULES = "rules"
    const val HISTORY = "history"
    const val RECENT = "recent"

    fun folder(path: String) = "folder/${Uri.encode(path)}"
    fun preview(path: String) = "preview/${Uri.encode(path)}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appVm: AppViewModel = viewModel()
            val theme by appVm.theme.collectAsState()
            FileZenTheme(mode = theme) {
                FileZenApp_(appVm)
            }
        }
    }
}

@Composable
fun FileZenApp_(appVm: AppViewModel) {
    var hasAccess by remember { mutableStateOf(hasStorageAccess()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) hasAccess = hasStorageAccess()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    if (!hasAccess) {
        Surface(Modifier.fillMaxSize()) { PermissionGate() }
        return
    }

    val nav = rememberNavController()
    val runningOp by appVm.runningOp.collectAsState()
    val snack = remember { SnackbarHostState() }
    val lastSummary by appVm.lastSummary.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // On a bottom-nav tab, Back returns to Home instead of exiting the app.
    androidx.activity.compose.BackHandler(
        enabled = currentRoute == null ||
            currentRoute in setOf(Routes.INBOX, Routes.BROWSE, Routes.STORAGE),
    ) {
        if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
            nav.navigate(Routes.HOME) {
                popUpTo(0) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(lastSummary) {
        lastSummary?.let { s ->
            val msg = when {
                s.cancelled -> "Operation cancelled"
                s.failed == 0 -> "${s.kind.name.lowercase().replaceFirstChar { it.uppercase() }}: ${s.succeeded} done"
                s.succeeded == 0 -> "Failed: ${s.results.firstOrNull { it.error != null }?.error ?: "error"}"
                else -> "${s.succeeded} done, ${s.failed} failed"
            }
            snack.showSnackbar(msg)
            appVm.dismissSummary()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Column {
                OpProgressCard(runningOp) { appVm.cancelOp() }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    val route = currentRoute
                    val items = listOf(
                        Triple(Routes.HOME, "Home", Icons.Rounded.Home),
                        Triple(Routes.INBOX, "Inbox", Icons.Rounded.Inbox),
                        Triple(Routes.BROWSE, "Browse", Icons.Rounded.Folder),
                        Triple(Routes.STORAGE, "Storage", Icons.Rounded.Storage),
                    )
                    items.forEach { (r, label, icon) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = {
                                nav.navigate(r) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) { HomeScreen(nav, appVm) }
            composable(Routes.INBOX) { InboxScreen(nav, appVm) }
            composable(Routes.BROWSE) { BrowseScreen(nav, appVm, null) }
            composable(
                Routes.FOLDER,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { back ->
                val p = Uri.decode(back.arguments?.getString("path") ?: "/")
                BrowseScreen(nav, appVm, p)
            }
            composable(
                Routes.SEARCH + "?type={type}",
                arguments = listOf(navArgument("type") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                }),
            ) { back ->
                SearchScreen(nav, appVm, back.arguments?.getString("type"))
            }
            composable(
                Routes.PREVIEW,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { back ->
                PreviewScreen(nav, Uri.decode(back.arguments?.getString("path") ?: ""))
            }
            composable(Routes.SETTINGS) { SettingsScreen(nav, appVm) }
            composable(Routes.STORAGE) { StorageScreen(nav, appVm) }
            composable(Routes.TRASH) { TrashScreen(nav, appVm) }
            composable(Routes.RULES) { SortRulesScreen(nav, appVm) }
            composable(Routes.HISTORY) { HistoryScreen(nav) }
            composable(Routes.RECENT) { RecentScreen(nav) }
        }
    }
}
