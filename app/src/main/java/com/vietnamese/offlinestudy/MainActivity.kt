
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
            status.text = "翻訳タイムアウト：120秒経過しました。通信状態を確認してください。"

        } catch (e: Exception) {
            status.text =
                "翻訳エラー：${e.message ?: e.javaClass.simpleName}"
        }
    }
}
