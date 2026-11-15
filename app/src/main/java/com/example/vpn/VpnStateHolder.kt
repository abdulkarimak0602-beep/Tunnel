package com.example.vpn

import com.example.model.ConnectionState
import com.example.model.GatewayProfile
import com.example.model.TunnelTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VpnStateHolder {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activeGateway = MutableStateFlow<GatewayProfile?>(null)
    val activeGateway: StateFlow<GatewayProfile?> = _activeGateway.asStateFlow()

    private val _telemetry = MutableStateFlow(TunnelTelemetry())
    val telemetry: StateFlow<TunnelTelemetry> = _telemetry.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun setState(state: ConnectionState, gateway: GatewayProfile? = _activeGateway.value, error: String? = null) {
        _connectionState.value = state
        _activeGateway.value = gateway
        _errorMessage.value = error
        if (state == ConnectionState.DISCONNECTED) {
            _telemetry.value = TunnelTelemetry()
        }
    }

    fun updateTelemetry(transform: (TunnelTelemetry) -> TunnelTelemetry) {
        _telemetry.update(transform)
    }

    fun addLog(message: String) {
        val stamped = "[${timeFormat.format(Date())}] $message"
        _logs.update { current -> (listOf(stamped) + current).take(40) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
