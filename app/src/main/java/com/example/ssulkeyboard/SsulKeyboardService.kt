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

    // ★ HTML에서 넘어오는 조합된 텍스트와 삭제 명령을 깔끔하게 처리하는 브릿지
    inner class KeyboardBridge {
        @JavascriptInterface
        fun commitText(text: String) {
            val inputConnection = currentInputConnection
            // 조합 중인 상태를 무시하고 텍스트를 정확하게 입력창에 반영
            inputConnection?.setComposingText("", 1)
            inputConnection?.commitText(text, 1)
        }

        @JavascriptInterface
        fun deleteText() {
            val inputConnection = currentInputConnection
            // 글자 단위로 깔끔하게 지워지도록 조정
            inputConnection?.deleteSurroundingText(1, 0)
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
