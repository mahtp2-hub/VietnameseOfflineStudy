
package com.vietnamese.offlinestudy

import android.content.Context
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

    private val cache = TranslationCache(context)
    private val translator: Translator
    private val modelManager = RemoteModelManager.getInstance()

    // ダウンロード中の処理を保持する
    private val downloadTasks = mutableMapOf<String, Task<Void>>()

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

        // 既存のダウンロードがあれば再利用する
        val downloadTask = synchronized(downloadTasks) {
            downloadTasks[language]
                ?: modelManager.download(model, conditions).also {
                    downloadTasks[language] = it
                }
        }

        onStatus("${displayName}モデルのダウンロードを開始・継続します...")

        try {
            for (second in 1..30) {
                if (downloadTask.isComplete) {
                    break
                }

                onStatus(
                    "${displayName}モデルをダウンロード中です。\n" +
                    "経過時間：${second}秒\n" +
                    "ダウンロード処理は継続しています。"
                )

                delay(1000)
            }

            if (!downloadTask.isComplete) {
                throw Exception(
                    "${displayName}モデルのダウンロードはまだ継続中です。\n" +
                    "処理は停止していません。\n" +
                    "少し時間をおいてから、もう一度翻訳を試してください。"
                )
            }

            // 完了した処理の成功・失敗を確認する
            await(downloadTask)

            synchronized(downloadTasks) {
                if (downloadTasks[language] === downloadTask) {
                    downloadTasks.remove(language)
                }
            }

            val confirmed = await(
                modelManager.isModelDownloaded(model)
            )

            if (!confirmed) {
                throw Exception(
                    "${displayName}モデルのダウンロード後、保存を確認できません。"
                )
            }

            onStatus("${displayName}モデルの準備が完了しました。")

        } catch (error: Exception) {

            // まだ実行中なら保持し、失敗・完了済みなら次回に備えて解除
            if (downloadTask.isComplete) {
                synchronized(downloadTasks) {
                    if (downloadTasks[language] === downloadTask) {
                        downloadTasks.remove(language)
                    }
                }
            }

            throw error
        }
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
