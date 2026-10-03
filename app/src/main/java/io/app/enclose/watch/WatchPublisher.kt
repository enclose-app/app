package io.app.enclose.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.app.enclose.data.Territory
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.watchlink.WatchLink
import io.app.enclose.watchlink.WatchMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the watch's copy of the walk current.
 *
 * Runs for the life of the process, from [io.app.enclose.EncloseApp], because
 * that is how long a walk can run: the map screen is often gone while
 * [io.app.enclose.tracking.LocationService] carries on in the background.
 *
 * Three items, each throttled to how fast it is worth sending:
 * - the coarse status, only when it changes — a rounded figure moves every ten
 *   metres or so;
 * - the map (path and position), at most every [MAP_INTERVAL_MS] — it changes
 *   on every fix and is the largest of the three;
 * - nearby claims, when the claims change or a walk starts somewhere.
 *
 * The first status write, at process start, is an IDLE that clears whatever a
 * walk that died with the process left on the watch.
 */
object WatchPublisher {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(context: Context, scope: CoroutineScope, territories: Flow<List<Territory>>) {
        val app = context.applicationContext
        scope.launch {
            combine(TrackingManager.walk, TrackingManager.pendingClaim, ::watchStatusOf)
                .distinctUntilChanged()
                .collect { publish(app, WatchLink.STATUS_PATH, it.toFields()) }
        }
        scope.launch {
            // Simplifying the path is the costly part, so it runs once per sent
            // map, on the latest walk, not once per fix: a StateFlow hands a slow
            // collector only its newest value, so the fixes that arrived during
            // the wait are skipped.
            var last: WatchMap? = null
            TrackingManager.walk.collect { walk ->
                val map = watchMapOf(walk, TrackingManager.closureRadiusMeters)
                if (map != last) {
                    publish(app, WatchLink.MAP_PATH, map.toFields())
                    last = map
                }
                delay(MAP_INTERVAL_MS)
            }
        }
        scope.launch {
            // Centred on where the walk started: claims don't move, and re-picking
            // them as the walker moves would rewrite the item for no change. Only
            // watched while a walk has a start, so an idle process holds no query
            // open on the claims table.
            TrackingManager.walk.map { it.start }.distinctUntilChanged()
                .flatMapLatest { center ->
                    if (center == null) emptyFlow()
                    else territories.map { watchClaimsNear(it, center) }
                }
                .distinctUntilChanged()
                .collect { publish(app, WatchLink.CLAIMS_PATH, it.toFields()) }
        }
    }

    private fun publish(context: Context, path: String, fields: Map<String, Any>) {
        val request = PutDataMapRequest.create(path).apply {
            fields.forEach { (key, value) ->
                when (value) {
                    is Int -> dataMap.putInt(key, value)
                    is Long -> dataMap.putLong(key, value)
                    is Boolean -> dataMap.putBoolean(key, value)
                    is String -> dataMap.putString(key, value)
                    is LongArray -> dataMap.putLongArray(key, value)
                }
            }
        }.asPutDataRequest()
            // Live state: worth sending now, not at the next batch.
            .setUrgent()
        // No await and no retry: the next change carries the full state anyway,
        // and a phone without the Wearable API (no watch, ever) fails here on
        // every write, which is not worth more than a debug line.
        Wearable.getDataClient(context).putDataItem(request)
            .addOnFailureListener { Log.d(TAG, "Watch $path not published: ${it.message}") }
    }

    /** Fast enough to follow a walker on the map; slow enough to spare both radios. */
    private const val MAP_INTERVAL_MS = 5_000L

    private const val TAG = "EncloseWatch"
}
