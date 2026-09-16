package com.example.ssulkeyboard

import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

    // 마지막으로 자판이 직접 만든 것으로 확인한 실제 앱 커서 위치입니다.
    private var lastKnownSelectionStart = -1
    private var lastKnownSelectionEnd = -1

    // 사용자가 실제 앱에서 커서를 옮긴 직후의 첫 입력에만 사용합니다.
    private var pendingExternalCursorUtf16: Int? = null
    private var suppressSelectionSyncUntil = 0L

    // 자판이 직접 입력/커서 이동을 한 직후의 selection callback은
    // 외부 커서 이동으로 오인하지 않도록 잠시 무시합니다.
    private var internalSelectionUntil = 0L

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
        val heightPx =
            (heightDp * resources.displayMetrics.density).toInt()

       
