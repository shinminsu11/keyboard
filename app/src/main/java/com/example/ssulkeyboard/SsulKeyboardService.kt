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
            inputConnection.finishComposingText()
            
            // 1. 드래그 등으로 텍스트가 선택되어 있는 경우 선택 영역 삭제
            val extractedText = inputConnection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            if (extractedText != null && extractedText.selectionStart != extractedText.selectionEnd) {
                val start = minOf(extractedText.selectionStart, extractedText.selectionEnd)
                val end = maxOf(extractedText.selectionStart, extractedText.selectionEnd)
                inputConnection.setSelection(start, end)
                inputConnection.deleteSurroundingText(end - start, 0)
                return
            }

            // 2. 선택 영역이 없을 경우 기존의 1글자 / 이모티콘 삭제 로직 수행
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
            inputConnection.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
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
        if (::webView.isInitialized) {
            webView.evaluateJavascript("javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }", null)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
