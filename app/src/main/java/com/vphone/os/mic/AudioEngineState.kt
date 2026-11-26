package com.vphone.os.mic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AudioEngineState {
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel = _inputLevel.asStateFlow()

    private val _connectedVirtualApps = MutableStateFlow(0)
    val connectedVirtualApps = _connectedVirtualApps.asStateFlow()

    // Routing toggles
    @Volatile var routeToVirtualPhone: Boolean = true
    @Volatile var localHeadphoneMonitor: Boolean = false
    @Volatile var externalUdpInputEnabled: Boolean = false

    // Core voice enhancement controls (0f to 1f) - from ClearVoice Live v1.1
    @Volatile var noiseGateAmount: Float = 0.35f
    @Volatile var voiceClarity: Float = 0.55f
    @Volatile var soothingWarmth: Float = 0.50f
    @Volatile var micGainBoost: Float = 0.40f

    // Male-to-Female voice changer controls - from ClearVoice Live v1.1
    @Volatile var maleToFemaleEnabled: Boolean = false
    @Volatile var pitchSemitones: Float = 5.5f
    @Volatile var feminineTone: Float = 0.65f

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun updateLevel(level: Float) {
        _inputLevel.value = level.coerceIn(0f, 1f)
    }

    fun updateConnectedVirtualApps(count: Int) {
        _connectedVirtualApps.value = count.coerceAtLeast(0)
    }
}
