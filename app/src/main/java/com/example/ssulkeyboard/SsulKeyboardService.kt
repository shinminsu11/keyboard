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

    // Android 실제 입력창의 현재 커서 위치
    private var nativeCursorStart = 0
    private var nativeCursorEnd = 0

    /*
     * 쓸기자판이 직접 발생시킨 selection callback인지 표시합니다.
     *
     * 자판이 setComposingText(), commitText(), setSelection()
     * 을 실행하면 Android가 onUpdateSelection()을 호출할 수 있습니다.
     *
     * 이때 그 callback을 HTML의 cursorPos에 다시 전달하면
     * 한글 조합 상태가 깨질 수 있습니다.
     *
     * 따라서 "다음 selection callback 하나"만 무시합니다.
     */
    private var ignoreNextSelection = false

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
     * Android 입력창의 실제 selection이 바뀔 때 호출됩니다.
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

        if (newSelStart < 0) {
            return
        }

        nativeCursorStart = newSelStart
        nativeCursorEnd = newSelEnd

        /*
         * 쓸기자판이 직접 만든 selection 변화라면
         * 이번 callback만 HTML로 보내지 않습니다.
         *
         * 중요:
         * 이 플래그는 여기서 바로 해제합니다.
         * 그래서 깐띄기 같은 다음 동작까지 막지 않습니다.
         */
        if (ignoreNextSelection) {

            ignoreNextSelection = false

            return
        }

        /*
         * 사용자가 외부 앱의 입력창을 손가락으로 눌러
         * 실제 커서를 이동한 경우입니다.
         *
         * 이 경우에는 HTML 자판의 cursorPos도
         * 실제 Android 커서 위치에 맞춥니다.
         */
        if (!::webView.isInitialized) {
            return
        }

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

            try {

                webView.evaluateJavascript(
                    js,
                    null
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
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

                /*
                 * commitText() 자체가 발생시키는
                 * selection callback은 HTML 커서에 다시 전달하지 않습니다.
                 */
                ignoreNextSelection = true

                inputConnection.commitText(
                    text,
                    1
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 한글 조합문자 입력
         */
        @JavascriptInterface
        fun setComposing(text: String) {

            val inputConnection =
                currentInputConnection ?: return

            try {

                /*
                 * setComposingText()가 발생시키는
                 * selection callback을 한 번 무시합니다.
                 *
                 * 이것이 '가'에서 ㄱ만 지워지는 문제를 막는 핵심입니다.
                 */
                ignoreNextSelection = true

                inputConnection.setComposingText(
                    text,
                    1
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * Android 실제 커서를 지정 위치로 이동
         */
        @JavascriptInterface
        fun setSelection(
            start: Int,
            end: Int
        ) {

            val inputConnection =
                currentInputConnection ?: return

            try {

                /*
                 * HTML 자판이 직접 요청한 커서 이동입니다.
                 */
                ignoreNextSelection = true

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
         * 일반 삭제
         *
         * 기존 삭제 동작을 유지합니다.
         */
        @JavascriptInterface
        fun deleteText() {

            val inputConnection =
                currentInputConnection ?: return

            try {

                /*
                 * 선택 영역이 있으면 선택된 내용을 삭제합니다.
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
                 * 커서 앞의 최대 2 UTF-16 단위를 가져옵니다.
                 */
                val textBefore =
                    inputConnection.getTextBeforeCursor(
                        2,
                        0
                    )

                if (!textBefore.isNullOrEmpty()) {

                    if (textBefore.length >= 2) {

                        val high =
                            textBefore[
                                textBefore.length - 2
                            ]

                        val low =
                            textBefore[
                                textBefore.length - 1
                            ]

                        /*
                         * 이모지처럼 surrogate pair인 경우
                         * 두 UTF-16 단위를 함께 삭제합니다.
                         */
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
         * 한자 입력 후 한 글자 삭제
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
         * 검색
         */
        @JavascriptInterface
        fun performSearch() {

            val inputConnection =
                currentInputConnection ?: return

            try {

                inputConnection.performEditorAction(
                    EditorInfo.IME_ACTION_SEARCH
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * URL 열기
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
     * 새로운 입력창이 시작될 때
     * HTML 자판 내부 상태를 초기화합니다.
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

        ignoreNextSelection = false

        if (::webView.isInitialized) {

            webView.post {

                try {

                    webView.evaluateJavascript(
                        """
                        javascript:
                        if (window.resetKeyboardBuffer) {
                            window.resetKeyboardBuffer();
                        }
                        """.trimIndent(),
                        null
                    )

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
