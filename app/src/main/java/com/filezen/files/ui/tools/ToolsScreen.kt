package com.filezen.files.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.Routes
import com.filezen.files.ui.common.SectionHeader

/**
 * The "everything" tab — every flagship feature reachable in one tap, grouped
 * so users actually find them (they were scattered across Storage/Settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(nav: NavController) {
    data class Tool(val icon: ImageVector, val title: String, val sub: String,
                    val route: String)

    val groups = listOf(
        "Find" to listOf(
            Tool(Icons.Rounded.ManageSearch, "Inside-file search", "Semantic search across document contents", Routes.SEARCH + "?mode=content"),
            Tool(Icons.Rounded.Search, "Search by name", "Instant file search across all storage", Routes.SEARCH),
        ),
        "Organise" to listOf(
            Tool(Icons.Rounded.Inbox, "Inbox & tidy rules", "Sort rules, auto-sort, watched folders", Routes.RULES),
            Tool(Icons.Rounded.CalendarMonth, "File calendar", "Browse & clean files by date", Routes.CALENDAR),
            Tool(Icons.Rounded.CompareArrows, "Compare folders", "Diff two directories side by side", Routes.COMPARE),
        ),
        "Privacy" to listOf(
            Tool(Icons.Rounded.PrivacyTip, "Safe share", "View & strip hidden metadata (GPS, camera, author) before sharing", Routes.METACLEAN),
        ),
        "Free up space" to listOf(
            Tool(Icons.Rounded.AutoAwesome, "Optimise", "Trash, duplicates, compress & convert", Routes.OPTIMISE),
            Tool(Icons.Rounded.Collections, "Photo cleaner", "Duplicates, similar shots & blurry photos", Routes.PHOTOCLEAN),
            Tool(Icons.Rounded.DonutLarge, "Storage analyser", "By type, large files, duplicates", Routes.STORAGE),
            Tool(Icons.Rounded.DeleteOutline, "Trash", "Restore or purge deleted files", Routes.TRASH),
        ),
        "Transfer & remote" to listOf(
            Tool(Icons.Rounded.Phonelink, "Transfer to PC", "Wi-Fi web page with full file manager", Routes.TRANSFER),
            Tool(Icons.Rounded.Sync, "Folder sync", "Mirror or two-way sync — local or remote folders", Routes.SYNCPAIRS),
            Tool(Icons.Rounded.Difference, "Delta transfer (CDC)", "FastCDC chunks — send only what changed", Routes.DELTA),
            Tool(Icons.Rounded.Cloud, "Remote storage", "SFTP, SMB, WebDAV, S3 connections", Routes.CONNECTIONS),
            Tool(Icons.Rounded.Splitscreen, "Dual pane", "Two folders side by side — drag files across", Routes.DUALPANE),
            Tool(Icons.Rounded.Terminal, "Power access (Shizuku)", "Open Android/data & restricted folders", Routes.POWER),
        ),
        "More" to listOf(
            Tool(Icons.Rounded.FolderZip, "Open archive as folder", "Browse ZIP / TAR without extracting", Routes.BROWSE),
            Tool(Icons.Rounded.History, "Operation history", "Success / failure log", Routes.HISTORY),
            Tool(Icons.Rounded.Settings, "Settings", "Theme, view, hidden files", Routes.SETTINGS),
        ),
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tools", fontWeight = FontWeight.Bold) })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            groups.forEach { (header, tools) ->
                SectionHeader(header)
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        tools.forEach { t ->
                            ListItem(
                                headlineContent = { Text(t.title, fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text(t.sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                leadingContent = {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(t.icon, null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp))
                                        }
                                    }
                                },
                                trailingContent = { Icon(Icons.Rounded.ChevronRight, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.clickable { nav.navigate(t.route) },
                                colors = ListItemDefaults.colors(
                                    containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
