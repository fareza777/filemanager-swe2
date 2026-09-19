package com.filezen.files.ui.settings

import android.app.Activity
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.BuildConfig
import com.filezen.files.FileZenApp
import com.filezen.files.data.prefs.ThemeAccent
import com.filezen.files.data.prefs.ThemeMode
import com.filezen.files.data.prefs.ViewMode
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.InboxViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController, appVm: AppViewModel, inboxVm: InboxViewModel = viewModel()) {
    val ctx = LocalContext.current
    val c = FileZenApp.c
    val theme by appVm.theme.collectAsState()
    val accent by appVm.accent.collectAsState()
    val adFree by appVm.adFree.collectAsState()
    val price by c.billing.price.collectAsState()
    val viewMode by c.settings.viewMode.collectAsState(initial = ViewMode.LIST)
    val showHiddenFlow by c.settings.showHidden.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {

            Text("Appearance", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Theme", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val themeIcons = listOf(
                            Icons.Rounded.SettingsBrightness,
                            Icons.Rounded.LightMode,
                            Icons.Rounded.DarkMode,
                        )
                        ThemeMode.values().forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = theme == t,
                                onClick = { scope.launch { c.settings.setTheme(t) } },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.values().size),
                                icon = { Icon(themeIcons[i], null, Modifier.size(18.dp)) },
                            ) { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Accent colour", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val accentColors = listOf(
                            ThemeAccent.DYNAMIC to null,
                            ThemeAccent.TEAL to Color(0xFF006B5D),
                            ThemeAccent.SUNSET to Color(0xFF9A4520),
                            ThemeAccent.VIOLET to Color(0xFF6750A4),
                            ThemeAccent.OCEAN to Color(0xFF0061A4),
                            ThemeAccent.ROSE to Color(0xFF9C4055),
                        )
                        accentColors.forEach { (a, col) ->
                            val sel = accent == a
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    scope.launch { c.settings.setAccent(a) }
                                },
                            ) {
                                Box(
                                    Modifier.size(44.dp)
                                        .then(
                                            if (sel) Modifier.border(
                                                3.dp, MaterialTheme.colorScheme.primary,
                                                CircleShape) else Modifier
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (col == null) {
                                        // Dynamic — split circle
                                        androidx.compose.foundation.Canvas(Modifier.size(36.dp)) {
                                            drawArc(Color(0xFF81D5C0), -90f, 180f, useCenter = true)
                                            drawArc(Color(0xFFEFC36D), 90f, 180f, useCenter = true)
                                        }
                                    } else {
                                        Surface(
                                            shape = CircleShape,
                                            color = col,
                                            modifier = Modifier.size(36.dp),
                                        ) {}
                                    }
                                    if (sel) {
                                        Icon(Icons.Rounded.Check, null,
                                            tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Text(
                                    a.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Default view", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ViewMode.values().forEachIndexed { i, v ->
                            SegmentedButton(
                                selected = viewMode == v,
                                onClick = { scope.launch { c.settings.setViewMode(v) } },
                                shape = SegmentedButtonDefaults.itemShape(i, ViewMode.values().size),
                            ) { Text(if (v == ViewMode.LIST) "List" else "Grid") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show hidden files", Modifier.weight(1f))
                        Switch(checked = showHiddenFlow,
                            onCheckedChange = { scope.launch { c.settings.setShowHidden(it) } })
                    }
                }
            }

            Text("Purchases", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ListItem(
                    headlineContent = { Text("Remove ads") },
                    supportingContent = {
                        Text(if (adFree) "Purchased — thank you!" else "One-time purchase")
                    },
                    leadingContent = { Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        if (adFree) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = {
                            (ctx as? Activity)?.let { c.billing.launchPurchase(it) }
                        }) { Text(price ?: "Buy") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            Text("About", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ListItem(
                    headlineContent = { Text("FileZen") },
                    supportingContent = { Text("Version ${BuildConfig.VERSION_NAME} · a tidy file manager") },
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
