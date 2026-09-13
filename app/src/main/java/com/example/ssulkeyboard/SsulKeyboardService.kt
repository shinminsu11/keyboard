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

    // 안드로이드 실제 입력창의 커서 위치
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
                }
            }

            loadUrl("file:///android_asset/keyboard.html")
        }

        container.addView(webView)

        return container
    }

    /**
     * 안드로이드 실제 입력창의 커서/선택 위치가 변경될 때 호출됩니다.
     *
     * 사용자가 외부 앱의 입력창을 손가락으로 터치해서
     * 문장 중간으로 커서를 옮긴 경우,
     * Android가 알려주는 실제 커서 위치를
     * HTML 자판의 cursorPos로 전달합니다.
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

        if (!::webView.isInitialized) {
            return
        }

        /*
         * Android의 selection 위치는 UTF-16 기준입니다.
         *
         * 374.html에서는 Array.from() 기준의 cursorPos를
         * 사용하므로 HTML 내부에서 변환합니다.
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
            try {
                webView.evaluateJavascript(js, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    inner class KeyboardBridge {

        /**
         * 일반 문자 확정 입력
         */
        @JavascriptInterface
        fun commitText(text: String) {

            val inputConnection =
                currentInputConnection ?: return

            try {
                inputConnection.commitText(text, 1)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 한글 조합 중인 글자 표시
         */
        @JavascriptInterface
        fun setComposing(text: String) {

            val inputConnection =
                currentInputConnection ?: return

            try {
                inputConnection.setComposingText(text, 1)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * HTML 자판에서 지정한 위치로
         * Android 실제 커서를 이동시킵니다.
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
         * 일반 삭제
         *
         * 기존 374.html의 삭제 구조를 유지합니다.
         *
         * 선택된 글자가 있으면 선택 영역 삭제.
         * 그렇지 않으면 실제 Android 커서 바로 앞의
         * 한 글자를 삭제합니다.
         *
         * UTF-16 surrogate pair도 처리합니다.
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
                 * 실제 Android 커서 바로 앞의 문자를 확인합니다.
                 *
                 * 이모지 등 surrogate pair는
                 * UTF-16 2칸을 삭제합니다.
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
         * 한자 입력 후 한 글자 삭제용
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
         * 검색/Enter 동작
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
         * 설정판의 URL 실행
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
     * HTML 자판의 내부 조합 버퍼를 초기화합니다.
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
