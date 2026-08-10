package com.hzzmonet.zkbomb.domain.visibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe, lock-free cache for the active [VisibilityPolicySnapshot].
 *
 * Master plan §12 — "Package Manager is a hot path. Never perform Room/disk/
 * network/remote Binder I/O for each visibility check. Compile configuration
 * into an immutable in-memory snapshot."
 *
 * The cache holds a single [AtomicReference] to the current snapshot. Reads are
 * wait-free. Writes use compare-and-swap so a concurrent reload does not corrupt
 * the reference.
 *
 * The [snapshotFlow] allows reactive subscribers (e.g. the service layer) to
 * observe each reload without polling.
 */
class VisibilityPolicyCache {

    private val _snapshot = AtomicReference(VisibilityPolicySnapshot.EMPTY)
    private val _snapshotFlow = MutableStateFlow<VisibilityPolicySnapshot>(VisibilityPolicySnapshot.EMPTY)

    /** The current active snapshot. Always non-null; [VisibilityPolicySnapshot.EMPTY] before first load. */
    val current: VisibilityPolicySnapshot get() = _snapshot.get()

    /**
     * Reactive stream of snapshot updates.
     * Emits the new snapshot immediately after each [swap].
     */
    val snapshotFlow: StateFlow<VisibilityPolicySnapshot> = _snapshotFlow.asStateFlow()

    /**
     * Atomically replace the active snapshot with [new].
     *
     * Only replaces if [new] has a higher [VisibilityPolicySnapshot.revision]
     * than what is currently held, preventing a stale reload from rolling back
     * a newer one that arrived concurrently.
     *
     * @return true if the swap was applied, false if it was rejected as stale.
     */
    fun swap(new: VisibilityPolicySnapshot): Boolean {
        while (true) {
            val old = _snapshot.get()
            if (new.revision <= old.revision) return false
            if (_snapshot.compareAndSet(old, new)) {
                _snapshotFlow.value = new
                return true
            }
            // Lost the CAS race — another thread updated concurrently; retry.
        }
    }

    /**
     * Convenience: delegates to [VisibilityPolicySnapshot.shouldHidePackage]
     * on the current snapshot with no allocation.
     */
    fun shouldHidePackage(callingUid: Int, userId: Int, targetPackage: String): Boolean =
        current.shouldHidePackage(callingUid, userId, targetPackage)
}
