package com.htb.carplan.car

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * 9장 voice: Pleos TTS + STT, on-device mode (verified on the emulator; server mode
 * needs a Pleos app key). TTS and STT cannot run at once, so it always speaks first
 * and listens only after `onDone`. If either service is missing the question is still
 * spoken (or skipped) and the screen buttons do the rest.
 */
class VoicePrompt(context: Context) {
    enum class Answer { YES, NO, UNKNOWN }

    private val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    @Volatile private var ttsReady = false
    @Volatile private var sttReady = false
    private val lock = Mutex()

    fun start() {
        runCatching {
            tts.initialize(object : TtsInit {
                override fun onSuccess() { ttsReady = true; Log.i(TAG, "TTS ready") }
                override fun onFailure(e: TtsError) { Log.w(TAG, "TTS init failed: $e") }
            })
        }.onFailure { Log.w(TAG, "TTS unavailable", it) }
        runCatching {
            stt.initialize(object : SttInit {
                override fun onSuccess() { sttReady = true; Log.i(TAG, "STT ready") }
                override fun onFailure(e: SttError) { Log.w(TAG, "STT init failed: $e") }
            })
        }.onFailure { Log.w(TAG, "STT unavailable", it) }
    }

    fun release() {
        runCatching { tts.release() }
        runCatching { stt.release() }
    }

    /**
     * Asks [question] and returns what the driver answered. Re-asks once when the answer
     * is not understood (Q21), then gives up and leaves the screen buttons.
     * [onListening] tells the screen when the mic is open and what was heard.
     */
    suspend fun ask(question: String, onListening: (listening: Boolean, heard: String?) -> Unit = { _, _ -> }): Answer = lock.withLock {
        repeat(2) { attempt ->
            speak(if (attempt == 0) question else "잘 못 들었어요. $question")
            if (!sttReady) return Answer.UNKNOWN
            onListening(true, null)
            val heard = listen(LISTEN_MS)
            onListening(false, heard)
            Log.i(TAG, "heard='$heard'")
            interpret(heard)?.let { return it }
        }
        Answer.UNKNOWN
    }

    /** Speaks [text] and returns when playback is done (or at once when TTS is unavailable). */
    suspend fun say(text: String) = lock.withLock { speak(text) }

    private suspend fun speak(text: String) {
        if (!ttsReady) { Log.w(TAG, "TTS not ready, skipping: $text"); return }
        Log.i(TAG, "speak: $text")
        // The emulator's on-device TTS never reports onDone, so wait about as long as the sentence takes.
        withTimeoutOrNull(SPEAK_BASE_MS + SPEAK_PER_CHAR_MS * text.length) {
            suspendCancellableCoroutine { cont ->
                val listener = object : EventListener {
                    private var done = false
                    private fun finish() { if (!done) { done = true; tts.removeEventListener(this); if (cont.isActive) cont.resume(Unit) } }
                    override fun onStart() = Unit
                    override fun onReady() = Unit
                    override fun onDone() { Log.i(TAG, "TTS done"); finish() }
                    override fun onStop() { Log.i(TAG, "TTS stopped"); finish() }
                    override fun onError(e: TtsError) { Log.w(TAG, "TTS error $e"); finish() }
                    override fun onUpdatedRms(rms: Double) = Unit
                }
                tts.addEventListener(listener)
                cont.invokeOnCancellation { tts.removeEventListener(listener); runCatching { tts.stop() } }
                runCatching { tts.speak(text, false) }.onFailure { Log.w(TAG, "speak failed", it); tts.removeEventListener(listener); cont.resume(Unit) }
            }
        }
    }

    /** Opens the mic and returns the final recognised text, or null on silence / error / timeout. */
    private suspend fun listen(timeoutMs: Long): String? {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { cont ->
                val listener = object : ResultListener {
                    private var done = false
                    private var partial: String? = null
                    private fun finish(text: String?) { if (!done) { done = true; stt.removeListener(this); if (cont.isActive) cont.resume(text) } }
                    override fun onUpdated(text: String, isFinal: Boolean) { partial = text; if (isFinal) finish(text) }
                    override fun onUpdatedEpdData(a: Long, b: Long) = Unit
                    override fun onStartedRecognition() = Unit
                    override fun onEndedRecognition() = finish(partial)
                    override fun onError(e: SttError) { Log.w(TAG, "STT error $e"); finish(partial) }
                    override fun onReady() = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                }
                stt.addListener(listener)
                cont.invokeOnCancellation { stt.removeListener(listener); runCatching { stt.stop() } }
                runCatching { stt.request() }.onFailure { Log.w(TAG, "STT request failed", it); stt.removeListener(listener); cont.resume(null) }
            }
        }
        if (result == null) { Log.i(TAG, "STT: nothing heard"); runCatching { stt.stop() } }
        return result?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "VoicePrompt"
        private const val SPEAK_BASE_MS = 1_000L
        private const val SPEAK_PER_CHAR_MS = 220L
        private const val LISTEN_MS = 7_000L
        private val YES = listOf("응", "어", "네", "예", "가자", "갈게", "가요", "갈까", "좋아", "그래", "출발", "오케이", "ok", "yes", "go")
        private val NO = listOf("아니", "아뇨", "싫어", "나중에", "안 가", "안가", "됐어", "no", "later")

        /** Null when the words match neither side (or both). */
        fun interpret(text: String?): Answer? {
            val t = text?.trim()?.lowercase()?.replace(" ", "") ?: return null
            val no = NO.any { t.contains(it.replace(" ", "")) }
            val yes = YES.any { t.contains(it) }
            return when {
                no && !yes -> Answer.NO
                yes && !no -> Answer.YES
                no -> Answer.NO // "아니 가지 말자" style: a refusal word wins
                else -> null
            }
        }
    }
}
