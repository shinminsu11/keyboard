package com.example.ssulkeyboard

import android.content.Intent
import android.net.Uri
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.graphics.Color

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

    // 안드로이드 실제 입력창의 커서 위치
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

    /**
     * 안드로이드 입력창의 실제 커서/선택 영역이 변경될 때 호출됩니다.
     *
     * 사용자가 문장 중간을 터치했을 경우
     * oldSelStart -> newSelStart 로 실제 커서가 이동합니다.
     *
     * 이 값을 기록해 두어 첫 번째 삭제부터 실제 커서 위치를 사용하도록 합니다.
     */
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

        // 현재 HTML 자판에도 실제 커서 위치를 알려줍니다.
        if (::webView.isInitialized) {

            val js = """
                (function() {
                    if (window.setNativeCursorPosition) {
                        window.setNativeCursorPosition(
                            $newSelStart,
                            $newSelEnd
                        );
                    }
                })();
            """.trimIndent()

            webView.post {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(text: String) {
            val inputConnection = currentInputConnection ?: return

            inputConnection.commitText(text, 1)
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            val inputConnection = currentInputConnection ?: return

            inputConnection.setComposingText(text, 1)
        }

        @JavascriptInterface
        fun setSelection(start: Int, end: Int) {
            val inputConnection = currentInputConnection ?: return

            try {
                inputConnection.setSelection(start, end)

                nativeCursorStart = start
                nativeCursorEnd = end

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteText() {
            val inputConnection = currentInputConnection ?: return

            try {

                /*
                 * 선택된 글자가 있다면 선택 영역 자체를 삭제합니다.
                 */
                val selectedText = inputConnection.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {
                    inputConnection.commitText("", 1)
                    return
                }

                /*
                 * 중요:
                 *
                 * 기존 코드에서는
                 *
                 *     finishComposingText()
                 *
                 * 를 먼저 실행했습니다.
                 *
                 * 문장 중간 커서에서 조합 상태와 실제 커서 위치가
                 * 엇갈릴 수 있으므로, 실제 InputConnection의
                 * 커서 위치를 기준으로 바로 삭제합니다.
                 */

                val textBefore = inputConnection.getTextBeforeCursor(2, 0)

                if (!textBefore.isNullOrEmpty()) {

                    /*
                     * 이모지처럼 UTF-16 surrogate pair인 경우
                     * 2칸 삭제합니다.
                     */
                    if (textBefore.length >= 2) {

                        val high = textBefore[textBefore.length - 2]
                        val low = textBefore[textBefore.length - 1]

                        if (Character.isSurrogatePair(high, low)) {
                            inputConnection.deleteSurroundingText(2, 0)
                        } else {
                            inputConnection.deleteSurroundingText(1, 0)
                        }

                    } else {

                        inputConnection.deleteSurroundingText(1, 0)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {
            val inputConnection = currentInputConnection ?: return

            try {
                inputConnection.finishComposingText()
                inputConnection.deleteSurroundingText(1, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun performSearch() {
            val inputConnection = currentInputConnection ?: return

            inputConnection.performEditorAction(
                EditorInfo.IME_ACTION_SEARCH
            )
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                startActivity(intent)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInputView(info, restarting)

        nativeCursorStart = 0
        nativeCursorEnd = 0

        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
                null
            )
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
