package com.example.ssulkey.keyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

class SswlKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

    override fun onCreateInputView(): View {
        val container = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        // 자판 전체 실제 높이(dp)를 화면 밀도에 맞춰 픽셀(px)로 변환
        // 여백 없이 자판 영역에 딱 맞추려면 약 250~260dp를 기준으로 잡습니다.
        val heightDp = 255
        val heightPx = (heightDp * resources.displayMetrics.density).toInt()

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
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
            val inputConnection = currentInputConnection
            inputConnection?.commitText(text, 1)
        }

        @JavascriptInterface
        fun setComposingText(text: String) {
            val inputConnection = currentInputConnection
            inputConnection?.setComposingText(text, 1)
        }

        @JavascriptInterface
        fun deleteText() {
            val inputConnection = currentInputConnection
            inputConnection?.deleteSurroundingText(1, 0)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
