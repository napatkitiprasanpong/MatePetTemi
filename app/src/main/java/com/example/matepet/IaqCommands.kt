package com.example.matepet

import android.content.Context

/** Voice commands for the IAQ app. Rule-based on purpose: a 360M model is too unreliable to pick tools. */
object IaqCommands {
    private val I = RegexOption.IGNORE_CASE
    private val OPEN = Regex("^(?:please )?(?:open|launch|start|show)\\s+(?:the )?(?:iaq|ieq|air quality|monitoring)(?: monitoring)?(?: app(?:lication)?)?$", I)
    private val MISSION = Regex("^(?:please )?(?:start|begin|run)\\s+(?:the |a |an )?(?:mission|inspection|patrol)(?:\\s+(?:on|for|with|using|route)\\s+(.+))?$", I)
    private val STOP_M = Regex("^(?:please )?(?:stop|cancel|abort|end)\\s+(?:the )?(?:mission|inspection|patrol)$", I)
    private val ROUTINE = Regex("^(?:please )?(?:start|run)\\s+(?:the )?routine(?:\\s+(.+))?$", I)
    private val STOP_R = Regex("^(?:please )?(?:stop|cancel)\\s+(?:the )?routine$", I)
    private val TAB = Regex("^(?:please )?(?:open|show|go to)\\s+(?:me )?(?:the )?(dashboard|analytics|mission|routine|settings|startup|sign language|camera and depth|depth)(?:\\s+(tab|screen|page))?$", I)
    private val AIR = Regex("air quality|how(?:'s| is) the air|air condition|room (?:condition|data)|\\biaq\\b", I)
    private val Q = Regex("^(?:what|how|tell|read|check|is|are)\\b", I)
    private val SENSOR = mapOf(
        "co2" to "co2|co 2|c o 2|carbon dioxide", "pm25" to "pm ?2\\.?5|pm two point five|fine particles", "pm10" to "pm ?10|pm ten",
        "temp" to "temperature|how (?:hot|warm|cold)", "humidity" to "humidity|humid", "tvoc" to "tvoc|voc",
        "hcho" to "hcho|formaldehyde", "light" to "\\blight\\b|brightness|\\blux\\b", "sound" to "sound level|noise|decibel|how loud")
        .mapValues { Regex(it.value, I) }
    private val CONTEXT = Regex("\\bair\\b|room|temperature|humid|co2|dust|pm ?2|smell|stuffy|ventilat|window|sensor", I)

    fun relevant(q: String) = CONTEXT.containsMatchIn(q)

    /** True if [q] was an IAQ command (temi answers through [say], on a background thread). */
    fun handle(ctx: Context, q: String, say: (String) -> Unit): Boolean {
        val t = q.trim().trimEnd('.', '!', '?')
        val job: (() -> Unit) = when {
            OPEN.matches(t) -> { { say(if (IaqClient.open(ctx)) "Opening the monitoring app." else "The monitoring app isn't installed.") } }
            MISSION.matches(t) -> { val r = MISSION.find(t)!!.groupValues[1]; { control(ctx, "START_MISSION", r, say) } }
            STOP_M.matches(t) -> { { control(ctx, "STOP_MISSION", "", say) } }
            ROUTINE.matches(t) -> { val r = ROUTINE.find(t)!!.groupValues[1]; { control(ctx, "START_ROUTINE", r, say) } }
            STOP_R.matches(t) -> { { control(ctx, "STOP_ROUTINE", "", say) } }
            TAB.matches(t) -> {
                val (name, suffix) = TAB.find(t)!!.destructured.let { it.component1().lowercase() to it.component2() }
                if (suffix.isEmpty() && name !in listOf("dashboard", "analytics")) return false
                { control(ctx, "OPEN_TAB", name, say) }
            }
            AIR.containsMatchIn(t) -> { { withStatus(ctx, say) { say(IaqClient.summary(it)) } } }
            Q.containsMatchIn(t) && SENSOR.any { it.value.containsMatchIn(t) } -> {
                val k = SENSOR.entries.first { it.value.containsMatchIn(t) }.key
                { withStatus(ctx, say) { say(IaqClient.sensorLine(it, k) ?: "I don't have a ${IaqClient.SENSORS[k]?.first} reading yet.") } }
            }
            else -> return false
        }
        Thread(job).start(); return true
    }

    private fun withStatus(ctx: Context, say: (String) -> Unit, f: (org.json.JSONObject) -> Unit) {
        val s = IaqClient.status(ctx)
        if (s == null) { IaqClient.open(ctx); say("The monitoring app wasn't ready, so I'm opening it. Please ask again in a moment.") } else f(s)
    }

    /** Send a command, then speak the monitoring app's own reply (e.g. "Not saved on temi: Lab A"). */
    private fun control(ctx: Context, cmd: String, arg: String, say: (String) -> Unit) = withStatus(ctx, say) { before ->
        val id = before.optLong("msgId")
        if (cmd == "OPEN_TAB") IaqClient.open(ctx)   // bring the monitoring app to the front (we are the foreground app, so this is allowed)
        IaqClient.send(ctx, cmd, arg)
        repeat(6) {
            Thread.sleep(400)
            val s = IaqClient.status(ctx)
            if (s != null && s.optLong("msgId") > id) return@withStatus say(s.optString("message", "Okay."))
        }
        say("I sent the command, but the monitoring app didn't answer.")
    }
}
