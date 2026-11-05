package com.personal.personalai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.personal.personalai.data.audio.OfflineMandarinTtsEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OfflineMandarinTtsEngineTest {

    @Test
    fun `test speak fails closed with explicit capability error when engine is not initialized`() {
        val context = mockk<Context>(relaxed = true)
        val engine = OfflineMandarinTtsEngine(context)

        var errorCaptured: String? = null
        engine.speak("Test text", onError = { errorCaptured = it })

        assertTrue(errorCaptured != null)
        assertTrue(errorCaptured!!.contains("LOCAL_TTS_UNAVAILABLE"))
    }
}
