package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppLanguage
import com.example.model.DnsProvider
import com.example.model.GatewayProfile
import com.example.model.SplitTunnelMode
import com.example.model.ThemeMode
import com.example.model.TunnelMode
import com.example.model.TunnelProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.deshDataStore: DataStore<Preferences> by preferencesDataStore(name = "deshtunnel_prefs")

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.BANGLA,
    val selectedGatewayId: String = BUILT_IN_SHIELD_ID,
    val customGateways: List<GatewayProfile> = emptyList(),
    val secureDnsEnabled: Boolean = true,
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val killSwitchEnabled: Boolean = false,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val allowedPackages: Set<String> = emptySet(),
    val tunnelMode: TunnelMode = TunnelMode.VPN_TUNNEL
)

const val BUILT_IN_SHIELD_ID = "built_in_dns_shield"

class PreferencesRepository(private val context: Context) {

    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_SELECTED_GATEWAY_ID = stringPreferencesKey("selected_gateway_id")
        private val KEY_CUSTOM_GATEWAYS_JSON = stringPreferencesKey("custom_gateways_json")
        private val KEY_SECURE_DNS_ENABLED = booleanPreferencesKey("secure_dns_enabled")
        private val KEY_DNS_PROVIDER = stringPreferencesKey("dns_provider")
        private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch")
        private val KEY_SPLIT_MODE = stringPreferencesKey("split_mode")
        private val KEY_ALLOWED_PACKAGES = stringSetPreferencesKey("allowed_packages")
        private val KEY_TUNNEL_MODE = stringPreferencesKey("tunnel_mode")

        fun builtInDnsShieldGateway(language: AppLanguage): GatewayProfile = GatewayProfile(
            id = BUILT_IN_SHIELD_ID,
            name = if (language == AppLanguage.BANGLA) "এনক্রিপ্টেড DNS শিল্ড" else "Encrypted DNS Shield",
            city = if (language == AppLanguage.BANGLA) "লোকাল TLS 1.3" else "Local TLS 1.3",
            host = "1.1.1.1",
            port = 853,
            protocol = TunnelProtocol.DNS_SHIELD,
            isBuiltInShield = true
        )
    }

    val preferencesFlow: Flow<UserPreferences> = context.deshDataStore.data.map { prefs ->
        val themeMode = prefs[KEY_THEME_MODE]?.let {
            runCatching { ThemeMode.valueOf(it) }.getOrNull()
        } ?: ThemeMode.SYSTEM

        val language = prefs[KEY_LANGUAGE]?.let {
            runCatching { AppLanguage.valueOf(it) }.getOrNull()
        } ?: AppLanguage.BANGLA

        val dnsProvider = prefs[KEY_DNS_PROVIDER]?.let {
            runCatching { DnsProvider.valueOf(it) }.getOrNull()
        } ?: DnsProvider.CLOUDFLARE

        val splitMode = prefs[KEY_SPLIT_MODE]?.let {
            runCatching { SplitTunnelMode.valueOf(it) }.getOrNull()
        } ?: SplitTunnelMode.ALL_APPS

        val customGateways = parseGatewaysJson(prefs[KEY_CUSTOM_GATEWAYS_JSON].orEmpty())

        val tunnelMode = prefs[KEY_TUNNEL_MODE]?.let {
            runCatching { TunnelMode.valueOf(it) }.getOrNull()
        } ?: TunnelMode.VPN_TUNNEL

        UserPreferences(
            themeMode = themeMode,
            language = language,
            selectedGatewayId = prefs[KEY_SELECTED_GATEWAY_ID] ?: BUILT_IN_SHIELD_ID,
            customGateways = customGateways,
            secureDnsEnabled = prefs[KEY_SECURE_DNS_ENABLED] ?: true,
            dnsProvider = dnsProvider,
            killSwitchEnabled = prefs[KEY_KILL_SWITCH] ?: false,
            splitTunnelMode = splitMode,
            allowedPackages = prefs[KEY_ALLOWED_PACKAGES] ?: emptySet(),
            tunnelMode = tunnelMode
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.deshDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.deshDataStore.edit { it[KEY_LANGUAGE] = language.name }
    }

    suspend fun setSelectedGatewayId(id: String) {
        context.deshDataStore.edit { it[KEY_SELECTED_GATEWAY_ID] = id }
    }

    suspend fun setDnsProvider(provider: DnsProvider) {
        context.deshDataStore.edit { it[KEY_DNS_PROVIDER] = provider.name }
    }

    suspend fun setSecureDnsEnabled(enabled: Boolean) {
        context.deshDataStore.edit { it[KEY_SECURE_DNS_ENABLED] = enabled }
    }

    suspend fun setKillSwitch(enabled: Boolean) {
        context.deshDataStore.edit { it[KEY_KILL_SWITCH] = enabled }
    }

    suspend fun setSplitTunnelMode(mode: SplitTunnelMode) {
        context.deshDataStore.edit { it[KEY_SPLIT_MODE] = mode.name }
    }

    suspend fun setAllowedPackages(packages: Set<String>) {
        context.deshDataStore.edit { it[KEY_ALLOWED_PACKAGES] = packages }
    }

    suspend fun setTunnelMode(mode: TunnelMode) {
        context.deshDataStore.edit { it[KEY_TUNNEL_MODE] = mode.name }
    }

    suspend fun addOrUpdateGateway(gateway: GatewayProfile) {
        context.deshDataStore.edit { prefs ->
            val current = parseGatewaysJson(prefs[KEY_CUSTOM_GATEWAYS_JSON].orEmpty()).toMutableList()
            val existingIdx = current.indexOfFirst { it.id == gateway.id }
            if (existingIdx >= 0) {
                current[existingIdx] = gateway
            } else {
                current.add(0, gateway)
            }
            prefs[KEY_CUSTOM_GATEWAYS_JSON] = serializeGatewaysJson(current)
            prefs[KEY_SELECTED_GATEWAY_ID] = gateway.id
        }
    }

    suspend fun deleteGateway(id: String) {
        context.deshDataStore.edit { prefs ->
            val current = parseGatewaysJson(prefs[KEY_CUSTOM_GATEWAYS_JSON].orEmpty())
                .filterNot { it.id == id }
            prefs[KEY_CUSTOM_GATEWAYS_JSON] = serializeGatewaysJson(current)
            if (prefs[KEY_SELECTED_GATEWAY_ID] == id) {
                prefs[KEY_SELECTED_GATEWAY_ID] = BUILT_IN_SHIELD_ID
            }
        }
    }

    private fun serializeGatewaysJson(list: List<GatewayProfile>): String {
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(
                JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("city", g.city)
                    put("host", g.host)
                    put("port", g.port)
                    put("protocol", g.protocol.name)
                    put("username", g.username)
                    put("password", g.password)
                    put("isCommunityRelay", g.isCommunityRelay)
                }
            )
        }
        return arr.toString()
    }

    private fun parseGatewaysJson(raw: String): List<GatewayProfile> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val proto = runCatching {
                        TunnelProtocol.valueOf(obj.optString("protocol", TunnelProtocol.SOCKS5_TLS.name))
                    }.getOrDefault(TunnelProtocol.SOCKS5_TLS)
                    add(
                        GatewayProfile(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            city = obj.optString("city", "Dhaka"),
                            host = obj.getString("host"),
                            port = obj.getInt("port"),
                            protocol = proto,
                            username = obj.optString("username", ""),
                            password = obj.optString("password", ""),
                            isCommunityRelay = obj.optBoolean("isCommunityRelay", false),
                            isBuiltInShield = false
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
