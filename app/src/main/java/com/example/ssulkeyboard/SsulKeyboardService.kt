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

    // 안드로이드 실제 입력창의 커서 / 선택 위치
    private var nativeCursorStart = 0
    private var nativeCursorEnd = 0

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
                     * HTML에 Android 실제 커서 위치를
                     * 받을 수 있는 함수를 만들어 둡니다.
                     *
                     * HTML 원본을 직접 수정하지 않아도
                     * Kotlin에서 이 함수를 만들어 사용할 수 있습니다.
                     */
                    val js = """
                        (function() {

                            window.setNativeCursorPosition = function(start, end) {

                                try {

                                    /*
                                     * 선택 영역이 있으면 일단
                                     * 시작 위치를 기준으로 합니다.
                                     */
                                    var utf16Pos = Number(start);

                                    if (isNaN(utf16Pos)) {
                                        return;
                                    }

                                    if (typeof committedText !== 'string') {
                                        return;
                                    }

                                    /*
                                     * Android의 selection 위치는 UTF-16 기준.
                                     *
                                     * HTML의 cursorPos는 Array.from()
                                     * 기준의 문자 위치이므로 변환합니다.
                                     */
                                    var before =
                                        committedText.substring(0, utf16Pos);

                                    cursorPos =
                                        Array.from(before).length;

                                    /*
                                     * 외부 앱에서 커서를 움직였으므로
                                     * 현재 조합 중인 한글 버퍼는 초기화합니다.
                                     */
                                    hangulBuffer = {
                                        cho: -1,
                                        jung: -1,
                                        jong: -1
                                    };

                                    lastSlideDir = '';

                                    if (typeof updateScreen === 'function') {
                                        updateScreen();
                                    }

                                } catch (e) {
                                    console.log(
                                        'setNativeCursorPosition error:',
                                        e
                                    );
                                }
                            };

                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(js, null)

                    /*
                     * 페이지가 다시 로드된 경우에도
                     * 현재 Android 커서 위치를 전달합니다.
                     */
                    syncNativeCursorToHtml()
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
     * Android 실제 입력창의 커서 위치를
     * 현재 HTML 자판에 전달합니다.
     */
    private fun syncNativeCursorToHtml() {

        if (!::webView.isInitialized) {
            return
        }

        val start = nativeCursorStart
        val end = nativeCursorEnd

        val js = """
            (function() {

                if (window.setNativeCursorPosition) {

                    window.setNativeCursorPosition(
                        $start,
                        $end
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

    /**
     * 실제 앱의 입력창에서
     * 사용자가 손으로 커서를 이동하면 호출됩니다.
     *
     * 예:
     *
     * 가나다라마
     *     ↑
     * 손터치
     *
     * Android가 실제 커서 위치를 알려주면
     * 그 위치를 HTML의 cursorPos와 동기화합니다.
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

        syncNativeCursorToHtml()
    }

    inner class KeyboardBridge {

        /**
         * 일반 문자 입력
         */
        @JavascriptInterface
        fun commitText(text: String) {

            val inputConnection =
                currentInputConnection ?: return

            inputConnection.commitText(
                text,
                1
            )
        }

        /**
         * 한글 조합 입력
         */
        @JavascriptInterface
        fun setComposing(text: String) {

            val inputConnection =
                currentInputConnection ?: return

            inputConnection.setComposingText(
                text,
                1
            )
        }

        /**
         * HTML에서 Android 실제 커서를 직접 이동시킬 때 사용
         */
        @JavascriptInterface
        fun setSelection(
            start: Int,
            end: Int
        ) {

            val inputConnection =
                currentInputConnection ?: return

            try {

                inputConnection.setSelection(
                    start,
                    end
                )

                nativeCursorStart = start
                nativeCursorEnd = end

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        /**
         * 기존 정상 삭제 기능
         *
         * 이 부분은 기존 코드 그대로 유지합니다.
         */
        @JavascriptInterface
        fun deleteText() {

            val inputConnection =
                currentInputConnection ?: return

            try {

                /*
                 * 선택된 글자가 있다면
                 * 선택 영역 자체를 삭제합니다.
                 */
                val selectedText =
                    inputConnection.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {

                    inputConnection.commitText(
                        "",
                        1
                    )

                    return
                }

                /*
                 * 커서 바로 앞의 문자를 확인합니다.
                 */
                val textBefore =
                    inputConnection.getTextBeforeCursor(
                        2,
                        0
                    )

                if (!textBefore.isNullOrEmpty()) {

                    /*
                     * 이모지처럼 UTF-16 surrogate pair인 경우
                     * 2칸 삭제합니다.
                     */
                    if (textBefore.length >= 2) {

                        val high =
                            textBefore[textBefore.length - 2]

                        val low =
                            textBefore[textBefore.length - 1]

                        if (
                            Character.isSurrogatePair(
                                high,
                                low
                            )
                        ) {

                            inputConnection.deleteSurroundingText(
                                2,
                                0
                            )

                        } else {

                            inputConnection.deleteSurroundingText(
                                1,
                                0
                            )
                        }

                    } else {

                        inputConnection.deleteSurroundingText(
                            1,
                            0
                        )
                    }
                }

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        /**
         * 한자 삭제
         *
         * 기존 기능 유지
         */
        @JavascriptInterface
        fun deleteOneCharForHanja() {

            val inputConnection =
                currentInputConnection ?: return

            try {

                inputConnection.finishComposingText()

                inputConnection.deleteSurroundingText(
                    1,
                    0
                )

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        /**
         * 검색 / Enter
         */
        @JavascriptInterface
        fun performSearch() {

            val inputConnection =
                currentInputConnection ?: return

            inputConnection.performEditorAction(
                EditorInfo.IME_ACTION_SEARCH
            )
        }

        /**
         * URL 실행
         */
        @JavascriptInterface
        fun openUrl(url: String) {

            try {

                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                ).apply {

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

                startActivity(intent)

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }
    }

    /**
     * 입력창이 시작될 때
     *
     * 기존 키보드 버퍼 초기화 기능 유지
     */
    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean
    ) {

        super.onStartInputView(
            info,
            restarting
        )

        nativeCursorStart = 0
        nativeCursorEnd = 0

        if (::webView.isInitialized) {

            webView.post {

                webView.evaluateJavascript(
                    "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
                    null
                )
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
