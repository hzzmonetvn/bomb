package com.hzzmonet.zkbomb.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The result of every privileged operation.
 *
 * Master plan §3.5 requires that privileged operations return an explicit
 * result and never silently swallow a failure. The distinctions matter to the
 * UI: *unsupported* means hide or disable the control permanently on this
 * device, *backend unavailable* means try again later, and *denied* means the
 * caller is wrong — three different screens, not one "it didn't work".
 *
 * Modelled as one concrete `Parcelable` with a status field rather than a sealed
 * hierarchy. A sealed class would read better in Kotlin, but each subclass would
 * have to be its own AIDL parcelable and the AIDL method signature would have to
 * name a supertype AIDL cannot express. One class with a bounded status crosses
 * Binder cleanly and is what the boundary can actually carry.
 */
@Parcelize
data class BombResult(
    val status: Status,
    /**
     * Human-readable detail for logs and error surfaces.
     *
     * Never carries secrets, credentials, recorded content, or user data — this
     * string is logged and shown. When in doubt the field stays null and the
     * status carries the meaning.
     */
    val detail: String? = null,
) : Parcelable {

    enum class Status {
        /** The operation completed and was verified against re-read state. */
        SUCCESS,

        /** This device cannot do this at all. The control should not be offered. */
        UNSUPPORTED,

        /** The caller is not permitted. A bug or an attack, never a normal path. */
        PERMISSION_DENIED,

        /** An argument failed validation before anything was attempted. */
        INVALID_ARGUMENT,

        /**
         * Supported, but the backend is not reachable right now — root manager
         * revoked, service not bound, device node temporarily absent.
         *
         * Distinct from [UNSUPPORTED] because it is transient: the UI keeps the
         * control and retries, rather than hiding it forever.
         */
        BACKEND_UNAVAILABLE,

        /** Attempted and failed. [detail] says what happened. */
        FAILED,
    }

    val isSuccess: Boolean get() = status == Status.SUCCESS

    companion object {
        fun success(): BombResult = BombResult(Status.SUCCESS)

        fun unsupported(detail: String? = null): BombResult =
            BombResult(Status.UNSUPPORTED, detail)

        fun permissionDenied(detail: String? = null): BombResult =
            BombResult(Status.PERMISSION_DENIED, detail)

        fun invalidArgument(detail: String? = null): BombResult =
            BombResult(Status.INVALID_ARGUMENT, detail)

        fun backendUnavailable(detail: String? = null): BombResult =
            BombResult(Status.BACKEND_UNAVAILABLE, detail)

        fun failed(detail: String? = null): BombResult =
            BombResult(Status.FAILED, detail)
    }
}
