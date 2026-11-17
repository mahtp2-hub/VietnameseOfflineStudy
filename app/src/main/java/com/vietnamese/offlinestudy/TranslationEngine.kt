
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslationEngine(context: Context) {

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

        val translated = try {
            withTimeout(60000) {
                await(translator.translate(text))
            }
        } catch (e: Exception) {
            throw Exception(
                "翻訳処理に失敗しました：${e.message ?: e.javaClass.simpleName}",
                e
            )
        }

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

        val downloaded = try {
            withTimeout(30000) {
                await(modelManager.isModelDownloaded(model))
            }
        } catch (e: Exception) {
            throw Exception(
                "${displayName}モデルの状態確認に失敗：${e.message ?: e.javaClass.simpleName}",
                e
            )
        }

        if (downloaded) {
            onStatus("${displayName}モデルはダウンロード済みです。")
            return
        }

        onStatus("${displayName}モデルをダウンロードしています...")

        try {
            withTimeout(120000) {
                await(modelManager.download(model, conditions))
            }
        } catch (e: Exception) {
            throw Exception(
                "${displayName}モデルのダウンロード失敗：${e.message ?: e.javaClass.simpleName}",
                e
            )
        }

        val confirmed = try {
            withTimeout(30000) {
                await(modelManager.isModelDownloaded(model))
            }
        } catch (e: Exception) {
            throw Exception(
                "${displayName}モデルの再確認に失敗：${e.message ?: e.javaClass.simpleName}",
                e
            )
        }

        if (!confirmed) {
            throw Exception(
                "${displayName}モデルのダウンロード後も、端末に保存されたことを確認できません。"
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
