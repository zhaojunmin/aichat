package com.octos.aichat.ocr

import com.octos.aichat.data.Speaker

internal object OcrSpeaker {

    /**
     * Who sent a message read by OCR. A green bubble (WeChat's "me", ~#95EC69 light, darker
     * green in dark mode) is always mine — colour is immune to message length, unlike
     * left/right position. For apps whose own messages are exactly the green ones
     * ([greenOnly], WeChat) anything else is the other side: white file / link cards and
     * system lines included. Other apps fall back to the side the text hugs.
     */
    fun of(green: Boolean, greenOnly: Boolean, leftMargin: Int, rightMargin: Int): Speaker = when {
        green -> Speaker.ME
        greenOnly -> Speaker.OTHER
        rightMargin < leftMargin -> Speaker.ME
        else -> Speaker.OTHER
    }
}
