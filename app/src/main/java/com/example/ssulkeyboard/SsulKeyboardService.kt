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

    /*
     * 입력 중 Android의 선택 위치 변화가
     * HTML 한글 조합 상태를 강제로 변경하지 않도록 합니다.
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
     * Android 실제 입력창의 현재 내용을
     * HTML 자판의 committedText / cursorPos와 맞춥니다.
     */
    private fun syncHtmlWithNativeText() {

        if (!::webView.isInitialized) return

        val inputConnection =
            currentInputConnection ?: return

        try {

            val before =
                inputConnection
                    .getTextBeforeCursor(10000, 0)
                    ?.toString()
                    ?: ""

            val after =
                inputConnection
                    .getTextAfterCursor(10000, 0)
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteText() {

            val inputConnection =
                currentInputConnection ?: return

            try {

                /*
                 * ★ 핵심 수정 ★
                 *
                 * 마지막 글자가 Android의 조합 상태에
                 * 남아 있을 수 있습니다.
                 *
                 * 예:
                 * 가나다라[마]
                 *
                 * 이 상태에서 외부 앱에서 '가' 위치로
                 * 커서를 옮긴 뒤 삭제하면 조합 상태인
                 * '마' 때문에 삭제 위치가 꼬일 수 있습니다.
                 *
                 * 삭제하기 전에 조합 상태를 확정합니다.
                 */
                inputConnection.finishComposingText()

                /*
                 * 선택 영역이 있으면
                 * 선택된 글자를 삭제합니다.
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
                 * 커서 바로 앞의 문자 확인
                 */
                val textBefore =
                    inputConnection.getTextBeforeCursor(
                        2,
                        0
                    )

                if (!textBefore.isNullOrEmpty()) {

                    /*
                     * 이모지처럼 UTF-16 surrogate pair를
                     * 사용하는 문자는 2개 단위로 삭제합니다.
                     */
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

                /*
                 * 삭제 후 실제 Android 입력 내용을
                 * HTML 자판 내부 상태에 다시 반영합니다.
                 */
                syncHtmlWithNativeText()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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

                syncHtmlWithNativeText()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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
