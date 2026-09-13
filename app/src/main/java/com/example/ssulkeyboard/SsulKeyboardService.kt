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
        val heightPx =
            (heightDp * resources.displayMetrics.density).toInt()

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            setBackgroundColor(Color.TRANSPARENT)

            addJavascriptInterface(
                KeyboardBridge(),
                "AndroidBridge"
            )

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
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
    }

    private fun syncHtmlWithNativeText() {
        if (!::webView.isInitialized) return

        val inputConnection = currentInputConnection ?: return

        try {
            val before = inputConnection
                .getTextBeforeCursor(10000, 0)
                ?.toString()
                ?: ""

            val after = inputConnection
                .getTextAfterCursor(10000, 0)
                ?.toString()
                ?: ""

            val beforeJs = JSONObject.quote(before)
            val afterJs = JSONObject.quote(after)

            val js = """
                (function() {
                    if (window.syncNativeText) {
                        window.syncNativeText($beforeJs, $afterJs);
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

            try {
                inputConnection.commitText(text, 1)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            val inputConnection = currentInputConnection ?: return

            try {
                inputConnection.setComposingText(text, 1)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                val selectedText =
                    inputConnection.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {
                    inputConnection.commitText("", 1)
                    syncHtmlWithNativeText()
                    return
                }

                inputConnection.sendKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_DEL
                    )
                )
                inputConnection.sendKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_DEL
                    )
                )

                syncHtmlWithNativeText()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {
            val inputConnection = currentInputConnection ?: return

            try {
                inputConnection.finishComposingText()

                inputConnection.deleteSurroundingText(
                    1,
                    0
                )

                syncHtmlWithNativeText()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun performSearch() {
            val inputConnection = currentInputConnection ?: return

            try {
                inputConnection.performEditorAction(
                    EditorInfo.IME_ACTION_SEARCH
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        super.onStartInputView(
            info,
            restarting
        )

        if (::webView.isInitialized) {

            webView.post {

                webView.evaluateJavascript(
                    """
                    (function() {
                        if (window.resetKeyboardBuffer) {
                            window.resetKeyboardBuffer();
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
