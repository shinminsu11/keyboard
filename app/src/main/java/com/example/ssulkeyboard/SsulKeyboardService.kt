package com.example.ssulkeyboard // 패키지명은 기존 설정에 맞게 유지 또는 확인해주세요

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

    override fun onCreateInputView(): View {
        // 웹뷰 레이아웃 생성
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            
            // HTML 파일 연결
            loadUrl("file:///android_asset/keyboard.html")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 페이지 로딩 완료 후 추가 설정이 필요하면 여기에 작성
                }
            }
        }
        return webView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 입력창이 시작될 때 자판 뷰의 크기와 레이아웃을 강제로 다시 잡도록 유도
        window.window?.let { window ->
            // 시스템 창 뒤로 숨지 않고 위로 쑥 올라오도록 레이아웃 플래그 조정 실험
            window.decorView.let { decorView ->
                decorView.requestLayout()
            }
        }
    }

    // ★ 핵심 실험 포인트: 자판 영역이 전체 화면 크기에 맞춰 위로 쑥 올라오도록 설정하는 함수
    override fun onEvaluateFullscreenMode(): Boolean {
        // 전체 화면 모드로 전환되지 않도록 강제하여 입력창 위로 자판이 자연스럽게 붙게 함
        return false
    }
}
