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
import org.json.JSONObject

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

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
                    super.onPageFinished(
                        view,
                        url
                    )
                }
            }

            loadUrl(
                "file:///android_asset/keyboard.html"
            )
        }

        container.addView(webView)

        return container
    }

    /*
     * Android에서 실제 커서가 움직였다는 사실은
     * 여기서 HTML에 강제로 전달하지 않습니다.
     *
     * setComposingText() 과정에서 발생하는
     * onUpdateSelection 때문에 한글 입력 상태가
     * 꼬이는 것을 막습니다.
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
    }

    /*
     * Android 실제 입력창의 상태를 HTML에 맞춥니다.
     */
    private fun syncHtmlWithNativeText() {

        if (!::webView.isInitialized) {
            return
        }

        val inputConnection =
            currentInputConnection ?: return

        try {

            val before =
                inputConnection
                    .getTextBeforeCursor(
                        10000,
                        0
                    )
                    ?.toString()
                    ?: ""

            val after =
                inputConnection
                    .getTextAfterCursor(
                        10000,
                        0
                    )
                    ?.toString()
                    ?: ""

            val beforeJs =
                JSONObject.quote(before)

            val afterJs =
                JSONObject.quote(after)

            val js = """
                (function() {
                    if (window.syncNativeText) {
                        window.syncNativeText(
                            $beforeJs,
                            $afterJs
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

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(
            text: String
        ) {

            val inputConnection =
                currentInputConnection
                    ?: return

            try {

                inputConnection.commitText(
                    text,
                    1
                )

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setComposing(
            text: String
        ) {

            val inputConnection =
                currentInputConnection
                    ?: return

            try {

                inputConnection.setComposingText(
                    text,
                    1
                )

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setSelection(
            start: Int,
            end: Int
        ) {

            val inputConnection =
                currentInputConnection
                    ?: return

            try {

                inputConnection.setSelection(
                    start,
                    end
                )

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        /*
         * =====================================================
         * 삭제
         * =====================================================
         *
         * 가장 중요한 부분입니다.
         *
         * nativeCursorStart 같은 오래된 값을 사용하지 않고
         * 삭제 버튼을 누른 바로 그 순간 Android에게
         * 현재 커서 앞/뒤 내용을 물어봅니다.
         */
        @JavascriptInterface
        fun deleteText() {

            val inputConnection =
                currentInputConnection
                    ?: return

            try {

                /*
                 * 1. 현재 선택 영역 확인
                 */
                val selectedText =
                    inputConnection.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {

                    inputConnection.commitText(
                        "",
                        1
                    )

                    syncHtmlWithNativeText()

                    return
                }

                /*
                 * 2. 삭제 버튼을 누른 바로 그 순간의
                 * 실제 커서 앞/뒤 내용을 확보합니다.
                 *
                 * 여기서 커서 위치를 결정합니다.
                 */
                val textBefore =
                    inputConnection
                        .getTextBeforeCursor(
                            10000,
                            0
                        )
                        ?.toString()
                        ?: ""

                val textAfter =
                    inputConnection
                        .getTextAfterCursor(
                            10000,
                            0
                        )
                        ?.toString()
                        ?: ""

                if (textBefore.isEmpty()) {
                    return
                }

                /*
                 * 현재 Android 커서의 UTF-16 위치입니다.
                 */
                val cursorUtf16 =
                    textBefore.length

                /*
                 * 3. 현재 조합 상태를 종료합니다.
                 *
                 * 단, 종료하기 전에 커서 위치를 확보했기
                 * 때문에 이후 다시 정확한 위치로 돌아갑니다.
                 */
                inputConnection.finishComposingText()

                /*
                 * 4. finishComposingText() 이후 Android가
                 * 커서를 다른 위치로 바꿀 가능성에 대비하여
                 * 원래 커서 위치를 다시 지정합니다.
                 */
                inputConnection.setSelection(
                    cursorUtf16,
                    cursorUtf16
                )

                /*
                 * 5. 다시 현재 커서 앞 2글자를 확인합니다.
                 */
                val beforeDelete =
                    inputConnection
                        .getTextBeforeCursor(
                            2,
                            0
                        )
                        ?.toString()
                        ?: ""

                if (beforeDelete.isEmpty()) {
                    return
                }

                /*
                 * 6. 커서 바로 앞 한 글자 삭제.
                 *
                 * 이모지처럼 UTF-16 surrogate pair인 경우
                 * 2개를 삭제합니다.
                 */
                if (beforeDelete.length >= 2) {

                    val high =
                        beforeDelete[
                            beforeDelete.length - 2
                        ]

                    val low =
                        beforeDelete[
                            beforeDelete.length - 1
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

                /*
                 * 7. 삭제가 끝난 실제 Android 상태를
                 * HTML 자판에 반영합니다.
                 */
                syncHtmlWithNativeText()

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {

            val inputConnection =
                currentInputConnection
                    ?: return

            try {

                val textBefore =
                    inputConnection
                        .getTextBeforeCursor(
                            10000,
                            0
                        )
                        ?.toString()
                        ?: ""

                if (textBefore.isEmpty()) {
                    return
                }

                val cursorUtf16 =
                    textBefore.length

                inputConnection.finishComposingText()

                inputConnection.setSelection(
                    cursorUtf16,
                    cursorUtf16
                )

                inputConnection.deleteSurroundingText(
                    1,
                    0
                )

                syncHtmlWithNativeText()

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun performSearch() {

            val inputConnection =
                currentInputConnection
                    ?: return

            try {

                inputConnection.performEditorAction(
                    EditorInfo.IME_ACTION_SEARCH
                )

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun openUrl(
            url: String
        ) {

            try {

                val intent =
                    Intent(
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

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean
    ) {

        super.onStartInputView(
            info,
            restarting
        )

        if (::webView.isInitialized) {

            webView.post {

                webView.evaluateJavascript(
                    """
                    (function() {
                        if (window.resetKeyboardBuffer) {
                            window.resetKeyboardBuffer();
                        }
                    })();
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
