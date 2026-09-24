package com.example.ssulkeyboard

import android.content.ClipboardManager
import android.content.Context
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
import org.json.JSONArray

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView
    private lateinit var clipboardManager: ClipboardManager

    private val clipboardPrefs by lazy {
        getSharedPreferences("ssul_clipboard_v1", Context.MODE_PRIVATE)
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureCurrentClipboard()
    }

    override fun onCreate() {
        super.onCreate()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)

        // 키보드가 시작될 때 현재 시스템 클립보드의 텍스트도 한 번 확인한다.
        captureCurrentClipboard()
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
                    sendClipboardHistoryToJs()
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
        fun deleteClipboardItem(index: Int) {
            val history = getClipboardHistory().toMutableList()
            if (index < 0 || index >= history.size) return
            history.removeAt(index)
            saveClipboardHistory(history)
            sendClipboardHistoryToJs()
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

    private fun captureCurrentClipboard() {
        if (!::clipboardManager.isInitialized) return

        try {
            val clip = clipboardManager.primaryClip ?: return
            if (clip.itemCount <= 0) return

            val item = clip.getItemAt(0)
            val text = item.coerceToText(this)?.toString() ?: return
            if (text.trim().isEmpty()) return

            // 텍스트 클립만 자동 저장한다. 이미 같은 내용이 있으면 맨 앞으로 이동한다.
            val history = getClipboardHistory().toMutableList()
            history.remove(text)
            history.add(0, text)
            saveClipboardHistory(history.take(40))
            sendClipboardHistoryToJs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getClipboardHistory(): List<String> {
        val raw = clipboardPrefs.getString("history", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val value = arr.optString(i, "")
                if (value.isNotBlank()) list.add(value)
            }
            list.take(40)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveClipboardHistory(history: List<String>) {
        val arr = JSONArray()
        history.distinct().take(40).forEach { arr.put(it) }
        clipboardPrefs.edit().putString("history", arr.toString()).apply()
    }

    private fun sendClipboardHistoryToJs() {
        if (!::webView.isInitialized) return

        val arr = JSONArray()
        getClipboardHistory().forEach { arr.put(it) }
        val js = "window.setNativeClipboardHistory && window.setNativeClipboardHistory(${arr});"

        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    override fun onDestroy() {
        if (::clipboardManager.isInitialized) {
            try {
                clipboardManager.removePrimaryClipChangedListener(clipboardListener)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
