
package com.vietnamese.offlinestudy

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val appScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var status: TextView
    private lateinit var resultText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var selectButton: Button
    private lateinit var saveButton: Button

    private var isWorking = false

    private val modelName = "ggml-base.bin"
    private val modelUrl =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "ベトナム語 オフライン学習"
            textSize = 23f
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "MP3ファイルを選択してください"
            textSize = 15f
            setPadding(0, 24, 0, 16)
        }

        progress = ProgressBar(
            this, null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            visibility = View.GONE
        }

        selectButton = Button(this).apply {
            text = "MP3を選択して文字起こし"
            setOnClickListener {
                if (!isWorking) {
                    val intent =
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "audio/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    startActivityForResult(intent, 100)
                }
            }
        }

        saveButton = Button(this).apply {
            text = "文字起こし結果をTXT保存"
            isEnabled = false
            setOnClickListener {
                val text = resultText.text.toString()

                if (text.isNotBlank()) {
                    val intent =
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TITLE,
                                "Vietnamese_transcription.txt"
                            )
                        }
                    startActivityForResult(intent, 101)
                }
            }
        }

        val resultTitle = TextView(this).apply {
            text = "文字起こし結果"
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }

        resultText = TextView(this).apply {
            text = "ここにベトナム語の文章が表示されます。"
            textSize = 17f
            setTextIsSelectable(true)
            setPadding(12, 12, 12, 12)
        }

        val resultScroll = ScrollView(this).apply {
            addView(resultText)
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(progress)
        layout.addView(selectButton)
        layout.addView(saveButton)
        layout.addView(resultTitle)

        layout.addView(
            resultScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(layout)
    }

    @Deprecated("Use Activity Result API in a future version")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100 && resultCode == RESULT_OK) {
            val uri = data?.data

            if (uri == null) {
                status.text = "ファイルを選択できませんでした。"
            } else {
                transcribe(uri)
            }

        } else if (requestCode == 101 && resultCode == RESULT_OK) {
            val uri = data?.data

            if (uri == null) {
                status.text = "保存先を選択できませんでした。"
            } else {
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(
                            resultText.text.toString()
                                .toByteArray(Charsets.UTF_8)
                        )
                    } ?: error("保存先を開けません。")

                    status.text = "TXTファイルを保存しました。"

                } catch (e: Exception) {
                    status.text =
                        "保存エラー：${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun transcribe(uri: Uri) {

        isWorking = true
        selectButton.isEnabled = false
        saveButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true

        appScope.launch {

            var audioFile: File? = null

            try {
                status.text = "音声ファイルを準備しています..."

                audioFile = withContext(Dispatchers.IO) {
                    val file = File(
                        cacheDir,
                        "audio_${System.currentTimeMillis()}.mp3"
                    )

                    val input = contentResolver.openInputStream(uri)
                        ?: error("音声ファイルを開けません。")

                    input.use { source ->
                        file.outputStream().use { output ->
                            source.copyTo(output)
                        }
                    }

                    file
                }

                val modelFile = File(
                    filesDir,
                    "models/$modelName"
                )

                if (!modelFile.exists() ||
                    modelFile.length() < 100_000_000L
                ) {
                    status.text =
                        "初回設定：認識モデルをダウンロード中..."

                    progress.isIndeterminate = false
                    progress.progress = 0

                    withContext(Dispatchers.IO) {
                        downloadModel(modelFile)
                    }
                }

                status.text = "音声認識モデルを読み込んでいます..."

                val model = Whisper.loadModel(
                    this@MainActivity,
                    modelFile.absolutePath
                )

                try {
                    status.text =
                        "ベトナム語を文字起こししています...\n" +
                        "音声の長さによって数分かかります。"

                    val result = Whisper.transcribe(
                        model,
                        audioFile.absolutePath,
                        WhisperConfig(language = "vi")
                    )

                    resultText.text = result.text.trim().ifEmpty {
                        "音声を認識できませんでした。"
                    }

                    saveButton.isEnabled =
                        resultText.text.toString().isNotBlank() &&
                        resultText.text.toString() !=
                        "音声を認識できませんでした。"

                    status.text = "文字起こしが完了しました。"

                } finally {
                    Whisper.releaseModel(model)
                }

            } catch (e: Exception) {

                status.text =
                    "エラー：${e.message ?: e.javaClass.simpleName}"

            } finally {

                audioFile?.delete()
                isWorking = false
                selectButton.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun downloadModel(destination: File) {

        destination.parentFile?.mkdirs()

        val tempFile = File(
            destination.parentFile,
            destination.name + ".download"
        )

        val connection =
            URL(modelUrl).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.connect()

            check(connection.responseCode in 200..299) {
                "モデルのダウンロードに失敗しました。"
            }

            val total = connection.contentLengthLong

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->

                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        downloaded += count

                        if (total > 0) {
                            val percent =
                                (downloaded * 100 / total)
                                    .toInt().coerceIn(0, 100)

                            runOnUiThread {
                                progress.progress = percent
                                status.text =
                                    "認識モデルをダウンロード中：$percent%"
                            }
                        }
                    }
                }
            }

            check(tempFile.length() > 100_000_000L) {
                "モデルが完全にダウンロードされていません。"
            }

            if (destination.exists()) {
                destination.delete()
            }

            check(tempFile.renameTo(destination)) {
                "モデルを保存できませんでした。"
            }

        } finally {
            connection.disconnect()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()
    }
}
