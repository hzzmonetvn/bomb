package com.hzzmonet.zkbomb.domain.model

/**
 * Result of any domain or privileged operation.
 *
 * Master plan §3.5 requires that privileged operations return an explicit
 * result and never silently swallow a failure.
 */
data class BombResult(
    val status: Status,
    val detail: String? = null,
) {
    enum class Status {
        /** The operation completed and was verified against re-read state. */
        SUCCESS,

        /** This device cannot do this at all. The control should not be offered. */
        UNSUPPORTED,

        /** The caller is not permitted. */
        PERMISSION_DENIED,

        /** An argument failed validation before anything was attempted. */
        INVALID_ARGUMENT,

        /** Supported, but the backend is not reachable right now. */
        BACKEND_UNAVAILABLE,

        /** Attempted and failed. [detail] says what happened. */
        FAILED,
    }

    val isSuccess: Boolean get() = status == Status.SUCCESS

    companion object {
        fun success(detail: String? = null): BombResult = BombResult(Status.SUCCESS, detail)

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
