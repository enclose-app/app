package io.app.enclose.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single local profile row. There is exactly one profile ("me"); it is
 * auto-created with a randomly assigned name on first access so the app is
 * usable fully offline. [isGuest] stays true until real sign-in lands (future
 * work), at which point the name would be replaced by the authenticated one.
 */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: String = SINGLETON_ID,
    val firstName: String,
    val lastName: String,
    val createdAtEpochMs: Long,
    val isGuest: Boolean = true,
) {
    fun toDomain(): Profile = Profile(
        id = id,
        firstName = firstName,
        lastName = lastName,
        createdAtEpochMs = createdAtEpochMs,
        isGuest = isGuest,
    )

    companion object {
        /** The fixed primary key of the one-and-only profile row. */
        const val SINGLETON_ID = "me"

        fun fromDomain(p: Profile): ProfileEntity = ProfileEntity(
            id = p.id,
            firstName = p.firstName,
            lastName = p.lastName,
            createdAtEpochMs = p.createdAtEpochMs,
            isGuest = p.isGuest,
        )
    }
}

/** Domain model for the local profile. */
data class Profile(
    val id: String = ProfileEntity.SINGLETON_ID,
    val firstName: String,
    val lastName: String,
    val createdAtEpochMs: Long,
    val isGuest: Boolean = true,
) {
    val displayName: String
        get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")

    val initials: String
        get() = buildString {
            firstName.firstOrNull()?.let { append(it.uppercaseChar()) }
            lastName.firstOrNull()?.let { append(it.uppercaseChar()) }
        }.ifEmpty { "?" }
}
