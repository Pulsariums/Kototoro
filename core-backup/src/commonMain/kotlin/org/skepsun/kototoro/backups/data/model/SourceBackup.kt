package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity

@Serializable
class SourceBackup(
    @SerialName("source") val source: String,
    @SerialName("sort_key") val sortKey: Int,
    @SerialName("used_at") val lastUsedAt: Long,
    @SerialName("added_in") val addedIn: Int,
    @SerialName("pinned") val isPinned: Boolean = false,
    @SerialName("enabled") val isEnabled: Boolean = true, // for compatibility purposes, should be only true
    /** Source lock: [LOCK_UNKNOWN] = not recorded (old backups), [LOCK_NONE] = unlocked, [LOCK_ON]/[LOCK_OFF] = locked at that state. */
    @SerialName("lock") val lock: Int = LOCK_UNKNOWN,
) {

    constructor(entity: MangaSourceEntity, lock: Int) : this(
        source = entity.source,
        sortKey = entity.sortKey,
        lastUsedAt = entity.lastUsedAt,
        addedIn = entity.addedIn,
        isPinned = entity.isPinned,
        isEnabled = entity.isEnabled,
        lock = lock,
    )

    constructor(entity: MangaSourceEntity) : this(entity, LOCK_UNKNOWN)

    fun toEntity() = MangaSourceEntity(
        source = source,
        isEnabled = isEnabled,
        sortKey = sortKey,
        addedIn = addedIn,
        lastUsedAt = lastUsedAt,
        isPinned = isPinned,
        cfState = 0,
    )

    companion object {
        const val LOCK_UNKNOWN = -1
        const val LOCK_OFF = 0
        const val LOCK_ON = 1
        const val LOCK_NONE = 2

        fun lockCode(locked: Boolean?): Int = when (locked) {
            null -> LOCK_NONE
            true -> LOCK_ON
            false -> LOCK_OFF
        }
    }
}
