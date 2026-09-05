package com.example.ssulkeyboard

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import org.json.JSONObject

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var extractedTextToken = 0

    override fun onCreateInputView(): View {
        val container = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        val heightDp = 235
        val heightPx = (heightDp * resources.displayMetrics.density).toInt()

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            addJavascriptInterface(KeyboardBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    requestEditorState()
                }
            }
            loadUrl("file:///android_asset/keyboard.html")
        }

        container.addView(webView)
        return container
    }

    private fun currentConnection(): InputConnection? = currentInputConnection

    private fun codePointIndexToUtf16(text: String, codePointIndex: Int): Int {
        val count = text.codePointCount(0, text.length)
        val safeIndex = codePointIndex.coerceIn(0, count)
        return text.offsetByCodePoints(0, safeIndex)
    }

    private fun requestEditorState() {
        mainHandler.post {
            val inputConnection = currentConnection() ?: return@post
            val request = ExtractedTextRequest().also {
                it.token = ++extractedTextToken
            }
            val extracted = inputConnection.getExtractedText(
                request,
                InputConnection.GET_EXTRACTED_TEXT_MONITOR
            )

            if (extracted != null) {
                sendEditorStateToHtml(extracted)
                return@post
            }

            // 일부 입력창은 ExtractedText를 제공하지 않으므로 앞·뒤 텍스트로 보완합니다.
            val before = inputConnection.getTextBeforeCursor(10000, 0)?.toString() ?: ""
            val after = inputConnection.getTextAfterCursor(10000, 0)?.toString() ?: ""
            sendEditorStateToHtml(before, after)
        }
    }

    private fun sendEditorStateToHtml(extracted: ExtractedText) {
        val text = extracted.text?.toString() ?: ""
        val selectionStart = extracted.selectionStart.coerceIn(0, text.length)
        val selectionEnd = extracted.selectionEnd.coerceIn(selectionStart, text.length)
        val before = text.substring(0, selectionStart)
        val after = text.substring(selectionEnd)
        sendEditorStateToHtml(before, after)
    }

    private fun sendEditorStateToHtml(before: String, after: String) {
        if (!::webView.isInitialized) return
        val beforeJson = JSONObject.quote(before)
        val afterJson = JSONObject.quote(after)
        val javascript = "window.setAndroidEditorState($beforeJson, $afterJson);"
        webView.post {
            webView.evaluateJavascript(javascript, null)
        }
    }

    private fun runWithConnection(action: (InputConnection) -> Unit) {
        mainHandler.post {
            currentConnection()?.let(action)
        }
    }

    inner class KeyboardBridge {

        @JavascriptInterface
        fun requestEditorState() {
            this@SsulKeyboardService.requestEditorState()
        }

        @JavascriptInterface
        fun commitText(text: String) {
            runWithConnection { inputConnection ->
                inputConnection.commitText(text, 1)
            }
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            runWithConnection { inputConnection ->
                inputConnection.setComposingText(text, 1)
            }
        }

        /** HTML 커서 위치를 실제 편집창 커서로 이동합니다. 위치는 코드 포인트 기준입니다. */
        @JavascriptInterface
        fun setCursorPosition(codePointPosition: Int, htmlText: String) {
            runWithConnection { inputConnection ->
                val extracted = readExtractedText(inputConnection) ?: return@runWithConnection
                val actualText = extracted.text?.toString() ?: return@runWithConnection
                val selectionStart = extracted.selectionStart.coerceIn(0, actualText.length)
                val selectionEnd = extracted.selectionEnd.coerceIn(selectionStart, actualText.length)
                val actualBefore = actualText.substring(0, selectionStart)
                val actualAfter = actualText.substring(selectionEnd)
                if (actualBefore + actualAfter != htmlText) {
                    sendEditorStateToHtml(actualBefore, actualAfter)
                    return@runWithConnection
                }

                val utf16Position = codePointIndexToUtf16(htmlText, codePointPosition)
                inputConnection.setSelection(utf16Position, utf16Position)
            }
        }

        /** HTML의 커서 앞 글자를 실제 입력창에서 삭제합니다. */
        @JavascriptInterface
        fun deleteTextAt(codePointPosition: Int, htmlText: String) {
            runWithConnection { inputConnection ->
                val extracted = readExtractedText(inputConnection) ?: return@runWithConnection
                val actualText = extracted.text?.toString() ?: return@runWithConnection
                val selectionStart = extracted.selectionStart.coerceIn(0, actualText.length)
                val selectionEnd = extracted.selectionEnd.coerceIn(selectionStart, actualText.length)
                val actualBefore = actualText.substring(0, selectionStart)
                val actualAfter = actualText.substring(selectionEnd)
                if (actualBefore + actualAfter != htmlText) {
                    sendEditorStateToHtml(actualBefore, actualAfter)
                    return@runWithConnection
                }

                val utf16Position = codePointIndexToUtf16(htmlText, codePointPosition)
                inputConnection.setSelection(utf16Position, utf16Position)
                inputConnection.deleteSurroundingTextInCodePoints(1, 0)
            }
        }

        @JavascriptInterface
        fun deleteText() {
            runWithConnection { inputConnection ->
                inputConnection.deleteSurroundingTextInCodePoints(1, 0)
            }
        }

        private fun readExtractedText(inputConnection: InputConnection): ExtractedText? {
            val request = ExtractedTextRequest().also {
                it.token = ++extractedTextToken
            }
            return inputConnection.getExtractedText(request, 0)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        window.window?.decorView?.requestLayout()
        requestEditorState()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
}
