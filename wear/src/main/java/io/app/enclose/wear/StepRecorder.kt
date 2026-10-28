package io.app.enclose.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearPassiveListenerService
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.flush
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.setPassiveListenerService
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import io.app.enclose.watchlink.WatchLink
import io.app.enclose.watchlink.WatchStatus
import io.app.enclose.watchlink.WatchSteps
import io.app.enclose.wear.StepWitness.Registration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Counts the walker's steps on the wrist while the phone records a walk, and
 * sends them to the phone as the walk's second witness — see [WatchSteps].
 *
 * Health Services does the counting, passively: the platform batches it and
 * wakes [StepListenerService] with each batch, so nothing here runs in between
 * and the cost is next to nothing. It is registered only while the phone says a
 * walk is on, and what it has counted is kept in preferences, because every
 * service that calls in here lives for one delivery.
 *
 * Called from listener threads, never the main one. [lock] keeps a delivery and
 * a status from interleaving their read-modify-write of what is held.
 */
class StepRecorder(context: Context) {

    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val passive by lazy { HealthServices.getClient(this.context).passiveMonitoringClient }

    /** Follows the phone's walk: counting while one is on, and holding that walk's steps only. */
    suspend fun follow(status: WatchStatus) {
        lock.withLock {
            val before = kept()
            val after = StepWitness.follow(before, status)
            if (after != before) store(after)
            // The walk's first fix: what was counted before it can be matched now.
            if (before.walk == null && after.walk != null && after.intervals.isNotEmpty()) {
                send(after, flushed = false)
            }
            when (StepWitness.actionFor(registration(), status)) {
                StepWitness.Action.REGISTER -> register()
                StepWitness.Action.CLEAR -> clear()
                StepWitness.Action.KEEP -> Unit
            }
        }
    }

    /** A batch from Health Services: held, and everything held for the walk sent. */
    suspend fun onDelivered(batch: List<WatchSteps.Interval>) {
        lock.withLock {
            // Nothing is counted between walks: a straggler from the last one is
            // no evidence for the next.
            if (batch.isEmpty() || registration() == Registration.NONE) return@withLock
            val kept = kept()
            val merged = kept.copy(intervals = StepWitness.merge(kept.intervals, batch))
            if (merged == kept) return@withLock
            store(merged)
            send(merged, flushed = false)
        }
        delivered.tryEmit(Unit)
    }

    /**
     * The phone's Stop, asking for everything: Health Services is told to hand
     * over what it is holding, and what is held once that arrives — or once
     * [FLUSH_WAIT_MS] has passed — is sent as the last word, so the phone can
     * stop waiting. Answered at once when nothing is counting.
     *
     * @param asked the walk the phone asked about, if its request could be read.
     */
    suspend fun flush(asked: Long?) {
        if (registration() != Registration.NONE) {
            withTimeoutOrNull(FLUSH_WAIT_MS) {
                coroutineScope {
                    // Listening before asking, so a delivery can't arrive unheard.
                    val next = async(start = CoroutineStart.UNDISPATCHED) { delivered.first() }
                    if (runCatching { passive.flush() }.isSuccess) next.await() else next.cancel()
                }
            }
        }
        lock.withLock {
            val kept = kept()
            val walk = asked ?: kept.walk ?: return@withLock
            // Held before the walk had a start, the steps are this walk's; held
            // for another walk, none of them are.
            val intervals = if (kept.walk == null || kept.walk == walk) kept.intervals else emptyList()
            send(StepWitness.Kept(walk, intervals), flushed = true)
        }
    }

    /** Health Services took the permission away, and the registration with it. */
    fun onPermissionLost() {
        prefs.edit { remove(KEY_REGISTERED) }
    }

    private suspend fun register() {
        // Asked for when the app opens. Until it is granted, the phone judges on
        // its own steps, as it does with no watch at all.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (unsupported) return
        runCatching {
            if (DataType.STEPS !in passive.getCapabilities().supportedDataTypesPassiveMonitoring) {
                unsupported = true
                return
            }
            passive.setPassiveListenerService(StepListenerService::class.java, CONFIG)
            prefs.edit { putString(KEY_REGISTERED, token()) }
        }.onFailure { Log.d(TAG, "Steps not registered: ${it.message}") }
    }

    private suspend fun clear() {
        runCatching { passive.clearPassiveListenerService() }
            .onFailure { Log.d(TAG, "Steps not cleared: ${it.message}") }
        // Forgotten either way: a registration that outlived this only delivers
        // steps that are then dropped, and the next walk registers afresh.
        prefs.edit { remove(KEY_REGISTERED) }
    }

    private fun registration(): Registration = when (prefs.getString(KEY_REGISTERED, null)) {
        null -> Registration.NONE
        token() -> Registration.CURRENT
        else -> Registration.STALE
    }

    /**
     * This boot and this build. Health Services forgets a registration across a
     * reboot, and may across an update, so one made under another of either is
     * made again rather than trusted.
     */
    private fun token(): String =
        "${Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)}:${BuildConfig.VERSION_CODE}"

    private fun kept() = StepWitness.Kept(
        walk = if (prefs.contains(KEY_WALK)) prefs.getLong(KEY_WALK, 0L) else null,
        intervals = StepWitness.decode(prefs.getString(KEY_INTERVALS, null)),
    )

    private fun store(kept: StepWitness.Kept) = prefs.edit(commit = true) {
        if (kept.walk == null) remove(KEY_WALK) else putLong(KEY_WALK, kept.walk)
        putString(KEY_INTERVALS, StepWitness.encode(kept.intervals))
    }

    /** To every phone in reach; there is one. A message lost is repaired by the next. */
    private suspend fun send(kept: StepWitness.Kept, flushed: Boolean) {
        val walk = kept.walk ?: return
        val payload = dataMapOf(WatchSteps(walk, kept.intervals, flushed).toFields()).toByteArray()
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }
            .getOrDefault(emptyList())
        val messages = Wearable.getMessageClient(context)
        for (node in nodes) {
            runCatching { messages.sendMessage(node.id, WatchLink.STEPS_PATH, payload).await() }
                .onFailure { Log.d(TAG, "Steps not sent: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "StepRecorder"
        const val PREFS = "step_witness"
        const val KEY_REGISTERED = "registered"
        const val KEY_WALK = "walk"
        const val KEY_INTERVALS = "intervals"

        /** Long enough for Health Services to hand a batch over; well short of the phone's wait. */
        const val FLUSH_WAIT_MS = 2_000L

        val CONFIG = PassiveListenerConfig.builder().setDataTypes(setOf(DataType.STEPS)).build()

        /** Every delivery and status in the process, one at a time. */
        val lock = Mutex()

        /** Deliveries as they land, for [flush] to wait on. */
        val delivered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Asked once per process: a watch without passive steps won't grow them. */
        @Volatile
        var unsupported = false
    }
}

/** A `:watchlink` codec's fields as a [DataMap]. */
private fun dataMapOf(fields: Map<String, Any>) = DataMap().apply {
    fields.forEach { (key, value) ->
        when (value) {
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            is LongArray -> putLongArray(key, value)
            is ByteArray -> putByteArray(key, value)
        }
    }
}
