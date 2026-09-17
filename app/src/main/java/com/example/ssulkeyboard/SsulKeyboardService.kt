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
                    """.trimIndent(),
                    null
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyPendingExternalCursor(
        ic: android.view.inputmethod.InputConnection
    ) {
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
            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 120L

            val ic = currentInputConnection ?: return

            try {
                applyPendingExternalCursor(ic)
                ic.commitText(text, 1)
                rememberActualSelection()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            val ic = currentInputConnection ?: return

            val target = pendingExternalCursorUtf16

            if (target != null) {
                insertAtExternalCursor(text, target)
                return
            }

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 120L

            try {
                ic.setComposingText(text, 1)
                rememberActualSelection()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // ============================
        // Pair35 수정
        // 중간 커서에 입력 문자만 직접 삽입
        // ============================
        private fun insertAtExternalCursor(
            text: String,
            target: Int
        ) {
            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 300L

            val ic = currentInputConnection ?: return

            try {
                ic.finishComposingText()

                val before =
                    ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""

                val after =
                    ic.getTextAfterCursor(10000, 0)?.toString() ?: ""

                val full = before + after

                if (target < 0 || target > full.length) {
                    pendingExternalCursorUtf16 = null
                    return
                }

                // 실제 입력창의 중간 커서 위치로 이동
                ic.setSelection(target, target)

                // 기존 문장은 건드리지 않고 입력 문자만 삽입
                ic.commitText(text, 1)

                val newPos = target + text.length

                pendingExternalCursorUtf16 = null
                lastKnownSelectionStart = newPos
                lastKnownSelectionEnd = newPos

                // HTML 자판 화면도 실제 입력창과 맞춤
                val rebuilt =
                    full.substring(0, target) +
                    text +
                    full.substring(target)

                val textJs =
                    org.json.JSONObject.quote(rebuilt)

                webView.post {
                    webView.evaluateJavascript(
                        "javascript:if(window.finishExternalInsert){window.finishExternalInsert($textJs,$newPos);}",
                        null
                    )
                }

            } catch (e: Exception) {
                pendingExternalCursorUtf16 = null
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun extendExternalSyllable(text: String) {
            val ic = currentInputConnection ?: return

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 180L

            try {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(text, 1)
                rememberActualSelection()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setSelection(start: Int, end: Int) {
            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 120L

            val ic = currentInputConnection ?: return

            try {
                ic.setSelection(start, end)

                lastKnownSelectionStart = start
                lastKnownSelectionEnd = end
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // ============================
        // Pair33 수정: 삭제
        // ============================
        @JavascriptInterface
        fun deleteText() {
            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 120L

            val ic = currentInputConnection ?: return

            try {
                val selectedText = ic.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {
                    ic.commitText("", 1)
                    rememberActualSelection()
                    syncHtmlWithNativeText()
                    return
                }

                val beforeNow =
                    ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""

                val originalCursor = beforeNow.length

                if (originalCursor <= 0) {
                    return
                }

                try {
                    ic.finishComposingText()
                } catch (_: Exception) {
                }

                try {
                    ic.setSelection(
                        originalCursor,
                        originalCursor
                    )
                } catch (_: Exception) {
                }

                val textBefore =
                    ic.getTextBeforeCursor(2, 0)?.toString() ?: ""

                if (textBefore.isEmpty()) {
                    return
                }

                if (textBefore.length >= 2) {

                    val high =
                        textBefore[textBefore.length - 2]

                    val low =
                        textBefore[textBefore.length - 1]

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
            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 120L

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

        if (!::webView.isInitialized ||
            newSelStart < 0 ||
            newSelEnd < 0
        ) {
            return
        }

        if (
            android.os.SystemClock.uptimeMillis() <
            internalSelectionUntil
        ) {
            return
        }

        if (
            android.os.SystemClock.uptimeMillis() <
            suppressSelectionSyncUntil
        ) {
            lastKnownSelectionStart = newSelStart
            lastKnownSelectionEnd = newSelEnd
            return
        }

        if (
            newSelStart == lastKnownSelectionStart &&
            newSelEnd == lastKnownSelectionEnd
        ) {
            return
        }

        lastKnownSelectionStart = newSelStart
        lastKnownSelectionEnd = newSelEnd

        pendingExternalCursorUtf16 = newSelStart

        suppressSelectionSyncUntil =
            android.os.SystemClock.uptimeMillis() + 150L

        webView.post {
            webView.evaluateJavascript(
                "javascript:if(window.beginExternalCursorInsert) { window.beginExternalCursorInsert(); }",
                null
            )
        }

        webView.post {
            if (
                android.os.SystemClock.uptimeMillis() <=
                suppressSelectionSyncUntil
            ) {
                syncHtmlWithNativeText()
            }
        }
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInputView(info, restarting)

        lastKnownSelectionStart = -1
        lastKnownSelectionEnd = -1
        pendingExternalCursorUtf16 = null

        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
                null
            )
        }

        webView.post {
            rememberActualSelection()
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
