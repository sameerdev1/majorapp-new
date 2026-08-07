package com.majorgym.app.kiosk

import android.media.AudioManager
import android.media.ToneGenerator

/** Simple system beeps via ToneGenerator — no bundled audio assets required.
 *  Works fine called from the background service; doesn't need an Activity. */
internal object KioskSound {
    fun playSuccess() = runCatching {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tg.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        tg.release()
    }
    fun playError() = runCatching {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tg.startTone(ToneGenerator.TONE_PROP_NACK, 350)
        tg.release()
    }
}
