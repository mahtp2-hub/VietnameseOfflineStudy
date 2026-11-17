package com.vietnamese.offlinestudy

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslationEngine(context: Context) {

    private val cache = TranslationCache(context)
    private val translator: Translator

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

        onStatus("翻訳モデルを確認しています...")

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        try {
            withTimeout(120000) {
                await(translator.downloadModelIfNeeded(conditions))
            }
        } catch (e: Exception) {
            throw Exception(
                "翻訳モデルの準備に失敗しました：${e.message ?: e.javaClass.simpleName}",
                e
            )
        }

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