package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.ConnectionState
import com.example.model.DnsProvider
import com.example.model.GatewayProfile
import com.example.model.SplitTunnelMode
import com.example.model.TunnelProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DeshVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"

        const val EXTRA_GATEWAY_ID = "extra_gateway_id"
        const val EXTRA_GATEWAY_NAME = "extra_gateway_name"
        const val EXTRA_GATEWAY_CITY = "extra_gateway_city"
        const val EXTRA_GATEWAY_HOST = "extra_gateway_host"
        const val EXTRA_GATEWAY_PORT = "extra_gateway_port"
        const val EXTRA_GATEWAY_PROTOCOL = "extra_gateway_protocol"
        const val EXTRA_GATEWAY_USER = "extra_gateway_user"
        const val EXTRA_GATEWAY_PASS = "extra_gateway_pass"
        const val EXTRA_GATEWAY_IS_SHIELD = "extra_gateway_is_shield"
        const val EXTRA_GATEWAY_IS_COMMUNITY = "extra_gateway_is_community"
        const val EXTRA_DNS_PROVIDER = "extra_dns_provider"
        const val EXTRA_SECURE_DNS_ENABLED = "extra_secure_dns_enabled"
        const val EXTRA_KILL_SWITCH = "extra_kill_switch"
        const val EXTRA_SPLIT_MODE = "extra_split_mode"
        const val EXTRA_ALLOWED_PACKAGES = "extra_allowed_packages"

        private const val CHANNEL_ID = "deshtunnel_vpn_channel"
        private const val NOTIFICATION_ID = 4102

        fun startConnect(
            context: Context,
            gateway: GatewayProfile,
            dnsProvider: DnsProvider,
            secureDnsEnabled: Boolean,
            killSwitch: Boolean,
            splitMode: SplitTunnelMode,
            allowedPackages: Set<String>
        ) {
            val intent = Intent(context, DeshVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_GATEWAY_ID, gateway.id)
                putExtra(EXTRA_GATEWAY_NAME, gateway.name)
                putExtra(EXTRA_GATEWAY_CITY, gateway.city)
                putExtra(EXTRA_GATEWAY_HOST, gateway.host)
                putExtra(EXTRA_GATEWAY_PORT, gateway.port)
                putExtra(EXTRA_GATEWAY_PROTOCOL, gateway.protocol.name)
                putExtra(EXTRA_GATEWAY_USER, gateway.username)
                putExtra(EXTRA_GATEWAY_PASS, gateway.password)
                putExtra(EXTRA_GATEWAY_IS_SHIELD, gateway.isBuiltInShield)
                putExtra(EXTRA_GATEWAY_IS_COMMUNITY, gateway.isCommunityRelay)
                putExtra(EXTRA_DNS_PROVIDER, dnsProvider.name)
                putExtra(EXTRA_SECURE_DNS_ENABLED, secureDnsEnabled)
                putExtra(EXTRA_KILL_SWITCH, killSwitch)
                putExtra(EXTRA_SPLIT_MODE, splitMode.name)
                putStringArrayListExtra(EXTRA_ALLOWED_PACKAGES, ArrayList(allowedPackages))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopTunnel(context: Context) {
            val intent = Intent(context, DeshVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private data class TcpFlowKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    private inner class TcpBridgeSession(
        val key: TcpFlowKey,
        val srcIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        initialClientSeq: Long
    ) {
        val serverSeq = AtomicLong((System.nanoTime() and 0x7FFFFFFFL) + 1000L)
        val clientNextSeq = AtomicLong((initialClientSeq + 1L) and 0xFFFFFFFFL)
        @Volatile
        var socket: Socket? = null
        @Volatile
        var closed = false

        fun closeQuietly() {
            if (closed) return
            closed = true
            tcpSessions.remove(key)
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnJob: Job? = null
    private var statsJob: Job? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunOutput: FileOutputStream? = null
    private val tunWriteLock = Any()
    private val isRunning = AtomicBoolean(false)

    private val totalRxBytes = AtomicLong(0L)
    private val totalTxBytes = AtomicLong(0L)
    private val dnsQueriesCount = AtomicInteger(0)
    private val tcpSessions = ConcurrentHashMap<TcpFlowKey, TcpBridgeSession>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnectAndStop()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val notification = buildNotification(
            title = getString(R.string.vpn_notification_title_connecting),
            text = "Initializing TLS 1.3 encrypted tunnel…"
        )
        startForegroundCompat(notification)

        val gateway = extractGateway(intent)
        val dnsProvider = runCatching {
            DnsProvider.valueOf(intent?.getStringExtra(EXTRA_DNS_PROVIDER) ?: DnsProvider.CLOUDFLARE.name)
        }.getOrDefault(DnsProvider.CLOUDFLARE)
        val secureDnsEnabled = intent?.getBooleanExtra(EXTRA_SECURE_DNS_ENABLED, true) ?: true
        val killSwitch = intent?.getBooleanExtra(EXTRA_KILL_SWITCH, false) ?: false
        val splitMode = runCatching {
            SplitTunnelMode.valueOf(intent?.getStringExtra(EXTRA_SPLIT_MODE) ?: SplitTunnelMode.ALL_APPS.name)
        }.getOrDefault(SplitTunnelMode.ALL_APPS)
        val allowedPackages = intent?.getStringArrayListExtra(EXTRA_ALLOWED_PACKAGES)?.toSet() ?: emptySet()

        startTunnel(gateway, dnsProvider, secureDnsEnabled, killSwitch, splitMode, allowedPackages)
        return START_STICKY
    }

    private fun extractGateway(intent: Intent?): GatewayProfile {
        val protocol = runCatching {
            TunnelProtocol.valueOf(intent?.getStringExtra(EXTRA_GATEWAY_PROTOCOL) ?: TunnelProtocol.DNS_SHIELD.name)
        }.getOrDefault(TunnelProtocol.DNS_SHIELD)

        return GatewayProfile(
            id = intent?.getStringExtra(EXTRA_GATEWAY_ID) ?: "built_in_dns_shield",
            name = intent?.getStringExtra(EXTRA_GATEWAY_NAME) ?: "Encrypted DNS Shield",
            city = intent?.getStringExtra(EXTRA_GATEWAY_CITY) ?: "Local TLS 1.3",
            host = intent?.getStringExtra(EXTRA_GATEWAY_HOST) ?: "1.1.1.1",
            port = intent?.getIntExtra(EXTRA_GATEWAY_PORT, 853) ?: 853,
            protocol = protocol,
            username = intent?.getStringExtra(EXTRA_GATEWAY_USER).orEmpty(),
            password = intent?.getStringExtra(EXTRA_GATEWAY_PASS).orEmpty(),
            isCommunityRelay = intent?.getBooleanExtra(EXTRA_GATEWAY_IS_COMMUNITY, false) ?: false,
            isBuiltInShield = intent?.getBooleanExtra(EXTRA_GATEWAY_IS_SHIELD, true) ?: true
        )
    }

    private fun startTunnel(
        gateway: GatewayProfile,
        dnsProvider: DnsProvider,
        secureDnsEnabled: Boolean,
        killSwitch: Boolean,
        splitMode: SplitTunnelMode,
        allowedPackages: Set<String>
    ) {
        stopCurrentTunnelResources()
        isRunning.set(true)
        totalRxBytes.set(0L)
        totalTxBytes.set(0L)
        dnsQueriesCount.set(0)

        VpnStateHolder.setState(ConnectionState.CONNECTING, gateway, null)
        VpnStateHolder.addLog("Starting tunnel: ${gateway.name} (${gateway.protocol.displayName})")

        vpnJob = serviceScope.launch {
            try {
                var negotiatedCipher = gateway.protocol.encryptionLabel

                // Pre-flight proxy check when routing through an external BD gateway
                if (!gateway.isBuiltInShield && gateway.protocol != TunnelProtocol.DNS_SHIELD) {
                    VpnStateHolder.addLog("Verifying handshake -> ${gateway.host}:${gateway.port}")
                    val check = ProxyConnector.verifyGatewayHandshake(this@DeshVpnService, gateway)
                    if (check.isFailure) {
                        val reason = check.exceptionOrNull()?.localizedMessage ?: "Gateway unreachable"
                        VpnStateHolder.addLog("Handshake failed: $reason")
                        VpnStateHolder.setState(ConnectionState.ERROR, gateway, reason)
                        disconnectAndStop(preserveErrorState = true)
                        return@launch
                    }
                    negotiatedCipher = check.getOrDefault(negotiatedCipher)
                    VpnStateHolder.addLog("Gateway verified ($negotiatedCipher)")
                }

                val builder = Builder()
                    .setSession("DeshTunnel (${gateway.city})")
                    .addAddress("10.8.0.2", 32)
                    .addDnsServer(dnsProvider.ip)
                    .setMtu(1500)

                if (gateway.isBuiltInShield || gateway.protocol == TunnelProtocol.DNS_SHIELD) {
                    builder.addRoute(dnsProvider.ip, 32)
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }

                if (!killSwitch) {
                    builder.allowBypass()
                }

                // Split tunneling configuration (always exclude DeshTunnel itself to avoid loops)
                if (splitMode == SplitTunnelMode.SELECTED_ONLY && allowedPackages.isNotEmpty()) {
                    var addedCount = 0
                    for (pkg in allowedPackages) {
                        if (pkg == packageName) continue
                        try {
                            packageManager.getPackageInfo(pkg, 0)
                            builder.addAllowedApplication(pkg)
                            addedCount++
                        } catch (_: PackageManager.NameNotFoundException) {
                        }
                    }
                    if (addedCount == 0) {
                        builder.addDisallowedApplication(packageName)
                    } else {
                        VpnStateHolder.addLog("Split tunnel active for $addedCount selected apps")
                    }
                } else {
                    builder.addDisallowedApplication(packageName)
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    val msg = "VPN interface establish() returned null (system revoked)"
                    VpnStateHolder.setState(ConnectionState.ERROR, gateway, msg)
                    disconnectAndStop(preserveErrorState = true)
                    return@launch
                }

                tunInterface = pfd
                tunOutput = FileOutputStream(pfd.fileDescriptor)
                val connectedTime = System.currentTimeMillis()

                val effectiveDot = secureDnsEnabled || gateway.isBuiltInShield || gateway.protocol == TunnelProtocol.DNS_SHIELD
                VpnStateHolder.setState(ConnectionState.CONNECTED, gateway, null)
                VpnStateHolder.updateTelemetry {
                    it.copy(
                        connectedSinceMs = connectedTime,
                        activeCipherSuite = negotiatedCipher,
                        activeModeLabel = gateway.protocol.displayName,
                        secureDnsActive = effectiveDot,
                        secureDnsHost = if (effectiveDot) dnsProvider.tlsHost else dnsProvider.ip
                    )
                }
                VpnStateHolder.addLog(
                    if (effectiveDot) {
                        "TUN interface up (10.8.0.2) • Secure DoT: ${dnsProvider.tlsHost}:853"
                    } else {
                        "TUN interface up (10.8.0.2) • Standard UDP DNS: ${dnsProvider.ip}:53"
                    }
                )

                updateNotification(
                    title = "${getString(R.string.vpn_notification_title_connected)} • ${gateway.city}",
                    text = if (effectiveDot) {
                        "${gateway.protocol.displayName} • DoT (${dnsProvider.tlsHost})"
                    } else {
                        "${gateway.protocol.displayName} • $negotiatedCipher"
                    }
                )

                startTelemetryLoop(
                    connectedSinceMs = connectedTime,
                    cipher = negotiatedCipher,
                    modeLabel = gateway.protocol.displayName,
                    secureDnsActive = effectiveDot,
                    secureDnsHost = if (effectiveDot) dnsProvider.tlsHost else dnsProvider.ip
                )
                runPacketLoop(pfd, gateway, dnsProvider, effectiveDot)
            } catch (e: Exception) {
                if (isRunning.get()) {
                    val msg = e.localizedMessage ?: "Tunnel error"
                    VpnStateHolder.addLog("Tunnel error: $msg")
                    VpnStateHolder.setState(ConnectionState.ERROR, gateway, msg)
                    disconnectAndStop(preserveErrorState = true)
                }
            }
        }
    }

    private fun startTelemetryLoop(
        connectedSinceMs: Long,
        cipher: String,
        modeLabel: String,
        secureDnsActive: Boolean,
        secureDnsHost: String
    ) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var lastRx = 0L
            var lastTx = 0L
            while (isActive && isRunning.get()) {
                delay(1000L)
                val rx = totalRxBytes.get()
                val tx = totalTxBytes.get()
                val rxRate = (rx - lastRx).coerceAtLeast(0L)
                val txRate = (tx - lastTx).coerceAtLeast(0L)
                lastRx = rx
                lastTx = tx

                VpnStateHolder.updateTelemetry {
                    it.copy(
                        rxBytes = rx,
                        txBytes = tx,
                        rxBytesPerSec = rxRate,
                        txBytesPerSec = txRate,
                        dnsQueriesSecured = dnsQueriesCount.get(),
                        activeFlows = tcpSessions.size,
                        connectedSinceMs = connectedSinceMs,
                        activeCipherSuite = cipher,
                        activeModeLabel = modeLabel,
                        secureDnsActive = secureDnsActive,
                        secureDnsHost = secureDnsHost
                    )
                }
            }
        }
    }

    private fun runPacketLoop(
        pfd: ParcelFileDescriptor,
        gateway: GatewayProfile,
        dnsProvider: DnsProvider,
        useSecureDoT: Boolean
    ) {
        val input = FileInputStream(pfd.fileDescriptor)
        val dotResolver = DnsOverTlsResolver(this, dnsProvider)
        val buffer = ByteArray(32767)

        while (isRunning.get()) {
            val length = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (length < 0) break
            if (length == 0) continue

            val ipHeader = PacketUtils.parseIpv4Header(buffer, length) ?: continue
            totalTxBytes.addAndGet(length.toLong())

            when (ipHeader.protocol) {
                PacketUtils.PROTO_UDP -> {
                    val udp = PacketUtils.parseUdpHeader(buffer, ipHeader.headerLength, ipHeader.totalLength)
                        ?: continue
                    if (udp.dstPort == 53) {
                        val payloadOffset = ipHeader.headerLength + 8
                        val payloadLen = (ipHeader.totalLength - payloadOffset).coerceAtLeast(0)
                        if (payloadLen > 0) {
                            val dnsPayload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLen)
                            serviceScope.launch {
                                val dnsResp = if (useSecureDoT) {
                                    dotResolver.resolve(dnsPayload)
                                } else {
                                    dotResolver.resolveStandardUdp(dnsPayload)
                                }
                                if (dnsResp != null) {
                                    if (useSecureDoT) {
                                        dnsQueriesCount.incrementAndGet()
                                    }
                                    val respPacket = PacketUtils.buildUdpResponsePacket(
                                        srcIp = ipHeader.dstIp,
                                        dstIp = ipHeader.srcIp,
                                        srcPort = udp.dstPort,
                                        dstPort = udp.srcPort,
                                        payload = dnsResp
                                    )
                                    writePacketToTun(respPacket)
                                }
                            }
                        }
                    }
                }

                PacketUtils.PROTO_TCP -> {
                    if (gateway.isBuiltInShield || gateway.protocol == TunnelProtocol.DNS_SHIELD) {
                        continue
                    }
                    val tcp = PacketUtils.parseTcpHeader(buffer, ipHeader.headerLength, ipHeader.totalLength)
                        ?: continue
                    val payloadOffset = ipHeader.headerLength + tcp.headerLength
                    val payloadLen = (ipHeader.totalLength - payloadOffset).coerceAtLeast(0)
                    val payload = if (payloadLen > 0) {
                        buffer.copyOfRange(payloadOffset, payloadOffset + payloadLen)
                    } else {
                        ByteArray(0)
                    }
                    handleTcpPacket(ipHeader, tcp, payload, gateway)
                }
            }
        }
    }

    private fun handleTcpPacket(
        ip: Ipv4Header,
        tcp: TcpHeader,
        payload: ByteArray,
        gateway: GatewayProfile
    ) {
        val key = TcpFlowKey(
            srcIp = PacketUtils.ipBytesToString(ip.srcIp),
            srcPort = tcp.srcPort,
            dstIp = PacketUtils.ipBytesToString(ip.dstIp),
            dstPort = tcp.dstPort
        )

        if (tcp.isRst) {
            tcpSessions[key]?.closeQuietly()
            return
        }

        if (tcp.isSyn && !tcp.isAck) {
            tcpSessions[key]?.closeQuietly()
            val session = TcpBridgeSession(
                key = key,
                srcIpBytes = ip.srcIp.copyOf(),
                dstIpBytes = ip.dstIp.copyOf(),
                srcPort = tcp.srcPort,
                dstPort = tcp.dstPort,
                initialClientSeq = tcp.seqNum
            )
            tcpSessions[key] = session

            serviceScope.launch {
                try {
                    val result = ProxyConnector.openTunnelStream(
                        vpnService = this@DeshVpnService,
                        gateway = gateway,
                        targetIp = session.dstIpBytes,
                        targetPort = session.dstPort
                    )
                    if (session.closed || !isRunning.get()) {
                        result.socket.close()
                        return@launch
                    }
                    session.socket = result.socket

                    // Send SYN-ACK back to TUN
                    val seq = session.serverSeq.getAndIncrement()
                    val synAck = PacketUtils.buildTcpPacket(
                        srcIp = session.dstIpBytes,
                        dstIp = session.srcIpBytes,
                        srcPort = session.dstPort,
                        dstPort = session.srcPort,
                        seqNum = seq,
                        ackNum = session.clientNextSeq.get(),
                        flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK
                    )
                    writePacketToTun(synAck)

                    // Pump downstream data from proxy socket back into TUN
                    val proxyIn = result.socket.getInputStream()
                    val readBuf = ByteArray(1360)
                    while (isRunning.get() && !session.closed) {
                        val read = proxyIn.read(readBuf)
                        if (read == -1) {
                            val finSeq = session.serverSeq.getAndIncrement()
                            val finPkt = PacketUtils.buildTcpPacket(
                                srcIp = session.dstIpBytes,
                                dstIp = session.srcIpBytes,
                                srcPort = session.dstPort,
                                dstPort = session.srcPort,
                                seqNum = finSeq,
                                ackNum = session.clientNextSeq.get(),
                                flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK
                            )
                            writePacketToTun(finPkt)
                            session.closeQuietly()
                            break
                        }
                        if (read > 0) {
                            val chunk = readBuf.copyOf(read)
                            val dataSeq = session.serverSeq.getAndAdd(read.toLong())
                            val dataPkt = PacketUtils.buildTcpPacket(
                                srcIp = session.dstIpBytes,
                                dstIp = session.srcIpBytes,
                                srcPort = session.dstPort,
                                dstPort = session.srcPort,
                                seqNum = dataSeq,
                                ackNum = session.clientNextSeq.get(),
                                flags = PacketUtils.TCP_PSH or PacketUtils.TCP_ACK,
                                payload = chunk
                            )
                            writePacketToTun(dataPkt)
                        }
                    }
                } catch (_: Exception) {
                    if (!session.closed && isRunning.get()) {
                        val rstPkt = PacketUtils.buildTcpPacket(
                            srcIp = session.dstIpBytes,
                            dstIp = session.srcIpBytes,
                            srcPort = session.dstPort,
                            dstPort = session.srcPort,
                            seqNum = session.serverSeq.get(),
                            ackNum = session.clientNextSeq.get(),
                            flags = PacketUtils.TCP_RST or PacketUtils.TCP_ACK
                        )
                        writePacketToTun(rstPkt)
                    }
                    session.closeQuietly()
                }
            }
            return
        }

        val session = tcpSessions[key] ?: return

        if (payload.isNotEmpty()) {
            val expectedSeq = session.clientNextSeq.get()
            if (tcp.seqNum == expectedSeq) {
                session.clientNextSeq.set((expectedSeq + payload.size) and 0xFFFFFFFFL)
                serviceScope.launch {
                    try {
                        session.socket?.getOutputStream()?.let { out ->
                            out.write(payload)
                            out.flush()
                        }
                        val ackPkt = PacketUtils.buildTcpPacket(
                            srcIp = session.dstIpBytes,
                            dstIp = session.srcIpBytes,
                            srcPort = session.dstPort,
                            dstPort = session.srcPort,
                            seqNum = session.serverSeq.get(),
                            ackNum = session.clientNextSeq.get(),
                            flags = PacketUtils.TCP_ACK
                        )
                        writePacketToTun(ackPkt)
                    } catch (_: Exception) {
                        session.closeQuietly()
                    }
                }
            }
        }

        if (tcp.isFin) {
            session.clientNextSeq.incrementAndGet()
            val finAck = PacketUtils.buildTcpPacket(
                srcIp = session.dstIpBytes,
                dstIp = session.srcIpBytes,
                srcPort = session.dstPort,
                dstPort = session.srcPort,
                seqNum = session.serverSeq.get(),
                ackNum = session.clientNextSeq.get(),
                flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK
            )
            writePacketToTun(finAck)
            session.closeQuietly()
        }
    }

    private fun writePacketToTun(packet: ByteArray) {
        synchronized(tunWriteLock) {
            try {
                tunOutput?.write(packet)
                totalRxBytes.addAndGet(packet.size.toLong())
            } catch (_: Exception) {
            }
        }
    }

    private fun stopCurrentTunnelResources() {
        isRunning.set(false)
        statsJob?.cancel()
        vpnJob?.cancel()
        tcpSessions.values.forEach { it.closeQuietly() }
        tcpSessions.clear()
        try {
            tunOutput?.close()
        } catch (_: Exception) {
        }
        tunOutput = null
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
    }

    private fun disconnectAndStop(preserveErrorState: Boolean = false) {
        stopCurrentTunnelResources()
        if (!preserveErrorState) {
            VpnStateHolder.addLog("Tunnel disconnected cleanly")
            VpnStateHolder.setState(ConnectionState.DISCONNECTED, VpnStateHolder.activeGateway.value, null)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        disconnectAndStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopCurrentTunnelResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.vpn_notification_channel_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DeshVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.vpn_action_disconnect), disconnectIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
