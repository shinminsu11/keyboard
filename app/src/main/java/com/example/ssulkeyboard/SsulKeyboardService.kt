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
                // 자판 내부에서 커서가 이동했을 때 안드로이드 시스템 커서도 함께 이동시킵니다.
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

                // [수정 포인트] 커서 바로 앞의 글자 1개를 정확하게 삭제하도록 수정
                // 기존의 2글자 서러게이트 체크 대신 시스템 커서 기준 1글자 삭제를 수행합니다.
                val textBefore = inputConnection.getTextBeforeCursor(1, 0)
                if (!textBefore.isNullOrEmpty()) {
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
