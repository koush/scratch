/*
 * Copyright (c) 1998, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.koushikdutta.scratch.uri

/**
 * Utility class for HTML form decoding. This class contains static methods
 * for decoding a String from the <CODE>application/x-www-form-urlencoded</CODE>
 * MIME format.
 *
 *
 * The conversion process is the reverse of that used by the URLEncoder class. It is assumed
 * that all characters in the encoded string are one of the following:
 * &quot;`a`&quot; through &quot;`z`&quot;,
 * &quot;`A`&quot; through &quot;`Z`&quot;,
 * &quot;`0`&quot; through &quot;`9`&quot;, and
 * &quot;`-`&quot;, &quot;`_`&quot;,
 * &quot;`.`&quot;, and &quot;`*`&quot;. The
 * character &quot;`%`&quot; is allowed but is interpreted
 * as the start of a special escaped sequence.
 *
 *
 * The following rules are applied in the conversion:
 *
 *
 *  * The alphanumeric characters &quot;`a`&quot; through
 * &quot;`z`&quot;, &quot;`A`&quot; through
 * &quot;`Z`&quot; and &quot;`0`&quot;
 * through &quot;`9`&quot; remain the same.
 *  * The special characters &quot;`.`&quot;,
 * &quot;`-`&quot;, &quot;`*`&quot;, and
 * &quot;`_`&quot; remain the same.
 *  * The plus sign &quot;`+`&quot; is converted into a
 * space character &quot; &nbsp; &quot; .
 *  * A sequence of the form "*`%xy`*" will be
 * treated as representing a byte where *xy* is the two-digit
 * hexadecimal representation of the 8 bits. Then, all substrings
 * that contain one or more of these byte sequences consecutively
 * will be replaced by the character(s) whose encoding would result
 * in those consecutive bytes.
 * The encoding scheme used to decode these characters may be specified,
 * or if unspecified, the default encoding of the platform will be used.
 *
 *
 *
 * There are two possible ways in which this decoder could deal with
 * illegal strings.  It could either leave illegal characters alone or
 * it could throw an [java.lang.IllegalArgumentException].
 * Which approach the decoder takes is left to the
 * implementation.
 *
 * @author  Mark Chamness
 * @author  Michael McCloskey
 * @since   1.2
 */

object URLDecoder {
    /**
     * Decodes a `application/x-www-form-urlencoded` string using a specific
     * encoding scheme.
     * The supplied encoding is used to determine
     * what characters are represented by any consecutive sequences of the
     * form "*`%xy`*".
     *
     *
     * ***Note:** The [
 * World Wide Web Consortium Recommendation](http://www.w3.org/TR/html40/appendix/notes.html#non-ascii-chars) states that
     * UTF-8 should be used. Not doing so may introduce
     * incompatibilities.*
     *
     * @param s the `String` to decode
     * @param enc   The name of a supported
     * [character
 * encoding](../lang/package-summary.html#charenc).
     * @return the newly decoded `String`
     * @exception  UnsupportedEncodingException
     * If character encoding needs to be consulted, but
     * named character encoding is not supported
     * @see URLEncoder.encode
     * @since 1.4
     */
    fun decode(s: String): String {

        var needToChange = false
        val numChars = s.length
        val sb = StringBuffer(if (numChars > 500) numChars / 2 else numChars)
        var i = 0

        var c: Char
        var bytes: ByteArray? = null
        while (i < numChars) {
            c = s[i]
            when (c) {
                '+' -> {
                    sb.append(' ')
                    i++
                    needToChange = true
                }
                '%' -> {
                    /*
                 * Starting with this instance of %, process all
                 * consecutive substrings of the form %xy. Each
                 * substring %xy will yield a byte. Convert all
                 * consecutive  bytes obtained this way to whatever
                 * character(s) they represent in the provided
                 * encoding.
                 */

                    try {

                        // (numChars-i)/3 is an upper bound for the number
                        // of remaining bytes
                        if (bytes == null)
                            bytes = ByteArray((numChars - i) / 3)
                        var pos = 0

                        while (i + 2 < numChars && c == '%') {
                            val v = s.substring(i + 1, i + 3).toInt(16)
                            require(v >= 0) { "URLDecoder: Illegal hex characters in escape (%) pattern - negative value" }
                            bytes[pos++] = v.toByte()
                            i += 3
                            if (i < numChars)
                                c = s[i]
                        }

                        // A trailing, incomplete byte encoding such as
                        // "%x" will cause an exception to be thrown

                        require(!(i < numChars && c == '%')) { "URLDecoder: Incomplete trailing escape (%) pattern" }

                        sb.append(bytes.decodeToString( 0, pos))
                    } catch (e: NumberFormatException) {
                        throw IllegalArgumentException(
                            "URLDecoder: Illegal hex characters in escape (%) pattern - " + e.message
                        )
                    }

                    needToChange = true
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }

        return if (needToChange) sb.toString() else s
    }
}
