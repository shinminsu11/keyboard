package com.example.ssulkeyboard

import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    // Android 입력창이 마지막으로 보고한 실제 커서/선택 위치입니다.
    private var nativeCursorStart = 0
    private var nativeCursorEnd = 0

    override fun onCreateInputView(): View {
        val container = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#d1d8e0"))
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
            setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(KeyboardBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }
            }
            loadUrl("file:///android_asset/keyboard.html")
        }

        container.addView(webView)
        return container
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )

        nativeCursorStart = newSelStart
        nativeCursorEnd = newSelEnd

        if (::webView.isInitialized) {
            val js = """
                (function() {
                    if (window.setNativeCursorPosition) {
                        window.setNativeCursorPosition($newSelStart, $newSelEnd);
                    }
                })();
            """.trimIndent()
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    private fun postToInputConnection(action: (android.view.inputmethod.InputConnection) -> Unit) {
        mainHandler.post {
            val connection = currentInputConnection ?: return@post
            try {
                action(connection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(text: String) {
            val connection = currentInputConnection ?: return
            connection.commitText(text, 1)
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            val connection = currentInputConnection ?: return
            connection.setComposingText(text, 1)
        }

        /** HTML의 UTF-16 커서 위치를 Android 실제 입력창에 반영합니다. */
        @JavascriptInterface
        fun setSelection(start: Int, end: Int) {
            val safeStart = start.coerceAtLeast(0)
            val safeEnd = end.coerceAtLeast(safeStart)
            postToInputConnection { connection ->
                connection.setSelection(safeStart, safeEnd)
                nativeCursorStart = safeStart
                nativeCursorEnd = safeEnd
            }
        }

        /**
         * HTML이 계산한 삭제 위치에서 삭제합니다.
         * cursorUtf16은 대상 입력창 전체 텍스트 기준의 UTF-16 위치여야 합니다.
         */
        @JavascriptInterface
        fun deleteTextAt(cursorUtf16: Int, deleteLenUtf16: Int) {
            val safeCursor = cursorUtf16.coerceAtLeast(0)
            val safeLength = deleteLenUtf16.coerceIn(1, 2)

            postToInputConnection { connection ->
                // 반드시 삭제 전에 실제 Android 커서를 HTML 커서 위치로 이동합니다.
                connection.setSelection(safeCursor, safeCursor)
                nativeCursorStart = safeCursor
                nativeCursorEnd = safeCursor
                connection.deleteSurroundingText(safeLength, 0)
            }
        }

        /** 기존 호출과의 호환용 fallback입니다. */
        @JavascriptInterface
        fun deleteText() {
            postToInputConnection { connection ->
                val selected = connection.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    connection.commitText("", 1)
                    return@postToInputConnection
                }
                val before = connection.getTextBeforeCursor(2, 0)
                if (!before.isNullOrEmpty()) {
                    val last = before.lastIndex
                    val deleteLength = if (
                        before.length >= 2 &&
                        Character.isSurrogatePair(before[last - 1], before[last])
                    ) 2 else 1
                    connection.deleteSurroundingText(deleteLength, 0)
                }
            }
        }

        @JavascriptInterface
        fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            postToInputConnection { connection ->
                connection.deleteSurroundingText(
                    beforeLength.coerceAtLeast(0),
                    afterLength.coerceAtLeast(0)
                )
            }
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {
            postToInputConnection { connection ->
                connection.finishComposingText()
                connection.deleteSurroundingText(1, 0)
            }
        }

        @JavascriptInterface
        fun performSearch() {
            val connection = currentInputConnection ?: return
            connection.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        nativeCursorStart = 0
        nativeCursorEnd = 0
        if (::webView.isInitialized) {
            webView.post {
                webView.evaluateJavascript(
                    "if(window.resetKeyboardBuffer){window.resetKeyboardBuffer();}",
                    null
                )
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
}
