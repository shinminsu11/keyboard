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

    private fun rememberActualSelection() {
        val ic = currentInputConnection ?: return

        try {
            val before =
                ic.getTextBeforeCursor(10000, 0)?.length ?: 0

            val after =
                ic.getTextAfterCursor(10000, 0)?.length ?: 0

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
            val before =
                ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""

            val after =
                ic.getTextAfterCursor(10000, 0)?.toString() ?: ""

            val beforeJs =
                org.json.JSONObject.quote(before)

            val afterJs =
                org.json.JSONObject.quote(after)

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
        val pos =
            pendingExternalCursorUtf16 ?: return

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

    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(text: String) {
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

                val target =
                    pendingExternalCursorUtf16

                if (target != null) {
                    ic.finishComposingText()
                    ic.setSelection(target, target)
                    ic.setComposingText(text, 1)

                    pendingExternalCursorUtf16 = null
                    externalComposingActive = true

                    val newPos =
                        target + text.length

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
        fun setExternalComposing(
            text: String,
            target: Int
        ) {
            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 300L

            val ic = currentInputConnection ?: return

            try {
                ic.finishComposingText()
                ic.setSelection(target, target)
                ic.setComposingText(text, 1)

                pendingExternalCursorUtf16 = null
                externalComposingActive = true

                val newPos =
                    target + text.length

                lastKnownSelectionStart = newPos
                lastKnownSelectionEnd = newPos

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun extendExternalSyllable(text: String) {
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
        fun setSelection(
            start: Int,
            end: Int
        ) {
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

        /*
         * ============================================================
         * 삭제
         *
         * 한글 삭제 순서:
         *
         * 한 = ㅎ + ㅏ + ㄴ
         *
         * 1회 삭제 : 한 → 하
         *             ㄴ 삭제
         *
         * 2회 삭제 : 하 → ㅎ
         *             ㅏ 삭제
         *
         * 3회 삭제 : ㅎ → 빈칸
         *             ㅎ 삭제
         *
         * 겹받침도 가능한 범위에서 한 자모씩 뒤로 되돌린다.
         * ============================================================
         */
        @JavascriptInterface
        fun deleteText() {
            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 250L

            val ic = currentInputConnection ?: return

            try {

                /*
                 * ----------------------------------------------------
                 * 1. 기존 선택 영역 삭제
                 * ----------------------------------------------------
                 */
                val rememberedStart =
                    lastKnownSelectionStart

                val rememberedEnd =
                    lastKnownSelectionEnd

                if (
                    rememberedStart >= 0 &&
                    rememberedEnd >= 0 &&
                    rememberedStart != rememberedEnd
                ) {
                    val selectionStart =
                        minOf(
                            rememberedStart,
                            rememberedEnd
                        )

                    val selectionEnd =
                        maxOf(
                            rememberedStart,
                            rememberedEnd
                        )

                    externalComposingActive = false
                    pendingExternalCursorUtf16 = null

                    try {
                        ic.finishComposingText()
                    } catch (_: Exception) {
                    }

                    ic.setSelection(
                        selectionStart,
                        selectionEnd
                    )

                    ic.commitText("", 1)

                    lastKnownSelectionStart =
                        selectionStart

                    lastKnownSelectionEnd =
                        selectionStart

                    cancelHtmlExternalCursorState()
                    rememberActualSelection()
                    syncHtmlWithNativeText()

                    return
                }

                /*
                 * ----------------------------------------------------
                 * 2. 실제 선택 영역 확인
                 * ----------------------------------------------------
                 */
                externalComposingActive = false

                val selectedText =
                    ic.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {
                    pendingExternalCursorUtf16 = null

                    ic.commitText("", 1)

                    cancelHtmlExternalCursorState()
                    rememberActualSelection()
                    syncHtmlWithNativeText()

                    return
                }

                /*
                 * ----------------------------------------------------
                 * 3. 커서 앞의 실제 텍스트
                 * ----------------------------------------------------
                 */
                val beforeNow =
                    ic.getTextBeforeCursor(
                        10000,
                        0
                    )?.toString() ?: ""

                val cursor =
                    beforeNow.length

                if (cursor <= 0) {
                    return
                }

                /*
                 * ----------------------------------------------------
                 * 4. 커서 바로 앞 Unicode code point
                 * ----------------------------------------------------
                 */
                val codePoint =
                    beforeNow.codePointBefore(cursor)

                val charLength =
                    Character.charCount(codePoint)

                val start =
                    cursor - charLength

                /*
                 * ----------------------------------------------------
                 * 5. 완성형 한글
                 * ----------------------------------------------------
                 */
                if (
                    codePoint in 0xAC00..0xD7A3
                ) {

                    val syllableIndex =
                        codePoint - 0xAC00

                    val cho =
                        syllableIndex /
                                (21 * 28)

                    val jung =
                        (syllableIndex %
                                (21 * 28)) / 28

                    val jong =
                        syllableIndex % 28

                    /*
                     * ------------------------------------------------
                     * 한글 자모 테이블
                     * ------------------------------------------------
                     */
                    val choseong =
                        arrayOf(
                            "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ",
                            "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ",
                            "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ",
                            "ㅋ", "ㅌ", "ㅍ", "ㅎ"
                        )[cho]

                    val jungseong =
                        arrayOf(
                            "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ",
                            "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ",
                            "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ",
                            "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ",
                            "ㅣ"
                        )[jung]

                    /*
                     * ------------------------------------------------
                     * 종성이 있는 경우
                     *
                     * 한 → 하
                     *
                     * ㄴ을 제거한다.
                     * ------------------------------------------------
                     */
                    if (jong != 0) {

                        /*
                         * 겹받침은 마지막 자음부터
                         * 하나씩 제거한다.
                         *
                         * ㄳ → ㄱ
                         * ㄵ → ㄴ
                         * ㄶ → ㄴ
                         * ㄺ → ㄹ
                         * ㄻ → ㄹ
                         * ㄼ → ㄹ
                         * ㄽ → ㄹ
                         * ㄾ → ㄹ
                         * ㄿ → ㄹ
                         * ㅀ → ㄹ
                         * ㅄ → ㅂ
                         */
                        val reducedJong =
                            when (jong) {
                                3 -> 1
                                5 -> 4
                                6 -> 4
                                9 -> 8
                                10 -> 8
                                11 -> 8
                                12 -> 8
                                13 -> 8
                                14 -> 8
                                15 -> 8
                                18 -> 17
                                else -> 0
                            }

                        /*
                         * 새로운 완성형 글자 생성
                         */
                        val replacementCode =
                            0xAC00 +
                                    (cho * 21 * 28) +
                                    (jung * 28) +
                                    reducedJong

                        val replacement =
                            String(
                                Character.toChars(
                                    replacementCode
                                )
                            )

                        /*
                         * 현재 한 글자를 선택하고
                         * 이전 단계 글자로 교체
                         */
                        ic.setSelection(
                            start,
                            cursor
                        )

                        ic.commitText(
                            replacement,
                            1
                        )

                        val newCursor =
                            start + replacement.length

                        lastKnownSelectionStart =
                            newCursor

                        lastKnownSelectionEnd =
                            newCursor

                        pendingExternalCursorUtf16 = null
                        externalComposingActive = false

                        cancelHtmlExternalCursorState()
                        rememberActualSelection()
                        syncHtmlWithNativeText()

                        return
                    }

                    /*
                     * ------------------------------------------------
                     * 종성 없음 (중성 'ㅏ'(인덱스 0) 포함 모든 모음 대상)
                     *
                     * 하 → ㅎ
                     *
                     * 중성을 제거하고 초성만 남긴다.
                     * ------------------------------------------------
                     */
                    ic.setSelection(
                        start,
                        cursor
                    )

                    ic.commitText(
                        choseong,
                        1
                    )

                    val newCursor =
                        start + choseong.length

                    lastKnownSelectionStart =
                        newCursor

                    lastKnownSelectionEnd =
                        newCursor

                    pendingExternalCursorUtf16 = null
                    externalComposingActive = false

                    cancelHtmlExternalCursorState()
                    rememberActualSelection()
                    syncHtmlWithNativeText()

                    return
                }

                /*
                 * ----------------------------------------------------
                 * 6. 일반 문자 / 이모지 / 숫자 / 기호
                 * ----------------------------------------------------
                 */
                ic.setSelection(
                    start,
                    cursor
                )

                ic.commitText(
                    "",
                    1
                )

                lastKnownSelectionStart =
                    start

                lastKnownSelectionEnd =
                    start

                pendingExternalCursorUtf16 = null
                externalComposingActive = false

                cancelHtmlExternalCursorState()
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
                externalComposingActive = false
                pendingExternalCursorUtf16 = null

                ic.finishComposingText()

                ic.deleteSurroundingText(
                    1,
                    0
                )

                rememberActualSelection()
                syncHtmlWithNativeText()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun isSearchField(): Boolean {
            val info =
                currentInputEditorInfo ?: return false

            val action =
                info.imeOptions and
                        EditorInfo.IME_MASK_ACTION

            return action ==
                    EditorInfo.IME_ACTION_SEARCH
        }

        @JavascriptInterface
        fun performSearch() {
            val ic =
                currentInputConnection ?: return

            ic.performEditorAction(
                EditorInfo.IME_ACTION_SEARCH
            )
        }

        @JavascriptInterface
        fun openUrl(url: String) {
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

        /*
         * 선택 영역
         */
        if (newSelStart != newSelEnd) {
            lastKnownSelectionStart =
                newSelStart

            lastKnownSelectionEnd =
                newSelEnd

            pendingExternalCursorUtf16 = null
            externalComposingActive = false

            suppressSelectionSyncUntil =
                android.os.SystemClock.uptimeMillis() + 120L

            return
        }

        /*
         * 우리가 방금 selection을 바꾼 직후라면
         * 다시 HTML 커서를 덮어쓰지 않는다.
         */
        if (
            android.os.SystemClock.uptimeMillis()
            < suppressSelectionSyncUntil
        ) {
            lastKnownSelectionStart =
                newSelStart

            lastKnownSelectionEnd =
                newSelEnd

            return
        }

        /*
         * 같은 위치면 불필요한 재동기화 방지
         */
        if (
            newSelStart ==
            lastKnownSelectionStart &&
            newSelEnd ==
            lastKnownSelectionEnd
        ) {
            return
        }

        externalComposingActive = false

        lastKnownSelectionStart =
            newSelStart

        lastKnownSelectionEnd =
            newSelEnd

        pendingExternalCursorUtf16 =
            newSelStart

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
        super.onStartInputView(
            info,
            restarting
        )

        lastKnownSelectionStart = -1
        lastKnownSelectionEnd = -1

        pendingExternalCursorUtf16 = null
        externalComposingActive = false

        if (::webView.isInitialized) {

            webView.evaluateJavascript(
                "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
                null
            )

            /*
             * 새 입력창은 메인판에서 시작
             */
            webView.evaluateJavascript(
                "javascript:if(window.resetToMainBoard) { window.resetToMainBoard(); }",
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
