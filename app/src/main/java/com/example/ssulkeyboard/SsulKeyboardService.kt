package com.example.ssulkeyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class SsulKeyboardService : InputMethodService() {

    private lateinit var webView: WebView

    override fun onCreateInputView(): ViewGroup {
        val container = FrameLayout(this)
        container.setBackgroundColor(Color.TRANSPARENT)

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        webView.addJavascriptInterface(KeyboardBridge(), "AndroidKeyboard")
        webView.loadUrl("file:///android_asset/index.html")

        container.addView(webView)
        return container
    }

    private fun getInputConnection(): InputConnection? {
        return currentInputConnection
    }

    inner class KeyboardBridge {

        @JavascriptInterface
        fun commitText(text: String) {
            runOnUiThread {
                getInputConnection()?.commitText(text, 1)
            }
        }

        @JavascriptInterface
        fun sendSpace() {
            runOnUiThread {
                getInputConnection()?.commitText(" ", 1)
            }
        }

        @JavascriptInterface
        fun deleteText() {
            runOnUiThread {
                getInputConnection()?.deleteSurroundingText(1, 0)
            }
        }

        @JavascriptInterface
        fun replacePreviousChar(text: String) {
            runOnUiThread {
                val ic = getInputConnection() ?: return@runOnUiThread
                ic.deleteSurroundingText(1, 0)
                ic.commitText(text, 1)
            }
        }

        @JavascriptInterface
        fun sendEnter() {
            runOnUiThread {
                val ic = getInputConnection() ?: return@runOnUiThread
                val info = currentInputEditorInfo
                val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                    ?: EditorInfo.IME_ACTION_NONE

                if (action != EditorInfo.IME_ACTION_NONE &&
                    action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    ic.commitText("\n", 1)
                }
            }
        }

        @JavascriptInterface
        fun moveCursor(direction: Int) {
            runOnUiThread {
                val ic = getInputConnection() ?: return@runOnUiThread
                val keyCode = when {
                    direction < 0 -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    direction > 0 -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    else -> return@runOnUiThread
                }

                ic.sendKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN,
                        keyCode
                    )
                )
                ic.sendKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_UP,
                        keyCode
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidKeyboard")
            webView.destroy()
        }
        super.onDestroy()
    }
}
