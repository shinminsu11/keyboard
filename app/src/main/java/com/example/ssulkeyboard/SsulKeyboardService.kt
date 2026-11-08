package com.example.ssulkeyboard

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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

    // 클립보드 자동 저장용 상태
    private var clipboardManager: ClipboardManager? = null
    private var lastObservedClipboardText = ""
    private var lastObservedClipboardTime = 0L

    // "복사 -> 클립보드 열기"를 최근 동작으로 인정하는 시간
    private companion object {
        const val CLIPBOARD_RECENT_WINDOW_MS = 10_000L
    }

    override fun onCreate() {
        super.onCreate()

        clipboardManager =
            getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager

        clipboardManager?.addPrimaryClipChangedListener(
            clipboardChangedListener
        )
    }

    private val clipboardChangedListener =
        ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val cm =
                    clipboardManager
                        ?: return@OnPrimaryClipChangedListener

                val clip = cm.primaryClip

                if (
                    clip != null &&
                    clip.itemCount > 0
                ) {
                    val text =
                        clip.getItemAt(0)
                            .coerceToText(
                                this@SsulKeyboardService
                            )
                            ?.toString()
                            ?: ""

                    if (text.trim().isNotEmpty()) {

                        lastObservedClipboardText = text

                        lastObservedClipboardTime =
                            android.os.SystemClock.elapsedRealtime()

                        if (::webView.isInitialized) {

                            val textJs =
                                org.json.JSONObject.quote(text)

                            webView.post {

                                webView.evaluateJavascript(
                                    """
                                    (function() {
                                        if (window.addClipboardFromAndroid) {
                                            window.addClipboardFromAndroid(
                                                $textJs
                                            );
                                        }
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }
                    }
                }

            } catch (_: Exception) {
            }
        }

    private fun normalizeClipboardText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
    }

    private fun isRecentClipboardText(text: String): Boolean {
        if (text.trim().isEmpty()) return false

        val now =
            android.os.SystemClock.elapsedRealtime()

        val elapsed =
            now - lastObservedClipboardTime

        if (lastObservedClipboardTime <= 0L) return false

        if (
            elapsed < 0L ||
            elapsed > CLIPBOARD_RECENT_WINDOW_MS
        ) {
            return false
        }

        return normalizeClipboardText(text) ==
            normalizeClipboardText(
                lastObservedClipboardText
            )
    }

    override fun onDestroy() {

        try {
            clipboardManager?.removePrimaryClipChangedListener(
                clipboardChangedListener
            )
        } catch (_: Exception) {
        }

        clipboardManager = null

        lastObservedClipboardText = ""

        lastObservedClipboardTime = 0L

        super.onDestroy()
    }

    override fun onCreateInputView(): View {

        val container =
            LinearLayout(this).apply {

                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    Color.parseColor("#d1d8e0")
                )
            }

        val heightDp = 235

        val heightPx =
            (
                heightDp *
                    resources.displayMetrics.density
                ).toInt()

        webView =
            WebView(this).apply {

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        heightPx
                    )

                settings.javaScriptEnabled = true

                settings.domStorageEnabled = true

                setBackgroundColor(
                    Color.TRANSPARENT
                )

                addJavascriptInterface(
                    KeyboardBridge(),
                    "AndroidBridge"
                )

                webViewClient =
                    object : WebViewClient() {}

                loadUrl(
                    "file:///android_asset/keyboard.html"
                )
            }

        container.addView(webView)

        return container
    }

    private fun rememberActualSelection() {

        val ic =
            currentInputConnection
                ?: return

        try {

            val before =
                ic.getTextBeforeCursor(
                    10000,
                    0
                )?.length ?: 0

            val after =
                ic.getTextAfterCursor(
                    10000,
                    0
                )?.length ?: 0

            if (after >= 0) {

                lastKnownSelectionStart =
                    before

                lastKnownSelectionEnd =
                    before
            }

        } catch (_: Exception) {
        }
    }

    private fun syncHtmlWithNativeText() {

        if (!::webView.isInitialized) return

        val ic =
            currentInputConnection
                ?: return

        try {

            val before =
                ic.getTextBeforeCursor(
                    10000,
                    0
                )?.toString() ?: ""

            val after =
                ic.getTextAfterCursor(
                    10000,
                    0
                )?.toString() ?: ""

            val beforeJs =
                org.json.JSONObject.quote(
                    before
                )

            val afterJs =
                org.json.JSONObject.quote(
                    after
                )

            webView.post {

                webView.evaluateJavascript(
                    """
                    (function() {
                        if (window.syncNativeText) {
                            window.syncNativeText(
                                $beforeJs,
                                $afterJs
                            );
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

    private fun syncHtmlAfterDelete(
        mode: String
    ) {

        if (!::webView.isInitialized) return

        val ic =
            currentInputConnection
                ?: return

        try {

            val before =
                ic.getTextBeforeCursor(
                    10000,
                    0
                )?.toString() ?: ""

            val after =
                ic.getTextAfterCursor(
                    10000,
                    0
                )?.toString() ?: ""

            val beforeJs =
                org.json.JSONObject.quote(
                    before
                )

            val afterJs =
                org.json.JSONObject.quote(
                    after
                )

            val modeJs =
                org.json.JSONObject.quote(
                    mode
                )

            webView.post {

                webView.evaluateJavascript(
                    """
                    (function() {
                        if (window.syncNativeTextAfterDelete) {
                            window.syncNativeTextAfterDelete(
                                $beforeJs,
                                $afterJs,
                                $modeJs
                            );
                        } else if (window.syncNativeText) {
                            window.syncNativeText(
                                $beforeJs,
                                $afterJs
                            );
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
        ic: InputConnection
    ) {

        val pos =
            pendingExternalCursorUtf16
                ?: return

        try {

            ic.finishComposingText()

            ic.setSelection(
                pos,
                pos
            )

        } catch (_: Exception) {
        }

        pendingExternalCursorUtf16 = null

        lastKnownSelectionStart = pos

        lastKnownSelectionEnd = pos
    }

    // 네이버 카페 본문처럼 커서 이동 직후 편집기 상태가 늦게 맞춰지는
    // 화면을 위한 재시도: 0.4초 간격으로 최대 3번, 추출 텍스트의
    // 커서 위치를 다시 확인해서 앞쪽 글이 보이면 그만큼 지운다.
    private fun retryDeleteLeft(attempt: Int) {

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({

            try {

                val ic = currentInputConnection ?: return@postDelayed

                val req = android.view.inputmethod.ExtractedTextRequest()
                req.hintMaxChars = 100000
                req.hintMaxLines = 10000

                val et = ic.getExtractedText(req, 0)

                val abs =
                    if (et == null) -1
                    else et.startOffset + et.selectionStart

                val len = et?.text?.length ?: -1

                if (abs > 0) {

                    internalSelectionUntil =
                        android.os.SystemClock.uptimeMillis() + 250L

                    ic.beginBatchEdit()
                    try {
                        ic.deleteSurroundingText(abs, 0)
                    } finally {
                        ic.endBatchEdit()
                    }

                    showToastFromNative("재시도 $attempt: 지움 커서=$abs")

                } else if (attempt < 3) {

                    retryDeleteLeft(attempt + 1)

                } else {

                    showToastFromNative("재시도 실패: 길이=$len 커서=$abs")
                }

            } catch (e: Exception) {

                e.printStackTrace()
            }

        }, 400L)
    }

    private fun showToastFromNative(message: String) {

        if (!::webView.isInitialized) return

        val msg = org.json.JSONObject.quote(message)

        webView.post {
            webView.evaluateJavascript(
                "if(typeof showToast==='function'){showToast(" + msg + ");}",
                null
            )
        }
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
        fun commitText(
            text: String
        ) {

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() +
                    120L

            val ic =
                currentInputConnection
                    ?: return

            try {

                if (externalComposingActive) {

                    ic.finishComposingText()

                    externalComposingActive =
                        false
                }

                applyPendingExternalCursor(ic)

                ic.commitText(
                    text,
                    1
                )

                if (
                    text.contains('\n') ||
                    text.contains('\r')
                ) {

                    pendingExternalCursorUtf16 =
                        null

                    externalComposingActive =
                        false

                    suppressSelectionSyncUntil =
                        android.os.SystemClock.uptimeMillis() +
                            250L

                    rememberActualSelection()

                } else {

                    rememberActualSelection()
                }

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setComposing(
            text: String
        ) {

            val ic =
                currentInputConnection
                    ?: return

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() +
                    180L

            try {

                if (externalComposingActive) {

                    ic.setComposingText(
                        text,
                        1
                    )

                    val isCompleteHangul =
                        text.length == 1 &&
                        text[0].code in
                            0xAC00..0xD7A3

                    if (isCompleteHangul) {

                        ic.finishComposingText()

                        externalComposingActive =
                            false
                    }

                    rememberActualSelection()

                    return
                }

                val target =
                    pendingExternalCursorUtf16

                if (target != null) {

                    ic.finishComposingText()

                    ic.setSelection(
                        target,
                        target
                    )

                    ic.setComposingText(
                        text,
                        1
                    )

                    pendingExternalCursorUtf16 =
                        null

                    externalComposingActive =
                        true

                    val newPos =
                        target + text.length

                    lastKnownSelectionStart =
                        newPos

                    lastKnownSelectionEnd =
                        newPos

                    rememberActualSelection()

                    return
                }

                ic.setComposingText(
                    text,
                    1
                )

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
                android.os.SystemClock.uptimeMillis() +
                    300L

            val ic =
                currentInputConnection
                    ?: return

            try {

                ic.finishComposingText()

                ic.setSelection(
                    target,
                    target
                )

                ic.setComposingText(
                    text,
                    1
                )

                pendingExternalCursorUtf16 =
                    null

                externalComposingActive =
                    true

                val newPos =
                    target + text.length

                lastKnownSelectionStart =
                    newPos

                lastKnownSelectionEnd =
                    newPos

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun extendExternalSyllable(
            text: String
        ) {

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() +
                    180L

            val ic =
                currentInputConnection
                    ?: return

            try {

                pendingExternalCursorUtf16 =
                    null

                if (externalComposingActive) {

                    ic.setComposingText(
                        text,
                        1
                    )

                    ic.finishComposingText()

                } else {

                    ic.deleteSurroundingText(
                        1,
                        0
                    )

                    ic.commitText(
                        text,
                        1
                    )
                }

                externalComposingActive =
                    false

                rememberActualSelection()

                syncHtmlWithNativeText()

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
                android.os.SystemClock.uptimeMillis() +
                    120L

            val ic =
                currentInputConnection
                    ?: return

            try {

                ic.setSelection(
                    start,
                    end
                )

                lastKnownSelectionStart =
                    start

                lastKnownSelectionEnd =
                    end

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        // ----------------------------------------------------
        // 설정 아이콘 좌우 슬라이드로 커서를 한 칸 옮기는 전용 함수.
        // ----------------------------------------------------
        @JavascriptInterface
        fun moveCursorStep(direction: Int) {

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 120L

            val ic = currentInputConnection ?: return

            try {

                val keyCode =
                    if (direction > 0) {
                        KeyEvent.KEYCODE_DPAD_RIGHT
                    } else {
                        KeyEvent.KEYCODE_DPAD_LEFT
                    }

                val now =
                    android.os.SystemClock.uptimeMillis()

                ic.sendKeyEvent(
                    KeyEvent(
                        now,
                        now,
                        KeyEvent.ACTION_DOWN,
                        keyCode,
                        0
                    )
                )

                ic.sendKeyEvent(
                    KeyEvent(
                        now,
                        now,
                        KeyEvent.ACTION_UP,
                        keyCode,
                        0
                    )
                )

                if (lastKnownSelectionStart >= 0) {
                    lastKnownSelectionStart += direction
                    lastKnownSelectionEnd = lastKnownSelectionStart
                }

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        // ----------------------------------------------------
        // 백스페이스 왼쪽 스와이프(좌측 전체 삭제) 전용 함수
        // - 우측 글은 절대 건드리지 않고, 오직 커서 기준 좌측(Before)만 삭제합니다.
        // ----------------------------------------------------
        @JavascriptInterface
        fun deleteAllByKeyEvents(count: Int) {

            val ic = currentInputConnection ?: return

            try {

                externalComposingActive = false
                pendingExternalCursorUtf16 = null

                try {
                    ic.finishComposingText()
                } catch (_: Exception) {
                }

                internalSelectionUntil =
                    android.os.SystemClock.uptimeMillis() + 250L

                val beforeLength =
                    try {
                        ic.getTextBeforeCursor(50000, 0)?.length ?: count
                    } catch (_: Exception) {
                        count
                    }

                // 네이버 카페 본문처럼 커서 이동 직후 "앞 글자 수"를 0으로
                // 답하는 화면: 물어본 값이 0이면 믿지 않고, 자판이 알고
                // 있는 글자 수(count)만큼 deleteSurroundingText를 그냥
                // 실행한다. (뒤쪽 글은 건드리지 않고, 모자라면 있는
                // 만큼만 지워진다.)
                val trustedQuery = beforeLength > 0
                val deleteLength = if (trustedQuery) beforeLength else count

                // 새 시도: getTextBeforeCursor가 0이어도 편집기가 직접
                // 알려주는 "추출 텍스트"(전체 글 + 커서 위치)는 다를 수
                // 있다. 그 커서 위치만큼 deleteSurroundingText로 지우고,
                // 안 지워졌으면 범위선택 후 삭제도 시도한다. 결과는
                // 화면에 짧게 표시해서 어느 단계가 통했는지 확인한다.
                var diagBefore = ""
                if (!trustedQuery) {
                    try {
                        val req = android.view.inputmethod.ExtractedTextRequest()
                        req.hintMaxChars = 100000
                        req.hintMaxLines = 10000
                        req.flags = 0

                        val et1 = ic.getExtractedText(req, 0)

                        if (et1 == null) {
                            diagBefore = "추출실패"
                        } else {
                            val len1 = et1.text?.length ?: -1
                            val abs1 = et1.startOffset + et1.selectionStart
                            diagBefore = "추출 길이=$len1 커서=$abs1"

                            if (abs1 > 0) {
                                ic.beginBatchEdit()
                                try {
                                    ic.deleteSurroundingText(abs1, 0)
                                } finally {
                                    ic.endBatchEdit()
                                }

                                val et2 = ic.getExtractedText(req, 0)
                                val abs2 =
                                    if (et2 == null) -1
                                    else et2.startOffset + et2.selectionStart

                                if (abs2 > 0) {
                                    try {
                                        ic.setSelection(0, abs2)
                                        ic.commitText("", 1)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        diagBefore = "추출오류"
                    }
                }

                if (deleteLength > 0) {
                    ic.beginBatchEdit()
                    try {
                        ic.deleteSurroundingText(deleteLength, 0)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        if (trustedQuery) {
                            try {
                                ic.setSelection(0, beforeLength)
                                ic.commitText("", 1)
                            } catch (_: Exception) {}
                        }
                    } finally {
                        ic.endBatchEdit()
                    }
                }

                if (diagBefore.isNotEmpty() && ::webView.isInitialized) {
                    val afterInfo =
                        try {
                            val req2 = android.view.inputmethod.ExtractedTextRequest()
                            req2.hintMaxChars = 100000
                            req2.hintMaxLines = 10000
                            val et3 = ic.getExtractedText(req2, 0)
                            if (et3 == null) "없음"
                            else "길이=" + (et3.text?.length ?: -1) +
                                " 커서=" + (et3.startOffset + et3.selectionStart)
                        } catch (_: Exception) {
                            "오류"
                        }
                    val msg =
                        org.json.JSONObject.quote(
                            "삭제전: " + diagBefore + " / 삭제후: " + afterInfo
                        )
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window.showToast || typeof showToast==='function'){showToast(" + msg + ");}",
                            null
                        )
                    }
                }

                if (!trustedQuery) {
                    retryDeleteLeft(1)
                }

                lastKnownSelectionStart = 0
                lastKnownSelectionEnd = 0

                cancelHtmlExternalCursorState()

                rememberActualSelection()

                syncHtmlWithNativeText()

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun deleteText() {

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() +
                    250L

            val ic =
                currentInputConnection
                    ?: return

            try {

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

                    externalComposingActive =
                        false

                    pendingExternalCursorUtf16 =
                        null

                    try {
                        ic.finishComposingText()
                    } catch (_: Exception) {
                    }

                    ic.setSelection(
                        selectionStart,
                        selectionEnd
                    )

                    ic.commitText(
                        "",
                        1
                    )

                    lastKnownSelectionStart =
                        selectionStart

                    lastKnownSelectionEnd =
                        selectionStart

                    cancelHtmlExternalCursorState()

                    rememberActualSelection()

                    syncHtmlWithNativeText()

                    return
                }

                externalComposingActive =
                    false

                val selectedText =
                    ic.getSelectedText(0)

                if (!selectedText.isNullOrEmpty()) {

                    pendingExternalCursorUtf16 =
                        null

                    ic.commitText(
                        "",
                        1
                    )

                    cancelHtmlExternalCursorState()

                    rememberActualSelection()

                    syncHtmlWithNativeText()

                    return
                }

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

                val codePoint =
                    beforeNow.codePointBefore(
                        cursor
                    )

                val charLength =
                    Character.charCount(
                        codePoint
                    )

                val start =
                    cursor - charLength

                if (
                    codePoint in
                        0xAC00..0xD7A3
                ) {

                    val syllableIndex =
                        codePoint - 0xAC00

                    val cho =
                        syllableIndex /
                            (21 * 28)

                    val jung =
                        (
                            syllableIndex %
                                (21 * 28)
                            ) / 28

                    val jong =
                        syllableIndex % 28

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

                    if (jong != 0) {

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

                        try {
                            ic.finishComposingText()
                        } catch (_: Exception) {
                        }

                        ic.setSelection(
                            start,
                            cursor
                        )

                        ic.setComposingText(
                            replacement,
                            1
                        )

                        externalComposingActive =
                            true

                        val newCursor =
                            start +
                                replacement.length

                        lastKnownSelectionStart =
                            newCursor

                        lastKnownSelectionEnd =
                            newCursor

                        pendingExternalCursorUtf16 =
                            null

                        cancelHtmlExternalCursorState()

                        rememberActualSelection()

                        syncHtmlAfterDelete(
                            "syllable"
                        )

                        return
                    }

                    ic.setSelection(
                        start,
                        cursor
                    )

                    ic.setComposingText(
                        choseong,
                        1
                    )

                    val newCursor =
                        start +
                            choseong.length

                    lastKnownSelectionStart =
                        newCursor

                    lastKnownSelectionEnd =
                        newCursor

                    pendingExternalCursorUtf16 =
                        null

                    cancelHtmlExternalCursorState()

                    rememberActualSelection()

                    syncHtmlAfterDelete(
                        "initial"
                    )

                    return
                }

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

                pendingExternalCursorUtf16 =
                    null

                externalComposingActive =
                    false

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
                android.os.SystemClock.uptimeMillis() +
                    120L

            val ic =
                currentInputConnection
                    ?: return

            try {

                externalComposingActive =
                    false

                pendingExternalCursorUtf16 =
                    null

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

        // ----------------------------------------------------
        // 한문 변환 전용: 커서 바로 앞 글자 1개를 지우고 한문으로 교체.
        // 네이버 카페 본문처럼 setSelection(범위선택)이 안 먹히는
        // 화면에서도 되도록, 범위선택 대신 deleteSurroundingText를
        // 쓰고 한 번의 배치 편집으로 묶는다.
        // ----------------------------------------------------
        @JavascriptInterface
        fun replaceLastChar(text: String) {

            internalSelectionUntil =
                android.os.SystemClock.uptimeMillis() + 250L

            val ic =
                currentInputConnection
                    ?: return

            try {

                externalComposingActive = false
                pendingExternalCursorUtf16 = null

                try {
                    ic.finishComposingText()
                } catch (_: Exception) {
                }

                val tail =
                    try {
                        ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
                    } catch (_: Exception) {
                        ""
                    }

                val deleteLen =
                    if (
                        tail.length >= 2 &&
                        Character.isLowSurrogate(tail[tail.length - 1]) &&
                        Character.isHighSurrogate(tail[tail.length - 2])
                    ) 2 else 1

                ic.beginBatchEdit()

                try {
                    ic.deleteSurroundingText(deleteLen, 0)
                    ic.commitText(text, 1)
                } finally {
                    ic.endBatchEdit()
                }

                rememberActualSelection()

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }

        // 설치된 앱 목록 조회
        @JavascriptInterface
        fun getInstalledApps(): String {
            return try {
                val pm =
                    this@SsulKeyboardService.packageManager

                val intent =
                    Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }

                val apps =
                    org.json.JSONArray()

                val resolved =
                    pm.queryIntentActivities(intent, 0)
                        .sortedBy {
                            it.loadLabel(pm)
                                .toString()
                                .lowercase()
                        }

                val seenPackages =
                    mutableSetOf<String>()

                for (info in resolved) {
                    val packageName =
                        info.activityInfo?.packageName
                            ?: continue

                    if (!seenPackages.add(packageName)) {
                        continue
                    }

                    val label =
                        info.loadLabel(pm)
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?: packageName

                    apps.put(
                        org.json.JSONObject()
                            .put("label", label)
                            .put("packageName", packageName)
                    )
                }

                apps.toString()

            } catch (e: Exception) {
                android.util.Log.e(
                    "SsulKeyboard",
                    "설치된 앱 목록 조회 실패",
                    e
                )
                "[]"
            }
        }

        // 선택한 앱 실행
        @JavascriptInterface
        fun launchApp(packageName: String) {
            try {
                val launchIntent =
                    this@SsulKeyboardService
                        .packageManager
                        .getLaunchIntentForPackage(packageName)
                        ?: return

                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )

                this@SsulKeyboardService
                    .startActivity(launchIntent)

            } catch (e: Exception) {
                android.util.Log.e(
                    "SsulKeyboard",
                    "앱 실행 실패: $packageName",
                    e
                )
            }
        }

        @JavascriptInterface
        fun isSearchField(): Boolean {

            val info =
                currentInputEditorInfo
                    ?: return false

            val action =
                info.imeOptions and
                    EditorInfo.IME_MASK_ACTION

            return action ==
                EditorInfo.IME_ACTION_SEARCH
        }

        @JavascriptInterface
        fun performSearch() {

            val ic =
                currentInputConnection
                    ?: return

            ic.performEditorAction(
                EditorInfo.IME_ACTION_SEARCH
            )
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

        @JavascriptInterface
        fun readClipboard(): String {

            return try {

                val cm =
                    clipboardManager
                        ?: (
                            getSystemService(
                                CLIPBOARD_SERVICE
                            ) as? ClipboardManager
                            )

                val clip =
                    cm?.primaryClip

                if (
                    clip != null &&
                    clip.itemCount > 0
                ) {

                    val item =
                        clip.getItemAt(0)

                    item.coerceToText(
                        this@SsulKeyboardService
                    )?.toString() ?: ""

                } else {

                    ""
                }

            } catch (e: Exception) {

                e.printStackTrace()

                ""
            }
        }

        @JavascriptInterface
        fun isRecentClipboard(
            text: String
        ): Boolean {

            return try {

                isRecentClipboardText(
                    text
                )

            } catch (_: Exception) {

                false
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

        if (newSelStart != newSelEnd) {

            try {

                currentInputConnection
                    ?.finishComposingText()

            } catch (_: Exception) {
            }

            lastKnownSelectionStart =
                newSelStart

            lastKnownSelectionEnd =
                newSelEnd

            pendingExternalCursorUtf16 =
                null

            externalComposingActive =
                false

            suppressSelectionSyncUntil =
                android.os.SystemClock.uptimeMillis() +
                    300L

            return
        }

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

        if (
            newSelStart ==
                lastKnownSelectionStart &&
            newSelEnd ==
                lastKnownSelectionEnd
        ) {
            return
        }

        if (externalComposingActive) {

            lastKnownSelectionStart =
                newSelStart

            lastKnownSelectionEnd =
                newSelEnd

            return
        }

        try {

            currentInputConnection
                ?.finishComposingText()

        } catch (_: Exception) {
        }

        externalComposingActive =
            false

        lastKnownSelectionStart =
            newSelStart

        lastKnownSelectionEnd =
            newSelEnd

        pendingExternalCursorUtf16 =
            newSelStart

        suppressSelectionSyncUntil =
            android.os.SystemClock.uptimeMillis() +
                300L

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

        pendingExternalCursorUtf16 =
            null

        externalComposingActive =
            false

        if (::webView.isInitialized) {

            webView.evaluateJavascript(
                "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
                null
            )

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
