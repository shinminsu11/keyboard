package com.example.ssulkeyboard

import android.content.Intent
import android.net.Uri
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.graphics.Color
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

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

            loadUrl("file:///android_asset/keyboard.html")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }
            }
        }

        container.addView(webView)
        return container
    }

    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(text: String) {
            runOnMainSync {
                val inputConnection = currentInputConnection ?: return@runOnMainSync
                if (text == "\n" || text.contains("\n")) {
                    inputConnection.sendKeyEvent(
                        android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_DOWN,
                            android.view.KeyEvent.KEYCODE_ENTER
                        )
                    )
                    inputConnection.sendKeyEvent(
                        android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_UP,
                            android.view.KeyEvent.KEYCODE_ENTER
                        )
                    )
                    inputConnection.performEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED)
                } else {
                    inputConnection.commitText(text, 1)
                }
            }
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            runOnMainSync {
                val inputConnection = currentInputConnection ?: return@runOnMainSync
                inputConnection.setComposingText(text, 1)
            }
        }

        /**
         * 중요: WebView JavascriptInterface에서 호출된 뒤 바로 다음 JS가 실행되므로
         * 단순 Handler.post()만 하면 커서 이동보다 삭제/입력이 먼저 실행될 수 있습니다.
         * 따라서 메인 스레드에서 처리한 뒤 JS 호출이 끝날 때까지 기다립니다.
         */
        @JavascriptInterface
        fun setSelection(start: Int, end: Int): Boolean {
            return runOnMainSync {
                val inputConnection = currentInputConnection ?: return@runOnMainSync false
                try {
                    inputConnection.setSelection(start, end)
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            } ?: false
        }

        @JavascriptInterface
        fun deleteText() {
            runOnMainSync {
                val inputConnection = currentInputConnection ?: return@runOnMainSync
                try {
                    inputConnection.deleteSurroundingText(1, 0)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        /**
         * HTML의 커서 위치를 먼저 실제 Android 커서로 옮긴 다음,
         * 같은 메인 스레드 작업 안에서 바로 앞 글자를 삭제합니다.
         * setSelection()과 delete() 사이에 다른 작업이 끼어들지 않습니다.
         */
        @JavascriptInterface
        fun deleteTextAtCursor(position: Int): Boolean {
            return runOnMainSync {
                val inputConnection = currentInputConnection ?: return@runOnMainSync false
                try {
                    // 이 버전에서는 setSelection()에 의존하지 않습니다.
                    // 현재 입력창의 실제 텍스트를 읽고, 커서를 끝으로 보낸 뒤
                    // 목표 위치까지 DPAD_LEFT로 직접 이동해서 삭제합니다.
                    inputConnection.finishComposingText()

                    val before = inputConnection.getTextBeforeCursor(100000, 0)?.toString() ?: ""
                    val after = inputConnection.getTextAfterCursor(100000, 0)?.toString() ?: ""
                    val fullText = before + after

                    if (position <= 0 || position > fullText.length) return@runOnMainSync false

                    val safePosition = position.coerceAtMost(fullText.length)
                    val rightText = fullText.substring(safePosition)
                    val moveLeftCount = rightText.codePointCount(0, rightText.length)

                    // 먼저 실제 입력 커서를 문장 끝으로 확실히 이동합니다.
                    inputConnection.sendKeyEvent(
                        android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_DOWN,
                            android.view.KeyEvent.KEYCODE_MOVE_END
                        )
                    )
                    inputConnection.sendKeyEvent(
                        android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_UP,
                            android.view.KeyEvent.KEYCODE_MOVE_END
                        )
                    )

                    // 목표 위치까지 왼쪽으로 이동합니다.
                    repeat(moveLeftCount) {
                        inputConnection.sendKeyEvent(
                            android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            )
                        )
                        inputConnection.sendKeyEvent(
                            android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_UP,
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            )
                        )
                    }

                    // 이제 실제 Android 커서 바로 앞의 문자 1개를 삭제합니다.
                    inputConnection.deleteSurroundingText(1, 0)
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            } ?: false
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

    /**
     * WebView bridge 호출을 Android 메인 스레드에서 동기적으로 실행합니다.
     * 이미 메인 스레드라면 바로 실행합니다.
     */
    private fun <T> runOnMainSync(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        var result: T? = null
        val latch = CountDownLatch(1)

        mainHandler.post {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }

        return try {
            if (latch.await(1000, TimeUnit.MILLISECONDS)) result else null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        webView.evaluateJavascript(
            "javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }",
            null
        )

        window.window?.let { window ->
            window.decorView.let { decorView ->
                decorView.requestLayout()
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
