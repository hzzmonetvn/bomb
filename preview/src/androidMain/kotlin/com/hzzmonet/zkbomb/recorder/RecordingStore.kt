package com.hzzmonet.zkbomb.recorder

import android.content.Context
import com.hzzmonet.zkbomb.domain.recorder.RecordingRef
import com.hzzmonet.zkbomb.domain.recorder.RetentionEnforcer
import com.hzzmonet.zkbomb.domain.recorder.RetentionPolicy
import java.io.File

/**
 * Owns the recording files and their metadata, and nothing about capture.
 *
 * Audio lives as `<id>.m4a` under an app-private directory; the per-recording
 * facts the UI needs — which app, when, how long, whether it came out silent —
 * live in a separate `SharedPreferences` so that listing does not have to parse
 * media headers. The audio file is the source of truth for *size*; the metadata
 * store is the source of truth for everything else, and a metadata entry with no
 * surviving file is skipped rather than shown as a zero-byte recording.
 *
 * Nothing here logs a file path into a shared log or copies audio anywhere — the
 * recordings stay in the private directory, per the project's rule that recorded
 * audio never leaves it or reaches a log.
 */
internal class RecordingStore(context: Context) {

    private val directory: File = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val metadata = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Allocates a new recording id and its (not yet created) audio file. */
    fun allocate(): Allocation {
        val id = "${System.currentTimeMillis()}-${(0..0xFFFF).random().toString(16)}"
        return Allocation(id, File(directory, "$id.$EXTENSION"))
    }

    /** Records the metadata for a finished capture. The audio file must already exist. */
    fun commit(
        id: String,
        packageName: String,
        startedAtEpochMillis: Long,
        durationMillis: Long,
        silent: Boolean,
    ) {
        metadata.edit()
            .putString(id, encode(packageName, startedAtEpochMillis, durationMillis, silent))
            .apply()
    }

    /** Every recording that still has both a metadata entry and a file, newest first. */
    fun list(): List<StoredRecording> =
        metadata.all.mapNotNull { (id, raw) ->
            val meta = (raw as? String)?.let(::decode) ?: return@mapNotNull null
            val file = File(directory, "$id.$EXTENSION")
            if (!file.exists()) return@mapNotNull null
            StoredRecording(
                id = id,
                file = file,
                packageName = meta.packageName,
                startedAtEpochMillis = meta.startedAt,
                durationMillis = meta.duration,
                sizeBytes = file.length(),
                silent = meta.silent,
            )
        }.sortedByDescending { it.startedAtEpochMillis }

    fun delete(id: String) {
        File(directory, "$id.$EXTENSION").delete()
        metadata.edit().remove(id).apply()
    }

    /** Deletes recordings the policy has outlived. Returns how many were removed. */
    fun enforceRetention(policy: RetentionPolicy, nowEpochMillis: Long): Int {
        val refs = list().map { RecordingRef(it.id, it.startedAtEpochMillis) }
        val expired = RetentionEnforcer.expired(refs, policy, nowEpochMillis)
        expired.forEach { delete(it.id) }
        return expired.size
    }

    fun totalBytes(): Long = list().sumOf { it.sizeBytes }

    private fun encode(pkg: String, startedAt: Long, duration: Long, silent: Boolean): String =
        // Package names cannot contain '|', so it is a safe separator here.
        "$pkg|$startedAt|$duration|${if (silent) 1 else 0}"

    private fun decode(raw: String): Meta? {
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val startedAt = parts[1].toLongOrNull() ?: return null
        val duration = parts[2].toLongOrNull() ?: return null
        return Meta(parts[0], startedAt, duration, parts[3] == "1")
    }

    data class Allocation(val id: String, val file: File)

    data class StoredRecording(
        val id: String,
        val file: File,
        val packageName: String,
        val startedAtEpochMillis: Long,
        val durationMillis: Long,
        val sizeBytes: Long,
        val silent: Boolean,
    )

    private data class Meta(
        val packageName: String,
        val startedAt: Long,
        val duration: Long,
        val silent: Boolean,
    )

    private companion object {
        const val DIR_NAME = "voip-recordings"
        const val PREFS_NAME = "voip_recordings_meta"
        const val EXTENSION = "m4a"
    }
}
