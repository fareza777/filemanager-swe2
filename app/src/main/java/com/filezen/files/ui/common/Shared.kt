package com.filezen.files.ui.common

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf

/** Shared element scope provided around the NavHost; null when unavailable. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The destination's AnimatedContentScope — provided per nav destination. */
val LocalAnimScope = compositionLocalOf<AnimatedContentScope?> { null }
