package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.ConnectionState
import com.example.model.GatewayProfile
import com.example.model.TunnelProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Local HTTP/CONNECT proxy bridge on 127.0.0.1:8888 forwarding to a configured BD gateway
 * WITHOUT creating an Android VpnService TUN interface (so TRANSPORT_VPN is never set).
 */
class LocalProxyBridgeService : Service() {

    companion object {
        const val BRIDGE_HOST = "127.0.0.1"
        const val BRIDGE_PORT = 8888

        const val ACTION_START_BRIDGE = "com.example.vpn.ACTION_START_BRIDGE"
        const val ACTION_STOP_BRIDGE = "com.example.vpn.ACTION_STOP_BRIDGE"

        private const val CHANNEL_ID = "deshtunnel_vpn_channel"
        private const val NOTIFICATION_ID = 4103

        fun startBridge(context: Context, gateway: GatewayProfile) {
            val intent = Intent(context, LocalProxyBridgeService::class.java).apply {
                action = ACTION_START_BRIDGE
                putExtra(DeshVpnService.EXTRA_GATEWAY_ID, gateway.id)
                putExtra(DeshVpnService.EXTRA_GATEWAY_NAME, gateway.name)
                putExtra(DeshVpnService.EXTRA_GATEWAY_CITY, gateway.city)
                putExtra(DeshVpnService.EXTRA_GATEWAY_HOST, gateway.host)
                putExtra(DeshVpnService.EXTRA_GATEWAY_PORT, gateway.port)
                putExtra(DeshVpnService.EXTRA_GATEWAY_PROTOCOL, gateway.protocol.name)
                putExtra(DeshVpnService.EXTRA_GATEWAY_USER, gateway.username)
                putExtra(DeshVpnService.EXTRA_GATEWAY_PASS, gateway.password)
                putExtra(DeshVpnService.EXTRA_GATEWAY_IS_SHIELD, gateway.isBuiltInShield)
                putExtra(DeshVpnService.EXTRA_GATEWAY_IS_COMMUNITY, gateway.isCommunityRelay)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopBridge(context: Context) {
            val intent = Intent(context, LocalProxyBridgeService::class.java).apply {
                action = ACTION_STOP_BRIDGE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var acceptJob: Job? = null
    private var statsJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val totalRxBytes = AtomicLong(0L)
    private val totalTxBytes = AtomicLong(0L)
    private val activeConnections = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_BRIDGE) {
            stopAndCleanup(preserveError = false)
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        startForegroundCompat(
            buildNotification(
                title = "DeshTunnel Bridge ($BRIDGE_HOST:$BRIDGE_PORT)",
                text = "Starting No-VPN stealth proxy bridge…"
            )
        )

        val gateway = extractGateway(intent)
        if (gateway.isBuiltInShield || gateway.protocol == TunnelProtocol.DNS_SHIELD) {
            val msg = "No-VPN Bridge requires a BD SOCKS5 / HTTPS proxy gateway (not DNS Shield)."
            VpnStateHolder.addLog(msg)
            VpnStateHolder.setState(ConnectionState.ERROR, gateway, msg)
            stopAndCleanup(preserveError = true)
            return START_NOT_STICKY
        }

        startBridgeServer(gateway)
        return START_STICKY
    }

    private fun extractGateway(intent: Intent?): GatewayProfile {
        val protocol = runCatching {
            TunnelProtocol.valueOf(
                intent?.getStringExtra(DeshVpnService.EXTRA_GATEWAY_PROTOCOL) ?: TunnelProtocol.SOCKS5.name
            )
        }.getOrDefault(TunnelProtocol.SOCKS5)

        return GatewayProfile(
            id = intent?.getStringExtra(DeshVpnService.EXTRA_GATEWAY_ID) ?: "bridge_gw",
            name = intent?.getStringExtra(DeshVpnService.EXTRA_GATEWAY_NAME) ?: "BD Proxy",
            city = intent?.getStringExtra(DeshVpnService.EXTRA_GATEWAY_CITY) ?: "Dhaka",
            host = intent?.getStringExtra(DeshVpnService.EXTRA_GATEWAY_HOST).orEmpty(),
            port = intent?.getIntExtra(DeshVpnService.EXTRA_GATEWAY_PORT, 1080) ?: 1080,
            protocol = protocol,
            username = intent?.getStringExtra(DeshVpnService.EXTRA_GATEWAY_USER).orEmpty(),
            password = intent?.getStringExtra(DeshVpnService.EXTRA_GATEWAY_PASS).orEmpty(),
            isCommunityRelay = intent?.getBooleanExtra(DeshVpnService.EXTRA_GATEWAY_IS_COMMUNITY, false) ?: false,
            isBuiltInShield = intent?.getBooleanExtra(DeshVpnService.EXTRA_GATEWAY_IS_SHIELD, false) ?: false
        )
    }

    private fun startBridgeServer(gateway: GatewayProfile) {
        stopServerResources()
        isRunning.set(true)
        totalRxBytes.set(0L)
        totalTxBytes.set(0L)
        activeConnections.set(0)

        VpnStateHolder.setState(ConnectionState.CONNECTING, gateway, null)
        VpnStateHolder.addLog("Starting No-VPN Proxy Bridge ($BRIDGE_HOST:$BRIDGE_PORT) -> ${gateway.host}:${gateway.port}")

        acceptJob = serviceScope.launch {
            try {
                val handshake = ProxyConnector.verifyGatewayHandshake(null, gateway)
                if (handshake.isFailure) {
                    val reason = handshake.exceptionOrNull()?.localizedMessage ?: "BD Gateway unreachable"
                    VpnStateHolder.addLog("Bridge handshake failed: $reason")
                    VpnStateHolder.setState(ConnectionState.ERROR, gateway, reason)
                    stopAndCleanup(preserveError = true)
                    return@launch
                }

                val cipher = handshake.getOrDefault(gateway.protocol.encryptionLabel)
                val server = ServerSocket(BRIDGE_PORT, 64, InetAddress.getByName(BRIDGE_HOST)).apply {
                    reuseAddress = true
                }
                serverSocket = server

                val connectedAt = System.currentTimeMillis()
                VpnStateHolder.setState(ConnectionState.CONNECTED, gateway, null)
                VpnStateHolder.updateTelemetry {
                    it.copy(
                        connectedSinceMs = connectedAt,
                        activeCipherSuite = cipher,
                        activeModeLabel = "No-VPN Bridge ($BRIDGE_HOST:$BRIDGE_PORT)",
                        secureDnsActive = false,
                        secureDnsHost = "Remote Proxy DNS"
                    )
                }
                VpnStateHolder.addLog("No-VPN Bridge active on $BRIDGE_HOST:$BRIDGE_PORT (No TRANSPORT_VPN)")

                updateNotification(
                    title = "No-VPN Bridge Active • ${gateway.city}",
                    text = "Wi-Fi Proxy: $BRIDGE_HOST:$BRIDGE_PORT -> ${gateway.host}"
                )

                startTelemetryLoop(connectedAt, cipher, gateway)

                while (isActive && isRunning.get()) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    serviceScope.launch {
                        handleClientSocket(client, gateway)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    val msg = e.localizedMessage ?: "Bridge error"
                    VpnStateHolder.addLog("Bridge error: $msg")
                    VpnStateHolder.setState(ConnectionState.ERROR, gateway, msg)
                    stopAndCleanup(preserveError = true)
                }
            }
        }
    }

    private fun handleClientSocket(client: Socket, gateway: GatewayProfile) {
        activeConnections.incrementAndGet()
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15000
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            val headerResult = readHttpHeaders(clientIn) ?: return
            val headerLines = headerResult.headers.split("\r\n")
            val requestLine = headerLines.firstOrNull().orEmpty()
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0].uppercase()
            val target = parts[1]
            val httpVer = parts[2]

            if (method == "CONNECT") {
                val hostPort = target.split(":")
                val host = hostPort.getOrNull(0).orEmpty()
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
                if (host.isBlank()) return

                val conn = ProxyConnector.openTunnelStreamByHost(null, gateway, host, port)
                upstream = conn.socket
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                clientOut.flush()
                if (headerResult.trailingBytes.isNotEmpty()) {
                    upstream.getOutputStream().write(headerResult.trailingBytes)
                    upstream.getOutputStream().flush()
                    totalTxBytes.addAndGet(headerResult.trailingBytes.size.toLong())
                }
                client.soTimeout = 0
                pipeBidirectional(client, upstream)
            } else {
                val uri = runCatching { URI(target) }.getOrNull()
                val hostHeader = headerLines.drop(1)
                    .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim().orEmpty()
                val host = uri?.host ?: hostHeader.substringBefore(":")
                val port = if (uri != null && uri.port > 0) {
                    uri.port
                } else {
                    hostHeader.substringAfter(":", "").toIntOrNull() ?: 80
                }
                if (host.isBlank()) return

                val conn = ProxyConnector.openTunnelStreamByHost(null, gateway, host, port)
                upstream = conn.socket

                val relativePath = buildString {
                    val path = uri?.rawPath?.ifEmpty { "/" } ?: target
                    append(path)
                    if (!uri?.rawQuery.isNullOrBlank()) {
                        append("?").append(uri?.rawQuery)
                    }
                }
                val rebuilt = buildString {
                    append("$method $relativePath $httpVer\r\n")
                    headerLines.drop(1).forEach { line ->
                        if (line.isNotBlank() &&
                            !line.startsWith("Proxy-Connection:", ignoreCase = true)
                        ) {
                            append(line).append("\r\n")
                        }
                    }
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)

                val upOut = upstream.getOutputStream()
                upOut.write(rebuilt)
                if (headerResult.trailingBytes.isNotEmpty()) {
                    upOut.write(headerResult.trailingBytes)
                }
                upOut.flush()
                totalTxBytes.addAndGet((rebuilt.size + headerResult.trailingBytes.size).toLong())
                client.soTimeout = 0
                pipeBidirectional(client, upstream)
            }
        } catch (_: Exception) {
        } finally {
            activeConnections.decrementAndGet()
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private data class HttpHeaderRead(
        val headers: String,
        val trailingBytes: ByteArray
    )

    private fun readHttpHeaders(input: InputStream): HttpHeaderRead? {
        val buf = ByteArrayOutputStream()
        val temp = ByteArray(1)
        var match = 0
        while (buf.size() < 16384) {
            val n = input.read(temp)
            if (n <= 0) return null
            val b = temp[0].toInt() and 0xFF
            buf.write(b)
            match = when {
                (match == 0 || match == 2) && b == '\r'.code -> match + 1
                (match == 1 || match == 3) && b == '\n'.code -> match + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (match == 4) {
                val all = buf.toByteArray()
                return HttpHeaderRead(
                    headers = String(all, 0, all.size - 4, Charsets.US_ASCII),
                    trailingBytes = ByteArray(0)
                )
            }
        }
        return null
    }

    private fun pipeBidirectional(client: Socket, upstream: Socket) {
        val upJob = serviceScope.launch {
            copyStream(client.getInputStream(), upstream.getOutputStream(), isUpload = true)
            runCatching { upstream.shutdownOutput() }
        }
        copyStream(upstream.getInputStream(), client.getOutputStream(), isUpload = false)
        runCatching { client.shutdownOutput() }
        upJob.cancel()
    }

    private fun copyStream(input: InputStream, output: OutputStream, isUpload: Boolean) {
        val buf = ByteArray(8192)
        try {
            while (isRunning.get()) {
                val read = input.read(buf)
                if (read == -1) break
                if (read > 0) {
                    output.write(buf, 0, read)
                    output.flush()
                    if (isUpload) totalTxBytes.addAndGet(read.toLong())
                    else totalRxBytes.addAndGet(read.toLong())
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startTelemetryLoop(connectedAt: Long, cipher: String, gateway: GatewayProfile) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var lastRx = 0L
            var lastTx = 0L
            while (isActive && isRunning.get()) {
                delay(1000L)
                val rx = totalRxBytes.get()
                val tx = totalTxBytes.get()
                VpnStateHolder.updateTelemetry {
                    it.copy(
                        rxBytes = rx,
                        txBytes = tx,
                        rxBytesPerSec = (rx - lastRx).coerceAtLeast(0L),
                        txBytesPerSec = (tx - lastTx).coerceAtLeast(0L),
                        activeFlows = activeConnections.get(),
                        connectedSinceMs = connectedAt,
                        activeCipherSuite = cipher,
                        activeModeLabel = "No-VPN Bridge (${gateway.host})",
                        secureDnsActive = false,
                        secureDnsHost = "Remote Proxy DNS"
                    )
                }
                lastRx = rx
                lastTx = tx
            }
        }
    }

    private fun stopServerResources() {
        isRunning.set(false)
        statsJob?.cancel()
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun stopAndCleanup(preserveError: Boolean) {
        stopServerResources()
        if (!preserveError) {
            VpnStateHolder.addLog("No-VPN Proxy Bridge stopped")
            VpnStateHolder.setState(ConnectionState.DISCONNECTED, VpnStateHolder.activeGateway.value, null)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServerResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
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
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LocalProxyBridgeService::class.java).apply { action = ACTION_STOP_BRIDGE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.vpn_action_disconnect), stopIntent)
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
