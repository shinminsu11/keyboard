package com.example.ssulkeyboard

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

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

            addJavascriptInterface(KeyboardBridge(), "AndroidBridge")
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/keyboard.html")
        }

        container.addView(webView)
        return container
    }

    /**
     * JS에서 전달한 논리 커서 위치를 실제 앱 입력창의 UTF-16 커서 위치로 변환합니다.
     * Android InputConnection의 setSelection()은 UTF-16 인덱스를 사용하고,
     * HTML의 Array.from() 기반 cursorPosition은 유니코드 코드 포인트 인덱스이므로
     * 이모지까지 고려해 변환합니다.
     */
    private fun codePointIndexToUtf16(text: String, codePointIndex: Int): Int {
        val safeIndex = codePointIndex.coerceIn(0, text.codePointCount(0, text.length))
        return text.offsetByCodePoints(0, safeIndex)
    }

    private fun runOnInputConnection(action: (android.view.inputmethod.InputConnection) -> Unit) {
        mainHandler.post {
            currentInputConnection?.let(action)
        }
    }

    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(text: String) {
            runOnInputConnection { inputConnection ->
                inputConnection.commitText(text, 1)
            }
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            runOnInputConnection { inputConnection ->
                inputConnection.setComposingText(text, 1)
            }
        }

        /**
         * HTML의 논리 커서 위치를 실제 편집창에 반영합니다.
         * htmlText가 현재 편집창 텍스트와 일치하면 정확한 위치를 사용하고,
         * 일치하지 않으면 현재 Android 커서를 보존해 다른 앱의 기존 텍스트를
         * 임의로 잘못 이동시키지 않습니다.
         */
        @JavascriptInterface
        fun setCursorPosition(codePointPosition: Int, htmlText: String) {
            runOnInputConnection { inputConnection ->
                val extracted = inputConnection.getExtractedText(
                    ExtractedTextRequest(),
                    0
                ) ?: return@runOnInputConnection

                val actualText = extracted.text?.toString() ?: return@runOnInputConnection
                if (actualText != htmlText) return@runOnInputConnection

                val utf16Position = codePointIndexToUtf16(htmlText, codePointPosition)
                inputConnection.setSelection(utf16Position, utf16Position)
            }
        }

        /**
         * deleteAt은 삭제 전 HTML 커서 위치입니다.
         * HTML 텍스트와 실제 편집창 텍스트가 일치하는 경우에만 해당 위치로 이동한 뒤
         * 커서 앞의 한 유니코드 코드 포인트를 삭제합니다.
         */
        @JavascriptInterface
        fun deleteTextAt(deleteAtCodePoint: Int, htmlText: String) {
            runOnInputConnection { inputConnection ->
                val extracted = inputConnection.getExtractedText(
                    ExtractedTextRequest(),
                    0
                ) ?: return@runOnInputConnection

                val actualText = extracted.text?.toString() ?: return@runOnInputConnection
                if (actualText != htmlText) return@runOnInputConnection

                val utf16Position = codePointIndexToUtf16(htmlText, deleteAtCodePoint)
                inputConnection.setSelection(utf16Position, utf16Position)
                inputConnection.deleteSurroundingTextInCodePoints(1, 0)
            }
        }

        /**
         * 기존 HTML과의 호환을 위한 기본 삭제 함수입니다.
         * 새 HTML은 deleteTextAt()을 사용합니다.
         */
        @JavascriptInterface
        fun deleteText() {
            runOnInputConnection { inputConnection ->
                inputConnection.deleteSurroundingTextInCodePoints(1, 0)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        window.window?.decorView?.requestLayout()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
}
