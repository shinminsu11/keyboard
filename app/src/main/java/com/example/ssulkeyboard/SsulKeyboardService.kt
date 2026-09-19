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

    private var lastKnownSelectionStart = -1
    private var lastKnownSelectionEnd = -1

    private var pendingExternalCursorUtf16: Int? = null
    private var suppressSelectionSyncUntil = 0L
    private var internalSelectionUntil = 0L

    // Pair45: 중간 커서에서 첫 초성을 네이티브 조합문자로 보여주되,
    // 다음 모음이 같은 조합문자 안에서 합쳐지도록 유지한다.
    private var externalComposingActive = false

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

    private fun rememberActualSelection() {
        val ic = currentInputConnection ?: return
        try {
            val before = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(10000, 0)?.length ?: 0
            if (after >= 0) {
                lastKnownSelectionStart = before
                lastKnownSelectionEnd = before
            }
        } catch (_: Exception) {
        }
    }

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
                    """.trimIndent(), null
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyPendingExternalCursor(ic: android.view.inputmethod.InputConnection) {
        val pos = pendingExternalCursorUtf16 ?: return
        try {
            ic.finishComposingText()
            ic.setSelection(pos, pos)
        } catch (_: Exception) {
        }
        pendingExternalCursorUtf16 = null
        lastKnownSelectionStart = pos
        lastKnownSelectionEnd = pos
    }

    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(text: String) {
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 120L
            val ic = currentInputConnection ?: return
            try {
                // 기존 외부 조합문자를 먼저 확정한다.
                if (externalComposingActive) {
                    ic.finishComposingText()
                    externalComposingActive = false
                }
                applyPendingExternalCursor(ic)
                ic.commitText(text, 1)

                // Pair48: 엔터(줄바꿈) 후에는 이전 중간커서 상태를 완전히 버린다.
                // 일부 입력창에서는 엔터 직후 다음 입력의 커서가 이전
                // pendingExternalCursorUtf16을 다시 사용하여 맨 앞으로 들어가는
                // 현상이 생길 수 있으므로, 실제 새 커서 위치를 다시 기억한다.
                if (text.contains('\n') || text.contains('\r')) {
                    pendingExternalCursorUtf16 = null
                    externalComposingActive = false
                    suppressSelectionSyncUntil =
                        android.os.SystemClock.uptimeMillis() + 250L
                    rememberActualSelection()
                } else {
                    rememberActualSelection()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            val ic = currentInputConnection ?: return

            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 180L

            try {
                // Pair45 핵심: 첫 초성으로 시작한 외부 조합문자가 있으면
                // 커서를 다시 옮기거나 commitText하지 않고 같은 composing span을 교체한다.
                if (externalComposingActive) {
                    ic.setComposingText(text, 1)

                    // Pair47: 외부 중간 커서에서 첫 음절이 완성되면
                    // composing 상태를 다음 글자까지 유지하지 않고 확정한다.
                    // 일부 입력창에서 다음 글자가 기존 composing 범위를
                    // 다시 대체하여 앞 글자가 사라지는 현상을 막는다.
                    val isCompleteHangul =
                        text.length == 1 &&
                        text[0].code in 0xAC00..0xD7A3

                    if (isCompleteHangul) {
                        ic.finishComposingText()
                        externalComposingActive = false
                    }

                    rememberActualSelection()
                    return
                }

                val target = pendingExternalCursorUtf16
                if (target != null) {
                    ic.finishComposingText()
                    ic.setSelection(target, target)
                    ic.setComposingText(text, 1)
                    pendingExternalCursorUtf16 = null
                    externalComposingActive = true
                    val newPos = target + text.length
                    lastKnownSelectionStart = newPos
                    lastKnownSelectionEnd = newPos
                    rememberActualSelection()
                    return
                }

                ic.setComposingText(text, 1)
                rememberActualSelection()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setExternalComposing(text: String, target: Int) {
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 300L
            val ic = currentInputConnection ?: return

            try {
                ic.finishComposingText()
                ic.setSelection(target, target)
                ic.setComposingText(text, 1)

                pendingExternalCursorUtf16 = null
                externalComposingActive = true

                val newPos = target + text.length
                lastKnownSelectionStart = newPos
                lastKnownSelectionEnd = newPos
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun extendExternalSyllable(text: String) {
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 180L
            val ic = currentInputConnection ?: return
            try {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(text, 1)
                externalComposingActive = false
                rememberActualSelection()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setSelection(start: Int, end: Int) {
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 120L
            val ic = currentInputConnection ?: return
            try {
                ic.setSelection(start, end)
                lastKnownSelectionStart = start
                lastKnownSelectionEnd = end
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteText() {
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 120L
            val ic = currentInputConnection ?: return
            try {
                externalComposingActive = false
                val selectedText = ic.getSelectedText(0)
                if (!selectedText.isNullOrEmpty()) {
                    ic.commitText("", 1)
                    rememberActualSelection()
                    syncHtmlWithNativeText()
                    return
                }

                val beforeNow = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
                val originalCursor = beforeNow.length
                if (originalCursor <= 0) return

                try { ic.finishComposingText() } catch (_: Exception) {}
                try { ic.setSelection(originalCursor, originalCursor) } catch (_: Exception) {}

                val textBefore = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
                if (textBefore.isEmpty()) return

                if (textBefore.length >= 2) {
                    val high = textBefore[textBefore.length - 2]
                    val low = textBefore[textBefore.length - 1]
                    if (Character.isSurrogatePair(high, low)) {
                        ic.deleteSurroundingText(2, 0)
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                } else {
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
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 120L
            val ic = currentInputConnection ?: return
            try {
                externalComposingActive = false
                ic.finishComposingText()
                ic.deleteSurroundingText(1, 0)
                rememberActualSelection()
                syncHtmlWithNativeText()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun isSearchField(): Boolean {
            val info = currentInputEditorInfo ?: return false
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            return action == EditorInfo.IME_ACTION_SEARCH
        }

        @JavascriptInterface
        fun performSearch() {
            val ic = currentInputConnection ?: return
            ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
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
            oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd
        )

        if (!::webView.isInitialized || newSelStart < 0 || newSelEnd < 0) return

        if (android.os.SystemClock.uptimeMillis() < internalSelectionUntil) return

        if (android.os.SystemClock.uptimeMillis() < suppressSelectionSyncUntil) {
            lastKnownSelectionStart = newSelStart
            lastKnownSelectionEnd = newSelEnd
            return
        }

        if (newSelStart == lastKnownSelectionStart && newSelEnd == lastKnownSelectionEnd) return

        // 외부 앱이 커서를 움직인 경우에는 현재 조합을 종료하고 새 위치를 기준으로 시작한다.
        externalComposingActive = false
        lastKnownSelectionStart = newSelStart
        lastKnownSelectionEnd = newSelEnd
        pendingExternalCursorUtf16 = newSelStart

        suppressSelectionSyncUntil = android.os.SystemClock.uptimeMillis() + 150L

        webView.post {
            webView.evaluateJavascript(
                "javascript:if(window.beginExternalCursorInsert) { window.beginExternalCursorInsert(); }",
                null
            )
        }

        webView.post {
            if (android.os.SystemClock.uptimeMillis() <= suppressSelectionSyncUntil) {
                syncHtmlWithNativeText()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        lastKnownSelectionStart = -1
        lastKnownSelectionEnd = -1
        pendingExternalCursorUtf16 = null
        externalComposingActive = false

        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
                null
            )
        }

        webView.post { rememberActualSelection() }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
}
