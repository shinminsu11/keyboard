
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
            currentInputConnection?.commitText(text, 1)
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            currentInputConnection?.setComposingText(text, 1)
        }

        // 항상 정확히 1유닛만 지운다. 여러 유닛(이모티콘 등)을 지울 때는
        // JS 쪽에서 필요한 횟수만큼 이 함수를 반복 호출한다.
        @JavascriptInterface
        fun deleteText() {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        // ⭐ 새로 추가: JS가 계산한 정확한 삭제 길이를 한 번에 그대로 반영한다.
        // keyboard.html의 backspaceHangul()이 이 함수를 우선적으로 찾아서 호출하는데,
        // 지금까지 이 함수가 Kotlin에 없어서 호출이 조용히 실패했고,
        // 그 사이 JS 쪽 committedText/cursorPos만 먼저 줄어들어
        // 실제 화면 텍스트와 어긋나는 문제가 있었다. (뒷글자가 지워지는 원인)
        @JavascriptInterface
        fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            currentInputConnection?.deleteSurroundingText(beforeLength, afterLength)
        }

        // 한자 변환 시, 바뀔 글자 하나를 정확히 지우기 위한 전용 함수
        @JavascriptInterface
        fun deleteOneCharForHanja() {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        @JavascriptInterface
        fun setSelection(start: Int, end: Int) {
            try {
                currentInputConnection?.setSelection(start, end)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun performSearch() {
            val inputConnection = currentInputConnection ?: return
            val editorInfo = currentInputEditorInfo
            val actionId = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_UNSPECIFIED

            if (actionId != EditorInfo.IME_ACTION_NONE &&
                actionId != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                inputConnection.performEditorAction(actionId)
            }
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
        webView.evaluateJavascript(
            "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
            null
        )

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
EOF
echo "완료"
