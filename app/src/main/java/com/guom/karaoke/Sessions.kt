package com.guom.karaoke

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** 요청한 사람: 호스트(플레이어 페이지·호스트 앱) 또는 동승자 */
sealed interface Actor {
    data object Host : Actor
    data class Guest(val id: String, val nickname: String) : Actor
}

class GuestSession(val secret: String, val publicId: String, @Volatile var nickname: String)

/**
 * 접근 제어 토큰과 동승자 세션.
 *  - hostToken: 호스트 앱이 여는 플레이어 페이지만 안다 (앱에 영구 저장).
 *    같은 폰 크롬에서 동승자 페이지를 열어도 호스트로 취급되지 않도록 IP 가 아닌 토큰으로 구분한다.
 *  - roomToken: 앱 프로세스가 뜰 때마다 새로 발급. 동승자 페이지 주소(QR)에 들어간다.
 *  - 동승자 세션: secret 은 본인 브라우저만 알고, 다른 사람에겐 publicId 만 보인다.
 */
object Sessions {
    private val random = SecureRandom()
    private val bySecret = ConcurrentHashMap<String, GuestSession>()

    @Volatile
    private var initialized = false
    lateinit var hostToken: String
        private set
    lateinit var roomToken: String
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            hostToken = prefs.getString("host_token", null) ?: newToken().also {
                prefs.edit().putString("host_token", it).apply()
            }
            roomToken = newToken(6)
            initialized = true
        }
    }

    fun create(nickname: String): GuestSession {
        val s = GuestSession(newToken(), newToken(6), nickname)
        bySecret[s.secret] = s
        return s
    }

    fun find(secret: String?): GuestSession? = secret?.let { bySecret[it] }

    private fun newToken(bytes: Int = 16): String {
        val b = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** 닉네임 규칙: 앞뒤 공백 제거, 1~12자 */
    fun cleanNickname(raw: String?): String? = raw?.trim()?.take(12)?.takeIf { it.isNotEmpty() }
}
