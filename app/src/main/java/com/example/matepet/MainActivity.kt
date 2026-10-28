package com.example.matepet

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.filament.utils.Utils
import com.robotemi.sdk.Robot
import com.robotemi.sdk.SttLanguage
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnRobotReadyListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), OnRobotReadyListener, Robot.AsrListener, Robot.TtsListener {
    private val robot by lazy { Robot.getInstance() }
    private val io = Executors.newSingleThreadExecutor()   // one inference at a time
    private val busy = AtomicBoolean(false)
    private lateinit var avatar: Talker
    private lateinit var reply: TextView
    @Volatile private var ready = false
    private val persona = "You are Mate, a friendly robot assistant. Answer in English in one or two short sentences."

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dir = getExternalFilesDir(null)
        val vrm = File(dir, "avatar.vrm")
        val view: View = runCatching { if (vrm.exists()) { Utils.init(); VrmAvatar(this, vrm) } else null }.getOrNull() ?: FaceView(this)
        avatar = view as Talker
        reply = TextView(this).apply { textSize = 22f; setTextColor(-1); gravity = Gravity.CENTER; text = "Loading model..." }
        val input = EditText(this).apply { hint = "Type to chat (test)"; setTextColor(-1); setHintTextColor(-0x777778) }
        val send = Button(this).apply { text = "Send"; setOnClickListener { ask(input.text.toString()); input.setText("") } }
        val row = LinearLayout(this).apply { addView(input, LinearLayout.LayoutParams(0, -2, 1f)); addView(send) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt())
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f)); addView(reply); addView(row)
        })
        io.execute {   // model is pushed with scripts/install-assets.sh
            val f = File(dir, "model.gguf")
            ready = f.exists() && LlamaBridge.nativeLoad(f.path, maxOf(2, Runtime.getRuntime().availableProcessors() - 1))
            if (ready) speak("Hello, I'm Mate. Ask me anything, or say open and an app name.")
            else runOnUiThread { reply.text = "Model missing: ${f.path} (app launching still works)" }
        }
    }

    override fun onStart() { super.onStart(); robot.addOnRobotReadyListener(this); robot.addAsrListener(this); robot.addTtsListener(this) }
    override fun onStop() { super.onStop(); robot.removeOnRobotReadyListener(this); robot.removeAsrListener(this); robot.removeTtsListener(this) }

    override fun onRobotReady(isReady: Boolean) { if (isReady) robot.hideTopBar() }

    // temi speech-to-text -> cancel temi's default reply -> app command or local LLM. Ignore temi's own voice and overlapping requests.
    override fun onAsrResult(asrResult: String, sttLanguage: SttLanguage) {
        if (avatar.talking || busy.get()) return
        robot.finishConversation(); ask(asrResult)
    }

    override fun onTtsStatusChanged(ttsRequest: TtsRequest) { avatar.talking = ttsRequest.status == TtsRequest.Status.STARTED }

    /** Shows the text and has temi say it aloud (markdown symbols stripped so TTS doesn't read them). */
    private fun speak(t: String) {
        val s = t.replace(Regex("[*_#`~]"), "").replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return
        runOnUiThread { reply.text = s }
        runCatching { robot.speak(TtsRequest.create(s, false)) }
    }

    private fun ask(q: String) {
        if (q.isBlank()) return
        AppLauncher.handle(this, q)?.let { speak(it); return }
        if (!ready || !busy.compareAndSet(false, true)) return
        io.execute {
            try {
                val out = ByteArrayOutputStream()
                LlamaBridge.nativeGenerate(persona, q.trim(), 96) { piece ->
                    out.write(piece); val s = String(out.toByteArray(), Charsets.UTF_8)
                    runOnUiThread { reply.text = s }; true
                }
                speak(String(out.toByteArray(), Charsets.UTF_8))
            } finally { busy.set(false) }
        }
    }
}
