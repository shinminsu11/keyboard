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

    // 안드로이드 실제 입력창의 커서 위치
    private var nativeCursorStart = 0
    private var nativeCursorEnd = 0

    // 자판이 직접 입력한 뒤의 예상 커서 위치
    // 외부 앱에서 손가락으로 커서를 움직였는지 구별하기 위해 사용
    private var expectedCursorStart = -1
    private var expectedCursorEnd = -1

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

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            setBackgroundColor(Color.TRANSPARENT)

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
                     * 외부 앱에서 손가락으로 커서를 움직였을 때
                     * HTML 자판의 cursorPos만 조정한다.
                     *
                     * 중요:
                     * 여기서는 updateScreen()을 호출하지 않는다.
                     * 그래야 setComposingText()와 충돌하지 않는다.
                     */
                    val js = """
                        (function() {

                            window.setNativeCursorPosition = function(start, end) {

                                try {

                                    if (typeof committedText !== 'string') {
                                        return;
                                    }

                                    if (typeof cursorPos !== 'number') {
                                        return;
                                    }

                                    var text = committedText;

                                    /*
                                     * Android selection 위치는 UTF-16 기준.
                                     * JavaScript Array.from()은 유니코드 문자 기준.
                                     */
                                    var safeStart = Math.max(
                                        0,
                                        Math.min(start, text.length)
                                    );

                                    var before =
                                        text.substring(0, safeStart);

                                    cursorPos =
                                        Array.from(before).length;

                                    /*
                                     * 외부 앱에서 커서를 움직였으므로
                                     * 현재 한글 조합 버퍼는 초기화한다.
                                     */
                                    if (typeof hangulBuffer !== 'undefined') {
                                        hangulBuffer = {
                                            cho: -1,
                                            jung: -1,
                                            jong: -1
                                        };
                                    }

                                    lastSlideDir = '';

                                    /*
                                     * 여기서는 updateScreen()을 호출하지 않는다.
                                     * 실제 다음 글자 입력 때 기존 updateScreen()이
                                     * 정상적으로 화면을 갱신한다.
                                     */

                                } catch (e) {
                                    console.log(
                                        'setNativeCursorPosition error:',
                                        e
                                    );
                                }
                            };

                        })();
                    """.trimIndent()

                    webView.post {
                        webView.evaluateJavascript(js, null)
                    }
                }
            }

            loadUrl(
                "file:///android_asset/keyboard.html"
            )
        }

        container.addView(webView)

        return container
    }

    /**
     * 외부 앱의 실제 커서 위치가 바뀔 때 호출된다.
     *
     * 중요한 점:
     * 자판이 직접 입력해서 발생한 selection 변경은 무시하고,
     * 외부 앱에서 손가락으로 커서를 옮긴 경우에만 HTML cursorPos를
     * 동기화한다.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {

        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )

        nativeCursorStart = newSelStart
        nativeCursorEnd = newSelEnd

        if (!::webView.isInitialized) {
            return
        }

        /*
         * 우리가 방금 입력해서 만들어진 커서 이동이면
         * HTML 쪽 cursorPos를 다시 건드리지 않는다.
         */
        if (
            expectedCursorStart == newSelStart &&
            expectedCursorEnd == newSelEnd
        ) {
            return
        }

        /*
         * 선택 영역이 있는 경우에는 여기서 건드리지 않는다.
         *
         * 사용자가 선택 삭제를 요구한 것이 아니므로
         * 기존 삭제 기능을 그대로 유지한다.
         */
        if (newSelStart != newSelEnd) {
            return
        }

        /*
         * 외부 앱에서 손가락으로 커서를 움직인 것으로 판단.
         *
         * HTML의 cursorPos만 변경한다.
         * updateScreen()이나 setComposingText()는 호출하지 않는다.
         */
        val js = """
            (function() {

                if (window.setNativeCursorPosition) {

                    window.setNativeCursorPosition(
                        $newSelStart,
                        $newSelEnd
                    );

                }

            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(
                js,
                null
            )
        }
    }

    inner class KeyboardBridge {

        /**
         * 일반 글자 확정 입력
         */
        @JavascriptInterface
        fun commitText(text: String) {

            val inputConnection =
                currentInputConnection ?: return

            try {

                inputConnection.commitText(
                    text,
                    1
                )

                /*
                 * commitText 이후 실제 Android 커서 위치를
                 * 다시 읽어서 예상 위치로 저장한다.
                 */
                val before =
                    inputConnection.getTextBeforeCursor(
                        10000,
                        0
                    )

                if (before != null) {

                    expectedCursorStart =
                        before.length

                    expectedCursorEnd =
                        before.length

                    nativeCursorStart =
                        before.length

                    nativeCursorEnd =
                        before.length
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 한글 조합 입력
         *
         * 기존 동작 그대로 유지.
         */
        @JavascriptInterface
        fun setComposing(text: String) {

            val inputConnection =
                currentInputConnection ?: return

            try {

                inputConnection.setComposingText(
                    text,
                    1
                )

                /*
                 * setComposingText가 실제 Android 커서를
                 * 이동시킨 뒤 그 위치를 기억한다.
                 *
                 * 다음 onUpdateSelection에서 같은 위치가 오면
                 * 자판이 만든 이동으로 판단하여
