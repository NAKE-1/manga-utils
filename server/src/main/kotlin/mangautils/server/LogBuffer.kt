package mangautils.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory ring buffer of recent WARN/ERROR log events, for the in-app log viewer (Settings →
 * Diagnostics). Captured by attaching a logback appender to the ROOT logger, so every warning/error
 * across the app is recorded with zero call-site changes. Bounded, so it can't grow unbounded.
 */
object LogBuffer {
    data class Entry(val ts: Long, val level: String, val logger: String, val msg: String)

    private const val MAX = 400
    private val buf = ConcurrentLinkedDeque<Entry>()

    private fun add(e: Entry) {
        buf.addLast(e)
        while (buf.size > MAX) buf.pollFirst()
    }

    /** Recent entries, newest first. minLevel "error" keeps only ERROR; anything else keeps WARN+. */
    fun recent(minLevel: String, limit: Int): List<Entry> {
        val errorOnly = minLevel.equals("error", ignoreCase = true)
        val out = ArrayList<Entry>(limit)
        val it = buf.descendingIterator() // newest first
        while (it.hasNext() && out.size < limit.coerceIn(1, MAX)) {
            val e = it.next()
            if (!errorOnly || e.level == "ERROR") out.add(e)
        }
        return out
    }

    /** Attach the capture appender to the root logger. No-op if the backend isn't logback. */
    fun install() {
        val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        if (root !is ch.qos.logback.classic.Logger) return
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(e: ILoggingEvent) {
                if (e.level.toInt() < Level.WARN.toInt()) return // only WARN + ERROR
                add(Entry(e.timeStamp, e.level.levelStr, e.loggerName.substringAfterLast('.'), e.formattedMessage ?: ""))
            }
        }
        appender.context = root.loggerContext
        appender.name = "mu-logbuffer"
        appender.start()
        root.addAppender(appender)
    }
}
