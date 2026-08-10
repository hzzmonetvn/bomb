package com.hzzmonet.zkbomb.data

/**
 * The UI state of a single v6 write (a firewall rule, a visibility policy, a
 * settings profile action).
 *
 * The v6 contract is write-only — the service exposes no getter for these
 * policies — so the app can never claim to show the *current* enforced state. It
 * shows exactly this: the policy the user has composed and, once applied, the
 * service's own [BombOperationResult]. [Done] carries that result verbatim,
 * success or not, so the screen reports what actually happened rather than
 * assuming the write took.
 */
sealed interface V6ApplyState {
    /** Composed but not yet sent to the service. */
    data object Idle : V6ApplyState

    /** Sent; awaiting the service's reply on a background thread. */
    data object Applying : V6ApplyState

    /** The service replied. [result] is surfaced as-is, including failures. */
    data class Done(val result: BombOperationResult) : V6ApplyState
}
