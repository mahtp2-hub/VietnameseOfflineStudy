
package com.vietnamese.offlinestudy

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
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

    // ダウンロード処理を保持する
    private var downloadTask: Task<Void>? = null
    private val downloadLock = Any()

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

        ensureModels(conditions, onStatus)

        onStatus("翻訳を実行しています...")

        val translated = await(translator.translate(text))

        onStatus("翻訳結果を保存しています...")
        cache.saveTranslation(text, translated)

        return translated
    }

    private suspend fun ensureModels(
        conditions: DownloadConditions,
        onStatus: (String) -> Unit
    ) {

        val viModel =
            TranslateRemoteModel.Builder(
                TranslateLanguage.VIETNAMESE
            ).build()

        val jaModel =
            TranslateRemoteModel.Builder(
                TranslateLanguage.JAPANESE
            ).build()

        onStatus("翻訳モデルの保存状態を確認しています...")

        val viReady = await(
            modelManager.isModelDownloaded(viModel)
        )

        val jaReady = await(
            modelManager.isModelDownloaded(jaModel)
        )

        if (viReady && jaReady) {
            onStatus("翻訳モデルは準備済みです。")
            return
        }

        val task = synchronized(downloadLock) {
            downloadTask ?: translator
                .downloadModelIfNeeded(conditions)
                .also {
                    downloadTask = it
                }
        }

        try {

            onStatus("Google ML Kitの翻訳モデルを準備しています...")

            for (second in 1..30) {

                if (task.isComplete) {
                    break
                }

                onStatus(
                    "翻訳モデルをダウンロード中です。\n" +
                    "経過時間：${second}秒\n" +
                    "Google ML Kitの応答を待っています。"
                )

                delay(1000)
            }

            if (!task.isComplete) {
                throw Exception(
                    "30秒経過しましたが、Google ML Kitから完了通知がありません。\n" +
                    "ダウンロード処理は保持しています。"
                )
            }

            // 成功・失敗を実際のTaskから取得
            await(task)

            val viConfirmed = await(
                modelManager.isModelDownloaded(viModel)
            )

            val jaConfirmed = await(
                modelManager.isModelDownloaded(jaModel)
            )

            if (!viConfirmed || !jaConfirmed) {
                throw Exception(
                    "ダウンロード処理は成功しましたが、モデル保存を確認できません。\n" +
                    "ベトナム語モデル：${if (viConfirmed) "保存済み" else "未確認"}\n" +
                    "日本語モデル：${if (jaConfirmed) "保存済み" else "未確認"}"
                )
            }

            onStatus("ベトナム語・日本語モデルの準備が完了しました。")

            synchronized(downloadLock) {
                if (downloadTask === task) {
                    downloadTask = null
                }
            }

        } catch (error: Exception) {

            if (task.isComplete) {
                synchronized(downloadLock) {
                    if (downloadTask === task) {
                        downloadTask = null
                    }
                }
            }

            if (error is MlKitException) {
                throw Exception(
                    "Google ML Kitエラー\n" +
                    "エラーコード：${error.errorCode}\n" +
                    "エラー種別：${error.javaClass.simpleName}\n" +
                    "詳細：${error.message ?: "詳細なし"}",
                    error
                )
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
