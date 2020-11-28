/*
 * Copyright (c) 1995, 2013, Oracle and/or its affiliates. All rights reserved.
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

internal typealias UnsupportedEncodingException = Exception
private typealias BitSet = BooleanArray
private fun BitSet.set(i: Int) {
    this[i] = true
}
private fun BitSet.set(char: Char) {
    this[char.toInt()] = true
}
internal typealias StringBuffer = StringBuilder
private typealias CharArrayWriter = StringBuilder
private fun CharArrayWriter.write(c: Int) {
    this.append(c.toChar())
}
private fun CharArrayWriter.flush() {
}
private fun CharArrayWriter.reset() {
    clear()
}
private fun CharArrayWriter.toCharArray(): CharArray {
    return toString().toCharArray()
}
private fun String.toByteArray(): ByteArray {
    return encodeToByteArray()
}
private class Character {
    companion object {
        fun forDigit(i: Int, radix: Int): Int {
            return i.toString(radix)[0].toInt()
        }
    }
}

/**
 * Utility class for HTML form encoding. This class contains static methods
 * for converting a String to the <CODE>application/x-www-form-urlencoded</CODE> MIME
 * format. For more information about HTML form encoding, consult the HTML
 * <A HREF="http://www.w3.org/TR/html4/">specification</A>.
 *
 *
 *
 * When encoding a String, the following rules apply:
 *
 *
 *  * The alphanumeric characters &quot;`a`&quot; through
 * &quot;`z`&quot;, &quot;`A`&quot; through
 * &quot;`Z`&quot; and &quot;`0`&quot;
 * through &quot;`9`&quot; remain the same.
 *  * The special characters &quot;`.`&quot;,
 * &quot;`-`&quot;, &quot;`*`&quot;, and
 * &quot;`_`&quot; remain the same.
 *  * The space character &quot; &nbsp; &quot; is
 * converted into a plus sign &quot;`+`&quot;.
 *  * All other characters are unsafe and are first converted into
 * one or more bytes using some encoding scheme. Then each byte is
 * represented by the 3-character string
 * &quot;*`%xy`*&quot;, where *xy* is the
 * two-digit hexadecimal representation of the byte.
 * The recommended encoding scheme to use is UTF-8. However,
 * for compatibility reasons, if an encoding is not specified,
 * then the default encoding of the platform is used.
 *
 *
 *
 *
 * For example using UTF-8 as the encoding scheme the string &quot;The
 * string &#252;@foo-bar&quot; would get converted to
 * &quot;The+string+%C3%BC%40foo-bar&quot; because in UTF-8 the character
 * &#252; is encoded as two bytes C3 (hex) and BC (hex), and the
 * character @ is encoded as one byte 40 (hex).
 *
 * @author  Herb Jellinek
 * @since   JDK1.0
 */
actual object URLEncoder {
    internal var dontNeedEncoding: BooleanArray
    internal val caseDiff = 'a' - 'A'
    internal var dfltEncName: String? = null

    init {

        /* The list of characters that are not encoded has been
         * determined as follows:
         *
         * RFC 2396 states:
         * -----
         * Data characters that are allowed in a URI but do not have a
         * reserved purpose are called unreserved.  These include upper
         * and lower case letters, decimal digits, and a limited set of
         * punctuation marks and symbols.
         *
         * unreserved  = alphanum | mark
         *
         * mark        = "-" | "_" | "." | "!" | "~" | "*" | "'" | "(" | ")"
         *
         * Unreserved characters can be escaped without changing the
         * semantics of the URI, but this should not be done unless the
         * URI is being used in a context that does not allow the
         * unescaped character to appear.
         * -----
         *
         * It appears that both Netscape and Internet Explorer escape
         * all special characters from this list with the exception
         * of "-", "_", ".", "*". While it is not clear why they are
         * escaping the other characters, perhaps it is safest to
         * assume that there might be contexts in which the others
         * are unsafe if not escaped. Therefore, we will use the same
         * list. It is also noteworthy that this is consistent with
         * O'Reilly's "HTML: The Definitive Guide" (page 164).
         *
         * As a last note, Intenet Explorer does not encode the "@"
         * character which is clearly not unreserved according to the
         * RFC. We are being consistent with the RFC in this matter,
         * as is Netscape.
         *
         */

        dontNeedEncoding = BitSet(256)
        var i: Int
        i = 'a'.toInt()
        while (i <= 'z'.toInt()) {
            dontNeedEncoding.set(i)
            i++
        }
        i = 'A'.toInt()
        while (i <= 'Z'.toInt()) {
            dontNeedEncoding.set(i)
            i++
        }
        i = '0'.toInt()
        while (i <= '9'.toInt()) {
            dontNeedEncoding.set(i)
            i++
        }
        dontNeedEncoding.set(' ') /* encoding a space to a + is done
                                    * in the encode() method */
        dontNeedEncoding.set('-')
        dontNeedEncoding.set('_')
        dontNeedEncoding.set('.')
        dontNeedEncoding.set('~')
    }

    /**
     * Translates a string into `application/x-www-form-urlencoded`
     * format using a specific encoding scheme. This method uses the
     * supplied encoding scheme to obtain the bytes for unsafe
     * characters.
     *
     *
     * ***Note:** The [
 * World Wide Web Consortium Recommendation](http://www.w3.org/TR/html40/appendix/notes.html#non-ascii-chars) states that
     * UTF-8 should be used. Not doing so may introduce
     * incompatibilities.*
     *
     * @param   s   `String` to be translated.
     * @param   enc   The name of a supported
     * [character
 * encoding](../lang/package-summary.html#charenc).
     * @return  the translated `String`.
     * @exception  UnsupportedEncodingException
     * If the named encoding is not supported
     * @see URLDecoder.decode
     * @since 1.4
     */
    actual fun encode(s: String): String {

        var needToChange = false
        val out = StringBuffer(s.length)
        val charArrayWriter = CharArrayWriter()

        var i = 0
        while (i < s.length) {
            var c = s[i].toInt()
            //System.out.println("Examining character: " + c);
            if (dontNeedEncoding.get(c)) {
                if (c == ' '.toInt()) {
                    c = '+'.toInt()
                    needToChange = true
                }
                //System.out.println("Storing: " + c);
                out.append(c.toChar())
                i++
            } else {
                // convert to external encoding before hex conversion
                do {
                    charArrayWriter.write(c)
                    /*
                     * If this character represents the start of a Unicode
                     * surrogate pair, then pass in two characters. It's not
                     * clear what should be done if a bytes reserved in the
                     * surrogate pairs range occurs outside of a legal
                     * surrogate pair. For now, just treat it as if it were
                     * any other character.
                     */
                    if (c >= 0xD800 && c <= 0xDBFF) {
                        /*
                          System.out.println(Integer.toHexString(c)
                          + " is high surrogate");
                        */
                        if (i + 1 < s.length) {
                            val d = s[i + 1].toInt()
                            /*
                              System.out.println("\tExamining "
                              + Integer.toHexString(d));
                            */
                            if (d >= 0xDC00 && d <= 0xDFFF) {
                                /*
                                  System.out.println("\t"
                                  + Integer.toHexString(d)
                                  + " is low surrogate");
                                */
                                charArrayWriter.write(d)
                                i++
                            }
                        }
                    }
                    i++
                    if (i >= s.length)
                        break
                    c = s[i].toInt()
                } while (!dontNeedEncoding.get(c))

                charArrayWriter.flush()
                val str = String(charArrayWriter.toCharArray())
                val ba = str.toByteArray()
                for (j in ba.indices) {
                    out.append('%')
                    var ch = Character.forDigit(ba[j].toInt() shr 4 and 0xF, 16)
                    // converting to use uppercase letter as part of
                    // the hex value if ch is a letter.
                    out.append(ch.toChar())
                    ch = Character.forDigit(ba[j].toInt() and 0xF, 16)
                    out.append(ch.toChar().toUpperCase())
                }
                charArrayWriter.reset()
                needToChange = true
            }
        }

        return if (needToChange) out.toString() else s
    }
}
/**
 * You can't call the constructor.
 */
