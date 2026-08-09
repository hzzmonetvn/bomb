package com.hzzmonet.zkbomb.domain.recorder

/**
 * One stored recording, reduced to what retention needs to know about it.
 *
 * [createdAtEpochMillis] is wall-clock creation time; the id is whatever the
 * store uses to find the file again. No path, no audio: retention decides *which*
 * recordings expire, and the caller does the deleting.
 */
data class RecordingRef(
    val id: String,
    val createdAtEpochMillis: Long,
)

/**
 * Works out which recordings a retention policy has outlived, as a pure function
 * of the list, the policy and the current time.
 *
 * Kept separate from anything that deletes files so the boundary condition — a
 * recording exactly at the cutoff — can be pinned in a test rather than guessed
 * at against a real clock. The rule is deliberately "strictly older than": a
 * recording made exactly `days` ago is kept, not deleted, so a 7-day policy
 * always keeps a full 7 days rather than 7-minus-an-instant.
 */
object RetentionEnforcer {

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /**
     * The recordings that should be deleted now.
     *
     * [RetentionPolicy.FOREVER] returns an empty list — nothing ever expires.
     * A future-dated recording (clock skew, a restored backup) is never treated
     * as expired.
     */
    fun expired(
        recordings: List<RecordingRef>,
        policy: RetentionPolicy,
        nowEpochMillis: Long,
    ): List<RecordingRef> {
        val days = policy.days ?: return emptyList()
        val cutoff = nowEpochMillis - days * MILLIS_PER_DAY
        return recordings.filter { it.createdAtEpochMillis < cutoff }
    }
}
