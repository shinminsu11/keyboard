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

    // ★ 쓸기 입력 및 조합 충돌을 방지하는 최종 브릿지
    inner class KeyboardBridge {
        @JavascriptInterface
        fun commitText(text: String) {
            val inputConnection = currentInputConnection
            if (inputConnection != null) {
                // 쓸기 입력 시 안드로이드 시스템의 자체 한글 조합기(IC)를 강제로 초기화하여 충돌 방지
                inputConnection.finishComposingText()
                inputConnection.commitText(text, 1)
            }
        }

        @JavascriptInterface
        fun deleteText() {
            val inputConnection = currentInputConnection
            if (inputConnection != null) {
                inputConnection.finishComposingText()
                inputConnection.deleteSurroundingText(1, 0)
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
