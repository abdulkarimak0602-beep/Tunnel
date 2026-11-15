package com.example.viewmodel

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BUILT_IN_SHIELD_ID
import com.example.data.NetworkInspectorRepository
import com.example.data.PreferencesRepository
import com.example.data.UserPreferences
import com.example.model.AppLanguage
import com.example.model.BdPortalItem
import com.example.model.ConnectionState
import com.example.model.DnsProvider
import com.example.model.DotProbeResult
import com.example.model.GatewayProfile
import com.example.model.InstalledAppInfo
import com.example.model.PublicIpInfo
import com.example.model.SplitTunnelMode
import com.example.model.ThemeMode
import com.example.model.TunnelProtocol
import com.example.model.TunnelTelemetry
import com.example.vpn.DeshVpnService
import com.example.vpn.DnsOverTlsResolver
import com.example.vpn.VpnStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

data class DeshTunnelUiState(
    val preferences: UserPreferences = UserPreferences(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val telemetry: TunnelTelemetry = TunnelTelemetry(),
    val vpnErrorMessage: String? = null,
    val tunnelLogs: List<String> = emptyList(),
    val builtInShield: GatewayProfile = PreferencesRepository.builtInDnsShieldGateway(AppLanguage.BANGLA),
    val customGateways: List<GatewayProfile> = emptyList(),
    val communityRelays: List<GatewayProfile> = emptyList(),
    val activeSelectedGateway: GatewayProfile = PreferencesRepository.builtInDnsShieldGateway(AppLanguage.BANGLA),
    val publicIpInfo: PublicIpInfo? = null,
    val isCheckingIp: Boolean = false,
    val ipError: String? = null,
    val isFetchingRelays: Boolean = false,
    val relaysFetchError: String? = null,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val bdPortals: List<BdPortalItem> = emptyList(),
    val isVerifyingDoT: Boolean = false,
    val dotProbeResult: DotProbeResult? = null,
    val isBkashInstalled: Boolean = false,
    val isAutoFindingBdRelay: Boolean = false,
    val autoFindMessage: String? = null,
    val verifyingExitGatewayId: String? = null,
    val verifiedExitByGatewayId: Map<String, PublicIpInfo?> = emptyMap()
)

private data class LocalTransientState(
    val publicIpInfo: PublicIpInfo? = null,
    val isCheckingIp: Boolean = false,
    val ipError: String? = null,
    val communityRelays: List<GatewayProfile> = emptyList(),
    val customLatencies: Map<String, Long?> = emptyMap(),
    val checkingIds: Set<String> = emptySet(),
    val isFetchingRelays: Boolean = false,
    val relaysFetchError: String? = null,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val bdPortals: List<BdPortalItem> = emptyList(),
    val isVerifyingDoT: Boolean = false,
    val dotProbeResult: DotProbeResult? = null,
    val isAutoFindingBdRelay: Boolean = false,
    val autoFindMessage: String? = null,
    val verifyingExitGatewayId: String? = null,
    val verifiedExitByGatewayId: Map<String, PublicIpInfo?> = emptyMap()
)

class DeshTunnelViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = PreferencesRepository(application.applicationContext)
    private val networkRepo = NetworkInspectorRepository()

    private val transientState = MutableStateFlow(
        LocalTransientState(bdPortals = networkRepo.defaultBdPortals())
    )

    val uiState: StateFlow<DeshTunnelUiState> = combine(
        prefsRepo.preferencesFlow,
        VpnStateHolder.connectionState,
        VpnStateHolder.telemetry,
        VpnStateHolder.errorMessage,
        VpnStateHolder.logs,
        transientState
    ) { values ->
        val prefs = values[0] as UserPreferences
        val connState = values[1] as ConnectionState
        val telemetry = values[2] as TunnelTelemetry
        val vpnErr = values[3] as String?
        @Suppress("UNCHECKED_CAST")
        val logs = values[4] as List<String>
        val local = values[5] as LocalTransientState

        val shield = PreferencesRepository.builtInDnsShieldGateway(prefs.language).let { base ->
            base.copy(
                latencyMs = if (local.customLatencies.containsKey(base.id)) local.customLatencies[base.id] else null,
                isCheckingLatency = local.checkingIds.contains(base.id)
            )
        }

        val customWithLatency = prefs.customGateways.map { gw ->
            gw.copy(
                latencyMs = if (local.customLatencies.containsKey(gw.id)) local.customLatencies[gw.id] else gw.latencyMs,
                isCheckingLatency = local.checkingIds.contains(gw.id)
            )
        }

        val relaysWithStatus = local.communityRelays.map { gw ->
            gw.copy(isCheckingLatency = local.checkingIds.contains(gw.id))
        }

        val allGateways = listOf(shield) + customWithLatency + relaysWithStatus
        val selected = allGateways.firstOrNull { it.id == prefs.selectedGatewayId } ?: shield

        DeshTunnelUiState(
            preferences = prefs,
            connectionState = connState,
            telemetry = telemetry,
            vpnErrorMessage = vpnErr,
            tunnelLogs = logs,
            builtInShield = shield,
            customGateways = customWithLatency,
            communityRelays = relaysWithStatus,
            activeSelectedGateway = selected,
            publicIpInfo = local.publicIpInfo,
            isCheckingIp = local.isCheckingIp,
            ipError = local.ipError,
            isFetchingRelays = local.isFetchingRelays,
            relaysFetchError = local.relaysFetchError,
            installedApps = local.installedApps,
            isLoadingApps = local.isLoadingApps,
            bdPortals = local.bdPortals,
            isVerifyingDoT = local.isVerifyingDoT,
            dotProbeResult = local.dotProbeResult,
            isBkashInstalled = local.installedApps.any { it.packageName == "com.bKash.customerapp" },
            isAutoFindingBdRelay = local.isAutoFindingBdRelay,
            autoFindMessage = local.autoFindMessage,
            verifyingExitGatewayId = local.verifyingExitGatewayId,
            verifiedExitByGatewayId = local.verifiedExitByGatewayId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = DeshTunnelUiState(bdPortals = networkRepo.defaultBdPortals())
    )

    init {
        refreshPublicIp()
        loadInstalledApps()
    }

    fun refreshPublicIp() {
        viewModelScope.launch {
            transientState.update { it.copy(isCheckingIp = true, ipError = null) }
            networkRepo.fetchPublicIp()
                .onSuccess { info ->
                    transientState.update {
                        it.copy(publicIpInfo = info, isCheckingIp = false, ipError = null)
                    }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(isCheckingIp = false, ipError = err.localizedMessage ?: "Network error")
                    }
                }
        }
    }

    fun startVpnConnection() {
        val state = uiState.value
        val ctx = getApplication<Application>().applicationContext
        DeshVpnService.startConnect(
            context = ctx,
            gateway = state.activeSelectedGateway,
            dnsProvider = state.preferences.dnsProvider,
            secureDnsEnabled = state.preferences.secureDnsEnabled,
            killSwitch = state.preferences.killSwitchEnabled,
            splitMode = state.preferences.splitTunnelMode,
            allowedPackages = state.preferences.allowedPackages
        )
        // Re-check public IP shortly after tunnel stabilizes
        viewModelScope.launch {
            kotlinx.coroutines.delay(2500L)
            if (VpnStateHolder.connectionState.value == ConnectionState.CONNECTED) {
                refreshPublicIp()
            }
        }
    }

    fun stopVpnConnection() {
        val ctx = getApplication<Application>().applicationContext
        DeshVpnService.stopTunnel(ctx)
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000L)
            refreshPublicIp()
        }
    }

    fun needsVpnConsent(): android.content.Intent? {
        return VpnService.prepare(getApplication<Application>().applicationContext)
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefsRepo.setThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { prefsRepo.setLanguage(language) }
    }

    fun selectGateway(gateway: GatewayProfile) {
        viewModelScope.launch {
            // If selecting a community relay, persist it in custom list or keep in transient?
            // Persist selected community relay cleanly if not already saved so service survives restarts
            if (gateway.isCommunityRelay && uiState.value.customGateways.none { it.id == gateway.id }) {
                prefsRepo.addOrUpdateGateway(gateway)
            } else {
                prefsRepo.setSelectedGatewayId(gateway.id)
            }
        }
    }

    fun saveCustomGateway(
        name: String,
        city: String,
        host: String,
        port: Int,
        protocol: TunnelProtocol,
        username: String,
        password: String
    ) {
        viewModelScope.launch {
            val gw = GatewayProfile(
                id = "gw_${UUID.randomUUID().toString().take(8)}",
                name = name.trim().ifBlank { "BD Gateway ($host)" },
                city = city.trim().ifBlank { "Dhaka" },
                host = host.trim(),
                port = port,
                protocol = protocol,
                username = username.trim(),
                password = password,
                isCommunityRelay = false,
                isBuiltInShield = false
            )
            prefsRepo.addOrUpdateGateway(gw)
            testGatewayLatency(gw)
        }
    }

    fun deleteGateway(id: String) {
        if (id == BUILT_IN_SHIELD_ID) return
        viewModelScope.launch { prefsRepo.deleteGateway(id) }
    }

    fun testGatewayLatency(gateway: GatewayProfile) {
        viewModelScope.launch {
            transientState.update { it.copy(checkingIds = it.checkingIds + gateway.id) }
            val ms = networkRepo.measureGatewayLatency(gateway)
            transientState.update { state ->
                val updatedRelays = state.communityRelays.map {
                    if (it.id == gateway.id) it.copy(latencyMs = ms, lastCheckedAt = System.currentTimeMillis()) else it
                }
                state.copy(
                    customLatencies = state.customLatencies + (gateway.id to ms),
                    communityRelays = updatedRelays,
                    checkingIds = state.checkingIds - gateway.id
                )
            }
        }
    }

    fun pingAllMyGateways() {
        val all = listOf(uiState.value.builtInShield) + uiState.value.customGateways
        viewModelScope.launch {
            transientState.update { it.copy(checkingIds = it.checkingIds + all.map { g -> g.id }) }
            val results = all.map { gw ->
                async { gw.id to networkRepo.measureGatewayLatency(gw) }
            }.awaitAll().toMap()
            transientState.update { state ->
                state.copy(
                    customLatencies = state.customLatencies + results,
                    checkingIds = state.checkingIds - all.map { it.id }.toSet()
                )
            }
        }
    }

    fun fetchCommunityBdRelays() {
        viewModelScope.launch {
            transientState.update { it.copy(isFetchingRelays = true, relaysFetchError = null) }
            networkRepo.fetchCommunityBdProxies()
                .onSuccess { list ->
                    transientState.update {
                        it.copy(
                            communityRelays = list,
                            isFetchingRelays = false,
                            relaysFetchError = null
                        )
                    }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(
                            isFetchingRelays = false,
                            relaysFetchError = err.localizedMessage ?: "Failed to fetch BD relays"
                        )
                    }
                }
        }
    }

    fun autoFindAndSelectBdRelay(noRelayErrorMessage: String, onGatewayReady: () -> Unit) {
        viewModelScope.launch {
            transientState.update { it.copy(isAutoFindingBdRelay = true, autoFindMessage = null) }
            networkRepo.fetchCommunityBdProxies()
                .onSuccess { list ->
                    transientState.update { it.copy(communityRelays = list) }
                    val best = list.firstOrNull { it.latencyMs != null }
                    if (best != null) {
                        prefsRepo.addOrUpdateGateway(best)
                        transientState.update { it.copy(isAutoFindingBdRelay = false, autoFindMessage = null) }
                        onGatewayReady()
                    } else {
                        transientState.update {
                            it.copy(isAutoFindingBdRelay = false, autoFindMessage = noRelayErrorMessage)
                        }
                    }
                }
                .onFailure {
                    transientState.update {
                        it.copy(isAutoFindingBdRelay = false, autoFindMessage = noRelayErrorMessage)
                    }
                }
        }
    }

    fun verifyGatewayExitIp(gateway: GatewayProfile) {
        viewModelScope.launch {
            transientState.update { it.copy(verifyingExitGatewayId = gateway.id) }
            val result = networkRepo.verifyGatewayExitIp(gateway)
            transientState.update { state ->
                state.copy(
                    verifyingExitGatewayId = null,
                    verifiedExitByGatewayId = state.verifiedExitByGatewayId + (gateway.id to result.getOrNull())
                )
            }
        }
    }

    fun enableBkashSplitTunnel() {
        viewModelScope.launch {
            val current = uiState.value.preferences.allowedPackages.toMutableSet()
            current.add("com.bKash.customerapp")
            // Also include common browsers if installed so Amber IT web portal routes through the tunnel too
            val browserCandidates = listOf("com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser")
            val installedPkgs = uiState.value.installedApps.map { it.packageName }.toSet()
            browserCandidates.firstOrNull { installedPkgs.contains(it) }?.let { current.add(it) }
            prefsRepo.setSplitTunnelMode(SplitTunnelMode.SELECTED_ONLY)
            prefsRepo.setAllowedPackages(current)
        }
    }

    fun setSecureDnsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setSecureDnsEnabled(enabled)
            if (VpnStateHolder.connectionState.value == ConnectionState.CONNECTED) {
                val state = uiState.value
                DeshVpnService.startConnect(
                    context = getApplication<Application>().applicationContext,
                    gateway = state.activeSelectedGateway,
                    dnsProvider = state.preferences.dnsProvider,
                    secureDnsEnabled = enabled,
                    killSwitch = state.preferences.killSwitchEnabled,
                    splitMode = state.preferences.splitTunnelMode,
                    allowedPackages = state.preferences.allowedPackages
                )
            }
        }
    }

    fun setDnsProvider(provider: DnsProvider) {
        viewModelScope.launch {
            prefsRepo.setDnsProvider(provider)
            transientState.update { it.copy(dotProbeResult = null) }
            if (VpnStateHolder.connectionState.value == ConnectionState.CONNECTED) {
                val state = uiState.value
                DeshVpnService.startConnect(
                    context = getApplication<Application>().applicationContext,
                    gateway = state.activeSelectedGateway,
                    dnsProvider = provider,
                    secureDnsEnabled = state.preferences.secureDnsEnabled,
                    killSwitch = state.preferences.killSwitchEnabled,
                    splitMode = state.preferences.splitTunnelMode,
                    allowedPackages = state.preferences.allowedPackages
                )
            }
        }
    }

    fun verifySecureDns() {
        val provider = uiState.value.preferences.dnsProvider
        viewModelScope.launch(Dispatchers.IO) {
            transientState.update { it.copy(isVerifyingDoT = true, dotProbeResult = null) }
            val result = DnsOverTlsResolver.verifyDoTProvider(provider)
            transientState.update { it.copy(isVerifyingDoT = false, dotProbeResult = result) }
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setKillSwitch(enabled) }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        viewModelScope.launch { prefsRepo.setSplitTunnelMode(mode) }
    }

    fun toggleAllowedApp(packageName: String) {
        val current = uiState.value.preferences.allowedPackages.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        viewModelScope.launch { prefsRepo.setAllowedPackages(current) }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            transientState.update { it.copy(isLoadingApps = true) }
            val apps = networkRepo.loadInstalledApps(getApplication())
            transientState.update { it.copy(installedApps = apps, isLoadingApps = false) }
        }
    }

    fun checkBdPortal(portalId: String) {
        val target = transientState.value.bdPortals.firstOrNull { it.id == portalId } ?: return
        viewModelScope.launch {
            transientState.update { state ->
                state.copy(
                    bdPortals = state.bdPortals.map {
                        if (it.id == portalId) it.copy(isChecking = true) else it
                    }
                )
            }
            val updated = networkRepo.checkBdPortal(target)
            transientState.update { state ->
                state.copy(
                    bdPortals = state.bdPortals.map {
                        if (it.id == portalId) updated else it
                    }
                )
            }
        }
    }

    fun checkAllBdPortals() {
        val current = transientState.value.bdPortals
        viewModelScope.launch {
            transientState.update { state ->
                state.copy(bdPortals = state.bdPortals.map { it.copy(isChecking = true) })
            }
            val checked = current.map { portal ->
                async { networkRepo.checkBdPortal(portal) }
            }.awaitAll()
            transientState.update { it.copy(bdPortals = checked) }
        }
    }

    fun parseProxyUri(raw: String): ParsedProxyUri? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            val normalized = if ("://" in trimmed) trimmed else "socks5://$trimmed"
            val uri = URI(normalized)
            val host = uri.host ?: return null
            val scheme = uri.scheme?.lowercase().orEmpty()
            val protocol = when {
                scheme.contains("https") -> TunnelProtocol.HTTPS_TLS
                scheme.contains("tls") -> TunnelProtocol.SOCKS5_TLS
                else -> TunnelProtocol.SOCKS5
            }
            val port = if (uri.port > 0) uri.port else when (protocol) {
                TunnelProtocol.HTTPS_TLS, TunnelProtocol.SOCKS5_TLS -> 443
                else -> 1080
            }
            val userInfo = uri.userInfo.orEmpty()
            val user = userInfo.substringBefore(":", "")
            val pass = if (":" in userInfo) userInfo.substringAfter(":") else ""
            ParsedProxyUri(host, port, protocol, user, pass)
        }.getOrNull()
    }
}

data class ParsedProxyUri(
    val host: String,
    val port: Int,
    val protocol: TunnelProtocol,
    val username: String,
    val password: String
)
