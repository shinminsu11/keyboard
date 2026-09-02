package com.example.ssulkeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

    override fun onCreateInputView(): View {
        val container = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                800
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            
            addJavascriptInterface(KeyboardBridge(), "AndroidBridge")

            loadUrl("file:///android_asset/keyboard.html")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }
            }
        }

        container.addView(webView)
        return container
    }

    // ★ 첫 입력 씹힘 현상을 방지하기 위해 InputConnection을 확실히 깨우는 브릿지
    inner class KeyboardBridge {
        @JavascriptInterface
        fun commitText(text: String) {
            val inputConnection = currentInputConnection
            if (inputConnection != null) {
                // 최초 입력 시 입력 연결의 배치를 강제로 동기화하여 첫 글자 누락 방지
                inputConnection.beginBatchEdit()
                inputConnection.finishComposingText()
                inputConnection.commitText(text, 1)
                inputConnection.endBatchEdit()
            }
        }

        @JavascriptInterface
        fun deleteText() {
            val inputConnection = currentInputConnection
            if (inputConnection != null) {
                inputConnection.beginBatchEdit()
                inputConnection.finishComposingText()
                inputConnection.deleteSurroundingText(1, 0)
                inputConnection.endBatchEdit()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        window.window?.let { window ->
            window.decorView.let { decorView ->
                decorView.requestLayout()
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
