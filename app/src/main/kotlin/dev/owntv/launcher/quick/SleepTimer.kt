package dev.owntv.launcher.quick

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The quick settings sleep timer: puts the TV into standby after a number of minutes. It lives as long as
 * OwnTV's process, which the system keeps while OwnTV is the Home app or its remote service is on.
 */
class SleepTimer(private val scope: CoroutineScope, private val onEnd: () -> Unit) {
    private val end = MutableStateFlow<Long?>(null)
    private var job: Job? = null

    /** When the timer ends, in [SystemClock.elapsedRealtime] time; null while it is off. */
    val endsAt: StateFlow<Long?> = end.asStateFlow()

    /** Whole minutes left, rounded up; null while the timer is off. */
    fun remainingMinutes(now: Long = SystemClock.elapsedRealtime()): Int? =
        end.value?.let { SleepSteps.remainingMinutes((it - now).coerceAtLeast(0)) }

    /** Starts the timer for [minutes], or stops it with 0. */
    fun set(minutes: Int) {
        job?.cancel()
        if (minutes <= 0) {
            end.value = null
            return
        }
        val ms = minutes * 60_000L
        end.value = SystemClock.elapsedRealtime() + ms
        job = scope.launch {
            delay(ms)
            end.value = null
            onEnd()
        }
    }
}
