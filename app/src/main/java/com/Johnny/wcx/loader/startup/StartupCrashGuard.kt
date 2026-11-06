package com.Johnny.wcx.loader.startup

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import java.io.File
import kotlin.io.path.createDirectories

/**
 * 连续启动失败的自愈守卫。
 *
 * 场景：某个 feature / hook 让微信在启动阶段必崩，用户就再也进不去微信（只能进管理器停用模块）。
 *
 * 做法：
 *  1. 主进程每次启动先给 [attemptsFile] 计数 +1（写在模块外部数据目录，微信 uid 可写）；
 *  2. 启动成功并稳定运行 [HEALTHY_DELAY_MS] 后清零；
 *  3. root 侧 `service.sh`（开机 late_start service）发现计数 ≥ [DISABLE_THRESHOLD]
 *     就写模块目录的 `disable`，下次启动 Zygisk 与本模块注入器都会跳过，微信恢复可用，
 *     同时把原因记到 `self-heal.log`。
 *
 * 计数放在 `/data/media/0/...`（= /storage/emulated/0/...）：微信自己的 data 目录更安全，
 * 但 service.sh 在开机早期就能读到 sdcard 的 backing 目录，这里用 KnownPaths.moduleData。
 */
object StartupCrashGuard {

    private const val TAG = "StartupCrashGuard"

    /** 连续失败几次就自动停用。 */
    private const val DISABLE_THRESHOLD = 3

    /** 启动后稳定运行这么久才算「健康」。 */
    private const val HEALTHY_DELAY_MS = 30_000L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val selfHealDir: File by lazy {
        File(KnownPaths.moduleData.toFile(), "selfheal").also { it.mkdirs() }
    }

    val attemptsFile: File get() = File(selfHealDir, "attempts")

    private var healthyScheduled = false

    /** 主进程启动时调用（每次进程启动一次）。 */
    fun onMainProcessStart() {
        runCatching {
            selfHealDir.toPath().createDirectories()
            val attempts = readAttempts() + 1
            attemptsFile.writeText(attempts.toString())
            File(selfHealDir, "last-attempt").writeText(System.currentTimeMillis().toString())
            WeLogger.i(TAG, "startup attempt #$attempts（连续失败 ≥ $DISABLE_THRESHOLD 次会被 service.sh 自动停用）")
        }.onFailure { WeLogger.w(TAG, "无法记录启动次数", it) }
    }

    /** 启动完成后调用：稳定运行一段时间就把计数清零。 */
    fun scheduleHealthyReset() {
        if (healthyScheduled) return
        healthyScheduled = true
        mainHandler.postDelayed({
            runCatching {
                if (readAttempts() == 0) return@runCatching
                attemptsFile.writeText("0")
                File(selfHealDir, "last-healthy").writeText(System.currentTimeMillis().toString())
                WeLogger.i(TAG, "启动已稳定，连续失败计数清零")
            }.onFailure { WeLogger.w(TAG, "复位启动次数失败", it) }
        }, HEALTHY_DELAY_MS)
    }

    /** 当前记录的连续启动次数（诊断用）。 */
    fun readAttempts(): Int =
        runCatching { attemptsFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0 }
            .getOrDefault(0)

    /** 给 WebUI / 诊断页用的描述。 */
    fun describe(context: Context): String = runCatching {
        "attempts=${readAttempts()} threshold=$DISABLE_THRESHOLD dir=${selfHealDir.absolutePath}"
    }.getOrElse { "unavailable" }
}
