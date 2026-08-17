package io.app.enclose.export

import io.app.enclose.data.OfflineRegionEntity
import io.app.enclose.data.ProfileEntity
import io.app.enclose.data.SettingsSnapshot
import io.app.enclose.data.SyncStatus
import io.app.enclose.data.TerritoryEntity
import io.app.enclose.data.WalkEntity
import io.app.enclose.data.WalkProgressEntity
import io.app.enclose.data.MapCamera
import io.app.enclose.geo.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Everything the app holds, in one value: every claim (standing and fallen),
 * every closed walk, the profile, the walk being recorded right now, the cached
 * map regions, and every remembered preference.
 *
 * "All data" is the requirement, so this is checked against the database rather
 * than against memory: it carries one field per table in
 * [io.app.enclose.data.EncloseDatabase] plus [SettingsSnapshot] for the
 * preferences file, which is the only other place anything is kept.
 */
data class BackupData(
    val createdAtEpochMs: Long,
    /** The app that wrote it, for a human reading the file — never branched on. */
    val appVersionName: String,
    /** The Room schema the rows came out of. See [Backup.decode]. */
    val schemaVersion: Int,
    val territories: List<TerritoryEntity> = emptyList(),
    val walks: List<WalkEntity> = emptyList(),
    val profile: ProfileEntity? = null,
    val walkProgress: WalkProgressEntity? = null,
    /**
     * The in-progress walk's path, in walked order.
     *
     * Positions only: the stored `seq` is an autoincrement rowid, local to the
     * database it came from, and re-inserting one would collide with a sequence
     * this device is still using. Order is the only thing about it that means
     * anything, and a list keeps that.
     */
    val walkProgressPoints: List<LatLng> = emptyList(),
    val offlineRegions: List<OfflineRegionEntity> = emptyList(),
    val settings: SettingsSnapshot = SettingsSnapshot(),
) {
    /** Rows that represent walking — what the headline count is about. */
    val walkedRowCount: Int get() = territories.size + walks.size
}

/**
 * The backup file format: one JSON document holding every table and every
 * setting, written and read by pure code so both halves are testable.
 *
 * **Columns are carried verbatim, including the geometry blobs.** A territory's
 * ring is already a JSON string in the database and it stays a JSON string here,
 * escaped inside the file rather than re-encoded as structure. It makes the file
 * uglier and it is deliberate: re-encoding geometry on the way out and back is
 * an opportunity for a walked outline to come back subtly different, and nothing
 * about a backup is worth that. What goes in the file is what was in the column.
 *
 * **Every field is read with a default** ([str], [long], [double] and friends),
 * so a file written by an older version restores into a newer schema — a column
 * added since simply takes the same default a migration would have given it.
 */
object Backup {

    /**
     * The envelope version. Bumped only when the *shape* of the document changes
     * in a way an older reader would misread; adding a field does not qualify,
     * because every reader defaults what it doesn't find.
     */
    const val FORMAT_VERSION = 1

    const val MIME_TYPE = "application/json"

    private const val KEY_FORMAT = "formatVersion"
    private const val KEY_SCHEMA = "schemaVersion"
    private const val KEY_CREATED = "createdAtEpochMs"
    private const val KEY_APP = "appVersionName"
    private const val KEY_TERRITORIES = "territories"
    private const val KEY_WALKS = "walks"
    private const val KEY_PROFILE = "profile"
    private const val KEY_PROGRESS = "walkInProgress"
    private const val KEY_PROGRESS_POINTS = "points"
    private const val KEY_OFFLINE = "offlineRegions"
    private const val KEY_SETTINGS = "settings"

    /** What [decode] made of a file the user picked. */
    sealed interface Decoded {

        /**
         * A readable backup. [note] is non-null when something about the file is
         * worth saying out loud even though it restored — currently only that it
         * came from a newer database than this build knows.
         */
        data class Ok(val data: BackupData, val note: String? = null) : Decoded

        /** Nothing usable, phrased for the user rather than for a log. */
        data class Failed(val reason: String) : Decoded
    }

    /**
     * A suggested filename, dated so a folder of backups sorts and reads in the
     * order they were taken. The date is the device's own — this names a file
     * for the person who made it, not a UTC timestamp for a machine.
     */
    fun fileName(atEpochMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(atEpochMs))
        return "enclose-backup-$stamp.json"
    }

    fun encode(data: BackupData): String = Json.write(
        linkedMapOf(
            KEY_FORMAT to FORMAT_VERSION,
            KEY_SCHEMA to data.schemaVersion,
            KEY_CREATED to data.createdAtEpochMs,
            KEY_APP to data.appVersionName,
            KEY_TERRITORIES to data.territories.map(::territoryToMap),
            KEY_WALKS to data.walks.map(::walkToMap),
            KEY_PROFILE to data.profile?.let(::profileToMap),
            KEY_PROGRESS to data.walkProgress?.let { progressToMap(it, data.walkProgressPoints) },
            KEY_OFFLINE to data.offlineRegions.map(::offlineRegionToMap),
            KEY_SETTINGS to settingsToMap(data.settings),
        ),
    )

    /**
     * Read [text] back, or say why it can't be.
     *
     * A **newer envelope is refused**: the one thing a reader cannot do is guess
     * what a shape it has never seen means, and half-restoring a backup is worse
     * than not restoring it, because the user believes they have their walks
     * back. A newer *schema* is accepted with a note instead — the rows are still
     * rows, and anything this build has no column for is dropped rather than
     * fabricated.
     *
     * @param currentSchemaVersion the database version of the build reading it.
     */
    fun decode(text: String, currentSchemaVersion: Int): Decoded {
        val root = runCatching { Json.parse(text) }.getOrElse {
            return Decoded.Failed("That file isn't an Enclose backup — it isn't even JSON.")
        }.asObject() ?: return Decoded.Failed("That file isn't an Enclose backup.")

        val format = root.int(KEY_FORMAT, fallback = -1)
        if (format < 0) {
            return Decoded.Failed(
                "That file isn't an Enclose backup — it has no format version in it.",
            )
        }
        if (format > FORMAT_VERSION) {
            return Decoded.Failed(
                "That backup was written by a newer version of Enclose (format $format; " +
                    "this build reads $FORMAT_VERSION). Update the app and try again.",
            )
        }

        val schema = root.int(KEY_SCHEMA, fallback = currentSchemaVersion)
        val progress = root[KEY_PROGRESS].asObject()
        val data = BackupData(
            createdAtEpochMs = root.long(KEY_CREATED),
            appVersionName = root.str(KEY_APP),
            schemaVersion = schema,
            territories = root.objects(KEY_TERRITORIES).map(::territoryFromMap),
            walks = root.objects(KEY_WALKS).map(::walkFromMap),
            profile = root[KEY_PROFILE].asObject()?.let(::profileFromMap),
            walkProgress = progress?.let(::progressFromMap),
            walkProgressPoints = progress?.let(::progressPointsFromMap).orEmpty(),
            offlineRegions = root.objects(KEY_OFFLINE).map(::offlineRegionFromMap),
            settings = root[KEY_SETTINGS].asObject()?.let(::settingsFromMap) ?: SettingsSnapshot(),
        )
        val note = if (schema > currentSchemaVersion) {
            "It was made with a newer version of the app's database (v$schema, this build " +
                "uses v$currentSchemaVersion). Anything this build has no place for was left out."
        } else {
            null
        }
        return Decoded.Ok(data, note)
    }

    // --- territories ---------------------------------------------------------

    private fun territoryToMap(t: TerritoryEntity): Map<String, Any?> = linkedMapOf(
        "id" to t.id,
        "name" to t.name,
        "ringJson" to t.ringJson,
        "geometryJson" to t.geometryJson,
        "areaSqMeters" to t.areaSqMeters,
        "perimeterMeters" to t.perimeterMeters,
        "claimedAtEpochMs" to t.claimedAtEpochMs,
        "colorHex" to t.colorHex,
        "notes" to t.notes,
        "city" to t.city,
        "country" to t.country,
        "conqueredAtEpochMs" to t.conqueredAtEpochMs,
        "conqueredById" to t.conqueredById,
        "snappedJson" to t.snappedJson,
        "snappedAtEpochMs" to t.snappedAtEpochMs,
        "carvedAtEpochMs" to t.carvedAtEpochMs,
        "syncStatus" to t.syncStatus.name,
    )

    private fun territoryFromMap(m: Map<String, Any?>): TerritoryEntity = TerritoryEntity(
        id = m.str("id"),
        name = m.str("name"),
        // Blank, not "[]", when absent: TerritoryEntity.toDomain already guards a
        // blank snapped ring, and an empty geometry array is what the rest of the
        // app reads as "nothing to draw" either way.
        ringJson = m.str("ringJson", "[]"),
        geometryJson = m.str("geometryJson", "[]"),
        areaSqMeters = m.double("areaSqMeters"),
        perimeterMeters = m.double("perimeterMeters"),
        claimedAtEpochMs = m.long("claimedAtEpochMs"),
        colorHex = m.str("colorHex", io.app.enclose.data.Territory.DEFAULT_COLOR),
        notes = m.str("notes"),
        city = m.str("city"),
        country = m.str("country"),
        conqueredAtEpochMs = m.longOrNull("conqueredAtEpochMs"),
        conqueredById = m.strOrNull("conqueredById"),
        snappedJson = m.str("snappedJson"),
        snappedAtEpochMs = m.longOrNull("snappedAtEpochMs"),
        carvedAtEpochMs = m.longOrNull("carvedAtEpochMs"),
        syncStatus = syncStatus(m.strOrNull("syncStatus")),
    )

    // --- walks ---------------------------------------------------------------

    private fun walkToMap(w: WalkEntity): Map<String, Any?> = linkedMapOf(
        "id" to w.id,
        "ringJson" to w.ringJson,
        "areaSqMeters" to w.areaSqMeters,
        "perimeterMeters" to w.perimeterMeters,
        "distanceToStartMeters" to w.distanceToStartMeters,
        "closedAtEpochMs" to w.closedAtEpochMs,
        "startedAtEpochMs" to w.startedAtEpochMs,
        "elevationGainMeters" to w.elevationGainMeters,
        "movingMs" to w.movingMs,
        "claimed" to w.claimed,
        "syncStatus" to w.syncStatus.name,
    )

    private fun walkFromMap(m: Map<String, Any?>): WalkEntity = WalkEntity(
        id = m.str("id"),
        ringJson = m.str("ringJson", "[]"),
        areaSqMeters = m.double("areaSqMeters"),
        perimeterMeters = m.double("perimeterMeters"),
        distanceToStartMeters = m.double("distanceToStartMeters"),
        closedAtEpochMs = m.long("closedAtEpochMs"),
        // Null rather than 0: a walk recorded before start times were kept has no
        // start time, and 0 would draw it as having begun in 1970.
        startedAtEpochMs = m.longOrNull("startedAtEpochMs"),
        elevationGainMeters = m.double("elevationGainMeters"),
        movingMs = m.longOrNull("movingMs"),
        claimed = m.bool("claimed"),
        syncStatus = syncStatus(m.strOrNull("syncStatus")),
    )

    // --- profile -------------------------------------------------------------

    private fun profileToMap(p: ProfileEntity): Map<String, Any?> = linkedMapOf(
        "id" to p.id,
        "firstName" to p.firstName,
        "lastName" to p.lastName,
        "createdAtEpochMs" to p.createdAtEpochMs,
        "isGuest" to p.isGuest,
    )

    private fun profileFromMap(m: Map<String, Any?>): ProfileEntity = ProfileEntity(
        id = m.str("id", ProfileEntity.SINGLETON_ID),
        firstName = m.str("firstName"),
        lastName = m.str("lastName"),
        createdAtEpochMs = m.long("createdAtEpochMs"),
        isGuest = m.bool("isGuest", fallback = true),
    )

    // --- the walk in progress ------------------------------------------------

    private fun progressToMap(
        session: WalkProgressEntity,
        points: List<LatLng>,
    ): Map<String, Any?> = linkedMapOf(
        "id" to session.id,
        "startedAtEpochMs" to session.startedAtEpochMs,
        "activityType" to session.activityType,
        "elevationGainMeters" to session.elevationGainMeters,
        "movingMs" to session.movingMs,
        // Flat [lat,lng] pairs rather than objects: this is the longest list in
        // the file by a wide margin (one entry per GPS fix), and the key names
        // repeated a few thousand times are most of what it would weigh.
        KEY_PROGRESS_POINTS to points.map { listOf(it.lat, it.lng) },
    )

    private fun progressFromMap(m: Map<String, Any?>): WalkProgressEntity = WalkProgressEntity(
        id = m.str("id", WalkProgressEntity.SINGLETON_ID),
        startedAtEpochMs = m.long("startedAtEpochMs"),
        activityType = m.str("activityType", "WALK"),
        elevationGainMeters = m.double("elevationGainMeters"),
        movingMs = m.long("movingMs"),
    )

    private fun progressPointsFromMap(m: Map<String, Any?>): List<LatLng> =
        m[KEY_PROGRESS_POINTS].asArray().mapNotNull { entry ->
            val pair = entry.asArray()
            if (pair.size < 2) return@mapNotNull null
            val lat = pair[0].asDouble() ?: return@mapNotNull null
            val lng = pair[1].asDouble() ?: return@mapNotNull null
            LatLng(lat, lng)
        }

    // --- offline regions -----------------------------------------------------

    private fun offlineRegionToMap(r: OfflineRegionEntity): Map<String, Any?> = linkedMapOf(
        "city" to r.city,
        "regionId" to r.regionId,
        "sizeBytes" to r.sizeBytes,
        "visitCount" to r.visitCount,
        "lastVisitedAtEpochMs" to r.lastVisitedAtEpochMs,
        "completedAtEpochMs" to r.completedAtEpochMs,
    )

    private fun offlineRegionFromMap(m: Map<String, Any?>): OfflineRegionEntity =
        OfflineRegionEntity(
            city = m.str("city"),
            regionId = m.long("regionId"),
            sizeBytes = m.long("sizeBytes"),
            visitCount = m.int("visitCount"),
            lastVisitedAtEpochMs = m.long("lastVisitedAtEpochMs"),
            completedAtEpochMs = m.longOrNull("completedAtEpochMs"),
        )

    // --- settings ------------------------------------------------------------

    private fun settingsToMap(s: SettingsSnapshot): Map<String, Any?> = linkedMapOf(
        "seenIntro" to s.seenIntro,
        "activityTypeName" to s.activityTypeName,
        "basemapStyleName" to s.basemapStyleName,
        "territorySortName" to s.territorySortName,
        "testMode" to s.testMode,
        "snapToPaths" to s.snapToPaths,
        "panelCollapsed" to s.panelCollapsed,
        "floatingWindow" to s.floatingWindow,
        "plannedDistanceMeters" to s.plannedDistanceMeters,
        "plannedRoute" to s.plannedRoute,
        "offlineStyleUrl" to s.offlineStyleUrl,
        "offlinePixelRatio" to s.offlinePixelRatio.toDouble(),
        "home" to s.home?.let { listOf(it.lat, it.lng) },
        "camera" to s.camera?.let {
            linkedMapOf(
                "lat" to it.lat,
                "lng" to it.lng,
                "zoom" to it.zoom,
                "bearing" to it.bearing,
                "tilt" to it.tilt,
            )
        },
    )

    private fun settingsFromMap(m: Map<String, Any?>): SettingsSnapshot {
        val defaults = SettingsSnapshot()
        val camera = m["camera"].asObject()
        val home = m["home"].asArray()
        return SettingsSnapshot(
            seenIntro = m.bool("seenIntro"),
            activityTypeName = m.strOrNull("activityTypeName"),
            basemapStyleName = m.strOrNull("basemapStyleName"),
            territorySortName = m.strOrNull("territorySortName"),
            testMode = m.bool("testMode"),
            snapToPaths = m.bool("snapToPaths"),
            panelCollapsed = m.bool("panelCollapsed"),
            floatingWindow = m.bool("floatingWindow"),
            plannedDistanceMeters = m.int(
                "plannedDistanceMeters",
                defaults.plannedDistanceMeters,
            ),
            plannedRoute = m.strOrNull("plannedRoute"),
            offlineStyleUrl = m.strOrNull("offlineStyleUrl"),
            offlinePixelRatio = m.double(
                "offlinePixelRatio",
                defaults.offlinePixelRatio.toDouble(),
            ).toFloat(),
            camera = camera?.let {
                MapCamera(
                    lat = it.double("lat"),
                    lng = it.double("lng"),
                    zoom = it.double("zoom"),
                    bearing = it.double("bearing"),
                    tilt = it.double("tilt"),
                )
            },
            home = if (home.size >= 2) {
                val lat = home[0].asDouble()
                val lng = home[1].asDouble()
                if (lat != null && lng != null) LatLng(lat, lng) else null
            } else {
                null
            },
        )
    }

    /**
     * An unreadable status restores as `PENDING`, never as `SYNCED`. Both are
     * guesses, but one of them tells a future backend the row is already up
     * there when nothing has ever been sent — and re-sending a row costs a
     * request, while skipping one loses it.
     */
    private fun syncStatus(name: String?): SyncStatus =
        SyncStatus.entries.firstOrNull { it.name == name } ?: SyncStatus.PENDING

    private fun Any?.asDouble(): Double? = when (this) {
        is Double -> this
        is Long -> toDouble()
        else -> null
    }
}
