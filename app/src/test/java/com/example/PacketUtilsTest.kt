package com.example

import com.example.vpn.DnsOverTlsResolver
import com.example.vpn.PacketUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetAddress

class PacketUtilsTest {

    @Test
    fun buildAndParseUdpResponsePacket_preservesAddressesPortsAndPayload() {
        val srcIp = InetAddress.getByName("1.1.1.1").address
        val dstIp = InetAddress.getByName("10.8.0.2").address
        val dnsPayload = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01)

        val packet = PacketUtils.buildUdpResponsePacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 53,
            dstPort = 54321,
            payload = dnsPayload
        )

        val ipHeader = PacketUtils.parseIpv4Header(packet, packet.size)
        assertNotNull(ipHeader)
        assertEquals(4, ipHeader!!.version)
        assertEquals(20, ipHeader.headerLength)
        assertEquals(PacketUtils.PROTO_UDP, ipHeader.protocol)
        assertArrayEquals(srcIp, ipHeader.srcIp)
        assertArrayEquals(dstIp, ipHeader.dstIp)

        // Verify IPv4 header checksum validates to 0
        assertEquals(0, PacketUtils.computeChecksum(packet, 0, 20))

        val udpHeader = PacketUtils.parseUdpHeader(packet, ipHeader.headerLength, ipHeader.totalLength)
        assertNotNull(udpHeader)
        assertEquals(53, udpHeader!!.srcPort)
        assertEquals(54321, udpHeader.dstPort)
        assertEquals(8 + dnsPayload.size, udpHeader.length)
    }

    @Test
    fun buildAndParseTcpPacket_preservesFlagsSequenceAndAck() {
        val srcIp = InetAddress.getByName("103.10.20.30").address
        val dstIp = InetAddress.getByName("10.8.0.2").address
        val payload = "HTTP/1.1 200 OK".toByteArray()

        val packet = PacketUtils.buildTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 443,
            dstPort = 49152,
            seqNum = 987654321L,
            ackNum = 123456789L,
            flags = PacketUtils.TCP_PSH or PacketUtils.TCP_ACK,
            payload = payload
        )

        val ipHeader = PacketUtils.parseIpv4Header(packet, packet.size)
        assertNotNull(ipHeader)
        assertEquals(PacketUtils.PROTO_TCP, ipHeader!!.protocol)

        val tcpHeader = PacketUtils.parseTcpHeader(packet, ipHeader.headerLength, ipHeader.totalLength)
        assertNotNull(tcpHeader)
        assertEquals(443, tcpHeader!!.srcPort)
        assertEquals(49152, tcpHeader.dstPort)
        assertEquals(987654321L, tcpHeader.seqNum)
        assertEquals(123456789L, tcpHeader.ackNum)
        assertEquals(true, tcpHeader.isPsh)
        assertEquals(true, tcpHeader.isAck)
        assertEquals(false, tcpHeader.isSyn)
    }

    @Test
    fun buildDnsQueryPacket_encodesValidRfc1035Query() {
        val query = DnsOverTlsResolver.buildDnsQueryPacket("bangladesh.gov.bd", transactionId = 0x1234)
        // Header is 12 bytes + QNAME ("bangladesh" 1+10, "gov" 1+3, "bd" 1+2, root 1 = 19) + 4 bytes QTYPE/QCLASS = 35 bytes
        assertEquals(35, query.size)
        assertEquals(0x12.toByte(), query[0])
        assertEquals(0x34.toByte(), query[1])
        assertEquals(0x01.toByte(), query[5]) // QDCOUNT = 1
        assertEquals(10.toByte(), query[12]) // length of "bangladesh"
        assertEquals(0x01.toByte(), query[query.size - 3]) // QTYPE A
        assertEquals(0x01.toByte(), query[query.size - 1]) // QCLASS IN
    }
}
