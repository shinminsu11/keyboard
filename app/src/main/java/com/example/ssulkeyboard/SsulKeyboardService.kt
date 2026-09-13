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

    // Android가 마지막으로 알려준 실제 커서 위치
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

    /*
     * Android 실제 커서 위치를 저장만 합니다.
     *
     * HTML의 커서 상태를 여기서 건드리지 않습니다.
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

    /*
     * Android 실제 입력창의 내용을 HTML에 동기화합니다.
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

                nativeCursorStart = start
                nativeCursorEnd = end

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
                 * ★ 핵심 ★
                 *
                 * 삭제 명령이 들어온 순간의 커서 위치를
                 * 먼저 보관합니다.
                 *
                 * 예:
                 *
                 * 가나다라마
                 * ^
                 *
                 * 또는
                 *
                 * 가|나다라마
                 *
                 * 여기서 Android 내부 상태가 바뀌더라도
                 * 원래 위치를 잃지 않도록 합니다.
                 */
                val deleteStart = nativeCursorStart
                val deleteEnd = nativeCursorEnd

                /*
                 * 선택 영역이 있으면 선택 영역 삭제.
                 */
                if (deleteStart != deleteEnd) {

                    val start =
                        minOf(
                            deleteStart,
                            deleteEnd
                        )

                    val end =
                        maxOf(
                            deleteStart,
                            deleteEnd
                        )

                    inputConnection.setSelection(
                        start,
                        end
                    )

                    inputConnection.commitText(
                        "",
                        1
                    )

                    nativeCursorStart = start
                    nativeCursorEnd = start

                    syncHtmlWithNativeText()
                    return
                }

                /*
                 * ★ 중요 ★
                 *
                 * finishComposingText()를 삭제 전에
                 * 무조건 호출하지 않습니다.
                 *
                 * 이것 때문에 이전 버전에서는
                 * 커서가 '마' 뒤로 이동하면서
                 *
                 * 마 → 라 → 다 → 나 → 가
                 *
                 * 순서로 삭제되는 문제가 생겼습니다.
                 */

                /*
                 * 현재 커서 위치를 확실하게 복원합니다.
                 */
                inputConnection.setSelection(
                    deleteStart,
                    deleteStart
                )

                /*
                 * 커서 앞의 글자를 확인합니다.
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

                /*
                 * 삭제된 뒤 실제 커서 위치는 한 칸 앞으로 갑니다.
                 */
                nativeCursorStart =
                    maxOf(
                        0,
                        deleteStart - 1
                    )

                nativeCursorEnd =
                    nativeCursorStart

                /*
                 * 실제 Android 문자 상태를 HTML과 맞춥니다.
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

                val deleteStart =
                    nativeCursorStart

                val deleteEnd =
                    nativeCursorEnd

                if (deleteStart != deleteEnd) {

                    val start =
                        minOf(
                            deleteStart,
                            deleteEnd
                        )

                    val end =
                        maxOf(
                            deleteStart,
                            deleteEnd
                        )

                    inputConnection.setSelection(
                        start,
                        end
                    )

                    inputConnection.commitText(
                        "",
                        1
                    )

                    nativeCursorStart = start
                    nativeCursorEnd = start

                } else {

                    inputConnection.setSelection(
                        deleteStart,
                        deleteStart
                    )

                    inputConnection.deleteSurroundingText(
                        1,
                        0
                    )

                    nativeCursorStart =
                        maxOf(
                            0,
                            deleteStart - 1
                        )

                    nativeCursorEnd =
                        nativeCursorStart
                }

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

        nativeCursorStart = 0
        nativeCursorEnd = 0

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
