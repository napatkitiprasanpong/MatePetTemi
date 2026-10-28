package com.example.matepet

import android.content.Context
import android.content.Intent

/** Voice command "open <app name>": fuzzy-matches installed launcher apps by label and starts the best one. */
object AppLauncher {
    private val CMD = Regex("^(?:please\\s+)?(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app(?:lication)?)?[.!?]?$", RegexOption.IGNORE_CASE)

    /** Returns what temi should say if [q] was an open-app command, or null if it was normal chat. */
    fun handle(ctx: Context, q: String): String? {
        val name = CMD.find(q.trim())?.groupValues?.get(1)?.lowercase()?.trim() ?: return null
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        val hit = apps.filter { (_, l) -> l.length >= 3 && l.lowercase().let { it == name || it.contains(name) || name.contains(it) } }
            .minByOrNull { (_, l) -> abs(l.length - name.length) }
            ?: return "Sorry, I can't find an app called $name."
        val intent = pm.getLaunchIntentForPackage(hit.first) ?: return "Sorry, I can't open ${hit.second}."
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opening ${hit.second}."
    }
    private fun abs(i: Int) = if (i < 0) -i else i
}
