package com.koushikdutta.scratch.http.client.middleware

import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Best-effort parser for HTTP dates.
 */
object HttpDate {
    /**
     * Most websites serve cookies in the blessed format. Eagerly create the parser to ensure such
     * cookies are on the fast path.
     */
    private val STANDARD_DATE_FORMAT: ThreadLocal<DateFormat> = object : ThreadLocal<DateFormat>() {
        override fun initialValue(): DateFormat {
            val rfc1123: DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            rfc1123.setTimeZone(TimeZone.getTimeZone("UTC"))
            return rfc1123
        }
    }

    /**
     * If we fail to parse a date in a non-standard format, try each of these formats in sequence.
     */
    private val BROWSER_COMPATIBLE_DATE_FORMATS = arrayOf( /* This list comes from  {@code org.apache.http.impl.cookie.BrowserCompatSpec}. */
            "EEEE, dd-MMM-yy HH:mm:ss zzz",  // RFC 1036
            "EEE MMM d HH:mm:ss yyyy",  // ANSI C asctime()
            "EEE, dd-MMM-yyyy HH:mm:ss z",
            "EEE, dd-MMM-yyyy HH-mm-ss z",
            "EEE, dd MMM yy HH:mm:ss z",
            "EEE dd-MMM-yyyy HH:mm:ss z",
            "EEE dd MMM yyyy HH:mm:ss z",
            "EEE dd-MMM-yyyy HH-mm-ss z",
            "EEE dd-MMM-yy HH:mm:ss z",
            "EEE dd MMM yy HH:mm:ss z",
            "EEE,dd-MMM-yy HH:mm:ss z",
            "EEE,dd-MMM-yyyy HH:mm:ss z",
            "EEE, dd-MM-yyyy HH:mm:ss z",  /* RI bug 6641315 claims a cookie of this format was once served by www.yahoo.com */
            "EEE MMM d yyyy HH:mm:ss z")

    /**
     * Returns the date for `value`. Returns null if the value couldn't be
     * parsed.
     */
    fun parse(value: String?): Date? {
        if (value == null) return null
        try {
            return STANDARD_DATE_FORMAT.get().parse(value)
        } catch (ignore: ParseException) {
        }
        for (formatString in BROWSER_COMPATIBLE_DATE_FORMATS) {
            try {
                return SimpleDateFormat(formatString, Locale.US).parse(value)
            } catch (ignore: ParseException) {
            }
        }
        return null
    }

    /**
     * Returns the string for `value`.
     */
    fun format(value: Date?): String {
        return STANDARD_DATE_FORMAT.get().format(value)
    }
}