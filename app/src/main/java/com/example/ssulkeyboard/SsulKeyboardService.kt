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

    // 안드로이드 실제 입력창의 커서 위치
    private var nativeCursorStart = 0
    private var nativeCursorEnd = 0

    /*
     * 쓸기자판 자체가 commitText / setComposingText를 실행한 직후에는
     * Android가 onUpdateSelection()을 다시 호출할 수 있습니다.
     *
     * 그때 그 selection을 HTML cursorPos에 다시 전달하면
     * 한글 조합 상태가 깨질 수 있으므로 잠시 무시합니다.
     *
     * 사용자가 외부 입력창을 손가락으로 터치해서 움직인 커서는
     * 이 시간이 지난 뒤 정상적으로 HTML에 전달됩니다.
     */
    private var ignoreSelectionUntil = 0L

    private fun ignoreOwnSelection() {
        ignoreSelectionUntil =
            SystemClock.uptimeMillis() + 300L
    }

    private fun isOwnSelectionIgnored(): Boolean {
        return SystemClock.uptimeMillis() < ignoreSelectionUntil
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
     * Android 실제 입력창의 selection이 바뀔 때 호출됩니다.
     *
     * 중요:
     *
     * 1. 쓸기자판이 직접 발생시킨 selection 변경은 무시합니다.
     *
     * 2. 사용자가 외부 앱의 입력창을 손가락으로 터치하여
     *    실제 커서를 이동한 경우에는 HTML cursorPos에 전달합니다.
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
         * 쓸기자판이 방금 입력한 글자 때문에 발생한
         * selection callback이면 HTML에 전달하지 않습니다.
         *
         * 이것이 '가' 입력 후 ㄱ만 지워지는 문제를 막는 핵심입니다.
         */
        if (isOwnSelectionIgnored()) {
            return
        }

        if (!::webView.isInitialized) {
            return
        }

        /*
         * 외부 앱에서 손가락으로 옮긴 실제 커서 위치를
         * 374.html에 전달합니다.
         *
         * Android selection은 UTF-16 기준입니다.
         * HTML 쪽에서 Array.from() 기준으로 변환합니다.
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
         * 일반 문자 확정 입력
         */
        @JavascriptInterface
        fun commitText(text: String) {

            val inputConnection =
                currentInputConnection ?: return

            try {

                /*
                 * 이 commitText()는 쓸기자판이 직접 실행하는 것이므로
                 * 뒤따라오는 onUpdateSelection()을 HTML에 전달하지 않습니다.
                 */
                ignoreOwnSelection()

                inputConnection.commitText(
                    text,
                    1
                )

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

                /*
                 * setComposingText() 역시 Android selection callback을
                 * 발생시킬 수 있으므로 HTML 커서 동기화를 잠시 막습니다.
                 */
                ignoreOwnSelection()

                inputConnection.setComposingText(
                    text,
                    1
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * HTML 자판에서 Android 실제 커서를
         * 지정한 위치로 이동시킵니다.
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
                 * 이것도 HTML 자판이 직접 요청한 selection 변경입니다.
                 */
                ignoreOwnSelection()

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
         * 374.html의 기존 삭제 동작을 유지합니다.
         *
         * 선택 영역이 있으면 선택 영역 삭제.
         * 선택 영역이 없으면 실제 Android 커서 바로 앞을 삭제합니다.
         *
         * UTF-16 surrogate pair도 처리합니다.
         */
        @JavascriptInterface
        fun deleteText() {

            val inputConnection =
                currentInputConnection ?: return

            try {

                /*
                 * 선택된 글자가 있으면 선택 영역 자체를 삭제합니다.
                 */
                val selectedText =
                    inputConnection.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {

                    ignoreOwnSelection()

                    inputConnection.commitText(
                        "",
                        1
                    )

                    return
                }

                /*
                 * 실제 커서 바로 앞의 텍스트를 가져옵니다.
                 *
                 * 이모지처럼 UTF-16 surrogate pair인 경우
                 * 2칸을 삭제합니다.
                 */
                val textBefore =
                    inputConnection.getTextBeforeCursor(
                        2,
                        0
                    )

                if (!textBefore.isNullOrEmpty()) {

                    ignoreOwnSelection()

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
         * 한자 입력 후 한 글자 삭제
         */
        @JavascriptInterface
        fun deleteOneCharForHanja() {

            val inputConnection =
                currentInputConnection ?: return

            try {

                ignoreOwnSelection()

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
     * HTML 자판 내부 버퍼를 초기화합니다.
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

        ignoreSelectionUntil = 0L

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
