package com.example.matepet

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.ArrayDeque

/**
 * Always-on, fully offline voice front end (Vosk).
 *
 *  WAKE    a tiny recognizer that only knows the phrase "hey mate" (very cheap on CPU).
 *  COMMAND a full-vocabulary recognizer that turns the next sentence into text.
 *
 * The last 2.5 s of audio are kept, so "hey mate, open YouTube" said in one breath works: when the wake phrase
 * is spotted, that audio is replayed into the command recognizer and the wake phrase is stripped from the text.
 * While temi talks or the model thinks the engine is paused (it discards audio), so temi never hears itself.
 */
class VoiceEngine(private val ctx: Context, private val cb: Callback) {
    interface Callback {
        fun onWake()                    // "hey mate" was heard
        fun onHeard(text: String)       // one spoken request, wake phrase removed. The engine pauses itself until resume()/listenFollowUp()
        fun onListenEnd()               // the listening window passed with nothing said
    }

    private enum class Mode { WAKE, COMMAND }

    @Volatile private var generation = 0
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var mode = Mode.WAKE
    @Volatile private var deadline = 0L
    @Volatile private var resetCmd = false
    @Volatile private var resetWake = false

    val modelDir: File get() = File(ctx.getExternalFilesDir(null), "vosk-model")

    fun start() {
        if (running) return
        running = true; paused = false; mode = Mode.WAKE
        val gen = ++generation
        Thread({ loop(gen) }, "matepet-voice").start()
    }

    fun stop() { running = false; generation++ }

    /** Stop reacting to audio (temi is thinking or talking). */
    fun pause() { paused = true; PetStatus.voice = "Busy (thinking or talking)" }

    /** Go back to waiting for "hey mate". */
    fun resume() {
        resetWake = true; mode = Mode.WAKE; paused = false
        PetStatus.voice = WAITING
    }

    /** After temi answers, keep listening for a few seconds so the user can just keep talking, no wake phrase needed. */
    fun listenFollowUp(ms: Long) {
        resetCmd = true; deadline = System.currentTimeMillis() + ms; mode = Mode.COMMAND; paused = false
        PetStatus.voice = "Listening..."
    }

    private fun loop(gen: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val dir = modelDir
        if (!File(dir, "am").exists()) {
            PetStatus.voice = "Voice model missing: ${dir.path} (use scripts/install-assets.sh)"; running = false; return
        }
        var model: Model? = null; var wake: Recognizer? = null; var cmd: Recognizer? = null; var rec: AudioRecord? = null
        try {
            PetStatus.voice = "Loading voice model..."
            model = Model(dir.path)
            wake = Recognizer(model, RATE, GRAMMAR)
            cmd = Recognizer(model, RATE)
            rec = openMic() ?: run { PetStatus.voice = "Microphone unavailable (another app may be using it)"; running = false; return }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                PetStatus.voice = "Microphone would not start"; running = false; return
            }
            PetStatus.voice = WAITING
            val chunk = ShortArray(CHUNK)
            val ring = ArrayDeque<ShortArray>()

            while (running && gen == generation) {
                val n = rec.read(chunk, 0, CHUNK)
                if (n < 0) { PetStatus.voice = "Microphone error ($n)"; break }
                if (n == 0) continue
                if (paused) { ring.clear(); continue }
                val now = System.currentTimeMillis()

                if (mode == Mode.WAKE) {
                    if (resetWake) { wake.reset(); ring.clear(); resetWake = false }
                    val c = chunk.copyOf(n)
                    ring.addLast(c); if (ring.size > RING) ring.removeFirst()
                    val end = wake.acceptWaveForm(c, n)
                    if (isWake(text(if (end) wake.result else wake.partialResult))) {
                        wake.reset()
                        cmd.reset()
                        deadline = now + WAKE_WINDOW_MS
                        mode = Mode.COMMAND
                        PetStatus.voice = "Listening..."
                        cb.onWake()
                        // replay: whatever came right before/with "hey mate" now flows into the command recognizer
                        for (r in ring) cmd.acceptWaveForm(r, r.size)
                        ring.clear()
                    }
                } else {
                    if (resetCmd) { cmd.reset(); resetCmd = false }
                    if (cmd.acceptWaveForm(chunk, n)) {
                        val said = stripWake(text(cmd.result))
                        if (accept(said)) {
                            mode = Mode.WAKE; paused = true; resetWake = true
                            PetStatus.voice = "Busy (thinking or talking)"
                            cb.onHeard(said)
                        } else if (now > deadline) endListen(wake, ring)
                    } else if (now > deadline) {
                        // window is over; only wait on if the user is in the middle of a sentence
                        val partial = text(cmd.partialResult)
                        if (partial.isBlank() || now > deadline + MAX_SENTENCE_MS) endListen(wake, ring)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("MatePet", "voice engine failed", e)
            PetStatus.voice = "Voice engine error: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { rec?.stop() }; runCatching { rec?.release() }
            runCatching { cmd?.close() }; runCatching { wake?.close() }; runCatching { model?.close() }
            if (gen == generation) running = false
        }
    }

    private fun endListen(wake: Recognizer, ring: ArrayDeque<ShortArray>) {
        mode = Mode.WAKE; wake.reset(); ring.clear()
        PetStatus.voice = WAITING
        cb.onListenEnd()
    }

    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = maxOf(min, CHUNK * 2 * 4)
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val r = runCatching {
                AudioRecord(src, RATE.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            r?.release()
        }
        return null
    }

    private fun text(json: String): String = runCatching {
        JSONObject(json).let { it.optString("text", it.optString("partial", "")) }
    }.getOrDefault("")

    private fun isWake(t: String) = WAKE_RE.containsMatchIn(t)

    /** Remove a leading "hey mate" (the recognizer sometimes hears "made" or "may"). */
    private fun stripWake(t: String) = t.replace(STRIP_RE, "").trim()

    /** Single stray words from room noise are ignored, except real one-word answers. */
    private fun accept(t: String): Boolean {
        if (t.isBlank()) return false
        return t.contains(' ') || t.lowercase() in ONE_WORD
    }

    companion object {
        private const val RATE = 16000f
        private const val CHUNK = 1600                      // 100 ms of audio
        private const val RING = 25                         // 2.5 s kept for the replay
        private const val WAKE_WINDOW_MS = 7000L            // time to start talking after "hey mate"
        private const val MAX_SENTENCE_MS = 15000L
        private const val WAITING = "Listening for \"hey mate\""
        // Only these phrases are recognized in wake mode; everything else collapses to [unk]. Add variants here if temi mishears you.
        private const val GRAMMAR = "[\"hey mate\", \"hay mate\", \"[unk]\"]"
        private val WAKE_RE = Regex("\\b(?:hey|hay)\\s+mate\\b", RegexOption.IGNORE_CASE)
        private val STRIP_RE = Regex("^(?:(?:hey|hay|hi|a)\\s+(?:mate|made|may|maid|mates)\\b[,.]?\\s*)+", RegexOption.IGNORE_CASE)
        private val ONE_WORD = setOf("yes", "no", "stop", "later", "hello", "hi", "thanks", "okay", "ok", "done", "yeah", "cancel")
    }
}
