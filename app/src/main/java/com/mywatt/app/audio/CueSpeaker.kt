package com.mywatt.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.mywatt.app.engine.Cue
import com.mywatt.app.engine.CueKind
import java.util.Locale

/**
 * Plays session cues: Android TextToSpeech in British English, or beeps when the rider
 * chose beep-only or the phone has no usable English voice. Each cue asks for transient
 * audio focus with ducking, so the rider's music dips under it rather than stopping, and
 * gives focus back once the voice has finished.
 */
class CueSpeaker(context: Context) {

    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var voiceReady = false
    private var voiceChecked = false
    private var pending = 0
    private var focusHeld = false
    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
        } else {
            null
        }
    private val releaseFocus = Runnable { abandonFocus() }

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        voiceReady = status == TextToSpeech.SUCCESS && setUpVoice()
        voiceChecked = true
        if (!voiceReady) Log.w(TAG, "No usable en-GB voice (status $status); cues will beep")
    }

    private val tones: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME) }.getOrNull()

    private fun setUpVoice(): Boolean {
        val result = tts.setLanguage(Locale.UK)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
        })
        return true
    }

    private fun finished() {
        main.post {
            pending = (pending - 1).coerceAtLeast(0)
            if (pending == 0) {
                main.removeCallbacks(releaseFocus)
                main.postDelayed(releaseFocus, FOCUS_TAIL_MS)
            }
        }
    }

    /** True when cues will be spoken rather than beeped (given the rider has not asked for beeps). */
    val canSpeak: Boolean get() = voiceReady

    /** Plays [cues] in order. A count or "go" cuts off anything still queued, so it lands on time. */
    fun play(cues: List<Cue>, beepOnly: Boolean) {
        if (cues.isEmpty()) return
        // While the voice is still starting up, say nothing rather than beep by mistake.
        if (!beepOnly && !voiceChecked) return
        requestFocus()
        if (beepOnly || !voiceReady) {
            cues.forEachIndexed { i, cue -> main.postDelayed({ beep(cue.kind) }, i * BEEP_GAP_MS) }
            main.removeCallbacks(releaseFocus)
            main.postDelayed(releaseFocus, cues.size * BEEP_GAP_MS + FOCUS_TAIL_MS)
            return
        }
        cues.forEachIndexed { i, cue ->
            val flush = i == 0 && cue.kind != CueKind.INFO
            if (flush) pending = 0
            pending++
            tts.speak(cue.text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "cue${counter++}")
        }
    }

    private var counter = 0

    private fun beep(kind: CueKind) {
        val t = tones ?: return
        when (kind) {
            CueKind.COUNT -> t.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            CueKind.GO -> t.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
            CueKind.INFO -> t.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        }
    }

    private fun requestFocus() {
        main.removeCallbacks(releaseFocus)
        if (focusHeld) return
        focusHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audio.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audio.abandonAudioFocusRequest(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
    }

    /** Stops anything playing and hands focus back. */
    fun stop() {
        tts.stop()
        pending = 0
        abandonFocus()
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        stop()
        tts.shutdown()
        tones?.release()
    }

    companion object {
        private const val TAG = "MyWattCues"
        private const val TONE_VOLUME = 80
        private const val BEEP_GAP_MS = 250L
        private const val FOCUS_TAIL_MS = 400L
    }
}
