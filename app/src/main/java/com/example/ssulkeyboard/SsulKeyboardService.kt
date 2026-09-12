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
                    syncNativeTextAfterDelete()
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

                syncNativeTextAfterDelete()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun syncNativeTextAfterDelete() {
            if (!::webView.isInitialized) return

            val ic = currentInputConnection ?: return

            try {
                val before = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
                val after = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""

                val beforeJs = JSONObject.quote(before)
                val afterJs = JSONObject.quote(after)

                webView.post {
                    webView.evaluateJavascript(
                        """
                        (function() {
                            if (window.setNativeTextAfterDelete) {
                                window.setNativeTextAfterDelete(
                                    $beforeJs,
                                    $afterJs
                                );
                            }
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {
            val inputConnection = currentInputConnection ?: return

            inputConnection.finishComposingText()
            inputConnection.deleteSurroundingText(1, 0)
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
            val before = inputConnection.getTextBeforeCursor(10000, 0)?.toString() ?: ""
            val after = inputConnection.getTextAfterCursor(10000, 0)?.toString() ?: ""

            val beforeJs = JSONObject.quote(before)
            val afterJs = JSONObject.quote(after)

            webView.post {
                webView.evaluateJavascript(
                    """
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
                    """.trimIndent(),
                    null
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
