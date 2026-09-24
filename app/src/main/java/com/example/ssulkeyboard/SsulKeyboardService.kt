package com.example.ssulkeyboard

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import org.json.JSONObject
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView
    private lateinit var clipboardManager: ClipboardManager

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        sendPrimaryClipboardToWebView()
    }

    override fun onCreate() {
        super.onCreate()

        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

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
                    sendPrimaryClipboardToWebView()
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
                    return
                }

                inputConnection.finishComposingText()

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
        fun getClipboardText(): String {
            return readPrimaryClipboardText() ?: ""
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

    private fun readPrimaryClipboardText(): String? {
        return try {
            if (!::clipboardManager.isInitialized) return null
            val clip = clipboardManager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun sendPrimaryClipboardToWebView() {
        if (!::webView.isInitialized) return

        val text = readPrimaryClipboardText() ?: return
        val quoted = JSONObject.quote(text)
        webView.post {
            webView.evaluateJavascript(
                "if(window.onSystemClipboardChanged){window.onSystemClipboardChanged($quoted);}",
                null
            )
        }
    }

    override fun onDestroy() {
        if (::clipboardManager.isInitialized) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        }
        super.onDestroy()
    }

    /**
     * 사용자가 앱의 실제 입력창을 손가락으로 직접 탭해서 옮긴
     * 네이티브 커서 위치를 HTML 자판의 cursorPos에도 전달한다.
     *
     * newSelStart는 UTF-16 기준 위치이므로 JS에서 Array.from 기준
     * 문자 위치로 변환한다.
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

        if (!::webView.isInitialized || newSelStart < 0) return

        val js = """
            if (window.setCursorFromNative) {
                window.setCursorFromNative($newSelStart);
            }
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(js, null)
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
