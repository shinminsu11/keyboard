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

    // 마지막으로 자판이 직접 만든 것으로 확인한 실제 앱 커서 위치입니다.
    // onUpdateSelection에서 이 값과 다를 때만 "사용자가 앱 화면을 직접
    // 터치해서 커서를 옮겼다"고 보고 HTML과 동기화합니다.
    private var lastKnownSelectionStart = -1
    private var lastKnownSelectionEnd = -1

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
        val heightPx = (heightDp * resources.displayMetrics.density).toInt()

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)

            addJavascriptInterface(KeyboardBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }
            }

            loadUrl("file:///android_asset/keyboard.html")
        }

        container.addView(webView)
        return container
    }

    /** 실제 입력창의 현재 커서 위치를 기록합니다. */
    private fun rememberActualSelection() {
        val ic = currentInputConnection ?: return
        try {
            val before = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(10000, 0)?.length ?: 0

            // collapsed cursor를 기본으로 기록합니다.
            // 실제 선택 영역은 setSelection()에서 별도로 기록합니다.
            if (after >= 0) {
                lastKnownSelectionStart = before
                lastKnownSelectionEnd = before
            }
        } catch (_: Exception) {
        }
    }

    /** HTML 자판을 실제 앱의 텍스트/커서 상태와 맞춥니다. */
    private fun syncHtmlWithNativeText() {
        if (!::webView.isInitialized) return
        val ic = currentInputConnection ?: return

        try {
            val before = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
            val after = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""

            val beforeJs = org.json.JSONObject.quote(before)
            val afterJs = org.json.JSONObject.quote(after)

            webView.post {
                webView.evaluateJavascript(
                    """
                    (function() {
                        if (window.syncNativeText) {
                            window.syncNativeText($beforeJs, $afterJs);
                        }
                    })();
                    """.trimIndent(),
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
            val ic = currentInputConnection ?: return
            try {
                ic.commitText(text, 1)
                // commitText 후 실제 위치를 다시 기록합니다.
                rememberActualSelection()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            val ic = currentInputConnection ?: return
            try {
                ic.setComposingText(text, 1)
                // 조합문자 입력으로 앱 커서가 이동한 위치를 기억합니다.
                rememberActualSelection()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setSelection(start: Int, end: Int) {
            val ic = currentInputConnection ?: return
            try {
                ic.setSelection(start, end)
                // 이 이동은 자판이 직접 요청한 이동이므로 다음 callback에서
                // 외부 터치로 오인하지 않도록 먼저 기록합니다.
                lastKnownSelectionStart = start
                lastKnownSelectionEnd = end
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteText() {
            val ic = currentInputConnection ?: return

            try {
                val selectedText = ic.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {
                    ic.commitText("", 1)
                    rememberActualSelection()
                    syncHtmlWithNativeText()
                    return
                }

                // 문장 끝에서 마지막 한글이 Android의 composing 상태로 남아 있으면
                // 일반 deleteSurroundingText()가 그 글자를 지우지 못할 수 있습니다.
                // 이 경우에만 먼저 조합을 확정한 뒤 삭제합니다.
                // 문장 중간에서는 finishComposingText()를 호출하지 않아 현재 커서 위치를 보존합니다.
                val textAfter = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
                if (textAfter.isEmpty()) {
                    try {
                        ic.finishComposingText()
                    } catch (_: Exception) {
                    }
                }

                val textBefore = ic.getTextBeforeCursor(2, 0)

                if (!textBefore.isNullOrEmpty() && textBefore.length >= 2) {
                    val high = textBefore[textBefore.length - 2]
                    val low = textBefore[textBefore.length - 1]

                    if (Character.isSurrogatePair(high, low)) {
                        ic.deleteSurroundingText(2, 0)
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                } else if (!textBefore.isNullOrEmpty()) {
                    ic.deleteSurroundingText(1, 0)
                }

                rememberActualSelection()
                syncHtmlWithNativeText()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {
            val ic = currentInputConnection ?: return
            try {
                ic.finishComposingText()
                ic.deleteSurroundingText(1, 0)
                rememberActualSelection()
                syncHtmlWithNativeText()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun performSearch() {
            val ic = currentInputConnection ?: return
            ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

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

        if (!::webView.isInitialized || newSelStart < 0 || newSelEnd < 0) return

        // 실제 한글 조합 중 Android가 보내는 selection callback은
        // candidates 영역을 함께 가지고 옵니다. 이 callback은 웹 자판의
        // 조합 버퍼를 초기화하면 안 됩니다.
        if (candidatesStart >= 0 || candidatesEnd >= 0) {
            return
        }

        // 자판이 방금 만든 selection callback이면 무시합니다.
        if (newSelStart == lastKnownSelectionStart &&
            newSelEnd == lastKnownSelectionEnd
        ) {
            return
        }

        // 실제 앱 화면에서 사용자가 손가락으로 커서를 옮긴 경우입니다.
        // 이때 기존 HTML 자판의 마지막 조합문자(예: 끝의 '마')가
        // Android의 composing 영역에 남아 있으면, 새 글자를 가운데에
        // 입력할 때 Android가 그 조합문자를 대신할 수 있습니다.
        // 먼저 기존 composing 상태만 확정하고, 실제 앞/뒤 문자열을
        // 다시 읽어 HTML과 맞춥니다.
        try {
            currentInputConnection?.finishComposingText()
        } catch (_: Exception) {
        }

        lastKnownSelectionStart = newSelStart
        lastKnownSelectionEnd = newSelEnd
        syncHtmlWithNativeText()
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInputView(info, restarting)

        lastKnownSelectionStart = -1
        lastKnownSelectionEnd = -1

        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
                null
            )
        }

        // 시작 시점의 실제 커서 위치를 기준점으로 잡습니다.
        webView.post {
            rememberActualSelection()
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
