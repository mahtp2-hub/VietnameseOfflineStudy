
package com.vietnamese.offlinestudy

import android.content.Context
import android.net.TrafficStats
import android.os.StatFs
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslationEngine(context: Context) {

    private val appContext = context.applicationContext
    private val cache = TranslationCache(context)
    private val translator: Translator
    private val modelManager = RemoteModelManager.getInstance()

    init {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.VIETNAMESE)
            .setTargetLanguage(TranslateLanguage.JAPANESE)
            .build()

        translator = Translation.getClient(options)
    }

    suspend fun translate(
        source: String,
        onStatus: (String) -> Unit = {}
    ): String {

        val text = source.trim()

        require(text.isNotEmpty()) {
            "翻訳する文章がありません。"
        }

        val saved = cache.getTranslation(text)
        if (saved != null) {
            onStatus("保存済みの翻訳を表示します...")
            return saved
        }

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        ensureModel(
            TranslateLanguage.VIETNAMESE,
            "ベトナム語",
            conditions,
            onStatus
        )

        ensureModel(
            TranslateLanguage.JAPANESE,
            "日本語",
            conditions,
            onStatus
        )

        onStatus("翻訳を実行しています...")

        val translated = await(translator.translate(text))

        onStatus("翻訳結果を保存しています...")
        cache.saveTranslation(text, translated)

        return translated
    }

    private suspend fun ensureModel(
        language: String,
        displayName: String,
        conditions: DownloadConditions,
        onStatus: (String) -> Unit
    ) {
        val model = TranslateRemoteModel.Builder(language).build()

        onStatus("${displayName}モデルの状態を確認しています...")

        val downloaded = await(
            modelManager.isModelDownloaded(model)
        )

        if (downloaded) {
            onStatus("${displayName}モデルは準備済みです。")
            return
        }

        onStatus("${displayName}モデルのダウンロードを開始します...")

        val startRx = TrafficStats.getTotalRxBytes()
        val startAvailable =
            StatFs(appContext.filesDir.absolutePath).availableBytes

        val downloadTask = modelManager.download(model, conditions)

        var previousRx = startRx
        var previousAvailable = startAvailable
        var changed = false

        for (second in 1..30) {
            delay(1000)

            val currentRx = TrafficStats.getTotalRxBytes()
            val currentAvailable =
                StatFs(appContext.filesDir.absolutePath).availableBytes

            if (currentRx > startRx ||
                currentAvailable < startAvailable
            ) {
                changed = true
            }

            val received =
                if (startRx >= 0 && currentRx >= startRx) {
                    (currentRx - startRx) / 1024.0 / 1024.0
                } else {
                    -1.0
                }

            val storageUsed =
                if (currentAvailable <= startAvailable) {
                    (startAvailable - currentAvailable) /
                        1024.0 / 1024.0
                } else {
                    0.0
                }

            val rxText =
                if (received >= 0) {
                    String.format("%.2f MB", received)
                } else {
                    "取得不可"
                }

            val storageText =
                String.format("%.2f MB", storageUsed)

            onStatus(
                "${displayName}モデルをダウンロード中\n" +
                "経過時間：${second}秒\n" +
                "端末全体の受信量：$rxText\n" +
                "ストレージ使用増加：$storageText"
            )

            if (downloadTask.isComplete) {
                break
            }

            previousRx = currentRx
            previousAvailable = currentAvailable
        }

        if (!downloadTask.isComplete) {
            val currentRx = TrafficStats.getTotalRxBytes()
            val currentAvailable =
                StatFs(appContext.filesDir.absolutePath).availableBytes

            val received =
                if (startRx >= 0 && currentRx >= startRx) {
                    (currentRx - startRx) / 1024.0 / 1024.0
                } else {
                    -1.0
                }

            val storageUsed =
                if (currentAvailable <= startAvailable) {
                    (startAvailable - currentAvailable) /
                        1024.0 / 1024.0
                } else {
                    0.0
                }

            val rxText =
                if (received >= 0) {
                    String.format("%.2f MB", received)
                } else {
                    "取得不可"
                }

            val storageText =
                String.format("%.2f MB", storageUsed)

            throw Exception(
                "${displayName}モデルのダウンロードが30秒で完了しませんでした。\n" +
                "端末全体の受信量：$rxText\n" +
                "ストレージ使用増加：$storageText\n" +
                "※どちらも翻訳モデル専用の数値ではありません。"
            )
        }

        await(downloadTask)

        val confirmed = await(
            modelManager.isModelDownloaded(model)
        )

        if (!confirmed) {
            throw Exception(
                "${displayName}モデルのダウンロード後、保存を確認できません。"
            )
        }

        onStatus("${displayName}モデルの準備が完了しました。")
    }

    fun close() {
        translator.close()
        cache.close()
    }

    private suspend fun <T> await(task: Task<T>): T {
        return suspendCancellableCoroutine { continuation ->

            task.addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            task.addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            task.addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.cancel()
                }
            }
        }
    }
}
