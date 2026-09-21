@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.filezen.files
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import com.filezen.files.core.fileops.OpKind
import androidx.compose.ui.res.stringResource
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
import com.filezen.files.ui.calendar.CalendarScreen
import com.filezen.files.ui.common.OpProgressCard
import com.filezen.files.ui.common.PermissionGate
import com.filezen.files.ui.common.hasStorageAccess
import com.filezen.files.ui.home.HomeScreen
import com.filezen.files.ui.inbox.InboxScreen
import com.filezen.files.ui.optimise.OptimiseScreen
import com.filezen.files.ui.preview.PreviewScreen
import com.filezen.files.ui.search.SearchScreen
import com.filezen.files.ui.settings.SettingsScreen
import com.filezen.files.ui.storage.StorageScreen
import com.filezen.files.ui.theme.FileZenTheme
import com.filezen.files.ui.transfer.TransferScreen
import com.filezen.files.ui.tools.ToolsScreen
import com.filezen.files.ui.remote.ConnectionsScreen
import com.filezen.files.ui.remote.RemoteScreen
import com.filezen.files.ui.dualpane.DualPaneScreen
import com.filezen.files.ui.archive.ArchiveScreen
import com.filezen.files.ui.compare.CompareScreen
import com.filezen.files.ui.privacy.PrivacyScreen
import com.filezen.files.ui.cleaner.PhotoCleanerScreen
import com.filezen.files.ui.sync.SyncScreen
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
    const val CALENDAR = "calendar"
    const val OPTIMISE = "optimise"
    const val TRANSFER = "transfer"
    const val TOOLS = "tools"
    const val CONNECTIONS = "connections"
    const val REMOTE = "remote/{id}"
    const val DUALPANE = "dualpane"
    const val COMPARE = "compare"
    const val METACLEAN = "metaclean"
    const val PHOTOCLEAN = "photoclean"
    const val SYNCPAIRS = "sync"
    const val ARCHIVE = "archive/{path}?inner={inner}"
    fun folder(path: String) = "folder/${Uri.encode(path)}"
    fun preview(path: String) = "preview/${Uri.encode(path)}"
    fun remote(id: Long) = "remote/$id"
    fun archive(path: String, inner: String = "") =
        "archive/${Uri.encode(path)}?inner=${Uri.encode(inner)}"
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appVm: AppViewModel = viewModel()
            val theme by appVm.theme.collectAsState()
            val accent by appVm.accent.collectAsState()
            val amoled by appVm.amoled.collectAsState()
            FileZenTheme(mode = theme, accent = accent, amoled = amoled) {
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
    val onboarded by FileZenApp.c.settings.onboarded.collectAsState(initial = null)
    if (onboarded == false) {
        com.filezen.files.ui.onboarding.OnboardingScreen { }
        return
    }
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(900); splash = false }
    val nav = rememberNavController()
    val runningOp by appVm.runningOp.collectAsState()
    val snack = remember { SnackbarHostState() }
    // Box lets the animated splash overlay sit on top of the whole UI
    val lastSummary by appVm.lastSummary.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // On a bottom-nav tab, Back returns to Home instead of exiting the app.
    androidx.activity.compose.BackHandler(
        enabled = currentRoute == null ||
            currentRoute in setOf(Routes.INBOX, Routes.BROWSE, Routes.STORAGE, Routes.TOOLS),
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
            val undoCount = if (s.kind == OpKind.TRASH && s.succeeded > 0) s.succeeded else 0
            val res = snack.showSnackbar(
                msg, actionLabel = if (undoCount > 0) "Undo" else null,
                withDismissAction = true,
                duration = if (undoCount > 0) SnackbarDuration.Long else SnackbarDuration.Short)
            if (res == SnackbarResult.ActionPerformed && undoCount > 0) appVm.undoTrash(undoCount)
            appVm.dismissSummary()
        }
    }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Column {
                OpProgressCard(runningOp) { appVm.cancelOp() }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    val route = currentRoute
                    val untidyList by FileZenApp.c.db.inbox().untidy()
                        .collectAsState(initial = emptyList())
                    val untidy = untidyList.size
                    // Inbox sits centre with a live badge — the flagship flow.
                    val items = listOf(
                        Triple(Routes.HOME, stringResource(R.string.nav_home), Icons.Rounded.Home),
                        Triple(Routes.BROWSE, stringResource(R.string.nav_browse), Icons.Rounded.FolderCopy),
                        Triple(Routes.INBOX, stringResource(R.string.nav_inbox), Icons.Rounded.Inbox),
                        Triple(Routes.STORAGE, stringResource(R.string.nav_storage), Icons.Rounded.DonutLarge),
                        Triple(Routes.TOOLS, stringResource(R.string.nav_tools), Icons.Rounded.Build),
                    )
                    items.forEach { (r, label, icon) ->
                        ZenNavItem(
                            route = r, label = label, icon = icon,
                            selected = route == r,
                            badge = if (r == Routes.INBOX && untidy > 0) untidy else 0,
                            onClick = {
                                if (route != r) {
                                    nav.navigate(r) {
                                        popUpTo(Routes.HOME)
                                        launchSingleTop = true
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        androidx.compose.animation.SharedTransitionLayout {
        CompositionLocalProvider(
            com.filezen.files.ui.common.LocalSharedScope provides this@SharedTransitionLayout
        ) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            enterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                    androidx.compose.animation.slideInHorizontally(
                        androidx.compose.animation.core.tween(220),
                    ) { it / 12 }
            },
            exitTransition = {
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
            },
            popEnterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                    androidx.compose.animation.slideInHorizontally(
                        androidx.compose.animation.core.tween(220),
                    ) { -it / 12 }
            },
            popExitTransition = {
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
            },
        ) {
            composable(Routes.HOME) { HomeScreen(nav, appVm) }
            composable(Routes.INBOX) { InboxScreen(nav, appVm) }
            composable(Routes.BROWSE) { CompositionLocalProvider(
                com.filezen.files.ui.common.LocalAnimScope provides this) { BrowseScreen(nav, appVm, null) } }
            composable(
                Routes.FOLDER,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { back ->
                val p = Uri.decode(back.arguments?.getString("path") ?: "/")
                CompositionLocalProvider(
                    com.filezen.files.ui.common.LocalAnimScope provides this) {
                    BrowseScreen(nav, appVm, p)
                }
            }
            composable(
                Routes.SEARCH + "?type={type}&mode={mode}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { back ->
                SearchScreen(nav, appVm, back.arguments?.getString("type"),
                    presetMode = back.arguments?.getString("mode"))
            }
            composable(
                Routes.PREVIEW,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { back ->
                CompositionLocalProvider(
                    com.filezen.files.ui.common.LocalAnimScope provides this) {
                    PreviewScreen(nav, Uri.decode(back.arguments?.getString("path") ?: ""))
                }
            }
            composable(Routes.SETTINGS) { SettingsScreen(nav, appVm) }
            composable(Routes.STORAGE) { StorageScreen(nav, appVm) }
            composable(Routes.OPTIMISE) { OptimiseScreen(nav, appVm) }
            composable(Routes.TRANSFER) { TransferScreen(nav) }
            composable(Routes.TRASH) { TrashScreen(nav, appVm) }
            composable(Routes.RULES) { SortRulesScreen(nav, appVm) }
            composable(Routes.HISTORY) { HistoryScreen(nav) }
            composable(Routes.CALENDAR) { CalendarScreen(nav, appVm) }
            composable(Routes.TOOLS) { ToolsScreen(nav) }
            composable(Routes.CONNECTIONS) { ConnectionsScreen(nav) }
            composable(
                Routes.REMOTE,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { back -> RemoteScreen(nav, back.arguments?.getLong("id") ?: 0L) }
            composable(Routes.DUALPANE) { DualPaneScreen(nav, appVm) }
            composable(Routes.COMPARE) { CompareScreen(nav) }
            composable(Routes.METACLEAN) { PrivacyScreen(nav, appVm) }
            composable(Routes.PHOTOCLEAN) { PhotoCleanerScreen(nav, appVm) }
            composable(Routes.SYNCPAIRS) { SyncScreen(nav, appVm) }
            composable(
                Routes.ARCHIVE,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("inner") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { back ->
                ArchiveScreen(nav,
                    Uri.decode(back.arguments?.getString("path") ?: ""),
                    back.arguments?.getString("inner")?.let { Uri.decode(it) } ?: "")
            }
            composable(Routes.RECENT) { CompositionLocalProvider(
                com.filezen.files.ui.common.LocalAnimScope provides this) { RecentScreen(nav, appVm) } }
        }
        }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        splash, exit = androidx.compose.animation.fadeOut(
            androidx.compose.animation.core.tween(400)),
    ) { com.filezen.files.ui.onboarding.SplashOverlay() }
    }
}
/**
 * Bottom-nav item with a spring-loaded pill that sweeps in behind the icon,
 * an icon that briefly pops bigger, and a label that firms up when active.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ZenNavItem(
    route: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int = 0,
) {
    val pillAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (selected) 1f else 0f,
        androidx.compose.animation.core.tween(250), label = "pillA")
    val pillScale by androidx.compose.animation.core.animateFloatAsState(
        if (selected) 1f else 0.55f,
        androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        label = "pillS")
    val iconTint = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center,
            modifier = Modifier.height(30.dp)) {
            if (pillAlpha > 0f) {
                Box(
                    Modifier
                        .size(width = 58.dp * pillScale, height = 30.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(15.dp))
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f * pillAlpha)
                        )
                )
            }
            Icon(icon, label, tint = iconTint,
                modifier = Modifier.size(23.dp + (if (selected) 1.dp else 0.dp)))
            if (badge > 0) {
                Box(
                    Modifier
                        .align(androidx.compose.ui.Alignment.TopEnd)
                        .padding(end = 2.dp)
                        .size(17.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text(if (badge > 99) "99+" else badge.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onError)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold
                else androidx.compose.ui.text.font.FontWeight.Normal,
            color = iconTint,
        )
    }
}