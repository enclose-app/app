package io.app.enclose.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Single access point for the local profile. Fully offline: on first access the
 * one-and-only row is auto-created with a randomly assigned guest name. Sign-in
 * is future work — until then [Profile.isGuest] stays true.
 */
class ProfileRepository(private val dao: ProfileDao) {

    /**
     * The profile, always present. Ensures the singleton row exists before
     * emitting, then observes it so name changes flow through reactively.
     */
    val profile: Flow<Profile> =
        dao.observe()
            .onStart { ensureExists() }
            .filterNotNull()
            .map { it.toDomain() }

    /** Create the guest profile if it doesn't exist yet. Returns the current row. */
    private suspend fun ensureExists(): ProfileEntity {
        dao.get()?.let { return it }
        val (first, last) = ProfileNameGenerator.random()
        val entity = ProfileEntity(
            firstName = first,
            lastName = last,
            createdAtEpochMs = System.currentTimeMillis(),
            isGuest = true,
        )
        dao.upsert(entity)
        return entity
    }

    /** Update the user's name (keeps createdAt and guest status). */
    suspend fun updateName(first: String, last: String) {
        ensureExists()
        dao.updateName(first.trim(), last.trim())
    }

    /** Roll a fresh random guest name, preserving the created-at timestamp. */
    suspend fun regenerate() {
        val current = ensureExists()
        val (first, last) = ProfileNameGenerator.random()
        dao.upsert(current.copy(firstName = first, lastName = last))
    }
}
