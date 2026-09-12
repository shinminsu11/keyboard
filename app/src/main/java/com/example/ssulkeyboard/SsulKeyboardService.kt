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

        // 해상도 차이를 극복하기 위해 dp를 픽셀로 자동 변환 (235dp)
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

            // HTML과 통신할 브릿지 연결
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

    // ★ 실시간 조합과 삭제를 완벽히 지원하는 최종 브릿지
    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(text: String) {
            val inputConnection = currentInputConnection
            if (inputConnection != null) {
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

        @JavascriptInterface
        fun setComposing(text: String) {
            val inputConnection = currentInputConnection
            inputConnection?.setComposingText(text, 1)
        }

        @JavascriptInterface
        fun finishComposing() {
            val inputConnection = currentInputConnection
            inputConnection?.finishComposingText()
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
