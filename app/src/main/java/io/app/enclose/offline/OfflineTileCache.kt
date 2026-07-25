package io.app.enclose.offline

import android.content.Context
import io.app.enclose.geo.LatLng
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.geometry.LatLng as MlLatLng

/** What a finished (or abandoned) download ended up costing. */
data class DownloadResult(val sizeBytes: Long, val complete: Boolean)

/**
 * Thin coroutine wrapper over MapLibre's callback-based offline API.
 *
 * Deliberately holds no policy: what to download, how big, and what to evict
 * all live in [OfflineTilePlanner], which needs neither a device nor a network
 * to test. This half is the part that can only be exercised on a device.
 *
 * Every call resolves rather than throws — a failed download leaves the map
 * streaming as it always has, which is a degradation, not a fault worth
 * interrupting a walk for.
 */
class OfflineTileCache(context: Context) {

    private val manager = OfflineManager.getInstance(context.applicationContext)

    /**
     * Download [region] at the planner's zoom range, returning what it cost.
     *
     * Suspends until the region reports complete or stops making progress. The
     * caller runs this inside a WorkManager job, so the wait is the job's, not
     * the user's.
     */
    suspend fun download(
        region: PlannedRegion,
        styleUrl: String,
        pixelRatio: Float,
    ): Pair<Long, DownloadResult>? {
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            region.southWest.toBounds(region.northEast),
            OfflineTilePlanner.MIN_ZOOM,
            OfflineTilePlanner.MAX_ZOOM,
            pixelRatio,
        )
        val created = createRegion(definition, region.city.toByteArray()) ?: return null
        val result = runDownload(created)
        return created.id to result
    }

    private suspend fun createRegion(
        definition: OfflineTilePyramidRegionDefinition,
        metadata: ByteArray,
    ): OfflineRegion? = suspendCancellableCoroutine { continuation ->
        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    if (continuation.isActive) continuation.resume(offlineRegion)
                }

                override fun onError(error: String) {
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }

    private suspend fun runDownload(region: OfflineRegion): DownloadResult =
        suspendCancellableCoroutine { continuation ->
            var settled = false
            fun finish(status: OfflineRegionStatus?, complete: Boolean) {
                if (settled) return
                settled = true
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                region.setObserver(null)
                if (continuation.isActive) {
                    continuation.resume(
                        DownloadResult(
                            sizeBytes = status?.completedResourceSize ?: 0L,
                            complete = complete,
                        ),
                    )
                }
            }

            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    if (status.isComplete) finish(status, complete = true)
                }

                // Keep whatever was downloaded: a partly cached city is still
                // better than a grey screen, and the next run resumes it.
                override fun onError(error: OfflineRegionError) = finish(null, complete = false)

                override fun mapboxTileCountLimitExceeded(limit: Long) =
                    finish(null, complete = false)
            })

            continuation.invokeOnCancellation {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                region.setObserver(null)
            }

            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }

    /** Delete a downloaded region. Returns true when the tiles are actually gone. */
    suspend fun delete(regionId: Long): Boolean {
        val region = findRegion(regionId) ?: return false
        return suspendCancellableCoroutine { continuation ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(error: String) {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        }
    }

    private suspend fun findRegion(regionId: Long): OfflineRegion? =
        suspendCancellableCoroutine { continuation ->
            manager.getOfflineRegion(
                regionId,
                object : OfflineManager.GetOfflineRegionCallback {
                    override fun onRegion(offlineRegion: OfflineRegion) {
                        if (continuation.isActive) continuation.resume(offlineRegion)
                    }

                    // Already gone — treat as nothing to delete rather than a
                    // failure, so the tracking row can be cleaned up.
                    override fun onRegionNotFound() {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onError(error: String) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }

    private fun LatLng.toBounds(northEast: LatLng): LatLngBounds =
        LatLngBounds.Builder()
            .include(MlLatLng(lat, lng))
            .include(MlLatLng(northEast.lat, northEast.lng))
            .build()
}
