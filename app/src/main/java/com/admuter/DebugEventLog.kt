package com.admuter

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory circular buffer that records diagnostic events so they can be
 * viewed on the phone screen without ADB/logcat access.
 *
 * Every receiver/service writes to this log; [MainActivity] displays the
 * contents in the debug-info section.
 */
object DebugEventLog {

    private val events = mutableListOf<String>()
    private const val MAX_EVENTS = 100
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Append a timestamped event line.
     */
    fun add(event: String) {
        val timestamp = dateFormat.format(Date())
        synchronized(events) {
            events.add("[$timestamp] $event")
            if (events.size > MAX_EVENTS) {
                events.removeAt(0)
            }
        }
    }

    /**
     * Return a copy of all stored events (newest last).
     */
    fun getEvents(): List<String> = synchronized(events) { events.toList() }

    /**
     * Return the full log as a single newline-separated string.
     */
    fun getText(): String = synchronized(events) { events.joinToString("\n") }

    /** Clear all events. */
    fun clear() { synchronized(events) { events.clear() } }
}
