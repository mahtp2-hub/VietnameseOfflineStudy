
package com.vietnamese.offlinestudy

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import android.media.MediaPlayer
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

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var resultText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var selectButton: Button
    private lateinit var saveButton: Button
    private lateinit var playButton: Button
    private lateinit var segmentContainer: LinearLayout
    private lateinit var translationEngine: TranslationEngine

    private var isWorking = false
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFile: File? = null
    private var segmentStopAtMs: Long? = null
    private var activeSegmentIndex = -1

    private data class TranscriptSegment(
        val startMs: Long,
        val endMs: Long,
        val view: TextView
    )

    private val segmentItems =
        mutableListOf<TranscriptSegment>()

    private val modelName = "ggml-base.bin"
    private val modelUrl =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"

    private val transcriptionProgressUpdater =
        object : Runnable {
            override fun run() {
                if (!isWorking) return

                if (progress.progress < 90) {
                    progress.progress =
                        (progress.progress + 1).coerceAtMost(90)
                }

                status.text =
                    "ベトナム語を文字起こししています...\n" +
                    "進捗目安：${progress.progress}%（実際の認識率ではありません）"

                handler.postDelayed(this, 2000)
            }
        }

    private val playbackUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return

            if (!player.isPlaying) return

            val position = player.currentPosition.toLong()

            val index = segmentItems.indexOfFirst {
                position >= it.startMs &&
                position < it.endMs
            }

            setActiveSegment(index)

            val stopAt = segmentStopAtMs

            if (stopAt != null && position >= stopAt) {
                player.pause()
                segmentStopAtMs = null
                playButton.text = "▶ 音声を再生"
                return
            }

            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        translationEngine = TranslationEngine(this)

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

        playButton = Button(this).apply {
            text = "▶ 音声を再生"
            isEnabled = false
            setOnClickListener {
                togglePlayback()
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
            text = "文字起こし結果（タップ：再生／長押し：日本語訳）"
            textSize = 17f
            setPadding(0, 20, 0, 8)
        }

        resultText = TextView(this).apply {
            visibility = View.GONE
        }

        segmentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val resultScroll = ScrollView(this).apply {
            addView(segmentContainer)
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(progress)
        layout.addView(selectButton)
        layout.addView(playButton)
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
        releasePlayer()

        handler.removeCallbacks(transcriptionProgressUpdater)

        segmentItems.clear()
        segmentContainer.removeAllViews()

        resultText.text = ""
        saveButton.isEnabled = false
        playButton.isEnabled = false
        selectButton.isEnabled = false

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        progress.progress = 0

        appScope.launch {

            try {
                status.text = "音声ファイルを準備しています..."

                val audioFile = withContext(Dispatchers.IO) {
                    val file = File(filesDir, "last_audio.mp3")

                    val input = contentResolver.openInputStream(uri)
                        ?: error("音声ファイルを開けません。")

                    input.use { source ->
                        file.outputStream().use { output ->
                            source.copyTo(output)
                        }
                    }

                    file
                }

                currentAudioFile = audioFile

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

                progress.isIndeterminate = false
                progress.progress = 0

                status.text =
                    "ベトナム語を文字起こししています...\n" +
                    "進捗目安：0%（実際の認識率ではありません）"

                handler.removeCallbacks(transcriptionProgressUpdater)
                handler.postDelayed(
                    transcriptionProgressUpdater,
                    2000
                )

                val result = withContext(Dispatchers.Default) {

                    val model = Whisper.loadModel(
                        this@MainActivity,
                        modelFile.absolutePath
                    )

                    try {
                        Whisper.transcribe(
                            model,
                            audioFile.absolutePath,
                            WhisperConfig(language = "vi")
                        )
                    } finally {
                        Whisper.releaseModel(model)
                    }
                }

                handler.removeCallbacks(transcriptionProgressUpdater)
                progress.progress = 100

                status.text = "文字起こし結果を整理しています..."

                resultText.text = result.text.trim()

                result.segments.forEach { s ->
                    val text = s.text.trim()

                    if (text.isNotEmpty()) {
                        val start = s.startMs.toLong()
                        val end = s.endMs.toLong()
                            .coerceAtLeast(start + 1)

                        val segmentView = TextView(this@MainActivity).apply {
                            this.text = text
                            textSize = 18f
                            setPadding(12, 14, 12, 14)
                            setBackgroundColor(0x00000000)
                            isClickable = true

                            setOnClickListener {
                                playSegment(start, end)
                            }

                            setOnLongClickListener {
                                translateSegment(text)
                                true
                            }
                        }

                        segmentItems.add(
                            TranscriptSegment(
                                start,
                                end,
                                segmentView
                            )
                        )

                        segmentContainer.addView(segmentView)
                    }
                }

                if (segmentItems.isEmpty() &&
                    resultText.text.isNotBlank()
                ) {
                    val fallback = TextView(this@MainActivity).apply {
                        text = resultText.text
                        textSize = 18f
                        setPadding(12, 14, 12, 14)

                        setOnClickListener {
                            playSegment(0, Long.MAX_VALUE)
                        }

                        setOnLongClickListener {
                            translateSegment(
                                resultText.text.toString()
                            )
                            true
                        }
                    }
                    segmentContainer.addView(fallback)
                }

                saveButton.isEnabled =
                    resultText.text.isNotBlank()

                playButton.isEnabled =
                    audioFile.exists()

                status.text =
                    "文字起こしが完了しました。\n" +
                    "タップ：再生／長押し：日本語訳"

            } catch (e: Exception) {

                status.text =
                    "エラー：${e.message ?: e.javaClass.simpleName}"

            } finally {

                handler.removeCallbacks(transcriptionProgressUpdater)

                isWorking = false
                selectButton.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun translateSegment(source: String) {
        status.text = "日本語に翻訳しています..."

        appScope.launch {
            try {
                val translated = translationEngine.translate(source)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("日本語訳")
                    .setMessage(translated)
                    .setPositiveButton("閉じる", null)
                    .show()

                status.text = "翻訳しました。"

            } catch (e: Exception) {
                status.text =
                    "翻訳エラー：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun togglePlayback() {
        try {
            val player = getPlayer() ?: return

            if (player.isPlaying) {
                player.pause()
                handler.removeCallbacks(playbackUpdater)
                playButton.text = "▶ 音声を再生"
            } else {
                segmentStopAtMs = null
                player.start()
                playButton.text = "⏸ 一時停止"
                handler.removeCallbacks(playbackUpdater)
                handler.post(playbackUpdater)
            }

        } catch (e: Exception) {
            status.text = "再生エラー：${e.message}"
        }
    }

    private fun playSegment(startMs: Long, endMs: Long) {
        try {
            val player = getPlayer() ?: return

            segmentStopAtMs =
                if (endMs == Long.MAX_VALUE) null else endMs

            player.seekTo(startMs.toInt().coerceAtLeast(0))
            player.start()

            playButton.text = "⏸ 一時停止"

            handler.removeCallbacks(playbackUpdater)
            handler.post(playbackUpdater)

        } catch (e: Exception) {
            status.text = "再生エラー：${e.message}"
        }
    }

    private fun getPlayer(): MediaPlayer? {
        if (mediaPlayer == null) {
            val file = currentAudioFile ?: return null

            if (!file.exists()) {
                status.text = "音声ファイルが見つかりません。"
                return null
            }

            val player = MediaPlayer()

            player.setDataSource(file.absolutePath)
            player.prepare()

            player.setOnCompletionListener {
                handler.removeCallbacks(playbackUpdater)
                segmentStopAtMs = null
                playButton.text = "▶ 音声を再生"
                setActiveSegment(-1)
                it.seekTo(0)
            }

            mediaPlayer = player
        }

        return mediaPlayer
    }

    private fun setActiveSegment(index: Int) {
        if (activeSegmentIndex == index) return

        activeSegmentIndex = index

        segmentItems.forEachIndexed { i, segment ->
            segment.view.setBackgroundColor(
                if (i == index) 0xFFE3F2FD.toInt()
                else 0x00000000
            )
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(playbackUpdater)
        mediaPlayer?.release()
        mediaPlayer = null
        segmentStopAtMs = null
        activeSegmentIndex = -1
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
        handler.removeCallbacks(transcriptionProgressUpdater)
        releasePlayer()
        translationEngine.close()
        appScope.cancel()
        super.onDestroy()
    }
}
