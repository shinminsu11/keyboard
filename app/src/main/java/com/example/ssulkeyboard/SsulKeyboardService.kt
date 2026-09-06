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
import android.app.AlertDialog
import android.widget.EditText
import android.view.WindowManager
import android.os.Handler
import android.os.Looper

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

        // 🌟 자판 바깥(시스템 창)에 네이티브 상용구 입력창 띄우기
        @JavascriptInterface
        fun openNativeSnippetModal(slotKey: String, currentText: String, modalType: String, currentTitle: String) {
            val context = this@SsulKeyboardService
            
            Handler(Looper.getMainLooper()).post {
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 40, 50, 20)
                }

                val titleInput = if (modalType == "url") {
                    EditText(context).apply {
                        setText(currentTitle)
                        hint = "표시할 제목 (예: 네이버)"
                        setPadding(30, 25, 30, 25)
                    }.also { layout.addView(it) }
                } else null

                val contentInput = EditText(context).apply {
                    setText(currentText)
                    hint = if (modalType == "url") "이동할 주소 (예: https://www.naver.com)" else "상용구 입력..."
                    setPadding(30, 25, 30, 25)
                }.also { layout.addView(it) }

                val dialogTitle = if (modalType == "url") "URL 설정" else "상용구 설정"

                val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle(dialogTitle)
                    .setView(layout)
                    .setPositiveButton("저장") { _, _ ->
                        val text1 = contentInput.text.toString().trim()
                        val text2 = titleInput?.text?.toString()?.trim() ?: ""
                        
                        // 자바스크립트로 저장 내용 전달
                        val jsCode = if (modalType == "url") {
                            "saveSnippetFromNative('$slotKey', '$text2', '$text1', 'url')"
                        } else {
                            "saveSnippetFromNative('$slotKey', '', '$text1', 'snippet')"
                        }
                        webView.evaluateJavascript("javascript:$jsCode", null)
                    }
                    .setNeutralButton("삭제") { _, _ ->
                        webView.evaluateJavascript("javascript:deleteSnippetFromNative('$slotKey')", null)
                    }
                    .setNegativeButton("취소", null)
                    .create()

                // 키보드 서비스 윈도우 위에 다이얼로그가 안정적으로 뜨도록 설정
                dialog.window?.let { window ->
                    window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
                    token?.let { window.attributes.token = it }
                }

                dialog.show()
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
