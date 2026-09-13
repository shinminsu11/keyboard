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
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

/**
 * Android 실제 입력창의 커서만 기준으로 동작하는 입력기 서비스입니다.
 *
 * 중요:
 * - HTML의 가상 cursorPos를 Kotlin에서 다시 setSelection하지 않습니다.
 * - 실제 앱(Gemini, 메모장 등)이 관리하는 InputConnection 커서를 그대로 사용합니다.
 * - JavaScript에서 들어온 입력 작업은 mainHandler 하나의 큐에서 순서대로 실행됩니다.
 */
class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    // 디버깅·상태 확인용입니다. 삭제 위치를 직접 결정하는 값으로 사용하지 않습니다.
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

    /** 실제 대상 앱이 보고한 커서 위치만 기록합니다. HTML로 다시 덮어쓰지 않습니다. */
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
        nativeCursorStart = newSelStart.coerceAtLeast(0)
        nativeCursorEnd = newSelEnd.coerceAtLeast(nativeCursorStart)
    }

    /** 모든 InputConnection 작업을 하나의 순서 있는 메인 큐에서 실행합니다. */
    private fun enqueueInputOperation(operation: (InputConnection) -> Unit) {
        mainHandler.post {
            val connection = currentInputConnection ?: return@post
            try {
                operation(connection)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    private fun updateRecordedSelection(connection: InputConnection) {
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) return
        val before = connection.getTextBeforeCursor(0, 0)
        // 대부분의 InputConnection 구현은 selection 변경을 onUpdateSelection으로 통지합니다.
        // 여기서는 임의로 커서를 옮기지 않고, 통지값만 사용합니다.
        @Suppress("UNUSED_VARIABLE")
        val ignored = before
    }

    inner class KeyboardBridge {

        /** 일반 문자를 실제 앱의 현재 커서 위치에 삽입합니다. */
        @JavascriptInterface
        fun commitText(text: String) {
            if (text.isEmpty()) return
            enqueueInputOperation { connection ->
                connection.commitText(text, 1)
            }
        }

        /** 한글 조합 문자를 실제 앱의 현재 커서 위치에 설정합니다. */
        @JavascriptInterface
        fun setComposing(text: String) {
            enqueueInputOperation { connection ->
                connection.setComposingText(text, 1)
            }
        }

        /**
         * 웹 성공 HTML과의 호환용입니다.
         * Android에서는 HTML cursorPos를 절대 위치로 해석하지 않습니다.
         * 실제 앱의 커서는 사용자가 대상 앱에서 지정한 위치를 그대로 사용합니다.
         */
        @JavascriptInterface
        fun setSelection(start: Int, end: Int) {
            // 의도적으로 InputConnection.setSelection을 호출하지 않습니다.
            // HTML의 가상 커서가 실제 Gemini/메모장 커서를 맨 끝이나 0번으로
            // 덮어쓰는 회귀를 막기 위한 호환 메서드입니다.
        }

        /**
         * 백스페이스: 실제 앱의 현재 커서 바로 앞을 삭제합니다.
         * HTML의 가상 커서나 committedText는 사용하지 않습니다.
         */
        @JavascriptInterface
        fun deleteText() {
            enqueueInputOperation { connection ->
                val selectedText = connection.getSelectedText(0)
                if (!selectedText.isNullOrEmpty()) {
                    connection.commitText("", 1)
                    return@enqueueInputOperation
                }

                val before = connection.getTextBeforeCursor(2, 0) ?: return@enqueueInputOperation
                if (before.isEmpty()) return@enqueueInputOperation

                val last = before.lastIndex
                val deleteLength = if (
                    before.length >= 2 &&
                    Character.isSurrogatePair(before[last - 1], before[last])
                ) 2 else 1

                connection.deleteSurroundingText(deleteLength, 0)
            }
        }

        /** 웹 성공 HTML의 기존 호출과 호환됩니다. */
        @JavascriptInterface
        fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            enqueueInputOperation { connection ->
                connection.deleteSurroundingText(
                    beforeLength.coerceAtLeast(0),
                    afterLength.coerceAtLeast(0)
                )
            }
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {
            enqueueInputOperation { connection ->
                connection.finishComposingText()
                connection.deleteSurroundingText(1, 0)
            }
        }

        @JavascriptInterface
        fun performSearch() {
            enqueueInputOperation { connection ->
                connection.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // 실제 앱의 커서를 0으로 초기화하지 않습니다.
        // 초기화해야 하는 것은 HTML의 한글 조합 버퍼뿐입니다.
        if (::webView.isInitialized) {
            webView.post {
                webView.evaluateJavascript(
                    "if(window.resetKeyboardBuffer){window.resetKeyboardBuffer();}",
                    null
                )
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        nativeCursorStart = 0
        nativeCursorEnd = 0
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
}
