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
                // 1. 선택된 글자가 있다면 선택 영역 자체를 삭제
                val selectedText = inputConnection.getSelectedText(0)
                if (!selectedText.isNullOrEmpty()) {
                    inputConnection.commitText("", 1)
                    return
                }

                // 2. 조합 중인 텍스트 정리 후 커서 바로 앞의 글자 정밀 타겟팅 삭제
                inputConnection.finishComposingText()

                val textBefore = inputConnection.getTextBeforeCursor(1, 0)
                if (!textBefore.isNullOrEmpty()) {
                    val high = textBefore[0]
                    if (textBefore.length >= 2) {
                        val low = textBefore[1]
                        if (Character.isSurrogatePair(high, low)) {
                            inputConnection.deleteSurroundingText(2, 0)
                        } else {
                            inputConnection.deleteSurroundingText(1, 0)
                        }
                    } else {
                        inputConnection.deleteSurroundingText(1, 0)
                    }
                } else {
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
