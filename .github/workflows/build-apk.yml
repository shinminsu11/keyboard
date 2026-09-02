package com.example.ssulkeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class SsulKeyboardService : InputMethodService() {

    private var webView: WebView? = null

    override fun onCreateInputView(): View {
        val heightPx = (280 * resources.displayMetrics.density).toInt()

        val container = FrameLayout(this)
        container.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            heightPx
        )
        container.setBackgroundColor(0xFFD1D8E0.toInt())

        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = true
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.setBackgroundColor(0x00000000)

        wv.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        wv.webViewClient = WebViewClient()
        wv.loadUrl("file:///android_asset/keyboard.html")

        container.addView(wv)

        webView = wv
        return container
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun commitText(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        @JavascriptInterface
        fun deleteText() {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }
}
