        @JavascriptInterface
        fun deleteText() {
            internalSelectionUntil = android.os.SystemClock.uptimeMillis() + 180L
            val ic = currentInputConnection ?: return
            try {
                val rememberedStart = lastKnownSelectionStart
                val rememberedEnd = lastKnownSelectionEnd

                if (rememberedStart >= 0 && rememberedEnd >= 0 && rememberedStart != rememberedEnd) {
                    val selectionStart = minOf(rememberedStart, rememberedEnd)
                    val selectionEnd = maxOf(rememberedStart, rememberedEnd)
                    externalComposingActive = false
                    pendingExternalCursorUtf16 = null
                    try { ic.finishComposingText() } catch (_: Exception) {}
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
                val selectedText = ic.getSelectedText(0)
                if (!selectedText.isNullOrEmpty()) {
                    pendingExternalCursorUtf16 = null
                    ic.commitText("", 1)
                    cancelHtmlExternalCursorState()
                    rememberActualSelection()
                    syncHtmlWithNativeText()
                    return
                }

                val beforeNow = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
                val originalCursor = beforeNow.length
                if (originalCursor <= 0) return

                try { ic.finishComposingText() } catch (_: Exception) {}
                try { ic.setSelection(originalCursor, originalCursor) } catch (_: Exception) {}

                val textBefore = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
                if (textBefore.isEmpty()) return

                // 서러게이트 쌍(이모티콘 등) 처리
                if (textBefore.length >= 2) {
                    val high = textBefore[textBefore.length - 2]
                    val low = textBefore[textBefore.length - 1]
                    if (Character.isSurrogatePair(high, low)) {
                        ic.deleteSurroundingText(2, 0)
                        pendingExternalCursorUtf16 = null
                        rememberActualSelection()
                        syncHtmlWithNativeText()
                        return
                    }
                }

                val lastChar = textBefore.last()
                val code = lastChar.code

                // 완성된 한글 음절인지 확인 (가 ~ 힣)
                if (code in 0xAC00..0xD7A3) {
                    val offset = code - 0xAC00
                    val cho = offset / (21 * 28)
                    val jung = (offset / 28) % 21
                    val jong = offset % 28

                    if (jong > 0) {
                        // 1단계: 종성(받침)이 있으면 받침만 떼어내고 초성과 모음만 남김
                        val newCode = 0xAC00 + cho * 21 * 28 + jung * 28
                        val newChar = Char(newCode)
                        ic.deleteSurroundingText(1, 0)
                        ic.commitText(newChar.toString(), 1)
                    } else {
                        // 2단계: 종성이 없고 모음만 남았으면 모음을 떼고 초성만 조합 상태(setComposingText)로 남김
                        val choseongChars = arrayOf('ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ')
                        val choChar = choseongChars[cho]
                        ic.deleteSurroundingText(1, 0)
                        ic.setComposingText(choChar.toString(), 1)
                    }
                } else {
                    // 한글이 아니거나 완성형이 아니면 기존처럼 1글자 통째로 삭제
                    ic.deleteSurroundingText(1, 0)
                }

                pendingExternalCursorUtf16 = null
                rememberActualSelection()
                syncHtmlWithNativeText()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
