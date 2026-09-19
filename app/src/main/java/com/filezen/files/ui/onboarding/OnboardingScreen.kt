package com.filezen.files.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filezen.files.FileZenApp
import kotlinx.coroutines.launch

private data class Page(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val body: String,
    val bullets: List<String>,
)

private val pages = listOf(
    Page(
        Icons.Rounded.AutoAwesome, Color(0xFF81D5C0),
        "Welcome to FileZen",
        "A file manager that stays tidy by itself.",
        listOf(
            "New downloads & docs land in your Inbox",
            "Preview before you file anything",
            "Safe trash with one-tap undo",
        ),
    ),
    Page(
        Icons.Rounded.MoveToInbox, Color(0xFF8AB4F8),
        "The Inbox flow",
        "File new stuff without digging through folders.",
        listOf(
            "Tap “Tidy up” → rename → pick a favourite folder",
            "Batch-select to file many at once",
            "Auto-sort rules move PDFs & friends automatically",
        ),
    ),
    Page(
        Icons.Rounded.VerifiedUser, Color(0xFFF0A7C3),
        "One permission",
        "FileZen needs All-files access to manage your storage.",
        listOf(
            "Everything stays on-device — no account, no cloud",
            "SD card & USB: grant once from Browse",
            "You can revoke it any time in system Settings",
        ),
    ),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val c = FileZenApp.c

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0A0F14), Color(0xFF10241F)))),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                val p = pages[i]
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Floating icon badge
                    val pulse = rememberInfiniteTransition(label = "ob")
                    val scale by pulse.animateFloat(1f, 1.06f,
                        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse), label = "obS")
                    Box(
                        Modifier.size(120.dp).clip(CircleShape)
                            .background(p.tint.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(p.icon, null, tint = p.tint,
                            modifier = Modifier.size((56 * scale).dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(p.title, style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = Color(0xFFE8F1EC),
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(p.body, style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF9FB3AC), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(26.dp))
                    p.bullets.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null,
                                tint = p.tint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(b, color = Color(0xFFDDE7E2),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Dots
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(pages.size) { i ->
                    val active = pager.currentPage == i
                    val w by animateDpAsState(if (active) 22.dp else 8.dp, label = "dot")
                    Box(Modifier.padding(4.dp).height(8.dp).width(w)
                        .clip(CircleShape)
                        .background(if (active) Color(0xFF81D5C0)
                            else Color.White.copy(alpha = 0.25f)))
                }
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (pager.currentPage < pages.size - 1) {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    } else {
                        scope.launch { c.settings.setOnboarded(true) }; onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF81D5C0), contentColor = Color(0xFF0A0F14)),
            ) {
                Text(
                    if (pager.currentPage < pages.size - 1) "Next" else "Get started",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Permission shortcut on the last page
            if (pager.currentPage == pages.size - 1) {
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + ctx.packageName)))
                    }
                }) { Text("Grant All-files access now", color = Color(0xFF81D5C0)) }
            } else {
                TextButton(onClick = {
                    scope.launch { c.settings.setOnboarded(true) }; onDone()
                }) { Text("Skip", color = Color(0xFF9FB3AC)) }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** In-app animated splash: gradient backdrop, pulsing logo, wordmark.
 *  Shown ~900ms on top of the UI while the first frame composes. */
@Composable
fun SplashOverlay() {
    val logoScale = remember { Animatable(0.6f) }
    val logoAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(500)) }
        logoScale.animateTo(1f,
            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
    }
    val ring = rememberInfiniteTransition(label = "ring")
    val ringScale by ring.animateFloat(1f, 1.5f,
        infiniteRepeatable(tween(1200, easing = LinearOutSlowInEasing)), label = "rs")
    val ringAlpha by ring.animateFloat(0.5f, 0f,
        infiniteRepeatable(tween(1200, easing = LinearOutSlowInEasing)), label = "ra")

    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                listOf(Color(0xFF14332C), Color(0xFF0A0F14)),
                radius = 1600f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(150.dp).graphicsLayer {
                scaleX = ringScale; scaleY = ringScale; alpha = ringAlpha
            }.clip(CircleShape).background(Color(0xFF81D5C0)))
            Box(
                Modifier.size(104.dp).graphicsLayer {
                    scaleX = logoScale.value; scaleY = logoScale.value; alpha = logoAlpha.value
                }.clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(
                        listOf(Color(0xFF81D5C0), Color(0xFF4E9E8B)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.FolderSpecial, null,
                    tint = Color(0xFF0A0F14), modifier = Modifier.size(52.dp))
            }
        }
        Column(
            Modifier.align(Alignment.Center).offset(y = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("FileZen", color = Color(0xFFE8F1EC),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Your files, effortlessly tidy.",
                color = Color(0xFF9FB3AC),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
