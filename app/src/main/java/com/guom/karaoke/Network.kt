package com.guom.karaoke

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 동승자 접속 주소용 IP 찾기. 안드로이드 11+ 는 핫스팟 서브넷이 고정이 아니라 실행 시 조회한다.
 * 핫스팟 인터페이스 이름은 기기마다 다르다 (삼성 swlan0, 그 외 ap0·softap0·wlan1 등).
 */
object Network {
    data class Address(val iface: String, val ip: String)

    private val EXCLUDED = listOf("rmnet", "ccmni", "dummy", "tun", "v4-", "p2p", "ip6tnl", "lo")

    fun candidates(): List<Address> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && EXCLUDED.none { p -> it.name.startsWith(p) } }
                .flatMap { ni ->
                    ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .mapNotNull { a -> a.hostAddress?.let { Address(ni.name, it) } }
                }
                .sortedBy { rank(it.iface) }
        }.getOrDefault(emptyList())

    fun isHotspotInterface(name: String) =
        name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap")

    /** 핫스팟 인터페이스에 IP 가 붙어 있으면 켜진 것으로 본다 */
    fun hotspotActive(): Boolean = candidates().any { isHotspotInterface(it.iface) }

    private fun rank(name: String) = when {
        isHotspotInterface(name) -> 0
        name.startsWith("wlan") -> 1
        name.startsWith("rndis") || name.startsWith("usb") -> 2
        else -> 3
    }
}
