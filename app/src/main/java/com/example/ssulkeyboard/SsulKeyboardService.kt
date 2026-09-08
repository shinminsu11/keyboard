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
            runOnMain {
                currentInputConnection?.commitText(text, 1)
            }
        }

        @JavascriptInterface
        fun setComposing(text: String) {
            runOnMain {
                currentInputConnection?.setComposingText(text, 1)
            }
        }

        @JavascriptInterface
        fun setSelection(start: Int, end: Int) {
            runOnMain {
                try {
                    currentInputConnection?.setSelection(start, end)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        @JavascriptInterface
        fun deleteText() {
            runOnMain {
                val ic = currentInputConnection ?: return@runOnMain
                ic.finishComposingText()
                ic.deleteSurroundingText(1, 0)
            }
        }

        @JavascriptInterface
        fun deleteBeforeCursor() {
            runOnMain {
                val ic = currentInputConnection ?: return@runOnMain
                ic.finishComposingText()
                ic.deleteSurroundingText(1, 0)
            }
        }

        @JavascriptInterface
        fun getCursorInfo(): String {
            var result = "{"before":"","after":"","composing":false}"
            val action = Runnable {
                val ic = currentInputConnection
                if (ic != null) {
                    try {
                        val before = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
                        val after = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""
                        val composing = try {
                            val ex = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { token = 1 }, 0)
                            ex?.selectionStart != null && ex.selectionStart >= 0 && ex.selectionEnd >= 0 && ex.text != null
                        } catch (_: Exception) { false }
                        result = "{"before":${jsonQuote(before)},"after":${jsonQuote(after)},"composing":$composing}"
                    } catch (_: Exception) { }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action.run()
            else {
                val latch = CountDownLatch(1)
                mainHandler.post { try { action.run() } finally { latch.countDown() } }
                try { latch.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
            }
            return result
        }

        @JavascriptInterface
        fun performSearch() {
            runOnMain {
                currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
            }
        }

        @JavascriptInterface
        fun onEnter() {
            runOnMain {
                val ic = currentInputConnection ?: return@runOnMain
                if (!ic.performEditorAction(EditorInfo.IME_ACTION_NONE)) {
                    ic.commitText("\n", 1)
                }
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnMain {
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
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun jsonQuote(value: String): String {
        return org.json.JSONObject.quote(value)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::webView.isInitialized) {
            webView.evaluateJavascript("javascript:if(window.resetKeyboardBuffer) { window.resetKeyboardBuffer(); }", null)
            webView.post { webView.requestLayout() }
        }
        window.window?.decorView?.requestLayout()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }
}
