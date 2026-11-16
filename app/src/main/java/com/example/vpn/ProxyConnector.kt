package com.example.vpn

import android.net.VpnService
import android.util.Base64
import com.example.model.GatewayProfile
import com.example.model.TunnelProtocol
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ProxyConnectionResult(
    val socket: Socket,
    val cipherSuite: String
)

object ProxyConnector {

    fun verifyGatewayHandshake(
        vpnService: VpnService,
        gateway: GatewayProfile
    ): Result<String> {
        val rawSocket = Socket()
        return try {
            vpnService.protect(rawSocket)
            rawSocket.soTimeout = 5000
            rawSocket.connect(InetSocketAddress(gateway.host, gateway.port), 4500)

            var cipher = gateway.protocol.encryptionLabel
            val activeSocket: Socket = when (gateway.protocol) {
                TunnelProtocol.SOCKS5_TLS, TunnelProtocol.HTTPS_TLS -> {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val ssl = factory.createSocket(rawSocket, gateway.host, gateway.port, true) as SSLSocket
                    ssl.soTimeout = 5000
                    ssl.startHandshake()
                    cipher = "${ssl.session.protocol} • ${ssl.session.cipherSuite.removePrefix("TLS_")}"
                    ssl
                }
                else -> rawSocket
            }

            if (gateway.protocol == TunnelProtocol.SOCKS5 || gateway.protocol == TunnelProtocol.SOCKS5_TLS) {
                performSocks5AuthOnly(activeSocket, gateway)
            }
            activeSocket.close()
            Result.success(cipher)
        } catch (e: Exception) {
            try {
                rawSocket.close()
            } catch (_: Exception) {
            }
            Result.failure(e)
        }
    }

    fun openTunnelStream(
        vpnService: VpnService?,
        gateway: GatewayProfile,
        targetIp: ByteArray,
        targetPort: Int
    ): ProxyConnectionResult {
        return openTunnelStreamInternal(
            vpnService = vpnService,
            gateway = gateway,
            targetIp = targetIp,
            targetHost = null,
            targetPort = targetPort
        )
    }

    fun openTunnelStreamByHost(
        vpnService: VpnService?,
        gateway: GatewayProfile,
        targetHost: String,
        targetPort: Int
    ): ProxyConnectionResult {
        return openTunnelStreamInternal(
            vpnService = vpnService,
            gateway = gateway,
            targetIp = null,
            targetHost = targetHost,
            targetPort = targetPort
        )
    }

    private fun openTunnelStreamInternal(
        vpnService: VpnService?,
        gateway: GatewayProfile,
        targetIp: ByteArray?,
        targetHost: String?,
        targetPort: Int
    ): ProxyConnectionResult {
        val rawSocket = Socket()
        try {
            vpnService?.protect(rawSocket)
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = 15000
            rawSocket.connect(InetSocketAddress(gateway.host, gateway.port), 6000)

            var cipher = gateway.protocol.encryptionLabel
            val activeSocket: Socket = when (gateway.protocol) {
                TunnelProtocol.SOCKS5_TLS, TunnelProtocol.HTTPS_TLS -> {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val ssl = factory.createSocket(rawSocket, gateway.host, gateway.port, true) as SSLSocket
                    ssl.soTimeout = 15000
                    ssl.startHandshake()
                    cipher = "${ssl.session.protocol} • ${ssl.session.cipherSuite.removePrefix("TLS_")}"
                    ssl
                }
                else -> rawSocket
            }

            when (gateway.protocol) {
                TunnelProtocol.SOCKS5, TunnelProtocol.SOCKS5_TLS -> {
                    performSocks5Connect(activeSocket, gateway, targetIp, targetHost, targetPort)
                }
                TunnelProtocol.HTTPS_TLS -> {
                    val hostStr = targetHost ?: PacketUtils.ipBytesToString(requireNotNull(targetIp))
                    performHttpConnect(activeSocket, gateway, hostStr, targetPort)
                }
                TunnelProtocol.DNS_SHIELD -> {
                    throw IllegalStateException("DNS_SHIELD does not proxy TCP streams")
                }
            }
            activeSocket.soTimeout = 0
            return ProxyConnectionResult(activeSocket, cipher)
        } catch (e: Exception) {
            try {
                rawSocket.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun performSocks5AuthOnly(socket: Socket, gateway: GatewayProfile) {
        val out = socket.getOutputStream()
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
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
            throw IllegalStateException("SOCKS5 handshake rejected (method=$method)")
        }
        if (method == 0x02) {
            sendSocks5UserPass(out, input, gateway.username, gateway.password)
        }
    }

    private fun performSocks5Connect(
        socket: Socket,
        gateway: GatewayProfile,
        targetIp: ByteArray?,
        targetHost: String?,
        targetPort: Int
    ) {
        val out = socket.getOutputStream()
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
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
            throw IllegalStateException("SOCKS5 auth negotiation failed")
        }
        if (method == 0x02) {
            sendSocks5UserPass(out, input, gateway.username, gateway.password)
        }

        val reqStream = ByteArrayOutputStream()
        reqStream.write(0x05)
        reqStream.write(0x01) // CONNECT
        reqStream.write(0x00)
        if (!targetHost.isNullOrBlank()) {
            val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
            reqStream.write(0x03) // DOMAINNAME
            reqStream.write(hostBytes.size.coerceAtMost(255))
            reqStream.write(hostBytes, 0, hostBytes.size.coerceAtMost(255))
        } else {
            val ip = requireNotNull(targetIp)
            reqStream.write(0x01) // IPv4
            reqStream.write(ip, 0, 4)
        }
        reqStream.write((targetPort ushr 8) and 0xFF)
        reqStream.write(targetPort and 0xFF)
        out.write(reqStream.toByteArray())
        out.flush()

        val respVer = input.readUnsignedByte()
        val rep = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val atyp = input.readUnsignedByte()
        if (respVer != 0x05 || rep != 0x00) {
            throw IllegalStateException("SOCKS5 CONNECT error code=$rep")
        }
        when (atyp) {
            0x01 -> {
                val skip = ByteArray(6)
                input.readFully(skip)
            }
            0x03 -> {
                val len = input.readUnsignedByte()
                val skip = ByteArray(len + 2)
                input.readFully(skip)
            }
            0x04 -> {
                val skip = ByteArray(18)
                input.readFully(skip)
            }
        }
    }

    private fun sendSocks5UserPass(
        out: OutputStream,
        input: DataInputStream,
        username: String,
        password: String
    ) {
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val buf = ByteArrayOutputStream()
        buf.write(0x01)
        buf.write(userBytes.size.coerceAtMost(255))
        buf.write(userBytes, 0, userBytes.size.coerceAtMost(255))
        buf.write(passBytes.size.coerceAtMost(255))
        buf.write(passBytes, 0, passBytes.size.coerceAtMost(255))
        out.write(buf.toByteArray())
        out.flush()

        val authVer = input.readUnsignedByte()
        val status = input.readUnsignedByte()
        if (authVer != 0x01 || status != 0x00) {
            throw IllegalStateException("SOCKS5 authentication failed")
        }
    }

    private fun performHttpConnect(
        socket: Socket,
        gateway: GatewayProfile,
        targetHost: String,
        targetPort: Int
    ) {
        val out = socket.getOutputStream()
        val sb = StringBuilder()
        sb.append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
        sb.append("Host: $targetHost:$targetPort\r\n")
        if (gateway.username.isNotBlank() || gateway.password.isNotBlank()) {
            val token = Base64.encodeToString(
                "${gateway.username}:${gateway.password}".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            sb.append("Proxy-Authorization: Basic $token\r\n")
        }
        sb.append("Proxy-Connection: Keep-Alive\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()

        val input = socket.getInputStream()
        val headerBuf = ByteArrayOutputStream()
        var prev = 0
        var endMatched = 0
        while (true) {
            val b = input.read()
            if (b == -1) throw IllegalStateException("Proxy closed connection during CONNECT")
            headerBuf.write(b)
            if ((endMatched == 0 || endMatched == 2) && b == '\r'.code) {
                endMatched++
            } else if ((endMatched == 1 || endMatched == 3) && b == '\n'.code) {
                endMatched++
                if (endMatched == 4) break
            } else {
                endMatched = if (b == '\r'.code) 1 else 0
            }
            if (headerBuf.size() > 8192) throw IllegalStateException("Proxy response header too large")
            prev = b
        }
        val statusLine = headerBuf.toString("US-ASCII").lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains("200")) {
            throw IllegalStateException("HTTPS proxy CONNECT rejected: $statusLine")
        }
    }
}
