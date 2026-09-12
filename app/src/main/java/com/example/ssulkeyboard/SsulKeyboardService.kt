package com.example.ssulkeyboard

import android.annotation.SuppressLint
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateInputView(): View {
        webView = WebView(this).apply {
            // 키보드 영역에 맞게 크기 강제 지정 (match_parent)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            // 웹뷰 클라이언트를 통해 파일 로드 상태 확인
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("SsulKeyboard", "웹 페이지 로드 완료: $url")
                }
            }

            // 웹 내부의 console.log나 JS 에러를 안드로이드 로그캣에서 볼 수 있게 설정
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    Log.d("SsulKeyboard-JS", "${consoleMessage?.message()} -- Line ${consoleMessage?.lineNumber()}")
                    return true
                }
            }

            addJavascriptInterface(IMEBridge(), "AndroidBridge")
            loadUrl("file:///android_asset/keyboard.html")
        }
        return webView
    }

    inner class IMEBridge {
        @JavascriptInterface
        fun commitText(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        @JavascriptInterface
        fun deleteText() {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        @JavascriptInterface
        fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            currentInputConnection?.deleteSurroundingText(beforeLength, afterLength)
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            currentInputConnection?.setComposingText(text, 1)
        }

        @JavascriptInterface
        fun performSearch() {
            currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
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
}
