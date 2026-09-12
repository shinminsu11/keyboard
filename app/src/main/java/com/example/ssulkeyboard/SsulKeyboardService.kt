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

    // Android 실제 입력창의 마지막 선택/커서 위치
    private var nativeCursorStart = 0
    private var nativeCursorEnd = 0

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
     * Android 실제 입력창의 커서/선택 위치가 바뀔 때 호출됩니다.
     *
     * 여기서는 위치만 기억합니다.
     *
     * 중요:
     * HTML에 다시 커서 위치를 밀어 넣지 않습니다.
     *
     * HTML과 Android 사이에서 서로 커서를 덮어쓰는
     * 경쟁 상태(race condition)를 막기 위한 것입니다.
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

                inputConnection.commitText(
                    text,
                    1
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        /**
         * 한글 조합 입력
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

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        /**
         * Android 실제 입력창의 커서/선택 위치 지정
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
         * 삭제
         *
         * 우선순위:
         *
         * 1. 실제 선택 영역이 있으면 선택 영역 전체 삭제
         * 2. 선택 영역이 없으면 커서 앞 1글자 삭제
         *
         * 이모지/유니코드 문자도 code point 기준으로 삭제합니다.
         */
        @JavascriptInterface
        fun deleteText() {

            val inputConnection =
                currentInputConnection ?: return

            try {

                /*
                 * --------------------------------------------------
                 * 1. 현재 Android 입력창에 실제 선택 영역이 있는지 확인
                 * --------------------------------------------------
                 */
                val selectedText =
                    inputConnection.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {

                    /*
                     * 선택된 영역 자체를 삭제합니다.
                     *
                     * 예:
                     * 가나다라마바
                     * 가나다 선택
                     * 삭제
                     * → 라마바
                     */
                    inputConnection.commitText(
                        "",
                        1
                    )

                    nativeCursorStart =
                        inputConnection.getTextBeforeCursor(
                            100000,
                            0
                        )?.length ?: 0

                    nativeCursorEnd =
                        nativeCursorStart

                    return
                }


                /*
                 * --------------------------------------------------
                 * 2. 선택이 없을 경우
                 * --------------------------------------------------
                 *
                 * 커서 앞의 문자 하나를 삭제합니다.
                 *
                 * deleteSurroundingTextInCodePoints()
                 * 를 사용하면 이모지 같은 surrogate pair도
                 * 한 글자로 처리할 수 있습니다.
                 */
                inputConnection.deleteSurroundingTextInCodePoints(
                    1,
                    0
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        /**
         * 한자 입력 후 한 글자 삭제
         *
         * 기존 동작 유지
         */
        @JavascriptInterface
        fun deleteOneCharForHanja() {

            val inputConnection =
                currentInputConnection ?: return

            try {

                inputConnection.finishComposingText()

                inputConnection.deleteSurroundingTextInCodePoints(
                    1,
                    0
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        /**
         * 검색 실행
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
         * 외부 URL 실행
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
     * 키보드가 새 입력창에 연결될 때
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
                    """
                    if (window.resetKeyboardBuffer) {
                        window.resetKeyboardBuffer();
                    }
                    """.trimIndent(),
                    null
                )
            }
        }
    }


    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
