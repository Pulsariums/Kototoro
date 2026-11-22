package org.skepsun.kototoro.core.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.adaptive.tabletLayoutClass
import org.skepsun.kototoro.core.ui.gestures.mouseDragScroll

val HomeScreenHorizontalPadding = 12.dp
val HomeScreenSectionGap = 4.dp

/** Android's home page layout. Hosts supply data-bound sections and platform scroll/focus adapters. */
@Composable
fun HomeScreenLayout(
    hasHero: Boolean,
    hasHighlights: Boolean,
    initialized: Boolean,
    tabletLayoutEnabled: Boolean,
    hero: @Composable (Modifier, TabletLayoutClass) -> Unit,
    highlights: @Composable () -> Unit,
    quickActions: @Composable (expanded: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    listModifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 12.dp),
    horizontalPadding: PaddingValues = PaddingValues(horizontal = HomeScreenHorizontalPadding),
    estimatedHeroHeight: Dp = HomeHeroCardHeight,
    heroInsideList: Boolean = false,
    greeting: @Composable () -> Unit = {},
    loading: @Composable () -> Unit = {},
    /**
     * Android caps the home pane for readability. A desktop host that already narrows the pane with its
     * navigation rail passes null so the page uses the whole pane instead of stopping 1200dp in.
     */
    contentMaxWidth: Dp? = HomeContentMaxWidth,
) {
    val density = LocalDensity.current
    var heroPx by rememberSaveable { mutableIntStateOf(with(density) { estimatedHeroHeight.roundToPx() }) }
    val heroHeight by remember(density) { derivedStateOf { with(density) { heroPx.toDp() } } }
    val contentWidth = contentMaxWidth?.let { it + HomeScreenHorizontalPadding * 2 }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        // The navigation rail is outside this pane; resolve the breakpoint from the pane itself.
        val layoutClass = tabletLayoutClass(maxWidth.value.toInt(), tabletLayoutEnabled)
        val expanded = layoutClass == TabletLayoutClass.EXPANDED
        LazyColumn(
            state = listState,
            modifier = Modifier.align(Alignment.TopCenter)
                .then(if (contentWidth != null) Modifier.widthIn(max = contentWidth) else Modifier)
                .fillMaxSize()
                .then(listModifier).padding(horizontalPadding)
                .mouseDragScroll(listState, Orientation.Vertical).testTag("home-scroll"),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(HomeScreenSectionGap),
        ) {
            if (hasHighlights) {
                if (hasHero) {
                    if (heroInsideList) item(key = "home_hero") { hero(Modifier, layoutClass) }
                    else item(key = "home_hero_spacer") { Spacer(Modifier.height(heroHeight)) }
                } else item(key = "home_greeting") { greeting() }
                item(key = "home_highlights") {
                    if (expanded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Box(Modifier.weight(0.62f).testTag("home-highlights")) { highlights() }
                            Box(Modifier.weight(0.38f).padding(top = 8.dp)) { quickActions(true) }
                        }
                    } else Box(Modifier.testTag("home-highlights")) { highlights() }
                }
            }
            if (!hasHighlights && !initialized) item(key = "home_loading_skeleton") { loading() }
            if (!(hasHighlights && expanded)) item(key = "home_quick_actions") { quickActions(false) }
        }
        if (hasHero && !heroInsideList) {
            hero(
                Modifier.align(Alignment.TopCenter)
                    .then(if (contentWidth != null) Modifier.widthIn(max = contentWidth) else Modifier)
                    // Move layout coordinates too, so cover shared-element bounds track scrolling.
                    .offset {
                        val scroll = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset
                            else heroPx
                        IntOffset(0, -scroll.coerceIn(0, heroPx))
                    }
                    .onGloballyPositioned { if (heroPx != it.size.height) heroPx = it.size.height },
                layoutClass,
            )
        }
    }
}
