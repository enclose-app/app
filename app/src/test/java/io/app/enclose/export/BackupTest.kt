package io.app.enclose.export

import io.app.enclose.data.MapCamera
import io.app.enclose.data.OfflineRegionEntity
import io.app.enclose.data.ProfileEntity
import io.app.enclose.data.SettingsSnapshot
import io.app.enclose.data.SyncStatus
import io.app.enclose.data.TerritoryEntity
import io.app.enclose.data.WalkEntity
import io.app.enclose.data.WalkProgressEntity
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup file. What matters is not that it round-trips a fixture — it is
 * that a file written today opens on a phone running a build nobody has written
 * yet, and that a file which isn't one of ours is refused instead of half
 * restored. Both are tested here; the database half is in `BackupRepository`,
 * which needs a device.
 */
class BackupTest {

    private val schema = 12

    private fun territory(id: String) = TerritoryEntity(
        id = id,
        name = "Πλατεία \"Ομονοίας\"", // quotes and non-ascii: both go through the codec
        ringJson = """[{"lat":37.9838,"lng":23.7275},{"lat":37.9848,"lng":23.7285}]""",
        geometryJson = """[[[{"lat":37.9838,"lng":23.7275}]]]""",
        areaSqMeters = 12345.678,
        perimeterMeters = 987.65,
        claimedAtEpochMs = 1_700_000_000_000,
        colorHex = "#7B1FA2",
        notes = "line one\nline two",
        city = "Athens",
        country = "Greece",
        conqueredAtEpochMs = 1_700_000_900_000,
        conqueredById = "other-claim",
        snappedJson = """[{"lat":37.9838,"lng":23.7275}]""",
        snappedAtEpochMs = 1_700_000_500_000,
        carvedAtEpochMs = 1_700_000_700_000,
        syncStatus = SyncStatus.SYNCED,
    )

    private fun walk(id: String) = WalkEntity(
        id = id,
        ringJson = """[{"lat":37.9838,"lng":23.7275}]""",
        areaSqMeters = 100.0,
        perimeterMeters = 200.0,
        distanceToStartMeters = 3.5,
        closedAtEpochMs = 1_700_000_000_000,
        startedAtEpochMs = 1_699_999_000_000,
        elevationGainMeters = 42.5,
        movingMs = 1_234_567,
        claimed = true,
        syncStatus = SyncStatus.PENDING,
    )

    private fun full() = BackupData(
        createdAtEpochMs = 1_700_100_000_000,
        appVersionName = "1.0",
        schemaVersion = schema,
        territories = listOf(territory("t1"), territory("t2")),
        walks = listOf(walk("w1")),
        profile = ProfileEntity(
            firstName = "Wandering",
            lastName = "Fox",
            createdAtEpochMs = 1_600_000_000_000,
            isGuest = true,
        ),
        walkProgress = WalkProgressEntity(
            startedAtEpochMs = 1_700_200_000_000,
            activityType = "RUN",
            elevationGainMeters = 12.0,
            movingMs = 60_000,
        ),
        walkProgressPoints = listOf(
            LatLng(37.9838, 23.7275),
            LatLng(37.9840, 23.7277),
            LatLng(37.9842, 23.7279),
        ),
        offlineRegions = listOf(
            OfflineRegionEntity(
                city = "Athens",
                regionId = 7,
                sizeBytes = 12_000_000,
                visitCount = 9,
                lastVisitedAtEpochMs = 1_700_000_000_000,
                completedAtEpochMs = 1_700_000_100_000,
            ),
        ),
        settings = SettingsSnapshot(
            seenIntro = true,
            activityTypeName = "RUN",
            basemapStyleName = "DARK",
            territorySortName = "LARGEST",
            testMode = true,
            snapToPaths = true,
            panelCollapsed = true,
            floatingWindow = true,
            plannedDistanceMeters = 7_500,
            plannedRoute = "_p~iF~ps|U",
            offlineStyleUrl = "https://tiles.openfreemap.org/styles/dark",
            offlinePixelRatio = 2.75f,
            camera = MapCamera(37.9838, 23.7275, 16.5, 90.0, 30.0),
            home = LatLng(37.9800, 23.7300),
        ),
    )

    private fun decodeOk(text: String): Backup.Decoded.Ok {
        val decoded = Backup.decode(text, currentSchemaVersion = schema)
        assertTrue("expected a readable backup, got $decoded", decoded is Backup.Decoded.Ok)
        return decoded as Backup.Decoded.Ok
    }

    /** The whole point of the feature: everything in, everything back out. */
    @Test
    fun `every table and every setting survives a round trip`() {
        val original = full()

        val restored = decodeOk(Backup.encode(original)).data

        assertEquals(original.territories, restored.territories)
        assertEquals(original.walks, restored.walks)
        assertEquals(original.profile, restored.profile)
        assertEquals(original.walkProgress, restored.walkProgress)
        assertEquals(original.walkProgressPoints, restored.walkProgressPoints)
        assertEquals(original.offlineRegions, restored.offlineRegions)
        assertEquals(original.settings, restored.settings)
        assertEquals(original.createdAtEpochMs, restored.createdAtEpochMs)
        assertEquals(original.schemaVersion, restored.schemaVersion)
    }

    /**
     * Geometry is carried as the column's own text, unparsed. If this ever
     * changes to re-encoding, a walked outline could come back subtly different,
     * which is the one thing a backup must never do.
     */
    @Test
    fun `geometry text is byte-for-byte what was stored`() {
        val original = full()

        val restored = decodeOk(Backup.encode(original)).data

        assertEquals(original.territories[0].ringJson, restored.territories[0].ringJson)
        assertEquals(original.territories[0].geometryJson, restored.territories[0].geometryJson)
        assertEquals(original.territories[0].snappedJson, restored.territories[0].snappedJson)
    }

    @Test
    fun `an empty device backs up and restores as empty`() {
        val empty = BackupData(
            createdAtEpochMs = 1L,
            appVersionName = "1.0",
            schemaVersion = schema,
        )

        val restored = decodeOk(Backup.encode(empty)).data

        assertTrue(restored.territories.isEmpty())
        assertTrue(restored.walks.isEmpty())
        assertNull(restored.profile)
        assertNull(restored.walkProgress)
        assertEquals(SettingsSnapshot(), restored.settings)
    }

    /**
     * The forward-compatibility promise: a file from an older build, missing
     * every column added since, restores with those columns at their defaults —
     * the same thing a migration would have given them.
     */
    @Test
    fun `a backup missing newer fields restores with defaults`() {
        val old = """
            {
              "formatVersion": 1,
              "schemaVersion": 5,
              "createdAtEpochMs": 1700000000000,
              "territories": [
                {"id":"t1","name":"Old claim","ringJson":"[]","geometryJson":"[]",
                 "areaSqMeters":10.0,"perimeterMeters":20.0,
                 "claimedAtEpochMs":1600000000000,"colorHex":"#2E7D4F"}
              ],
              "walks": [{"id":"w1","ringJson":"[]","closedAtEpochMs":1600000000000}]
            }
        """.trimIndent()

        val data = decodeOk(old).data

        val t = data.territories.single()
        assertEquals("", t.city)
        assertEquals("", t.country)
        assertEquals("", t.snappedJson)
        assertNull(t.conqueredAtEpochMs)
        assertNull(t.carvedAtEpochMs)
        // An unstated status restores as PENDING, never as already-uploaded.
        assertEquals(SyncStatus.PENDING, t.syncStatus)
        // A walk with no recorded start time keeps having none — 0 would draw it
        // as having begun in 1970.
        assertNull(data.walks.single().startedAtEpochMs)
        assertNull(data.walks.single().movingMs)
        assertEquals(SettingsSnapshot(), data.settings)
    }

    @Test
    fun `a backup from a newer database restores with a note`() {
        val text = Backup.encode(full().copy(schemaVersion = schema + 3))

        val ok = decodeOk(text)

        assertNotNull("a newer schema has to be said out loud", ok.note)
        assertTrue(ok.note!!.contains("newer"))
        assertEquals(2, ok.data.territories.size)
    }

    /** Half-restoring a shape nobody has seen is worse than not restoring it. */
    @Test
    fun `a newer format version is refused`() {
        val text = Backup.encode(full()).replace(
            "\"formatVersion\":${Backup.FORMAT_VERSION}",
            "\"formatVersion\":${Backup.FORMAT_VERSION + 1}",
        )

        val decoded = Backup.decode(text, currentSchemaVersion = schema)

        assertTrue(decoded is Backup.Decoded.Failed)
        assertTrue((decoded as Backup.Decoded.Failed).reason.contains("newer version"))
    }

    @Test
    fun `files that are not backups are refused, not half read`() {
        val notBackups = listOf(
            "" to "empty",
            "not json at all" to "prose",
            """{"tracks":[]}""" to "some other app's JSON",
            "[1,2,3]" to "a JSON array",
            """<?xml version="1.0"?><gpx/>""" to "a GPX file",
        )

        notBackups.forEach { (text, what) ->
            val decoded = Backup.decode(text, currentSchemaVersion = schema)
            assertTrue("$what should be refused", decoded is Backup.Decoded.Failed)
        }
    }

    @Test
    fun `the walk in progress keeps its points in walked order`() {
        val original = full()

        val restored = decodeOk(Backup.encode(original)).data

        assertEquals(original.walkProgressPoints, restored.walkProgressPoints)
        assertEquals(3, restored.walkProgressPoints.size)
        assertEquals(37.9838, restored.walkProgressPoints.first().lat, 1e-9)
        assertEquals(37.9842, restored.walkProgressPoints.last().lat, 1e-9)
    }

    /** A truncated or malformed point is dropped; it can't shift the rest along. */
    @Test
    fun `a malformed progress point is skipped rather than shifting the path`() {
        val text = """
            {"formatVersion":1,"schemaVersion":12,
             "walkInProgress":{"startedAtEpochMs":1,"activityType":"WALK",
               "points":[[1.0,2.0],[3.0],"nonsense",[5.0,6.0]]}}
        """.trimIndent()

        val data = decodeOk(text).data

        assertEquals(listOf(LatLng(1.0, 2.0), LatLng(5.0, 6.0)), data.walkProgressPoints)
    }

    /**
     * The one setting that sends anything off the device. It has to survive in
     * both directions — a restore must neither turn it on for somebody who had
     * it off, nor turn it off for somebody relying on it.
     */
    @Test
    fun `snapToPaths is carried faithfully both ways`() {
        val on = full().copy(settings = SettingsSnapshot(snapToPaths = true))
        val off = full().copy(settings = SettingsSnapshot(snapToPaths = false))

        assertTrue(decodeOk(Backup.encode(on)).data.settings.snapToPaths)
        assertTrue(!decodeOk(Backup.encode(off)).data.settings.snapToPaths)
    }

    @Test
    fun `the suggested filename is dated and sorts`() {
        val name = Backup.fileName(1_700_000_000_000)

        assertTrue(name.startsWith("enclose-backup-"))
        assertTrue(name.endsWith(".json"))
        assertTrue(Backup.fileName(1_700_000_000_000) < Backup.fileName(1_800_000_000_000))
    }

    @Test
    fun `a corrupt file is refused with something a person can act on`() {
        val truncated = Backup.encode(full()).take(200)

        val decoded = Backup.decode(truncated, currentSchemaVersion = schema)

        assertTrue(decoded is Backup.Decoded.Failed)
        assertTrue((decoded as Backup.Decoded.Failed).reason.isNotBlank())
    }
}
