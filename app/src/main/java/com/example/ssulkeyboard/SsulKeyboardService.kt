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

    // 사용자가 실제 앱에서 커서를 옮긴 직후의 "첫 입력"에만 사용합니다.
    // 평상시 한글 입력에는 영향을 주지 않습니다.
    private var pendingExternalCursorUtf16: Int? = null
    private var suppressSelectionSyncUntil = 0L

    // 자판이 직접 입력/커서 이동을 한 직후의 selection callback은
    // 외부 커서 이동으로 오인하지 않도록 잠시 무시합니다.
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
                applyPendingExternalCursor(ic)
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

            // 중간 커서 입력은 HTML의 composing 경로와 섞지 않고
            // 여기서 딱 한 번만 처리합니다.
            val target = pendingExternalCursorUtf16
            if (target != null) {
                insertAtExternalCursor(text, target)
                return
            }

            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 120L
            try {
                ic.setComposingText(text, 1)
                rememberActualSelection()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun insertAtExternalCursor(text: String, target: Int) {
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 300L
            val ic = currentInputConnection ?: return

            try {
                ic.finishComposingText()

                val before = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
                val after = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""
                val full = before + after

                if (target < 0 || target > full.length) {
                    pendingExternalCursorUtf16 = null
                    return
                }

                val prefix = full.substring(0, target)
                val suffix = full.substring(target)
                val rebuilt = prefix + text + suffix

                // 현재 입력창 전체를 한 번에 다시 넣습니다.
                ic.deleteSurroundingText(before.length, after.length)
                ic.commitText(rebuilt, 1)

                val newPos = (prefix + text).length

                // 전체 문자열을 넣은 직후 원하는 위치로 커서를 옮깁니다.
                // 이번에는 기존 텍스트 위에서 이동시키는 것이 아니라
                // 방금 새로 넣은 텍스트 위에서 이동시키므로 충돌 가능성이 작습니다.
                ic.setSelection(newPos, newPos)

                // 중간에 삽입한 글자는 확정 문자로 유지합니다.

                pendingExternalCursorUtf16 = null
                lastKnownSelectionStart = newPos
                lastKnownSelectionEnd = newPos

                // HTML에도 최종 결과와 커서 위치를 정확히 알려서
                // 같은 글자를 다시 setComposingText()로 보내지 못하게 합니다.
                val textJs = org.json.JSONObject.quote(rebuilt)
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
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 180L
            try {
                // 중간 삽입으로 방금 들어간 한 음절의 마지막 글자를
                // 새로 만든 완성 음절로 교체합니다.
                ic.deleteSurroundingText(1, 0)
                ic.commitText(text, 1)
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
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 120L
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
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 120L
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

        // 정상 한글 입력/삭제/자판이 요청한 커서 이동에서 발생한 callback은
        // 외부 커서 이동으로 처리하지 않습니다. 이것이 Pair19에서
        // 일반 입력까지 중간삽입 모드로 들어가던 문제를 막습니다.
        if (android.os.SystemClock.uptimeMillis() < internalSelectionUntil) {
            return
        }

        if (android.os.SystemClock.uptimeMillis() < suppressSelectionSyncUntil) {
            lastKnownSelectionStart = newSelStart
            lastKnownSelectionEnd = newSelEnd
            return
        }

        // candidates 영역이 잡혀 있어도 사용자가 실제 입력창을 터치해
        // 커서를 옮긴 경우에는 이 callback이 들어올 수 있습니다.
        // 앞의 internalSelectionUntil 차단으로 자판이 만든 callback은
        // 이미 걸러지므로 여기서는 candidates를 이유로 무시하지 않습니다.

        // 자판이 방금 만든 selection callback이면 무시합니다.
        if (newSelStart == lastKnownSelectionStart &&
            newSelEnd == lastKnownSelectionEnd
        ) {
            return
        }

        // 실제 앱에서 손가락으로 커서를 옮긴 경우입니다.
        // 마지막 한글(예: '마')이 composing 상태라면 먼저 확정합니다.
        // finishComposingText()는 비동기로 selection callback을 다시 만들 수
        // 있으므로, 그 callback이 HTML 상태를 덮어쓰지 못하게 잠시 막습니다.
        // 그리고 실제 Android 입력창의 앞/뒤 문자열과 현재 커서를 다시 읽은 뒤
        // HTML에 동기화합니다.
        lastKnownSelectionStart = newSelStart
        lastKnownSelectionEnd = newSelEnd

        // 여기서 바로 finishComposingText()/setSelection()을 실행하면
        // 정상 한글 입력의 조합 과정까지 건드릴 수 있습니다.
        // 대신 "외부 커서 이동 후 첫 입력"에만 실제 커서 위치를 적용합니다.
        pendingExternalCursorUtf16 = newSelStart
        suppressSelectionSyncUntil = android.os.SystemClock.uptimeMillis() + 150L

        // 이번 커서 이동은 "외부에서 손가락으로 옮긴 커서"임을
        // HTML에 명확히 표시합니다. 다음 한글 첫 글자 한 번만
        // 전용 중간 삽입 경로로 보내고, 그 뒤에는 기존 조합 경로로 돌아갑니다.
        webView.post {
            webView.evaluateJavascript(
                "javascript:if(window.beginExternalCursorInsert) { window.beginExternalCursorInsert(); }",
                null
            )
        }

        // 현재 앱의 실제 앞/뒤 문자열을 HTML에 알려 주되,
        // 아직 Android 커서를 강제로 움직이지 않습니다.
        webView.post {
            if (android.os.SystemClock.uptimeMillis() <= suppressSelectionSyncUntil) {
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

        // 시작 시점의 실제 커서 위치를 기준점으로 잡습니다.
        webView.post {
            rememberActualSelection()
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
