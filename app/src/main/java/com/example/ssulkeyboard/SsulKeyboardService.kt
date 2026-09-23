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
import kotlin.math.max
import kotlin.math.min

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

    /*
     * 실제 Android 입력창의 커서 위치
     * UTF-16 기준
     */
    private var lastKnownSelectionStart = -1
    private var lastKnownSelectionEnd = -1

    /*
     * HTML 자판에서 중간 커서 입력을 할 때
     * 네이티브 커서 위치를 잠시 보관
     */
    private var pendingExternalCursorUtf16: Int? = null

    /*
     * Android가 발생시키는 selection callback을
     * HTML 내부 커서 처리와 충돌시키지 않기 위한 시간값
     */
    private var suppressSelectionSyncUntil = 0L

    /*
     * 우리가 직접 selection을 변경한 직후
     * onUpdateSelection()이 다시 들어오는 것을 잠시 무시
     */
    private var internalSelectionUntil = 0L

    /*
     * 외부 커서 입력 중 composing 상태
     */
    private var externalComposingActive = false


    /*
     * 한글 초성
     */
    private val CHOSUNG_FOR_ANDROID = arrayOf(
        "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ",
        "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ",
        "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ",
        "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    )

    /*
     * 한글 중성
     */
    private val JUNGSUNG_FOR_ANDROID = arrayOf(
        "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ",
        "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ",
        "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ",
        "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ",
        "ㅣ"
    )


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


        /*
         * 현재 사용 중인 키보드 높이
         */
        val heightDp = 235

        val heightPx =
            (heightDp * resources.displayMetrics.density).toInt()


        webView = WebView(this).apply {

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            setBackgroundColor(Color.TRANSPARENT)

            /*
             * HTML ↔ Android 연결
             */
            addJavascriptInterface(
                KeyboardBridge(),
                "AndroidBridge"
            )

            webViewClient = object : WebViewClient() {

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(view, url)

                    /*
                     * 페이지가 다시 열린 직후
                     * 실제 커서 위치를 한번 맞춘다.
                     */
                    view?.post {
                        rememberActualSelection()
                    }
                }
            }

            /*
             * 현재 기준 HTML
             */
            loadUrl(
                "file:///android_asset/keyboard.html"
            )
        }


        container.addView(webView)

        return container
    }


    /*
     * HTML 자판에서 Android 입력창을 조작하는 브리지
     */
    inner class KeyboardBridge {


        /*
         * 일반 문자 입력
         */
        @JavascriptInterface
        fun commitText(text: String) {

            val ic = currentInputConnection ?: return

            try {

                /*
                 * 외부 커서 입력이 끝났다면
                 * Android composing 상태를 정리한다.
                 */
                if (externalComposingActive) {
                    try {
                        ic.finishComposingText()
                    } catch (_: Exception) {
                    }

                    externalComposingActive = false
                }

                pendingExternalCursorUtf16 = null

                ic.commitText(text, 1)

                rememberActualSelection()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        /*
         * 조합 중인 글자
         */
        @JavascriptInterface
        fun setComposing(text: String) {

            val ic = currentInputConnection ?: return

            try {

                ic.setComposingText(text, 1)

                externalComposingActive = true

                rememberActualSelection()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        /*
         * HTML에서 실제 Android 커서를 직접 지정
         */
        @JavascriptInterface
        fun setSelection(
            start: Int,
            end: Int
        ) {

            val ic = currentInputConnection ?: return

            try {

                internalSelectionUntil =
                    SystemClock.uptimeMillis() + 250L

                ic.setSelection(start, end)

                lastKnownSelectionStart = start
                lastKnownSelectionEnd = end

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        /*
         * ============================================================
         * 핵심 수정
         *
         * 한글 삭제 순서
         *
         * 한 → 하 → ㅎ → 삭제
         *
         * 기존 방식처럼
         *
         * finishComposingText()
         * +
         * deleteSurroundingText()
         *
         * 만 사용하면 Android 조합 상태 때문에
         * ㄴ / ㅏ / ㅎ 등이 다시 살아나는 문제가 발생할 수 있다.
         *
         * 따라서 완성형 한글을 직접 분석하여
         * 한 글자씩 이전 단계로 되돌린다.
         * ============================================================
         */
        @JavascriptInterface
        fun deleteText() {

            internalSelectionUntil =
                SystemClock.uptimeMillis() + 300L

            val ic = currentInputConnection ?: return

            try {

                /*
                 * ----------------------------------------------------
                 * 1. 선택 영역 삭제
                 * ----------------------------------------------------
                 */
                val selectedText =
                    ic.getSelectedText(
