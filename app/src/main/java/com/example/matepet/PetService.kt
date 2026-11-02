package com.example.matepet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.robotemi.sdk.Robot
import com.robotemi.sdk.SttLanguage
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnRobotReadyListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MatePet lives here, not in an Activity. The service keeps running behind every other app, listens for "hey mate",
 * and shows/hides the floating pet (PetOverlay). Everything is voice driven.
 */
class PetService : Service(), OnRobotReadyListener, Robot.AsrListener, Robot.TtsListener, VoiceEngine.Callback, PetPanel.Host {
    companion object {
        const val ACTION_SHOW = "com.example.matepet.SHOW"
        private const val CHANNEL = "matepet"
        private const val FOLLOW_UP_MS = 6000L      // after an answer, keep listening this long without needing "hey mate"
        private const val MAX_TOKENS = 48           // short answers = fast answers
        private const val MAX_SENTENCES = 2

        fun start(c: Context, greet: Boolean = false) {
            val i = Intent(c, PetService::class.java).setAction(ACTION_SHOW).putExtra("greet", greet)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val llmThread = Executors.newSingleThreadExecutor()    // one inference at a time
    private val ctl = Executors.newSingleThreadExecutor()          // commands (open app, IAQ...) never block the audio or UI thread
    private val busy = AtomicBoolean(false)
    private val robot by lazy { Robot.getInstance() }
    private lateinit var wm: android.view.WindowManager
    @Volatile private var pet: PetOverlay? = null
    private var panel: PetPanel? = null
    private var voice: VoiceEngine? = null
    private var tone: ToneGenerator? = null
    @Volatile private var llmReady = false
    @Volatile private var ttsActive = false
    private val persona = "You are Mate, a friendly robot assistant. Answer in English in one short sentence."

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        startInForeground()
        runCatching { robot.addOnRobotReadyListener(this); robot.addAsrListener(this); robot.addTtsListener(this) }
        runCatching { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60) }
        loadModel()
        startVoice()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        // i == null means Android restarted us after killing the process: stay hidden, keep listening
        if (i?.action == ACTION_SHOW) { val greet = i.getBooleanExtra("greet", false); main.post { show(greet) } }
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { robot.removeOnRobotReadyListener(this); robot.removeAsrListener(this); robot.removeTtsListener(this) }
        main.removeCallbacksAndMessages(null)
        stopVoice(); panel?.dismiss(); pet?.remove(); pet = null
        tone?.release()
        llmThread.shutdown(); ctl.shutdown()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "MatePet", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, CHANNEL)
        } else Notification.Builder(this)
        val n = b.setContentTitle("MatePet").setContentText("Say \"hey mate\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
    }

    // ---------------------------------------------------------------- overlay
    private fun show(greet: Boolean = false) {
        if (pet != null) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("MatePet needs \"Display over other apps\" (see README)"); return
        }
        val p = PetOverlay(this, wm) { main.post { openMenu() } }
        if (!p.add()) return
        pet = p
        if (greet) speak("Hi, I'm Mate. Just say hey mate when you need me.")
    }

    private fun hide() {
        panel?.dismiss(); panel = null
        pet?.remove(); pet = null
        PetStatus.avatar = "Hidden"
    }

    private fun openMenu() {
        panel?.dismiss()
        panel = PetPanel(this, wm, this).also { it.show() }
    }

    // PetPanel.Host
    override fun quit() {
        main.post {
            hide()
            if (voice != null) toast("MatePet hidden. Say \"hey mate\" to bring it back.") else stopSelf()
        }
    }

    override fun exitAll() { main.post { stopSelf() } }

    override fun settingsChanged() {
        main.post {
            if (pet != null) { pet?.remove(); pet = null; show() }    // size / graphics / transparency apply right away
            if (Prefs.wake(this)) startVoice() else { stopVoice(); PetStatus.voice = "Wake word off" }
        }
    }

    override fun statusText(): String = buildString {
        append("Voice    ").append(PetStatus.voice).append('\n')
        append("AI       ").append(PetStatus.llm).append('\n')
        append("Avatar   ").append(PetStatus.avatar).append('\n')
        append("Render   ").append(if (PetStatus.fps > 0) "${PetStatus.fps} fps" else "idle").append('\n')
        append("Last     ").append(PetStatus.speed).append('\n')
        append("Heard    ").append(PetStatus.heard).append('\n')
        append("IAQ app  ").append(PetStatus.iaq)
    }

    override fun refreshExternalStatus() {
        ctl.execute {
            val s = IaqClient.status(this)
            PetStatus.iaq = when {
                s != null -> "connected (IAQ ${s.optInt("iaq")}, ${s.optString("level")})"
                IaqClient.denied -> "blocked: apps are signed with different keys"
                packageManager.getLaunchIntentForPackage(IaqClient.PKG) == null -> "not installed"
                else -> "installed, not answering"
            }
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- voice
    private fun startVoice() {
        if (!Prefs.wake(this)) { PetStatus.voice = "Wake word off"; return }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            PetStatus.voice = "Microphone permission missing (see README)"; return
        }
        if (voice == null) voice = VoiceEngine(this, this)
        voice?.start()
    }

    private fun stopVoice() { voice?.stop(); voice = null }

    // VoiceEngine.Callback (audio thread)
    override fun onWake() {
        main.post {
            show()
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150) }
            pet?.showBubble("I'm listening...")
        }
    }

    override fun onHeard(text: String) {
        PetStatus.heard = text
        pet?.showBubble("\"$text\"")
        scheduleResume(15_000)      // safety net in case the answer never speaks
        ctl.execute { ask(text) }
    }

    override fun onListenEnd() { pet?.hideBubbleSoon() }

    // temi's own "Hey temi" speech-to-text still works as a second way in
    override fun onAsrResult(asrResult: String, sttLanguage: SttLanguage) {
        if (ttsActive || busy.get()) return
        runCatching { robot.finishConversation() }
        voice?.pause()
        main.post { show() }
        scheduleResume(15_000)
        ctl.execute { ask(asrResult) }
    }

    override fun onRobotReady(isReady: Boolean) {}

    // Listening resumes only once temi has stopped talking and the model has stopped thinking.
    private val resumeRun = Runnable {
        if (ttsActive || busy.get()) { scheduleResume(800); return@Runnable }
        voice?.listenFollowUp(FOLLOW_UP_MS)
    }

    private fun scheduleResume(ms: Long) { main.removeCallbacks(resumeRun); main.postDelayed(resumeRun, ms) }

    override fun onTtsStatusChanged(ttsRequest: TtsRequest) {
        // compared by name so this compiles against any temi SDK version
        when (ttsRequest.status.name) {
            "STARTED" -> { ttsActive = true; pet?.avatar?.talking = true; main.removeCallbacks(resumeRun) }
            "COMPLETED", "ERROR", "CANCELED", "NOT_ALLOWED" -> { ttsActive = false; pet?.avatar?.talking = false; scheduleResume(500) }
        }
    }

    // ---------------------------------------------------------------- brain
    private fun loadModel() = llmThread.execute {   // model is pushed with scripts/install-assets.sh
        val dir = getExternalFilesDir(null)
        val f = File(dir, "model.gguf").takeIf { it.exists() } ?: File(dir, "SmolLM2-360M-Instruct-Q4_K_M.gguf")
        if (!f.exists()) { PetStatus.llm = "Model missing: ${f.path} (opening apps still works)"; return@execute }
        PetStatus.llm = "Loading..."
        val t0 = SystemClock.elapsedRealtime()
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        val ok = runCatching { LlamaBridge.nativeLoad(f.path, threads) }.getOrDefault(false)
        if (!ok) { PetStatus.llm = "Could not load ${f.name}"; return@execute }
        // Warm-up: the weights are memory-mapped, so the first real question would otherwise wait for them to be read from flash.
        runCatching { LlamaBridge.nativeGenerate(persona, "Hi", 1) { false } }
        llmReady = true
        PetStatus.llm = "Ready (${f.name}, ${(SystemClock.elapsedRealtime() - t0) / 100 / 10.0} s to load, $threads threads)"
    }

    /** Shows the text and has temi say it aloud (markdown symbols stripped so TTS doesn't read them). */
    private fun speak(t: String, show: Boolean = true) {
        val s = t.replace(Regex("[*_#`~]"), "").replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return
        if (show) pet?.showBubble(s)
        runCatching { robot.speak(TtsRequest.create(s, false)) }
    }

    private fun ask(q: String) {
        if (q.isBlank()) return
        PetStatus.heard = q
        QuickReplies.handle(q)?.let { speak(it); return }              // small talk: instant, no model
        if (IaqCommands.handle(this, q, ::speak, ::chat)) return       // IAQ app: open / control / read / answer popup
        AppLauncher.handle(this, q)?.let { speak(it); return }         // any other app
        chat(q)
    }

    /** Local AI chat. Each finished sentence is spoken immediately so temi does not stay silent while the model works. */
    private fun chat(q: String) {
        if (!llmReady) { speak("My brain is still waking up. Please ask again in a moment."); return }
        if (!busy.compareAndSet(false, true)) return
        llmThread.execute {
            val t0 = SystemClock.elapsedRealtime()
            var first = 0L; var tokens = 0; var lastUi = 0L
            try {
                val out = ByteArrayOutputStream()
                val sys = persona + (if (IaqCommands.relevant(q)) IaqClient.status(this)?.let { " Live room data: " + IaqClient.compact(it) + "." } ?: "" else "")
                var spoken = 0; var sentences = 0
                val sentence = Regex("^(.+?[.!?])(\\s|$)", RegexOption.DOT_MATCHES_ALL)
                LlamaBridge.nativeGenerate(sys, q.trim(), MAX_TOKENS) { piece ->
                    val now = SystemClock.elapsedRealtime()
                    if (first == 0L) first = now
                    tokens++
                    out.write(piece); val s = String(out.toByteArray(), Charsets.UTF_8)
                    if (now - lastUi > 150) { lastUi = now; pet?.showBubble(s) }
                    sentence.find(s.substring(spoken.coerceAtMost(s.length)))?.let { speak(it.groupValues[1], show = false); spoken += it.range.last + 1; sentences++ }
                    sentences < MAX_SENTENCES        // two sentences are plenty; stopping early frees the CPU sooner
                }
                val all = String(out.toByteArray(), Charsets.UTF_8)
                val rest = all.substring(spoken.coerceAtMost(all.length)).trim()
                if (rest.isNotEmpty()) speak(rest, show = false) else if (spoken == 0) speak("Sorry, I did not catch that.")
                pet?.showBubble(all)
                val end = SystemClock.elapsedRealtime()
                PetStatus.speed = if (first > 0) "first words after ${first - t0} ms, ${tokens * 1000L / (end - first).coerceAtLeast(1)} tokens/s" else "-"
            } catch (e: Throwable) {
                Log.e("MatePet", "chat failed", e)
            } finally { busy.set(false); scheduleResume(600) }
        }
    }
}
