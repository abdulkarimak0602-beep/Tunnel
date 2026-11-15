package com.example.vpn

import java.net.InetAddress

data class Ipv4Header(
    val version: Int,
    val headerLength: Int,
    val totalLength: Int,
    val protocol: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray
)

data class TcpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val seqNum: Long,
    val ackNum: Long,
    val headerLength: Int,
    val flags: Int,
    val windowSize: Int
) {
    val isFin: Boolean get() = (flags and 0x01) != 0
    val isSyn: Boolean get() = (flags and 0x02) != 0
    val isRst: Boolean get() = (flags and 0x04) != 0
    val isPsh: Boolean get() = (flags and 0x08) != 0
    val isAck: Boolean get() = (flags and 0x10) != 0
}

data class UdpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val length: Int
)

object PacketUtils {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    fun parseIpv4Header(packet: ByteArray, length: Int): Ipv4Header? {
        if (length < 20) return null
        val verIhl = packet[0].toInt() and 0xFF
        val version = verIhl ushr 4
        if (version != 4) return null
        val ihl = (verIhl and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null
        val totalLength = readUInt16(packet, 2).coerceAtMost(length)
        val protocol = packet[9].toInt() and 0xFF
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        return Ipv4Header(version, ihl, totalLength, protocol, srcIp, dstIp)
    }

    fun parseUdpHeader(packet: ByteArray, ipHeaderLen: Int, totalLength: Int): UdpHeader? {
        if (totalLength < ipHeaderLen + 8) return null
        val srcPort = readUInt16(packet, ipHeaderLen)
        val dstPort = readUInt16(packet, ipHeaderLen + 2)
        val udpLen = readUInt16(packet, ipHeaderLen + 4)
        return UdpHeader(srcPort, dstPort, udpLen)
    }

    fun parseTcpHeader(packet: ByteArray, ipHeaderLen: Int, totalLength: Int): TcpHeader? {
        if (totalLength < ipHeaderLen + 20) return null
        val srcPort = readUInt16(packet, ipHeaderLen)
        val dstPort = readUInt16(packet, ipHeaderLen + 2)
        val seqNum = readUInt32(packet, ipHeaderLen + 4)
        val ackNum = readUInt32(packet, ipHeaderLen + 8)
        val dataOffset = ((packet[ipHeaderLen + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffset < 20 || totalLength < ipHeaderLen + dataOffset) return null
        val flags = packet[ipHeaderLen + 13].toInt() and 0x3F
        val windowSize = readUInt16(packet, ipHeaderLen + 14)
        return TcpHeader(srcPort, dstPort, seqNum, ackNum, dataOffset, flags, windowSize)
    }

    fun buildUdpResponsePacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + payload.size
        val totalLen = ipHeaderLen + udpLen
        val packet = ByteArray(totalLen)

        writeIpv4Header(packet, totalLen, PROTO_UDP, srcIp, dstIp)

        writeUInt16(packet, ipHeaderLen, srcPort)
        writeUInt16(packet, ipHeaderLen + 2, dstPort)
        writeUInt16(packet, ipHeaderLen + 4, udpLen)
        System.arraycopy(payload, 0, packet, ipHeaderLen + 8, payload.size)

        val udpChecksum = computeTransportChecksum(srcIp, dstIp, PROTO_UDP, packet, ipHeaderLen, udpLen)
        writeUInt16(packet, ipHeaderLen + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return packet
    }

    fun buildTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        windowSize: Int = 65535,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val tcpTotalLen = tcpHeaderLen + payload.size
        val totalLen = ipHeaderLen + tcpTotalLen
        val packet = ByteArray(totalLen)

        writeIpv4Header(packet, totalLen, PROTO_TCP, srcIp, dstIp)

        writeUInt16(packet, ipHeaderLen, srcPort)
        writeUInt16(packet, ipHeaderLen + 2, dstPort)
        writeUInt32(packet, ipHeaderLen + 4, seqNum)
        writeUInt32(packet, ipHeaderLen + 8, ackNum)
        packet[ipHeaderLen + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
        packet[ipHeaderLen + 13] = (flags and 0x3F).toByte()
        writeUInt16(packet, ipHeaderLen + 14, windowSize)
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, ipHeaderLen + tcpHeaderLen, payload.size)
        }

        val tcpChecksum = computeTransportChecksum(srcIp, dstIp, PROTO_TCP, packet, ipHeaderLen, tcpTotalLen)
        writeUInt16(packet, ipHeaderLen + 16, tcpChecksum)
        return packet
    }

    fun ipBytesToString(ip: ByteArray): String =
        InetAddress.getByAddress(ip).hostAddress ?: "0.0.0.0"

    private fun writeIpv4Header(
        packet: ByteArray,
        totalLen: Int,
        protocol: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ) {
        packet[0] = 0x45.toByte()
        packet[1] = 0x00
        writeUInt16(packet, 2, totalLen)
        writeUInt16(packet, 4, (System.nanoTime() and 0xFFFF).toInt())
        packet[6] = 0x40
        packet[7] = 0x00
        packet[8] = 64
        packet[9] = protocol.toByte()
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)
        val ipChecksum = computeChecksum(packet, 0, 20)
        writeUInt16(packet, 10, ipChecksum)
    }

    fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun computeTransportChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        packet: ByteArray,
        transportOffset: Int,
        transportLength: Int
    ): Int {
        var sum = 0L
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += protocol.toLong()
        sum += transportLength.toLong()

        var i = transportOffset
        val end = transportOffset + transportLength
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    fun writeUInt32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
