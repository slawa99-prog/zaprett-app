package com.cherret.zaprett.ui.component

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Add D-pad scrolling while still letting Compose move focus to the next control. */
fun Modifier.tvDpadScroll(state: LazyListState): Modifier = composed {
    val scope = rememberCoroutineScope()
    val pixels = with(LocalDensity.current) { 96.dp.toPx() }
    focusGroup().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.DirectionDown -> if (state.canScrollForward) scope.launch { state.scrollBy(pixels) }
                Key.DirectionUp -> if (state.canScrollBackward) scope.launch { state.scrollBy(-pixels) }
                else -> Unit
            }
        }
        false
    }
}

fun Modifier.tvDpadScroll(state: ScrollState): Modifier = composed {
    val scope = rememberCoroutineScope()
    val pixels = with(LocalDensity.current) { 96.dp.toPx() }
    focusGroup().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.DirectionDown -> if (state.value < state.maxValue) scope.launch { state.scrollBy(pixels) }
                Key.DirectionUp -> if (state.value > 0) scope.launch { state.scrollBy(-pixels) }
                else -> Unit
            }
        }
        false
    }
}
