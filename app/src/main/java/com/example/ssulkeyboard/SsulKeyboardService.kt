package com.example.ssulkeyboard

import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

    // Android 실제 입력창의 현재 커서 위치
    private var nativeCursorStart = 0
    private var nativeCursorEnd = 0

    /*
     * 자판이 직접 발생시킨 selection 변경을 구분하기 위한 상태.
     *
     * 이전 버전처럼 일정 시간 동안 무조건 무시하지 않습니다.
     * 실제 selection 값이 자판이 예상한 값과 같을 때만
     * 내부 selection으로 판단합니다.
     */
    private var expectedSelectionStart = -1
    private var expectedSelectionEnd = -1

    private var expectedSelectionValid = false

    private fun setExpectedSelection(
        start: Int,
        end: Int
    ) {
        expectedSelectionStart = start
        expectedSelectionEnd = end
        expectedSelectionValid = true
    }

    private fun isExpectedSelection(
        start: Int,
        end: Int
    ): Boolean {
        return expectedSelectionValid &&
                start == expectedSelectionStart &&
                end == expectedSelectionEnd
    }

    private fun clearExpectedSelection() {
        expectedSelectionValid = false
        expectedSelectionStart = -1
        expectedSelectionEnd = -1
    }

    override fun onCreateInputView(): View {

        val container = LinearLayout(this).apply {

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            orientation = LinearLayout.VERTICAL

            setBackgroundColor(
                Color.parseColor("#d1d8e0")
            )
        }

        val heightDp = 235

        val heightPx =
            (heightDp * resources.displayMetrics.density).toInt()

        webView = WebView(this).apply {

            layoutParams =
