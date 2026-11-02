package com.example.matepet

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Instant answers for the most common small talk. Skips the language model entirely, so these reply in milliseconds. */
object QuickReplies {
    private val I = RegexOption.IGNORE_CASE
    private val HELLO = Regex("^(?:hello|hi|hey|good (?:morning|afternoon|evening))(?: there)?$", I)
    private val THANKS = Regex("^(?:thanks|thank you|thanks a lot|thank you (?:so|very) much)$", I)
    private val NAME = Regex("^(?:what(?:'s| is) your name|who are you)$", I)
    private val HOW = Regex("^how are you(?: doing| today)?$", I)
    private val TIME = Regex("^(?:what(?:'s| is) the time|what time is it(?: now)?|tell me the time|current time)$", I)
    private val DATE = Regex("^(?:what(?:'s| is) (?:the date|today(?:'s date)?)|what day is it(?: today)?|what is today)$", I)

    fun handle(q: String): String? {
        val t = q.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        return when {
            HELLO.matches(t) -> "Hello! How can I help?"
            THANKS.matches(t) -> "You're welcome!"
            NAME.matches(t) -> "I'm Mate, your robot companion."
            HOW.matches(t) -> "I'm doing great, thanks for asking!"
            TIME.matches(t) -> "It's " + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date()) + "."
            DATE.matches(t) -> "Today is " + SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH).format(Date()) + "."
            else -> null
        }
    }
}
