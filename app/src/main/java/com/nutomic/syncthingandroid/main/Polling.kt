package com.nutomic.syncthingandroid.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Value returned by [read], read at once and then every [intervalMs] while the screen is resumed,
 * like the Handler loops of the former fragments. Null until the first read, and a null read keeps
 * the last value. The loop restarts when one of [keys] changes, keeping the last value meanwhile.
 *
 * [read] starts on the main thread, where RestApi expects to be called; it switches dispatcher
 * itself for disk reads.
 */
@Composable
internal fun <T : Any> rememberPolled(vararg keys: Any?, intervalMs: Long, read: suspend () -> T?): T? {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentRead by rememberUpdatedState(read)
    val state = produceState<T?>(null, *keys, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                currentRead()?.let { value = it }
                delay(intervalMs)
            }
        }
    }
    return state.value
}
