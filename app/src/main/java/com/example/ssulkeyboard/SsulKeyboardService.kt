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
    private var externalComposingActive = false

    // 역순 삭제 테스트용 상태
    private val reverseDeleteStack = mutableListOf<String>()
    private var reverseDeleteSource = ""

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
            webViewClient = object : WebViewClient() {}
            loadUrl("file:///android_asset/keyboard.html")
        }

        container.addView(webView)
        return container
    }

    private fun clearReverseDeleteState() {
        reverseDeleteStack.clear()
        reverseDeleteSource = ""
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

    private fun cancelHtmlExternalCursorState() {
        if (!::webView.isInitialized) return

        webView.post {
            webView.evaluateJavascript(
                "javascript:if(window.cancelExternalCursorState) { window.cancelExternalCursorState(); }",
                null
            )
        }
    }

    /**
     * 한글 음절을 구성요소로 나누고,
     * '실제로 쓴 순서의 역순'으로 삭제할 목록을 만든다.
     *
     * 예:
     * 한 = ㅎ + ㅏ + ㄴ
     * 삭제 순서 = ㄴ -> ㅏ -> ㅎ
     */
    private fun buildReverseDeleteParts(ch: Char): List<String>? {
        val code = ch.code

        if (code !in 0xAC00..0xD7A3) {
            return null
        }

        val syllableIndex = code - 0xAC00
        val choIndex = syllableIndex / (21 * 28)
        val jungIndex = (syllableIndex % (21 * 28)) / 28
        val jongIndex = syllableIndex % 28

        val cho = arrayOf(
            "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ",
            "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ",
            "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ",
            "ㅋ", "ㅌ", "ㅍ", "ㅎ"
        )

        val jung = arrayOf(
            "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ",
            "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ",
            "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ",
            "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ",
            "ㅣ"
        )

        val jong = arrayOf(
            "",
            "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ",
            "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ",
            "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ",
            "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ",
            "ㅌ", "ㅍ", "ㅎ"
        )

        val result = mutableListOf<String>()

        // 쓴 순서의 역순: 종성 -> 중성 -> 초성
        if (jongIndex != 0) {
            result.add(jong[jongIndex])
        }

        result.add(jung[jungIndex])
        result.add(cho[choIndex])

        return result
    }

    /**
     * 역순 삭제를 시작한다.
     *
     * 예:
     * 한 -> ㄴ
     */
    private fun startReverseDelete(
        ic: android.view.inputmethod.InputConnection,
        source: String
    ): Boolean {
        if (source.length != 1) return false

        val parts = buildReverseDeleteParts(source[0]) ?: return false
        if (parts.isEmpty()) return false

        clearReverseDeleteState()

        // 첫 번째 결과를 현재 글자 대신 보여주고,
        // 나머지를 다음 삭제 순서로 저장한다.
        reverseDeleteStack.addAll(parts.drop(1))
        reverseDeleteSource = source

        return try {
            ic.finishComposingText()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(parts[0], 1)

            rememberActualSelection()
            syncHtmlWithNativeText()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            clearReverseDeleteState()
            false
        }
    }

    /**
     * 이미 역순 삭제 중인 경우 다음 구성요소로 진행한다.
     *
     * 한 -> ㄴ -> ㅏ -> ㅎ
     */
    private fun continueReverseDelete(
        ic: android.view.inputmethod.InputConnection
    ): Boolean {
        if (reverseDeleteStack.isEmpty()) {
            return false
        }

        return try {
            ic.finishComposingText()
            ic.deleteSurroundingText(1, 0)

            val next = reverseDeleteStack.removeAt(0)
            ic.commitText(next, 1)

            rememberActualSelection()
            syncHtmlWithNativeText()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            clearReverseDeleteState()
            false
        }
    }

    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(text: String) {
            clearReverseDeleteState()

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 120L

            val ic = currentInputConnection ?: return

            try {
                if (externalComposingActive) {
                    ic.finishComposingText()
                    externalComposingActive = false
                }

                applyPendingExternalCursor(ic)
                ic.commitText(text, 1)

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
            clearReverseDeleteState()

            val ic = currentInputConnection ?: return

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 180L

            try {
                if (externalComposingActive) {
                    ic.setComposingText(text, 1)

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
            clearReverseDeleteState()

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 300L

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

            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun extendExternalSyllable(text: String) {
            clearReverseDeleteState()

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 180L

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
            clearReverseDeleteState()

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

        @JavascriptInterface
        fun deleteText() {
            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 180L

            val ic = currentInputConnection ?: return

            try {
                val rememberedStart = lastKnownSelectionStart
                val rememberedEnd = lastKnownSelectionEnd

                // 선택 영역 삭제는 기존 기능 그대로 유지
                if (
                    rememberedStart >= 0 &&
                    rememberedEnd >= 0 &&
                    rememberedStart != rememberedEnd
                ) {
                    clearReverseDeleteState()

                    val selectionStart =
                        minOf(rememberedStart, rememberedEnd)

                    val selectionEnd =
                        maxOf(rememberedStart, rememberedEnd)

                    externalComposingActive = false
                    pendingExternalCursorUtf16 = null

                    try {
                        ic.finishComposingText()
                    } catch (_: Exception) {
                    }

                    ic.setSelection(selectionStart, selectionEnd)
                    ic.commitText("", 1)

                    val newCursor = selectionStart

                    lastKnownSelectionStart = newCursor
                    lastKnownSelectionEnd = newCursor

                    cancelHtmlExternalCursorState()
                    rememberActualSelection()
                    syncHtmlWithNativeText()
                    return
                }

                externalComposingActive = false

                // Android가 선택 영역을 직접 알려주는 경우도 기존 방식 유지
                val selectedText = ic.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {
                    clearReverseDeleteState()

                    pendingExternalCursorUtf16 = null
                    ic.commitText("", 1)

                    cancelHtmlExternalCursorState()
                    rememberActualSelection()
                    syncHtmlWithNativeText()
                    return
                }

                val beforeNow =
                    ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""

                val originalCursor = beforeNow.length

                if (originalCursor <= 0) {
                    clearReverseDeleteState()
                    return
                }

                try {
                    ic.finishComposingText()
                } catch (_: Exception) {
                }

                try {
                    ic.setSelection(originalCursor, originalCursor)
                } catch (_: Exception) {
                }

                // --------------------------------------------
                // 역순 삭제 테스트
                // --------------------------------------------

                // 이미 역순 삭제 중이면 다음 단계
                if (reverseDeleteStack.isNotEmpty()) {
                    continueReverseDelete(ic)
                    return
                }

                // 마지막 문자가 완성형 한글이면 역순 삭제 시작
                val lastChar = beforeNow.last()

                if (lastChar.code in 0xAC00..0xD7A3) {
                    if (startReverseDelete(ic, lastChar.toString())) {
                        return
                    }
                }

                // --------------------------------------------
                // 역순 삭제 대상이 아니면 기존 일반 삭제
                // --------------------------------------------

                clearReverseDeleteState()

                val textBefore =
                    ic.getTextBeforeCursor(2, 0)?.toString() ?: ""

                if (textBefore.isEmpty()) return

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

                pendingExternalCursorUtf16 = null

                rememberActualSelection()
                syncHtmlWithNativeText()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteOneCharForHanja() {
            clearReverseDeleteState()

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 120L

            val ic = currentInputConnection ?: return

            try {
                externalComposingActive = false
                pendingExternalCursorUtf16 = null

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

            val action =
                info.imeOptions and EditorInfo.IME_MASK_ACTION

            return action == EditorInfo.IME_ACTION_SEARCH
        }

        @JavascriptInterface
        fun performSearch() {
            val ic = currentInputConnection ?: return
            ic.performEditorAction(
                EditorInfo.IME_ACTION_SEARCH
            )
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent =
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
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

        if (
            !::webView.isInitialized ||
            newSelStart < 0 ||
            newSelEnd < 0
        ) {
            return
        }

        if (
            android.os.SystemClock.uptimeMillis()
            < internalSelectionUntil
        ) {
            return
        }

        // 외부 커서 이동이 발생하면 역순 삭제 상태 초기화
        if (
            newSelStart != lastKnownSelectionStart ||
            newSelEnd != lastKnownSelectionEnd
        ) {
            clearReverseDeleteState()
        }

        if (newSelStart != newSelEnd) {
            lastKnownSelectionStart = newSelStart
            lastKnownSelectionEnd = newSelEnd

            pendingExternalCursorUtf16 = null
            externalComposingActive = false

            suppressSelectionSyncUntil =
                android.os.SystemClock.uptimeMillis() + 120L

            return
        }

        if (
            android.os.SystemClock.uptimeMillis()
            < suppressSelectionSyncUntil
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

        externalComposingActive = false

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
                android.os.SystemClock.uptimeMillis()
                <= suppressSelectionSyncUntil
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

        clearReverseDeleteState()

        lastKnownSelectionStart = -1
        lastKnownSelectionEnd = -1

        pendingExternalCursorUtf16 = null
        externalComposingActive = false

        if (::webView.isInitialized) {

            webView.evaluateJavascript(
                "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
                null
            )

            // 새 입력창에서는 항상 메인판에서 시작
            webView.evaluateJavascript(
                "javascript:if(window.resetToMainBoard) { window.resetToMainBoard(); }",
                null
            )
        }

        webView.post {
            rememberActualSelection()
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
}
