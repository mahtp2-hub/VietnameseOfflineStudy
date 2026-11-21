
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
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
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
import org.json.JSONArray
import org.json.JSONObject
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

    private val modelName = "ggml-small.bin"
    private val modelUrl =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"

    private var transcriptionStartedAt = 0L
    private var ninetyPercentReachedAt = 0L

    private val transcriptionProgressUpdater =
        object : Runnable {
            override fun run() {
                if (!isWorking) return

                val now = android.os.SystemClock.elapsedRealtime()

                if (progress.progress < 90) {
                    progress.progress =
                        (progress.progress + 1).coerceAtMost(90)

                    if (progress.progress == 90) {
                        ninetyPercentReachedAt = now
                    }

                    status.text =
                        "ベトナム語を文字起こししています...\n" +
                        "進捗目安：${progress.progress}%"
                } else {
                    val elapsedSeconds =
                        ((now - ninetyPercentReachedAt) / 1000).coerceAtLeast(0)

                    val elapsedMinutes = elapsedSeconds / 60
                    val elapsedRemainder = elapsedSeconds % 60

                    val estimatedTotalSeconds = 360L
                    val remainingSeconds =
                        (estimatedTotalSeconds - elapsedSeconds)
                            .coerceAtLeast(0)

                    val remainingText =
                        if (remainingSeconds > 0) {
                            "予想残り時間：約${remainingSeconds / 60}分${remainingSeconds % 60}秒"
                        } else {
                            "予想時間を超過しています。処理は継続中です。"
                        }

                    status.text =
                        "最終認識処理中（進捗率は取得できません）\n" +
                        "90%到達後の経過時間：${elapsedMinutes}分${elapsedRemainder}秒\n" +
                        remainingText
                }

                handler.postDelayed(this, 2000)
            }
        }

    private val playbackUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            if (!player.isPlaying) return

            val position = player.currentPosition.toLong()

            val index = segmentItems.indexOfFirst {
                position >= it.startMs && position < it.endMs
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
            text = "文字起こし結果（単語タップ：単語訳／長押し：文章訳）"
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

        restoreTranscriptLocally()
    }





    private fun createSegmentView(
        text: String,
        start: Long,
        end: Long
    ): TextView {

        val transcriptText = text
        var downX = 0f
        var downY = 0f
        var downTime = 0L

        return TextView(this).apply {
            this.text = transcriptText
            textSize = 18f
            setPadding(12, 14, 12, 14)
            setBackgroundColor(0x00000000)
            setTextIsSelectable(true)

            customSelectionActionModeCallback =
                object : android.view.ActionMode.Callback {

                    override fun onCreateActionMode(
                        mode: android.view.ActionMode,
                        menu: android.view.Menu
                    ): Boolean {
                        menu.add(0, 1001, 0, "選択範囲を翻訳")
                        return true
                    }

                    override fun onPrepareActionMode(
                        mode: android.view.ActionMode,
                        menu: android.view.Menu
                    ): Boolean = false

                    override fun onActionItemClicked(
                        mode: android.view.ActionMode,
                        item: android.view.MenuItem
                    ): Boolean {
                        if (item.itemId != 1001) return false

                        val first = selectionStart
                        val last = selectionEnd

                        if (first >= 0 && last >= 0 && first != last) {
                            val from = minOf(first, last)
                            val to = maxOf(first, last)

                            val selectedText =
                                transcriptText.substring(from, to).trim()

                            if (selectedText.isNotBlank()) {
                                translateSegment(selectedText)
                            }
                        }

                        mode.finish()
                        return true
                    }

                    override fun onDestroyActionMode(
                        mode: android.view.ActionMode
                    ) {
                    }
                }

            setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downTime = event.eventTime
                        false
                    }

                    android.view.MotionEvent.ACTION_UP -> {
                        val duration = event.eventTime - downTime
                        val distance = kotlin.math.hypot(
                            (event.x - downX).toDouble(),
                            (event.y - downY).toDouble()
                        )

                        if (duration < 350 &&
                            distance < android.view.ViewConfiguration
                                .get(context).scaledTouchSlop
                        ) {
                            val offset = getOffsetForPosition(
                                event.x,
                                event.y
                            )

                            val word = Regex(
                                "[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*"
                            ).findAll(transcriptText)
                                .firstOrNull {
                                    offset >= it.range.first &&
                                    offset <= it.range.last
                                }?.value

                            if (!word.isNullOrBlank()) {
                                translateSegment(word)
                            }
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
        }
    }















    private fun saveTranscriptLocally() {
        try {
            val array = JSONArray()

            segmentItems.forEach { segment ->
                array.put(
                    JSONObject().apply {
                        put("start", segment.startMs)
                        put("end", segment.endMs)
                        put("text", segment.view.text.toString())
                    }
                )
            }

            getSharedPreferences(
                "transcript_data",
                MODE_PRIVATE
            ).edit()
                .putString("transcript", resultText.text.toString())
                .putString("segments", array.toString())
                .putBoolean("hasTranscript", true)
                .apply()

        } catch (e: Exception) {
            status.text = "自動保存エラー：${e.message}"
        }
    }

    private fun restoreTranscriptLocally() {
        try {
            val prefs = getSharedPreferences(
                "transcript_data",
                MODE_PRIVATE
            )

            if (!prefs.getBoolean("hasTranscript", false)) {
                return
            }

            val savedText = prefs.getString("transcript", "") ?: ""
            if (savedText.isBlank()) return

            resultText.text = savedText

            segmentItems.clear()
            segmentContainer.removeAllViews()

            val audioFile = File(filesDir, "last_audio.mp3")
            currentAudioFile =
                if (audioFile.exists()) audioFile else null

            val array = JSONArray(
                prefs.getString("segments", "[]") ?: "[]"
            )

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                val start = item.getLong("start")
                val end = item.getLong("end")
                val text = item.getString("text")

                if (text.isNotBlank()) {
                    val view = createSegmentView(text, start, end)

                    segmentItems.add(
                        TranscriptSegment(start, end, view)
                    )

                    segmentContainer.addView(view)
                }
            }

            if (segmentItems.isEmpty()) {
                val fallback = createSegmentView(
                    savedText,
                    0,
                    Long.MAX_VALUE
                )
                segmentContainer.addView(fallback)
            }

            saveButton.isEnabled = true
            playButton.isEnabled =
                currentAudioFile?.exists() == true

            status.text = "前回の文字起こし結果を復元しました。"

        } catch (e: Exception) {
            status.text =
                "保存データの読み込みエラー：${e.message}"
        }
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

                        val segmentView = createSegmentView(
                            text,
                            start,
                            end
                        )

                        segmentItems.add(
                            TranscriptSegment(start, end, segmentView)
                        )

                        segmentContainer.addView(segmentView)
                    }
                }

                if (segmentItems.isEmpty() &&
                    resultText.text.isNotBlank()
                ) {
                    val fallback = createSegmentView(
                        resultText.text.toString(),
                        0,
                        Long.MAX_VALUE
                    )
                    segmentContainer.addView(fallback)
                }

                saveTranscriptLocally()

                saveButton.isEnabled =
                    resultText.text.isNotBlank()

                playButton.isEnabled = audioFile.exists()

                status.text =
                    "文字起こしが完了しました。自動保存しました。\n" +
                    "単語タップ：単語訳／長押し：文章訳"

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
        status.text = "翻訳を開始します..."

        appScope.launch {
            try {
                val translated = translationEngine.translate(source) { message ->
                    runOnUiThread {
                        status.text = message
                    }
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("日本語訳")
                    .setMessage(translated)
                    .setPositiveButton("閉じる", null)
                    .show()

                status.text = "翻訳しました。"

            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                status.text =
                    "翻訳タイムアウト：120秒経過しました。通信状態を確認してください。"

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
