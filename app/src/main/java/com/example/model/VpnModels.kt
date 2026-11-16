package com.example.model

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

enum class AppLanguage {
    BANGLA,
    ENGLISH
}

enum class TunnelProtocol(val displayName: String, val encryptionLabel: String) {
    SOCKS5_TLS("SOCKS5 + TLS 1.3", "TLS 1.3 • AES-256-GCM"),
    HTTPS_TLS("HTTPS CONNECT (TLS 1.3)", "TLS 1.3 • ChaCha20-Poly1305"),
    SOCKS5("SOCKS5 Tunnel", "SOCKS5 + Encrypted DoT"),
    DNS_SHIELD("Encrypted DNS Shield", "DNS-over-TLS 1.3 (853)")
}

enum class DnsProvider(
    val displayName: String,
    val ip: String,
    val tlsHost: String,
    val port: Int = 853
) {
    CLOUDFLARE("Cloudflare DoT (1.1.1.1)", "1.1.1.1", "cloudflare-dns.com", 853),
    QUAD9("Quad9 Secure (9.9.9.9)", "9.9.9.9", "dns.quad9.net", 853),
    ADGUARD("AdGuard DoT (94.140.14.14)", "94.140.14.14", "dns.adguard-dns.com", 853)
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class SplitTunnelMode {
    ALL_APPS,
    SELECTED_ONLY
}

enum class TunnelMode {
    VPN_TUNNEL,
    NO_VPN_BRIDGE
}

data class GatewayProfile(
    val id: String,
    val name: String,
    val city: String,
    val host: String,
    val port: Int,
    val protocol: TunnelProtocol,
    val username: String = "",
    val password: String = "",
    val isCommunityRelay: Boolean = false,
    val isBuiltInShield: Boolean = false,
    val latencyMs: Long? = null,
    val isCheckingLatency: Boolean = false,
    val lastCheckedAt: Long? = null
)

data class PublicIpInfo(
    val ip: String,
    val countryName: String,
    val countryCode: String,
    val city: String,
    val isp: String,
    val isBangladesh: Boolean,
    val latencyMs: Long,
    val checkedAt: Long,
    val asn: String = "",
    val isLikelyResidentialIsp: Boolean = false
)

data class TunnelTelemetry(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
    val dnsQueriesSecured: Int = 0,
    val activeFlows: Int = 0,
    val connectedSinceMs: Long = 0L,
    val activeCipherSuite: String = "TLS 1.3 • AES-256-GCM",
    val activeModeLabel: String = "",
    val secureDnsActive: Boolean = false,
    val secureDnsHost: String = ""
)

data class DotProbeResult(
    val providerName: String,
    val tlsHost: String,
    val cipherSuite: String,
    val latencyMs: Long,
    val success: Boolean,
    val errorMessage: String? = null
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String
)

data class BdPortalItem(
    val id: String,
    val titleBn: String,
    val titleEn: String,
    val categoryBn: String,
    val categoryEn: String,
    val url: String,
    val host: String,
    val statusCode: Int? = null,
    val latencyMs: Long? = null,
    val isReachable: Boolean? = null,
    val isChecking: Boolean = false
)
