package io.app.enclose.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A city whose map tiles are cached on the device.
 *
 * MapLibre owns the tiles themselves and gives back only a numeric region id;
 * this table is what turns that id back into "which city, and does the user
 * ever go there" — the two things eviction needs and MapLibre doesn't track.
 */
@Entity(tableName = "offline_regions")
data class OfflineRegionEntity(
    @PrimaryKey val city: String,
    /** MapLibre's id for the downloaded region. */
    val regionId: Long,
    /** Bytes on disk as last reported; 0 until the download reports in. */
    val sizeBytes: Long = 0L,
    /** How many times the map has been opened over this region. */
    val visitCount: Int = 0,
    val lastVisitedAtEpochMs: Long = 0L,
    /** Null while the download is still incomplete. */
    val completedAtEpochMs: Long? = null,
)
