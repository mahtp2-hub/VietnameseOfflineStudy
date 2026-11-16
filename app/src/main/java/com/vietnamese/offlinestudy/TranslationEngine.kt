
package com.vietnamese.offlinestudy

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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

    suspend fun translate(source: String): String {
        val text = source.trim()

        require(text.isNotEmpty()) {
            "翻訳する文章がありません。"
        }

        // 保存済みの翻訳を検索
        val saved = cache.getTranslation(text)

        if (saved != null) {
            return saved
        }

        // 初回はWi-Fiで翻訳モデルをダウンロード
        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        await(translator.downloadModelIfNeeded(conditions))

        // ベトナム語から日本語へ翻訳
        val translated = await(translator.translate(text))

        // 翻訳結果を端末に保存
        cache.saveTranslation(text, translated)

        return translated
    }

    fun close() {
        translator.close()
        cache.close()
    }

    private suspend fun <T> await(task: Task<T>): T {
        return suspendCoroutine { continuation ->
            task.addOnSuccessListener { result ->
                continuation.resume(result)
            }

            task.addOnFailureListener { error ->
                continuation.resumeWithException(error)
            }
        }
    }
}
