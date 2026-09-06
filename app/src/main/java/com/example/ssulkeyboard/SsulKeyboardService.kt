package com.example.ssulkeyboard

import android.content.Intent
import android.net.Uri
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.EditText
import android.app.AlertDialog
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
            inputConnection.setSelection(start, end)
        }

        @JavascriptInterface
        fun deleteText() {
            val inputConnection = currentInputConnection ?: return
            inputConnection.finishComposingText()
            inputConnection.deleteSurroundingText(1, 0)
        }

        @JavascriptInterface
        fun showSoftInput() {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            inputMethodManager?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
            window.window?.let { w ->
                w.decorView.post {
                    w.decorView.requestLayout()
                }
            }
        }

        @JavascriptInterface
        fun forceShowKeyboard() {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }

        @JavascriptInterface
        fun openNativeModal(slotKey: String, type: String, titleVal: String, urlVal: String) {
            val handler = android.os.Handler(mainLooper)
            handler.post {
                val builder = AlertDialog.Builder(this@SsulKeyboardService, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                val layout = LinearLayout(this@SsulKeyboardService).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40, 20, 40, 20)
                }

                if (type == "url") {
                    builder.setTitle("URL 설정")
                    val titleInput = EditText(this@SsulKeyboardService).apply {
                        setText(titleVal)
                        hint = "표시할 제목 (예: 네이버)"
                    }
                    val urlInput = EditText(this@SsulKeyboardService).apply {
                        setText(urlVal)
                        hint = "이동할 주소 (예: https://www.naver.com)"
                    }
                    layout.addView(titleInput)
                    layout.addView(urlInput)
                    builder.setView(layout)

                    builder.setPositiveButton("저장") { _, _ ->
                        val t = titleInput.text.toString()
                        val u = urlInput.text.toString()
                        webView.evaluateJavascript("saveUrlData('$slotKey', '${t.replace("'", "\\'")}', '${u.replace("'", "\\'")}')", null)
                    }
                } else {
                    builder.setTitle("상용구 설정")
                    val input = EditText(this@SsulKeyboardService).apply {
                        setText(titleVal)
                        hint = "상용구 입력..."
                    }
                    layout.addView(input)
                    builder.setView(layout)

                    builder.setPositiveButton("저장") { _, _ ->
                        val text = input.text.toString()
                        webView.evaluateJavascript("saveSnippetData('$slotKey', '${text.replace("'", "\\'")}')", null)
                    }
                }
                builder.setNegativeButton("취소", null)
                builder.setNeutralButton("삭제") { _, _ ->
                    webView.evaluateJavascript("deleteModalData('$slotKey')", null)
                }

                val dialog = builder.create()
                dialog.window?.let { window ->
                    window.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
                    token?.let { window.attributes.token = it }
                }
                dialog.show()
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
