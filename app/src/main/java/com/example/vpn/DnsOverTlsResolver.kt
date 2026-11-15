package com.example.vpn

import android.net.VpnService
import com.example.model.DnsProvider
import com.example.model.DotProbeResult
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class DnsOverTlsResolver(
    private val vpnService: VpnService?,
    private val dnsProvider: DnsProvider
) {
    @Volatile
    var lastNegotiatedCipher: String = "TLS 1.3 • AES-256-GCM"
        private set

    fun resolve(dnsQuery: ByteArray): ByteArray? {
        if (dnsQuery.size < 12) return null
        val rawSocket = Socket()
        return try {
            vpnService?.protect(rawSocket)
            rawSocket.soTimeout = 4500
            rawSocket.connect(InetSocketAddress(dnsProvider.ip, dnsProvider.port), 3500)

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = factory.createSocket(
                rawSocket,
                dnsProvider.tlsHost,
                dnsProvider.port,
                true
            ) as SSLSocket

            sslSocket.soTimeout = 4500
            val params = sslSocket.sslParameters
            params.serverNames = listOf(SNIHostName(dnsProvider.tlsHost))
            params.endpointIdentificationAlgorithm = "HTTPS"
            sslSocket.sslParameters = params
            sslSocket.startHandshake()

            val session = sslSocket.session
            lastNegotiatedCipher = "${session.protocol} • ${session.cipherSuite.removePrefix("TLS_")}"

            val out = DataOutputStream(sslSocket.outputStream)
            out.writeShort(dnsQuery.size)
            out.write(dnsQuery)
            out.flush()

            val input = DataInputStream(sslSocket.inputStream)
            val respLen = input.readUnsignedShort()
            if (respLen <= 0 || respLen > 4096) {
                sslSocket.close()
                return null
            }
            val resp = ByteArray(respLen)
            input.readFully(resp)
            sslSocket.close()
            resp
        } catch (_: Exception) {
            try {
                rawSocket.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    fun resolveStandardUdp(dnsQuery: ByteArray): ByteArray? {
        if (dnsQuery.size < 12) return null
        val socket = DatagramSocket()
        return try {
            vpnService?.protect(socket)
            socket.soTimeout = 3500
            val address = InetAddress.getByName(dnsProvider.ip)
            val req = DatagramPacket(dnsQuery, dnsQuery.size, address, 53)
            socket.send(req)

            val buf = ByteArray(2048)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            buf.copyOf(resp.length)
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun buildDnsQueryPacket(domain: String, transactionId: Int = 0x4A2F): ByteArray {
            val out = ByteArrayOutputStream()
            // Header (12 bytes): ID, Flags (RD=1), QDCOUNT=1, AN/NS/AR=0
            out.write((transactionId ushr 8) and 0xFF)
            out.write(transactionId and 0xFF)
            out.write(0x01)
            out.write(0x00)
            out.write(0x00)
            out.write(0x01)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)

            domain.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                val len = bytes.size.coerceAtMost(63)
                out.write(len)
                out.write(bytes, 0, len)
            }
            out.write(0x00) // root label
            // QTYPE = A (1), QCLASS = IN (1)
            out.write(0x00)
            out.write(0x01)
            out.write(0x00)
            out.write(0x01)
            return out.toByteArray()
        }

        fun verifyDoTProvider(provider: DnsProvider): DotProbeResult {
            val start = System.currentTimeMillis()
            val resolver = DnsOverTlsResolver(vpnService = null, dnsProvider = provider)
            return try {
                val query = buildDnsQueryPacket("bangladesh.gov.bd")
                val resp = resolver.resolve(query)
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                if (resp != null && resp.size >= 12) {
                    DotProbeResult(
                        providerName = provider.displayName,
                        tlsHost = provider.tlsHost,
                        cipherSuite = resolver.lastNegotiatedCipher,
                        latencyMs = elapsed,
                        success = true
                    )
                } else {
                    DotProbeResult(
                        providerName = provider.displayName,
                        tlsHost = provider.tlsHost,
                        cipherSuite = "",
                        latencyMs = elapsed,
                        success = false,
                        errorMessage = "Empty DNS-over-TLS response"
                    )
                }
            } catch (e: Exception) {
                DotProbeResult(
                    providerName = provider.displayName,
                    tlsHost = provider.tlsHost,
                    cipherSuite = "",
                    latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                    success = false,
                    errorMessage = e.localizedMessage ?: "TLS handshake error"
                )
            }
        }
    }
}
