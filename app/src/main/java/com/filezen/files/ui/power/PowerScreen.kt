package com.filezen.files.ui.power

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.Routes
import com.filezen.files.core.shizuku.ShizukuAccess

/**
 * Power access (Shizuku): browse and manage Android/data, Android/obb and
 * other folders Android 11+ hides from apps — no SAF dance required.
 * Based on https://github.com/RikkaApps/Shizuku (Apache-2.0).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerAccessScreen(nav: NavController) {
    val ctx = LocalContext.current
    val shizuku = remember { FileZenApp.c.shizuku }
    val status by shizuku.status.collectAsState()
    LaunchedEffect(Unit) { shizuku.refreshStatus() }

    val (title, desc, icon) = when (status) {
        ShizukuAccess.Status.READY -> Triple(
            "Power access ready",
            "FileZen can read and manage restricted folders as the shell user.",
            Icons.Rounded.Terminal)
        ShizukuAccess.Status.NO_PERMISSION -> Triple(
            "Permission needed",
            "Shizuku is running — grant FileZen access to use it.",
            Icons.Rounded.Key)
        ShizukuAccess.Status.NOT_RUNNING -> Triple(
            "Shizuku not running",
            "Open the Shizuku app and tap Start (ADB or root).",
            Icons.Rounded.Power)
        ShizukuAccess.Status.NOT_INSTALLED -> Triple(
            "Shizuku not installed",
            "Install the free Shizuku app to unlock power file access.",
            Icons.Rounded.CloudDownload)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Power access", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Rounded.ArrowBack, "Back")
                }
            })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (status == ShizukuAccess.Status.READY)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(34.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(title, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium)
                                Text(desc, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (status) {
                                ShizukuAccess.Status.NOT_INSTALLED -> {
                                    Button(onClick = {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW,
                                            Uri.parse("https://shizuku.rikka.app/download/")))
                                    }) { Text("Get Shizuku") }
                                }
                                ShizukuAccess.Status.NOT_RUNNING -> {
                                    Button(onClick = {
                                        runCatching {
                                            ctx.startActivity(ctx.packageManager
                                                .getLaunchIntentForPackage("moe.shizuku.privileged.api"))
                                        }
                                    }) { Text("Open Shizuku") }
                                }
                                ShizukuAccess.Status.NO_PERMISSION -> {
                                    Button(onClick = { shizuku.requestPermission() }) {
                                        Text("Grant permission")
                                    }
                                }
                                ShizukuAccess.Status.READY -> {}
                            }
                            OutlinedButton(onClick = { shizuku.refreshStatus() }) {
                                Text("Refresh")
                            }
                        }
                    }
                }
            }

            item {
                Text("Restricted folders", Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            item {
                val root = "/storage/emulated/0"
                listOf(
                    Triple(Icons.Rounded.FolderZip, "App data (Android/data)", "$root/Android/data"),
                    Triple(Icons.Rounded.Gamepad, "Game data (Android/obb)", "$root/Android/obb"),
                ).forEach { (ic, label, p) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = { Text(p, style = MaterialTheme.typography.labelSmall) },
                        leadingContent = { Icon(ic, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                        modifier = Modifier.clickable {
                            if (status == ShizukuAccess.Status.READY)
                                nav.navigate(Routes.folder(p))
                            else shizuku.refreshStatus()
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), thickness = 0.5.dp)
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("How it works", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Shizuku runs file operations as the ADB shell user — enough to " +
                                "reach Android/data and Android/obb without SAF pickers or root. " +
                                "Deletions in restricted folders are permanent (no trash).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
