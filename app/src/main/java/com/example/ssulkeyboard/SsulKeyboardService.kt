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

                        // 기존 기능
                        // 최근 복사 내용과 시간을 기록
                        lastObservedClipboardText = text

                        lastObservedClipboardTime =
                            android.os.SystemClock.elapsedRealtime()

                        // ------------------------------------------------
                        // 추가 기능
                        // 클립보드 창이 이미 열려 있으면
                        // 복사한 내용을 즉시 현재 목록에 추가
                        // ------------------------------------------------
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

    // 진단 2 전용: 기존 삭제 동작은 바꾸지 않고 현재 값을 화면에 표시합니다.
    private fun showSwipeDiagnostic(message: String) {
        if (!::webView.isInitialized) return
        webView.post {
            try {
                val safeMessage = message.replace("\\", "\\\\").replace("'", "\\'")
                webView.evaluateJavascript("showToast('$safeMessage')", null)
            } catch (_: Exception) {
            }
        }
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

            val ic = currentInputConnection
            if (ic == null) {
                showSwipeDiagnostic("진단4: 입력 연결 없음 / HTML=${count}자")
                return
            }

            try {
                externalComposingActive = false
                pendingExternalCursorUtf16 = null
                try { ic.finishComposingText() } catch (_: Exception) {}

                internalSelectionUntil =
                    android.os.SystemClock.uptimeMillis() + 250L

                // 진단4: 삭제 직전 커서 앞/뒤 실제 문자열을 함께 기록합니다.
                var beforeTextLength: Int? = null
                var beforeError = ""
                var beforeSnippet = ""
                var rightSnippet = ""
                var selectedLength: Int? = null
                try {
                    val beforeText = ic.getTextBeforeCursor(50000, 0)
                    beforeTextLength = beforeText?.length
                    beforeSnippet = beforeText?.takeLast(8)?.toString()?.replace("\n", "↵") ?: "null"
                    if (beforeTextLength == null) beforeError = "null"
                } catch (e: Exception) {
                    beforeError = e.javaClass.simpleName
                }
                try {
                    rightSnippet = ic.getTextAfterCursor(8, 0)?.toString()?.replace("\n", "↵") ?: "null"
                } catch (e: Exception) {
                    rightSnippet = "오류"
                }
                try {
                    selectedLength = ic.getSelectedText(0)?.length ?: 0
                } catch (_: Exception) {
                    selectedLength = -1
                }

                val deleteLength = beforeTextLength ?: count
                if (deleteLength > 0) {
                    ic.beginBatchEdit()
                    try {
                        ic.deleteSurroundingText(deleteLength, 0)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        try {
                            ic.setSelection(0, deleteLength)
                            ic.commitText("", 1)
                        } catch (_: Exception) {}
                    } finally {
                        ic.endBatchEdit()
                    }
                }

                var afterDeleteLength: Int? = null
                var afterDeleteError = ""
                try {
                    afterDeleteLength = ic.getTextBeforeCursor(50000, 0)?.length
                    if (afterDeleteLength == null) afterDeleteError = "null"
                } catch (e: Exception) {
                    afterDeleteError = e.javaClass.simpleName
                }

                lastKnownSelectionStart = 0
                lastKnownSelectionEnd = 0
                cancelHtmlExternalCursorState()
                rememberActualSelection()
                syncHtmlWithNativeText()

                var afterSyncLength: Int? = null
                var afterSyncError = ""
                try {
                    afterSyncLength = ic.getTextBeforeCursor(50000, 0)?.length
                    if (afterSyncLength == null) afterSyncError = "null"
                } catch (e: Exception) {
                    afterSyncError = e.javaClass.simpleName
                }

                showSwipeDiagnostic(
                    "진단4: HTML=${count} / 앞=${beforeTextLength?.toString() ?: beforeError} / 선택=${selectedLength} / 앞문자=${beforeSnippet} / 뒤문자=${rightSnippet} / 삭제후=${afterDeleteLength?.toString() ?: afterDeleteError} / 동기화후=${afterSyncLength?.toString() ?: afterSyncError}"
                )

            } catch (e: Exception) {
                e.printStackTrace()
                showSwipeDiagnostic("진단4 오류: ${e.javaClass.simpleName}")
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
