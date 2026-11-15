package com.example.data

import android.content.Context
import android.content.Intent
import com.example.model.BdPortalItem
import com.example.model.GatewayProfile
import com.example.model.InstalledAppInfo
import com.example.model.PublicIpInfo
import com.example.model.TunnelProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class NetworkInspectorRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchPublicIp(proxyClient: OkHttpClient = client): Result<PublicIpInfo> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        runCatching {
            val req = Request.Builder()
                .url("https://ipwho.is/")
                .header("Accept", "application/json")
                .build()
            proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                if (!json.optBoolean("success", true)) {
                    error(json.optString("message", "GeoIP lookup failed"))
                }
                val ip = json.optString("ip", "")
                val countryCode = json.optString("country_code", "")
                val countryName = json.optString("country", "")
                val city = json.optString("city", "")
                val conn = json.optJSONObject("connection")
                val isp = conn?.optString("isp", "")?.ifBlank { conn.optString("org", "") } ?: ""
                if (ip.isBlank()) error("Empty IP response")
                PublicIpInfo(
                    ip = ip,
                    countryName = countryName.ifBlank { countryCode },
                    countryCode = countryCode,
                    city = city,
                    isp = isp,
                    isBangladesh = countryCode.equals("BD", ignoreCase = true),
                    latencyMs = System.currentTimeMillis() - start,
                    checkedAt = System.currentTimeMillis()
                )
            }
        }.recoverCatching {
            val req = Request.Builder()
                .url("https://ipapi.co/json/")
                .header("Accept", "application/json")
                .header("User-Agent", "DeshTunnel/1.0")
                .build()
            proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                val ip = json.optString("ip", "")
                val countryCode = json.optString("country_code", "")
                val countryName = json.optString("country_name", "")
                val city = json.optString("city", "")
                val isp = json.optString("org", "")
                if (ip.isBlank()) error("Empty fallback IP response")
                PublicIpInfo(
                    ip = ip,
                    countryName = countryName.ifBlank { countryCode },
                    countryCode = countryCode,
                    city = city,
                    isp = isp,
                    isBangladesh = countryCode.equals("BD", ignoreCase = true),
                    latencyMs = System.currentTimeMillis() - start,
                    checkedAt = System.currentTimeMillis()
                )
            }
        }
    }

    suspend fun verifyGatewayExitIp(gateway: GatewayProfile): Result<PublicIpInfo> = withContext(Dispatchers.IO) {
        if (gateway.isBuiltInShield || gateway.protocol == TunnelProtocol.DNS_SHIELD) {
            return@withContext fetchPublicIp()
        }
        val proxyType = when (gateway.protocol) {
            TunnelProtocol.SOCKS5, TunnelProtocol.SOCKS5_TLS -> Proxy.Type.SOCKS
            TunnelProtocol.HTTPS_TLS -> Proxy.Type.HTTP
            TunnelProtocol.DNS_SHIELD -> Proxy.Type.DIRECT
        }
        val builder = OkHttpClient.Builder()
            .proxy(Proxy(proxyType, InetSocketAddress(gateway.host, gateway.port)))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)

        if (gateway.username.isNotBlank() || gateway.password.isNotBlank()) {
            if (proxyType == Proxy.Type.HTTP) {
                builder.proxyAuthenticator(Authenticator { _, response ->
                    val credential = Credentials.basic(gateway.username, gateway.password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                })
            }
        }
        fetchPublicIp(builder.build())
    }

    suspend fun measureGatewayLatency(gateway: GatewayProfile): Long? = withContext(Dispatchers.IO) {
        val useTls = gateway.protocol == TunnelProtocol.SOCKS5_TLS ||
            gateway.protocol == TunnelProtocol.HTTPS_TLS ||
            gateway.protocol == TunnelProtocol.DNS_SHIELD
        val start = System.currentTimeMillis()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(gateway.host, gateway.port), 3500)
            socket.soTimeout = 3500
            val activeSocket: Socket = if (useTls) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, gateway.host, gateway.port, true) as SSLSocket
                ssl.soTimeout = 3500
                ssl.startHandshake()
                ssl
            } else {
                socket
            }

            // Perform real SOCKS5 greeting check on SOCKS5 gateways so dead TCP listeners are filtered out
            if (gateway.protocol == TunnelProtocol.SOCKS5 || gateway.protocol == TunnelProtocol.SOCKS5_TLS) {
                val out = activeSocket.getOutputStream()
                val input = DataInputStream(BufferedInputStream(activeSocket.getInputStream()))
                val hasCreds = gateway.username.isNotBlank() || gateway.password.isNotBlank()
                if (hasCreds) {
                    out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                } else {
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                }
                out.flush()
                val ver = input.readUnsignedByte()
                val method = input.readUnsignedByte()
                if (ver != 0x05 || method == 0xFF) {
                    activeSocket.close()
                    return@withContext null
                }
            }

            activeSocket.close()
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        } catch (_: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    suspend fun fetchCommunityBdProxies(): Result<List<GatewayProfile>> = withContext(Dispatchers.IO) {
        runCatching {
            val discovered = linkedMapOf<String, GatewayProfile>()

            runCatching {
                val req = Request.Builder()
                    .url("https://proxylist.geonode.com/api/proxy-list?country=BD&limit=20&page=1&sort_by=lastChecked&sort_type=desc")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val data = json.optJSONArray("data")
                        if (data != null) {
                            for (i in 0 until data.length()) {
                                val item = data.getJSONObject(i)
                                val ip = item.optString("ip", "").trim()
                                val port = item.optString("port", "").toIntOrNull() ?: continue
                                val city = item.optString("city", "").ifBlank { "Dhaka" }
                                val protocols = item.optJSONArray("protocols")
                                val protoList = buildList {
                                    if (protocols != null) {
                                        for (p in 0 until protocols.length()) {
                                            add(protocols.optString(p).lowercase())
                                        }
                                    }
                                }
                                val protocol = when {
                                    protoList.contains("socks5") -> TunnelProtocol.SOCKS5
                                    protoList.contains("https") -> TunnelProtocol.HTTPS_TLS
                                    else -> continue
                                }
                                if (ip.isNotBlank()) {
                                    val key = "$ip:$port"
                                    discovered[key] = GatewayProfile(
                                        id = "pub_${ip}_$port",
                                        name = "BD Relay ($city)",
                                        city = city,
                                        host = ip,
                                        port = port,
                                        protocol = protocol,
                                        isCommunityRelay = true
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (discovered.size < 12) {
                runCatching {
                    val req = Request.Builder()
                        .url("https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=5000&country=BD")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val lines = resp.body?.string().orEmpty().lineSequence()
                            lines.map { it.trim() }
                                .filter { it.contains(":") }
                                .take(15)
                                .forEach { line ->
                                    val parts = line.split(":")
                                    val ip = parts.getOrNull(0)?.trim().orEmpty()
                                    val port = parts.getOrNull(1)?.trim()?.toIntOrNull()
                                    if (ip.isNotBlank() && port != null && port in 1..65535) {
                                        val key = "$ip:$port"
                                        discovered.putIfAbsent(
                                            key,
                                            GatewayProfile(
                                                id = "pub_${ip}_$port",
                                                name = "BD SOCKS5 Relay",
                                                city = "Bangladesh",
                                                host = ip,
                                                port = port,
                                                protocol = TunnelProtocol.SOCKS5,
                                                isCommunityRelay = true
                                            )
                                        )
                                    }
                                }
                        }
                    }
                }
            }

            val list = discovered.values.toList()
            coroutineScope {
                list.map { gw ->
                    async {
                        val ms = measureGatewayLatency(gw)
                        gw.copy(latencyMs = ms, lastCheckedAt = System.currentTimeMillis())
                    }
                }.awaitAll()
            }.sortedWith(compareBy(nullsLast()) { it.latencyMs })
        }
    }

    suspend fun checkBdPortal(portal: BdPortalItem): BdPortalItem = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(portal.url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Android) DeshTunnel/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                val ms = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val reachable = resp.code in 200..499
                portal.copy(
                    statusCode = resp.code,
                    latencyMs = ms,
                    isReachable = reachable,
                    isChecking = false
                )
            }
        } catch (_: Exception) {
            portal.copy(
                statusCode = null,
                latencyMs = null,
                isReachable = false,
                isChecking = false
            )
        }
    }

    suspend fun loadInstalledApps(context: Context): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        resolveInfos
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isBlank()) return@mapNotNull null
                InstalledAppInfo(packageName = pkg, appName = label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    fun defaultBdPortals(): List<BdPortalItem> = listOf(
        BdPortalItem(
            id = "bkash",
            titleBn = "বিকাশ (bKash)",
            titleEn = "bKash Portal",
            categoryBn = "মোবাইল ব্যাংকিং",
            categoryEn = "MFS / Wallet",
            url = "https://www.bkash.com",
            host = "www.bkash.com"
        ),
        BdPortalItem(
            id = "amberit_selfcare",
            titleBn = "আম্বার আইটি (My Swift পোর্টাল)",
            titleEn = "Amber IT My Swift Portal",
            categoryBn = "ISP সেলফ-কেয়ার",
            categoryEn = "ISP Self-Care",
            url = "https://myswift.amberit.com.bd",
            host = "myswift.amberit.com.bd"
        ),
        BdPortalItem(
            id = "amberit_web",
            titleBn = "আম্বার আইটি অফিশিয়াল",
            titleEn = "Amber IT Official",
            categoryBn = "ISP ও ব্রডব্যান্ড",
            categoryEn = "ISP & Broadband",
            url = "https://www.amberit.com.bd",
            host = "www.amberit.com.bd"
        ),
        BdPortalItem(
            id = "nid",
            titleBn = "বাংলাদেশ NID সার্ভিস",
            titleEn = "Bangladesh NID Portal",
            categoryBn = "সরকারি সেবা",
            categoryEn = "Govt Service",
            url = "https://services.nidw.gov.bd",
            host = "services.nidw.gov.bd"
        ),
        BdPortalItem(
            id = "epassport",
            titleBn = "ই-পাসপোর্ট পোর্টাল",
            titleEn = "e-Passport Bangladesh",
            categoryBn = "সরকারি সেবা",
            categoryEn = "Govt Service",
            url = "https://www.epassport.gov.bd",
            host = "www.epassport.gov.bd"
        ),
        BdPortalItem(
            id = "sonali",
            titleBn = "সোনালী ব্যাংক পিএলসি",
            titleEn = "Sonali Bank PLC",
            categoryBn = "ব্যাংকিং",
            categoryEn = "Banking",
            url = "https://www.sonalibank.com.bd",
            host = "www.sonalibank.com.bd"
        )
    )
}
