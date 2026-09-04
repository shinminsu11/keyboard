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

    inner class KeyboardBridge {
        @JavascriptInterface
        fun commitText(text: String) {
            val inputConnection = currentInputConnection ?: return
            // 엔터(\n)를 포함한 모든 텍스트를 InputConnection의 commitText로 통일하여 전달합니다.
            inputConnection.commitText(text, 1)
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            val inputConnection = currentInputConnection ?: return
            inputConnection.setComposingText(text, 1)
        }

        @JavascriptInterface
        fun deleteText() {
            val inputConnection = currentInputConnection ?: return
            // 커서 앞의 글자 1개를 지웁니다.
            inputConnection.deleteSurroundingText(1, 0)
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
