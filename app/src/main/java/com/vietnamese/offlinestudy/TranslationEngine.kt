
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
            return saved
        }

        return withTimeout(120000) {

            onStatus("翻訳モデルを確認しています...")

            val conditions = DownloadConditions.Builder()
                .requireWifi()
                .build()

            await(translator.downloadModelIfNeeded(conditions))

            onStatus("翻訳モデルの準備完了。日本語に翻訳しています...")

            val translated = await(translator.translate(text))

            cache.saveTranslation(text, translated)

            translated
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
        }
    }
}
