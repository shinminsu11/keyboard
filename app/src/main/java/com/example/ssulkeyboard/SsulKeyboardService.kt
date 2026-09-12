package com.example.ssulkeyboard

import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import org.json.JSONObject

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

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
     * 안드로이드 입력창의 실제 커서/선택 위치가 바뀔 때마다 호출됩니다.
     *
     * 핵심은 단순히 selection 숫자만 HTML에 보내는 것이 아니라,
     * 그 순간 InputConnection에서 실제 앞/뒤 문자열도 함께 읽어
     * HTML의 committedText와 cursorPos를 같은 상태로 맞추는 것입니다.
     *
     * 이렇게 해야 문장 중간을 터치한 직후 첫 번째 삭제에서도
     * 맨 뒤 글자가 아니라 실제 커서 앞의 글자가 삭제됩니다.
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

        if (!::webView.isInitialized) return

        val inputConnection = currentInputConnection ?: return

        try {
            // 현재 실제 입력창의 커서 앞/뒤 문자열을 가져옵니다.
            // 충분히 큰 값으로 가져와 문장 전체를 동기화합니다.
            val before = inputConnection.getTextBeforeCursor(10000, 0)?.toString() ?: ""
            val after = inputConnection.getTextAfterCursor(10000, 0)?.toString() ?: ""

            val beforeJs = JSONObject.quote(before)
            val afterJs = JSONObject.quote(after)

            val js = """
                (function() {
                    if (window.setNativeCursorPosition) {
                        window.setNativeCursorPosition(
                            $newSelStart,
                            $newSelEnd,
                            $beforeJs,
                            $afterJs
                        );
                    }
                })();
            """.trimIndent()

            webView.post {
                webView.evaluateJavascript(js, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteText() {
            val inputConnection = currentInputConnection ?: return

            try {
                val selectedText = inputConnection.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {
                    inputConnection.commitText("", 1)
                    return
                }

                val textBefore = inputConnection.getTextBeforeCursor(2, 0)

                if (!textBefore.isNullOrEmpty() && textBefore.length >= 2) {
                    val high = textBefore[textBefore.length - 2]
                    val low = textBefore[textBefore.length - 1]

                    if (Character.isSurrogatePair(high, low)) {
                        inputConnection.deleteSurroundingText(2, 0)
                    } else {
                        inputConnection.deleteSurroundingText(1, 0)
                    }
                } else if (!textBefore.isNullOrEmpty()) {
                    inputConnection.deleteSurroundingText(1, 0)
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
