package com.example.ssulkeyboard

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 간단하게 WebView를 화면 전체에 생성합니다.
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 웹뷰 내에서 링크 등이 열리도록 설정
            webViewClient = WebViewClient()
            
            // 자바스크립트에서 "AndroidBridge" 이름으로 부를 수 있게 인터페이스를 연결합니다.
            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
        }
        
        setContentView(webView)

        // assets 폴더에 index.html을 넣었을 경우 로드하는 방법
        webView.loadUrl("file:///android_asset/index.html")
        
        // 또는 외부 서버나 로컬 서버 주소가 있다면 아래처럼 로드하세요.
        // webView.loadUrl("https://your-server-url.com")
    }

    // 자바스크립트와 통신할 브릿지 클래스
    inner class WebAppInterface(private val context: MainActivity) {

        @JavascriptInterface
        fun commitText(text: String) {
            // 웹에서 글자가 확정되어 입력될 때 신호를 받는 곳
            // TODO: 안드로이드 InputConnection과 연동하여 실제 입력창에 글자 주입
        }

        @JavascriptInterface
        fun deleteText() {
            // 웹에서 백스페이스가 눌렸을 때 신호를 받는 곳
            // TODO: 안드로이드 InputConnection을 통해 글자 삭제
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {
            // 한자 변환 시 직전 글자 지울 때 호출
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            // URL 열기 요청 처리
        }
        
        @JavascriptInterface
        fun setComposing(text: String) {
            // 조합 중인 텍스트 처리
        }
    }
}
