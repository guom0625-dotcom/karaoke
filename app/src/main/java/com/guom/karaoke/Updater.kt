package com.guom.karaoke

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 기반 자동 업데이트.
 * 최신 릴리스 태그(v1.0.<run_number>)의 마지막 숫자 = versionCode 로 비교하고,
 * APK 를 캐시에 받아 PackageInstaller 세션으로 설치를 요청한다 (시스템 확인 화면이 뜬다).
 * 서명 키가 고정이어야 덮어쓰기 설치가 된다.
 */
object Updater {
    private const val REPO = "guom0625-dotcom/karaoke"
    private val TAG = Regex("^v\\d+\\.\\d+\\.(\\d+)$")

    data class Release(val versionCode: Long, val versionName: String, val notes: String, val apkUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data object WaitingForUser : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** 비인증 GitHub API 는 IP당 시간당 60회 → 앱 실행 시 1회 + 수동 확인만 한다. */
    suspend fun check(context: Context) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        _state.value = try {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            when {
                release == null -> State.Failed("릴리스 정보를 읽을 수 없어요")
                release.versionCode > currentVersionCode(context) -> State.Available(release)
                else -> State.UpToDate
            }
        } catch (e: Exception) {
            State.Failed("업데이트 확인 실패: ${e.message}")
        }
    }

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "karaoke-app")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val json = AppJson.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val tag = json["tag_name"]?.jsonPrimitive?.content ?: return null
            val code = TAG.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val apkUrl = json["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.jsonPrimitive?.content
                ?: return null
            val notes = json["body"]?.jsonPrimitive?.content.orEmpty().trim()
            return Release(code, tag.removePrefix("v"), notes, apkUrl)
        } finally {
            conn.disconnect()
        }
    }

    /** APK 다운로드 후 설치 세션 커밋. 설치 확인 화면은 [InstallResultReceiver] 가 띄운다. */
    suspend fun downloadAndInstall(context: Context, release: Release) {
        val app = context.applicationContext
        try {
            val apk = withContext(Dispatchers.IO) { download(app, release) }
            withContext(Dispatchers.IO) { install(app, apk) }
            _state.value = State.WaitingForUser
        } catch (e: Exception) {
            _state.value = State.Failed("업데이트 실패: ${e.message}")
        }
    }

    private fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "karaoke-${release.versionName}.apk")

        // GitHub 에셋 URL 은 https 간 리다이렉트 → HttpURLConnection 이 따라간다.
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "karaoke-app")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        val percent = if (total > 0) (read * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.value = State.Downloading(release, percent)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java), flags
            )
            session.commit(pi.intentSender)
        }
    }

    internal fun onInstallFailed(message: String?) {
        _state.value = State.Failed("설치 실패: ${message ?: "알 수 없는 오류"}")
    }
}

/** PackageInstaller 세션 결과. 사용자 확인이 필요하면 시스템 설치 화면을 띄운다. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // 설치되면 앱 프로세스가 교체된다
            else -> Updater.onInstallFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
