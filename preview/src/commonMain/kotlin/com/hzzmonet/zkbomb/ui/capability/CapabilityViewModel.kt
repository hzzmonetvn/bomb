package com.hzzmonet.zkbomb.ui.capability

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.hzzmonet.zkbomb.data.BombServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CapabilityViewModel manages reactive StateFlow streams for Bomb capabilities.
 *
 * Provides lifecycle-aware and reactive observation of capability states as reported by IBombService.
 * Unsupported states are explicitly captured (UNSUPPORTED, REQUIRES_ROOT, DECLARED_NOT_IMPLEMENTED, NOT_PROBED)
 * ensuring that UI components render truthful unsupported cards without inventing mock data.
 */
class CapabilityViewModel(
    initialState: BombServiceState = BombServiceState.NOT_CONNECTED,
) {
    private val _serviceState = MutableStateFlow(initialState)
    val serviceState: StateFlow<BombServiceState> = _serviceState.asStateFlow()

    fun updateState(newState: BombServiceState) {
        if (_serviceState.value != newState) {
            _serviceState.value = newState
        }
    }

    /**
     * Checks if a specific capability key is explicitly probed as SUPPORTED.
     */
    fun isSupported(capabilityKey: String): Boolean =
        _serviceState.value.isSupported(capabilityKey)

    /**
     * Obtains the explicit capability status string for a given key.
     */
    fun capabilityState(capabilityKey: String): String =
        _serviceState.value.stateOf(capabilityKey)
}

@Composable
fun rememberCapabilityViewModel(serviceState: BombServiceState): CapabilityViewModel {
    val viewModel = remember { CapabilityViewModel(serviceState) }
    viewModel.updateState(serviceState)
    return viewModel
}
