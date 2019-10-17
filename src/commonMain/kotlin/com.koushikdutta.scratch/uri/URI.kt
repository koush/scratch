/*
 * Copyright (c) 2000, 2013, Oracle and/or its affiliates. All rights reserved.
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

import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.createByteBuffer
import kotlin.math.max
import kotlin.math.min

// for javadoc

class URISyntaxException(val input: String, val reason: String, val p: Int? = null) : Exception()

/**
 * Represents a Uniform Resource Identifier (URI) reference.
 *
 *
 *  Aside from some minor deviations noted below, an instance of this
 * class represents a URI reference as defined by
 * [*RFC&nbsp;2396: Uniform
 * Resource Identifiers (URI): Generic Syntax*](http://www.ietf.org/rfc/rfc2396.txt), amended by [*RFC&nbsp;2732: Format for
 * Literal IPv6 Addresses in URLs*](http://www.ietf.org/rfc/rfc2732.txt). The Literal IPv6 address format
 * also supports scope_ids. The syntax and usage of scope_ids is described
 * [here](Inet6Address.html#scoped).
 * This class provides constructors for creating URI instances from
 * their components or by parsing their string forms, methods for accessing the
 * various components of an instance, and methods for normalizing, resolving,
 * and relativizing URI instances.  Instances of this class are immutable.
 *
 *
 * <h3> URI syntax and components </h3>
 *
 * At the highest level a URI reference (hereinafter simply "URI") in string
 * form has the syntax
 *
 * <blockquote>
 * [*scheme***`:`**]*scheme-specific-part*[**`#`***fragment*]
</blockquote> *
 *
 * where square brackets [...] delineate optional components and the characters
 * **`:`** and **`#`** stand for themselves.
 *
 *
 *  An *absolute* URI specifies a scheme; a URI that is not absolute is
 * said to be *relative*.  URIs are also classified according to whether
 * they are *opaque* or *hierarchical*.
 *
 *
 *  An *opaque* URI is an absolute URI whose scheme-specific part does
 * not begin with a slash character (`'/'`).  Opaque URIs are not
 * subject to further parsing.  Some examples of opaque URIs are:
 *
 * <blockquote><table cellpadding=0 cellspacing=0 summary="layout">
 * <tr><td>`mailto:java-net@java.sun.com`</td><td></td></tr>
 * <tr><td>`news:comp.lang.java`</td><td></td></tr>
 * <tr><td>`urn:isbn:096139210x`</td></tr>
</table></blockquote> *
 *
 *
 *  A *hierarchical* URI is either an absolute URI whose
 * scheme-specific part begins with a slash character, or a relative URI, that
 * is, a URI that does not specify a scheme.  Some examples of hierarchical
 * URIs are:
 *
 * <blockquote>
 * `http://java.sun.com/j2se/1.3/`<br></br>
 * `docs/guide/collections/designfaq.html#28`<br></br>
 * `../../../demo/jfc/SwingSet2/src/SwingSet2.java`<br></br>
 * `file:///~/calendar`
</blockquote> *
 *
 *
 *  A hierarchical URI is subject to further parsing according to the syntax
 *
 * <blockquote>
 * [*scheme***`:`**][**`//`***authority*][*path*][**`?`***query*][**`#`***fragment*]
</blockquote> *
 *
 * where the characters **`:`**, **`/`**,
 * **`?`**, and **`#`** stand for themselves.  The
 * scheme-specific part of a hierarchical URI consists of the characters
 * between the scheme and fragment components.
 *
 *
 *  The authority component of a hierarchical URI is, if specified, either
 * *server-based* or *registry-based*.  A server-based authority
 * parses according to the familiar syntax
 *
 * <blockquote>
 * [*user-info***`@`**]*host*[**`:`***port*]
</blockquote> *
 *
 * where the characters **`@`** and **`:`** stand for
 * themselves.  Nearly all URI schemes currently in use are server-based.  An
 * authority component that does not parse in this way is considered to be
 * registry-based.
 *
 *
 *  The path component of a hierarchical URI is itself said to be absolute
 * if it begins with a slash character (`'/'`); otherwise it is
 * relative.  The path of a hierarchical URI that is either absolute or
 * specifies an authority is always absolute.
 *
 *
 *  All told, then, a URI instance has the following nine components:
 *
 * <blockquote><table summary="Describes the components of a URI:scheme,scheme-specific-part,authority,user-info,host,port,path,query,fragment">
 * <tr><th>*Component*</th><th>*Type*</th></tr>
 * <tr><td>scheme</td><td>`String`</td></tr>
 * <tr><td>scheme-specific-part&nbsp;&nbsp;&nbsp;&nbsp;</td><td>`String`</td></tr>
 * <tr><td>authority</td><td>`String`</td></tr>
 * <tr><td>user-info</td><td>`String`</td></tr>
 * <tr><td>host</td><td>`String`</td></tr>
 * <tr><td>port</td><td>`int`</td></tr>
 * <tr><td>path</td><td>`String`</td></tr>
 * <tr><td>query</td><td>`String`</td></tr>
 * <tr><td>fragment</td><td>`String`</td></tr>
</table></blockquote> *
 *
 * In a given instance any particular component is either *undefined* or
 * *defined* with a distinct value.  Undefined string components are
 * represented by `null`, while undefined integer components are
 * represented by `-1`.  A string component may be defined to have the
 * empty string as its value; this is not equivalent to that component being
 * undefined.
 *
 *
 *  Whether a particular component is or is not defined in an instance
 * depends upon the type of the URI being represented.  An absolute URI has a
 * scheme component.  An opaque URI has a scheme, a scheme-specific part, and
 * possibly a fragment, but has no other components.  A hierarchical URI always
 * has a path (though it may be empty) and a scheme-specific-part (which at
 * least contains the path), and may have any of the other components.  If the
 * authority component is present and is server-based then the host component
 * will be defined and the user-information and port components may be defined.
 *
 *
 * <h4> Operations on URI instances </h4>
 *
 * The key operations supported by this class are those of
 * *normalization*, *resolution*, and *relativization*.
 *
 *
 *  *Normalization* is the process of removing unnecessary `"."`
 * and `".."` segments from the path component of a hierarchical URI.
 * Each `"."` segment is simply removed.  A `".."` segment is
 * removed only if it is preceded by a non-`".."` segment.
 * Normalization has no effect upon opaque URIs.
 *
 *
 *  *Resolution* is the process of resolving one URI against another,
 * *base* URI.  The resulting URI is constructed from components of both
 * URIs in the manner specified by RFC&nbsp;2396, taking components from the
 * base URI for those not specified in the original.  For hierarchical URIs,
 * the path of the original is resolved against the path of the base and then
 * normalized.  The result, for example, of resolving
 *
 * <blockquote>
 * `docs/guide/collections/designfaq.html#28`
 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
 * &nbsp;&nbsp;&nbsp;&nbsp;(1)
</blockquote> *
 *
 * against the base URI `http://java.sun.com/j2se/1.3/` is the result
 * URI
 *
 * <blockquote>
 * `https://docs.oracle.com/javase/1.3/docs/guide/collections/designfaq.html#28`
</blockquote> *
 *
 * Resolving the relative URI
 *
 * <blockquote>
 * `../../../demo/jfc/SwingSet2/src/SwingSet2.java`&nbsp;&nbsp;&nbsp;&nbsp;(2)
</blockquote> *
 *
 * against this result yields, in turn,
 *
 * <blockquote>
 * `http://java.sun.com/j2se/1.3/demo/jfc/SwingSet2/src/SwingSet2.java`
</blockquote> *
 *
 * Resolution of both absolute and relative URIs, and of both absolute and
 * relative paths in the case of hierarchical URIs, is supported.  Resolving
 * the URI `file:///~calendar` against any other URI simply yields the
 * original URI, since it is absolute.  Resolving the relative URI (2) above
 * against the relative base URI (1) yields the normalized, but still relative,
 * URI
 *
 * <blockquote>
 * `demo/jfc/SwingSet2/src/SwingSet2.java`
</blockquote> *
 *
 *
 *  *Relativization*, finally, is the inverse of resolution: For any
 * two normalized URIs *u* and&nbsp;*v*,
 *
 * <blockquote>
 * *u*`.relativize(`*u*`.resolve(`*v*`)).equals(`*v*`)`&nbsp;&nbsp;and<br></br>
 * *u*`.resolve(`*u*`.relativize(`*v*`)).equals(`*v*`)`&nbsp;&nbsp;.<br></br>
</blockquote> *
 *
 * This operation is often useful when constructing a document containing URIs
 * that must be made relative to the base URI of the document wherever
 * possible.  For example, relativizing the URI
 *
 * <blockquote>
 * `https://docs.oracle.com/javase/1.3/docs/guide/index.html`
</blockquote> *
 *
 * against the base URI
 *
 * <blockquote>
 * `http://java.sun.com/j2se/1.3`
</blockquote> *
 *
 * yields the relative URI `docs/guide/index.html`.
 *
 *
 * <h4> Character categories </h4>
 *
 * RFC&nbsp;2396 specifies precisely which characters are permitted in the
 * various components of a URI reference.  The following categories, most of
 * which are taken from that specification, are used below to describe these
 * constraints:
 *
 * <blockquote><table cellspacing=2 summary="Describes categories alpha,digit,alphanum,unreserved,punct,reserved,escaped,and other">
 * <tr><th valign=top>*alpha*</th>
 * <td>The US-ASCII alphabetic characters,
 * `'A'`&nbsp;through&nbsp;`'Z'`
 * and `'a'`&nbsp;through&nbsp;`'z'`</td></tr>
 * <tr><th valign=top>*digit*</th>
 * <td>The US-ASCII decimal digit characters,
 * `'0'`&nbsp;through&nbsp;`'9'`</td></tr>
 * <tr><th valign=top>*alphanum*</th>
 * <td>All *alpha* and *digit* characters</td></tr>
 * <tr><th valign=top>*unreserved*&nbsp;&nbsp;&nbsp;&nbsp;</th>
 * <td>All *alphanum* characters together with those in the string
 * `"_-!.~'()*"`</td></tr>
 * <tr><th valign=top>*punct*</th>
 * <td>The characters in the string `",;:$&+="`</td></tr>
 * <tr><th valign=top>*reserved*</th>
 * <td>All *punct* characters together with those in the string
 * `"?/[]@"`</td></tr>
 * <tr><th valign=top>*escaped*</th>
 * <td>Escaped octets, that is, triplets consisting of the percent
 * character (`'%'`) followed by two hexadecimal digits
 * (`'0'`-`'9'`, `'A'`-`'F'`, and
 * `'a'`-`'f'`)</td></tr>
 * <tr><th valign=top>*other*</th>
 * <td>The Unicode characters that are not in the US-ASCII character set,
 * are not control characters (according to the [           ][java.lang.Character.isISOControl]
 * method), and are not space characters (according to the [           ][java.lang.Character.isSpaceChar]
 * method)&nbsp;&nbsp;*(**Deviation from RFC 2396**, which is
 * limited to US-ASCII)*</td></tr>
</table></blockquote> *
 *
 *
 * <a name="legal-chars"></a> The set of all legal URI characters consists of
 * the *unreserved*, *reserved*, *escaped*, and *other*
 * characters.
 *
 *
 * <h4> Escaped octets, quotation, encoding, and decoding </h4>
 *
 * RFC 2396 allows escaped octets to appear in the user-info, path, query, and
 * fragment components.  Escaping serves two purposes in URIs:
 *
 *
 *
 *  *
 *
 * To *encode* non-US-ASCII characters when a URI is required to
 * conform strictly to RFC&nbsp;2396 by not containing any *other*
 * characters.
 *
 *  *
 *
 * To *quote* characters that are otherwise illegal in a
 * component.  The user-info, path, query, and fragment components differ
 * slightly in terms of which characters are considered legal and illegal.
 *
 *
 *
 *
 * These purposes are served in this class by three related operations:
 *
 *
 *
 *  *
 *
 *<a name="encode"></a> A character is *encoded* by replacing it
 * with the sequence of escaped octets that represent that character in the
 * UTF-8 character set.  The Euro currency symbol (`'\u005Cu20AC'`),
 * for example, is encoded as `"%E2%82%AC"`.  *(**Deviation from
 * RFC&nbsp;2396**, which does not specify any particular character
 * set.)*
 *
 *  *
 *
 *<a name="quote"></a> An illegal character is *quoted* simply by
 * encoding it.  The space character, for example, is quoted by replacing it
 * with `"%20"`.  UTF-8 contains US-ASCII, hence for US-ASCII
 * characters this transformation has exactly the effect required by
 * RFC&nbsp;2396.
 *
 *  *
 *
 *<a name="decode"></a>
 * A sequence of escaped octets is *decoded* by
 * replacing it with the sequence of characters that it represents in the
 * UTF-8 character set.  UTF-8 contains US-ASCII, hence decoding has the
 * effect of de-quoting any quoted US-ASCII characters as well as that of
 * decoding any encoded non-US-ASCII characters.  If a [decoding error](../nio/charset/CharsetDecoder.html#ce) occurs
 * when decoding the escaped octets then the erroneous octets are replaced by
 * `'\u005CuFFFD'`, the Unicode replacement character.
 *
 *
 *
 * These operations are exposed in the constructors and methods of this class
 * as follows:
 *
 *
 *
 *  *
 *
 * The [single-argument][.URI] requires any illegal characters in its argument to be
 * quoted and preserves any escaped octets and *other* characters that
 * are present.
 *
 *  *
 *
 * The [   ][.URI] quote illegal characters as
 * required by the components in which they appear.  The percent character
 * (`'%'`) is always quoted by these constructors.  Any *other*
 * characters are preserved.
 *
 *  *
 *
 * The [getRawUserInfo][.getRawUserInfo], [   getRawPath][.getRawPath], [getRawQuery][.getRawQuery], [   getRawFragment][.getRawFragment], [getRawAuthority][.getRawAuthority], and [   ][.getRawSchemeSpecificPart] methods return the
 * values of their corresponding components in raw form, without interpreting
 * any escaped octets.  The strings returned by these methods may contain
 * both escaped octets and *other* characters, and will not contain any
 * illegal characters.
 *
 *  *
 *
 * The [getUserInfo][.getUserInfo], [   getPath][.getPath], [getQuery][.getQuery], [   getFragment][.getFragment], [getAuthority][.getAuthority], and [   ][.getSchemeSpecificPart] methods decode any escaped
 * octets in their corresponding components.  The strings returned by these
 * methods may contain both *other* characters and illegal characters,
 * and will not contain any escaped octets.
 *
 *  *
 *
 * The [toString][.toString] method returns a URI string with
 * all necessary quotation but which may contain *other* characters.
 *
 *
 *  *
 *
 * The [toASCIIString][.toASCIIString] method returns a fully
 * quoted and encoded URI string that does not contain any *other*
 * characters.
 *
 *
 *
 *
 * <h4> Identities </h4>
 *
 * For any URI *u*, it is always the case that
 *
 * <blockquote>
 * `new URI(`*u*`.toString()).equals(`*u*`)`&nbsp;.
</blockquote> *
 *
 * For any URI *u* that does not contain redundant syntax such as two
 * slashes before an empty authority (as in `file:///tmp/`&nbsp;) or a
 * colon following a host name but no port (as in
 * `http://java.sun.com:`&nbsp;), and that does not encode characters
 * except those that must be quoted, the following identities also hold:
 * <pre>
 * new URI(*u*.getScheme(),
 * *u*.getSchemeSpecificPart(),
 * *u*.getFragment())
 * .equals(*u*)</pre>
 * in all cases,
 * <pre>
 * new URI(*u*.getScheme(),
 * *u*.getUserInfo(), *u*.getAuthority(),
 * *u*.getPath(), *u*.getQuery(),
 * *u*.getFragment())
 * .equals(*u*)</pre>
 * if *u* is hierarchical, and
 * <pre>
 * new URI(*u*.getScheme(),
 * *u*.getUserInfo(), *u*.getHost(), *u*.getPort(),
 * *u*.getPath(), *u*.getQuery(),
 * *u*.getFragment())
 * .equals(*u*)</pre>
 * if *u* is hierarchical and has either no authority or a server-based
 * authority.
 *
 *
 * <h4> URIs, URLs, and URNs </h4>
 *
 * A URI is a uniform resource *identifier* while a URL is a uniform
 * resource *locator*.  Hence every URL is a URI, abstractly speaking, but
 * not every URI is a URL.  This is because there is another subcategory of
 * URIs, uniform resource *names* (URNs), which name resources but do not
 * specify how to locate them.  The `mailto`, `news`, and
 * `isbn` URIs shown above are examples of URNs.
 *
 *
 *  The conceptual distinction between URIs and URLs is reflected in the
 * differences between this class and the [URL] class.
 *
 *
 *  An instance of this class represents a URI reference in the syntactic
 * sense defined by RFC&nbsp;2396.  A URI may be either absolute or relative.
 * A URI string is parsed according to the generic syntax without regard to the
 * scheme, if any, that it specifies.  No lookup of the host, if any, is
 * performed, and no scheme-dependent stream handler is constructed.  Equality,
 * hashing, and comparison are defined strictly in terms of the character
 * content of the instance.  In other words, a URI instance is little more than
 * a structured string that supports the syntactic, scheme-independent
 * operations of comparison, normalization, resolution, and relativization.
 *
 *
 *  An instance of the [URL] class, by contrast, represents the
 * syntactic components of a URL together with some of the information required
 * to access the resource that it describes.  A URL must be absolute, that is,
 * it must always specify a scheme.  A URL string is parsed according to its
 * scheme.  A stream handler is always established for a URL, and in fact it is
 * impossible to create a URL instance for a scheme for which no handler is
 * available.  Equality and hashing depend upon both the scheme and the
 * Internet address of the host, if any; comparison is not defined.  In other
 * words, a URL is a structured string that supports the syntactic operation of
 * resolution as well as the network I/O operations of looking up the host and
 * opening a connection to the specified resource.
 *
 *
 * @author Mark Reinhold
 * @since 1.4
 *
 * @see [*RFC&nbsp;2279: UTF-8, a
 * transformation format of ISO 10646*](http://www.ietf.org/rfc/rfc2279.txt), <br></br>[*RFC&nbsp;2373: IPv6 Addressing
 * Architecture*](http://www.ietf.org/rfc/rfc2373.txt), <br></br>[*RFC&nbsp;2396: Uniform
 * Resource Identifiers
*](http://www.ietf.org/rfc/rfc2396.txt) */

class URI : Comparable<URI> {


    // -- Properties and components of this instance --

    // Components of all URIs: [<scheme>:]<scheme-specific-part>[#<fragment>]
    // -- Component access methods --

    /**
     * Returns the scheme component of this URI.
     *
     *
     *  The scheme component of a URI, if defined, only contains characters
     * in the *alphanum* category and in the string `"-.+"`.  A
     * scheme always starts with an *alpha* character.
     *
     *
     *
     * The scheme component of a URI cannot contain escaped octets, hence this
     * method does not perform any decoding.
     *
     * @return  The scheme component of this URI,
     * or `null` if the scheme is undefined
     */
    
    var scheme: String? = null
        private set            // null ==> relative URI
    /**
     * Returns the raw fragment component of this URI.
     *
     *
     *  The fragment component of a URI, if defined, only contains legal URI
     * characters.
     *
     * @return  The raw fragment component of this URI,
     * or `null` if the fragment is undefined
     */
    
    var rawFragment: String? = null
        private set

    // Hierarchical URI components: [//<authority>]<path>[?<query>]
    /**
     * Returns the raw authority component of this URI.
     *
     *
     *  The authority component of a URI, if defined, only contains the
     * commercial-at character (`'@'`) and characters in the
     * *unreserved*, *punct*, *escaped*, and *other*
     * categories.  If the authority is server-based then it is further
     * constrained to have valid user-information, host, and port
     * components.
     *
     * @return  The raw authority component of this URI,
     * or `null` if the authority is undefined
     */
    
    var rawAuthority: String? = null
        private set         // Registry or server

    // Server-based authority: [<userInfo>@]<host>[:<port>]
    /**
     * Returns the raw user-information component of this URI.
     *
     *
     *  The user-information component of a URI, if defined, only contains
     * characters in the *unreserved*, *punct*, *escaped*, and
     * *other* categories.
     *
     * @return  The raw user-information component of this URI,
     * or `null` if the user information is undefined
     */
    
    var rawUserInfo: String? = null
        private set
    /**
     * Returns the host component of this URI.
     *
     *
     *  The host component of a URI, if defined, will have one of the
     * following forms:
     *
     *
     *
     *  *
     *
     * A domain name consisting of one or more *labels*
     * separated by period characters (`'.'`), optionally followed by
     * a period character.  Each label consists of *alphanum* characters
     * as well as hyphen characters (`'-'`), though hyphens never
     * occur as the first or last characters in a label. The rightmost
     * label of a domain name consisting of two or more labels, begins
     * with an *alpha* character.
     *
     *  *
     *
     * A dotted-quad IPv4 address of the form
     * *digit*`+.`*digit*`+.`*digit*`+.`*digit*`+`,
     * where no *digit* sequence is longer than three characters and no
     * sequence has a value larger than 255.
     *
     *  *
     *
     * An IPv6 address enclosed in square brackets (`'['` and
     * `']'`) and consisting of hexadecimal digits, colon characters
     * (`':'`), and possibly an embedded IPv4 address.  The full
     * syntax of IPv6 addresses is specified in [*RFC&nbsp;2373: IPv6
 * Addressing Architecture*](http://www.ietf.org/rfc/rfc2373.txt).
     *
     *
     *
     * The host component of a URI cannot contain escaped octets, hence this
     * method does not perform any decoding.
     *
     * @return  The host component of this URI,
     * or `null` if the host is undefined
     */
    
    var host: String? = null
        private set              // null ==> registry-based
    /**
     * Returns the port number of this URI.
     *
     *
     *  The port component of a URI, if defined, is a non-negative
     * integer.
     *
     * @return  The port component of this URI,
     * or `-1` if the port is undefined
     */
    
    var port = -1
        private set            // -1 ==> undefined

    // Remaining components of hierarchical URIs
    /**
     * Returns the raw path component of this URI.
     *
     *
     *  The path component of a URI, if defined, only contains the slash
     * character (`'/'`), the commercial-at character (`'@'`),
     * and characters in the *unreserved*, *punct*, *escaped*,
     * and *other* categories.
     *
     * @return  The path component of this URI,
     * or `null` if the path is undefined
     */
    
    var rawPath: String? = null
        private set              // null ==> opaque
    /**
     * Returns the raw query component of this URI.
     *
     *
     *  The query component of a URI, if defined, only contains legal URI
     * characters.
     *
     * @return  The raw query component of this URI,
     * or `null` if the query is undefined
     */
    
    var rawQuery: String? = null
        private set

    // The remaining fields may be computed on demand

    
    
    private var schemeSpecificPart: String? = null
    
    
    private var hash: Int = 0        // Zero ==> undefined

    
    
    private var decodedUserInfo: String? = null
    
    
    private var decodedAuthority: String? = null
    
    
    private var decodedPath: String? = null
    
    
    private var decodedQuery: String? = null
    
    
    private var decodedFragment: String? = null
    
    
    private var decodedSchemeSpecificPart: String? = null

    /**
     * The string form of this URI.
     *
     * @serial
     */
    
    private var string: String? = null             // The only serializable field

    /**
     * Tells whether or not this URI is absolute.
     *
     *
     *  A URI is absolute if, and only if, it has a scheme component.
     *
     * @return  `true` if, and only if, this URI is absolute
     */
    val isAbsolute: Boolean
        get() = scheme != null

    /**
     * Tells whether or not this URI is opaque.
     *
     *
     *  A URI is opaque if, and only if, it is absolute and its
     * scheme-specific part does not begin with a slash character ('/').
     * An opaque URI has a scheme, a scheme-specific part, and possibly
     * a fragment; all other components are undefined.
     *
     * @return  `true` if, and only if, this URI is opaque
     */
    val isOpaque: Boolean
        get() = rawPath == null

    /**
     * Returns the raw scheme-specific part of this URI.  The scheme-specific
     * part is never undefined, though it may be empty.
     *
     *
     *  The scheme-specific part of a URI only contains legal URI
     * characters.
     *
     * @return  The raw scheme-specific part of this URI
     * (never `null`)
     */
    val rawSchemeSpecificPart: String?
        get() {
            defineSchemeSpecificPart()
            return schemeSpecificPart
        }

    /**
     * Returns the decoded authority component of this URI.
     *
     *
     *  The string returned by this method is equal to that returned by the
     * [getRawAuthority][.getRawAuthority] method except that all
     * sequences of escaped octets are [decoded](#decode).
     *
     * @return  The decoded authority component of this URI,
     * or `null` if the authority is undefined
     */
    val authority: String?
        get() {
            if (decodedAuthority == null)
                decodedAuthority = decode(rawAuthority)
            return decodedAuthority
        }

    /**
     * Returns the decoded user-information component of this URI.
     *
     *
     *  The string returned by this method is equal to that returned by the
     * [getRawUserInfo][.getRawUserInfo] method except that all
     * sequences of escaped octets are [decoded](#decode).
     *
     * @return  The decoded user-information component of this URI,
     * or `null` if the user information is undefined
     */
    val userInfo: String?
        get() {
            if (decodedUserInfo == null && rawUserInfo != null)
                decodedUserInfo = decode(rawUserInfo)
            return decodedUserInfo
        }

    /**
     * Returns the decoded path component of this URI.
     *
     *
     *  The string returned by this method is equal to that returned by the
     * [getRawPath][.getRawPath] method except that all sequences of
     * escaped octets are [decoded](#decode).
     *
     * @return  The decoded path component of this URI,
     * or `null` if the path is undefined
     */
    val path: String?
        get() {
            if (decodedPath == null && rawPath != null)
                decodedPath = decode(rawPath)
            return decodedPath
        }

    /**
     * Returns the decoded query component of this URI.
     *
     *
     *  The string returned by this method is equal to that returned by the
     * [getRawQuery][.getRawQuery] method except that all sequences of
     * escaped octets are [decoded](#decode).
     *
     * @return  The decoded query component of this URI,
     * or `null` if the query is undefined
     */
    val query: String?
        get() {
            if (decodedQuery == null && rawQuery != null)
                decodedQuery = decode(rawQuery)
            return decodedQuery
        }

    /**
     * Returns the decoded fragment component of this URI.
     *
     *
     *  The string returned by this method is equal to that returned by the
     * [getRawFragment][.getRawFragment] method except that all
     * sequences of escaped octets are [decoded](#decode).
     *
     * @return  The decoded fragment component of this URI,
     * or `null` if the fragment is undefined
     */
    val fragment: String?
        get() {
            if (decodedFragment == null && rawFragment != null)
                decodedFragment = decode(rawFragment)
            return decodedFragment
        }


    // -- Constructors and factories --

    private constructor() {}                           // Used internally

    /**
     * Constructs a URI by parsing the given string.
     *
     *
     *  This constructor parses the given string exactly as specified by the
     * grammar in [RFC&nbsp;2396](http://www.ietf.org/rfc/rfc2396.txt),
     * Appendix&nbsp;A, ***except for the following deviations:***
     *
     *
     *
     *  *
     *
     * An empty authority component is permitted as long as it is
     * followed by a non-empty path, a query component, or a fragment
     * component.  This allows the parsing of URIs such as
     * `"file:///foo/bar"`, which seems to be the intent of
     * RFC&nbsp;2396 although the grammar does not permit it.  If the
     * authority component is empty then the user-information, host, and port
     * components are undefined.
     *
     *  *
     *
     * Empty relative paths are permitted; this seems to be the
     * intent of RFC&nbsp;2396 although the grammar does not permit it.  The
     * primary consequence of this deviation is that a standalone fragment
     * such as `"#foo"` parses as a relative URI with an empty path
     * and the given fragment, and can be usefully [resolved](#resolve-frag) against a base URI.
     *
     *  *
     *
     * IPv4 addresses in host components are parsed rigorously, as
     * specified by [RFC&nbsp;2732](http://www.ietf.org/rfc/rfc2732.txt): Each
     * element of a dotted-quad address must contain no more than three
     * decimal digits.  Each element is further constrained to have a value
     * no greater than 255.
     *
     *  *
     *
     * Hostnames in host components that comprise only a single
     * domain label are permitted to start with an *alphanum*
     * character. This seems to be the intent of [RFC&nbsp;2396](http://www.ietf.org/rfc/rfc2396.txt)
     * section&nbsp;3.2.2 although the grammar does not permit it. The
     * consequence of this deviation is that the authority component of a
     * hierarchical URI such as `s://123`, will parse as a server-based
     * authority.
     *
     *  *
     *
     * IPv6 addresses are permitted for the host component.  An IPv6
     * address must be enclosed in square brackets (`'['` and
     * `']'`) as specified by [RFC&nbsp;2732](http://www.ietf.org/rfc/rfc2732.txt).  The
     * IPv6 address itself must parse according to [RFC&nbsp;2373](http://www.ietf.org/rfc/rfc2373.txt).  IPv6
     * addresses are further constrained to describe no more than sixteen
     * bytes of address information, a constraint implicit in RFC&nbsp;2373
     * but not expressible in the grammar.
     *
     *  *
     *
     * Characters in the *other* category are permitted wherever
     * RFC&nbsp;2396 permits *escaped* octets, that is, in the
     * user-information, path, query, and fragment components, as well as in
     * the authority component if the authority is registry-based.  This
     * allows URIs to contain Unicode characters beyond those in the US-ASCII
     * character set.
     *
     *
     *
     * @param  str   The string to be parsed into a URI
     *
     * @throws  NullPointerException
     * If `str` is `null`
     *
     * @throws  URISyntaxException
     * If the given string violates RFC&nbsp;2396, as augmented
     * by the above deviations
     */
    constructor(str: String) {
        Parser(str).parse(false)
    }

    /**
     * Constructs a hierarchical URI from the given components.
     *
     *
     *  If a scheme is given then the path, if also given, must either be
     * empty or begin with a slash character (`'/'`).  Otherwise a
     * component of the new URI may be left undefined by passing `null`
     * for the corresponding parameter or, in the case of the `port`
     * parameter, by passing `-1`.
     *
     *
     *  This constructor first builds a URI string from the given components
     * according to the rules specified in [RFC&nbsp;2396](http://www.ietf.org/rfc/rfc2396.txt),
     * section&nbsp;5.2, step&nbsp;7:
     *
     *
     *
     *  1.
     *
     * Initially, the result string is empty.
     *
     *  1.
     *
     * If a scheme is given then it is appended to the result,
     * followed by a colon character (`':'`).
     *
     *  1.
     *
     * If user information, a host, or a port are given then the
     * string `"//"` is appended.
     *
     *  1.
     *
     * If user information is given then it is appended, followed by
     * a commercial-at character (`'@'`).  Any character not in the
     * *unreserved*, *punct*, *escaped*, or *other*
     * categories is [quoted](#quote).
     *
     *  1.
     *
     * If a host is given then it is appended.  If the host is a
     * literal IPv6 address but is not enclosed in square brackets
     * (`'['` and `']'`) then the square brackets are added.
     *
     *
     *  1.
     *
     * If a port number is given then a colon character
     * (`':'`) is appended, followed by the port number in decimal.
     *
     *
     *  1.
     *
     * If a path is given then it is appended.  Any character not in
     * the *unreserved*, *punct*, *escaped*, or *other*
     * categories, and not equal to the slash character (`'/'`) or the
     * commercial-at character (`'@'`), is quoted.
     *
     *  1.
     *
     * If a query is given then a question-mark character
     * (`'?'`) is appended, followed by the query.  Any character that
     * is not a [legal URI character](#legal-chars) is quoted.
     *
     *
     *  1.
     *
     * Finally, if a fragment is given then a hash character
     * (`'#'`) is appended, followed by the fragment.  Any character
     * that is not a legal URI character is quoted.
     *
     *
     *
     *
     *  The resulting URI string is then parsed as if by invoking the [ ][.URI] constructor and then invoking the [ ][.parseServerAuthority] method upon the result; this may cause a [ ] to be thrown.
     *
     * @param   scheme    Scheme name
     * @param   userInfo  User name and authorization information
     * @param   host      Host name
     * @param   port      Port number
     * @param   path      Path
     * @param   query     Query
     * @param   fragment  Fragment
     *
     * @throws URISyntaxException
     * If both a scheme and a path are given but the path is relative,
     * if the URI string constructed from the given components violates
     * RFC&nbsp;2396, or if the authority component of the string is
     * present but cannot be parsed as a server-based authority
     */
    constructor(
        scheme: String,
        userInfo: String?, host: String, port: Int,
        path: String, query: String?, fragment: String
    ) {
        val s = toString(
            scheme, null, null, userInfo, host, port,
            path, query, fragment
        )
        checkPath(s, scheme, path)
        Parser(s).parse(true)
    }

    /**
     * Constructs a hierarchical URI from the given components.
     *
     *
     *  If a scheme is given then the path, if also given, must either be
     * empty or begin with a slash character (`'/'`).  Otherwise a
     * component of the new URI may be left undefined by passing `null`
     * for the corresponding parameter.
     *
     *
     *  This constructor first builds a URI string from the given components
     * according to the rules specified in [RFC&nbsp;2396](http://www.ietf.org/rfc/rfc2396.txt),
     * section&nbsp;5.2, step&nbsp;7:
     *
     *
     *
     *  1.
     *
     * Initially, the result string is empty.
     *
     *  1.
     *
     * If a scheme is given then it is appended to the result,
     * followed by a colon character (`':'`).
     *
     *  1.
     *
     * If an authority is given then the string `"//"` is
     * appended, followed by the authority.  If the authority contains a
     * literal IPv6 address then the address must be enclosed in square
     * brackets (`'['` and `']'`).  Any character not in the
     * *unreserved*, *punct*, *escaped*, or *other*
     * categories, and not equal to the commercial-at character
     * (`'@'`), is [quoted](#quote).
     *
     *  1.
     *
     * If a path is given then it is appended.  Any character not in
     * the *unreserved*, *punct*, *escaped*, or *other*
     * categories, and not equal to the slash character (`'/'`) or the
     * commercial-at character (`'@'`), is quoted.
     *
     *  1.
     *
     * If a query is given then a question-mark character
     * (`'?'`) is appended, followed by the query.  Any character that
     * is not a [legal URI character](#legal-chars) is quoted.
     *
     *
     *  1.
     *
     * Finally, if a fragment is given then a hash character
     * (`'#'`) is appended, followed by the fragment.  Any character
     * that is not a legal URI character is quoted.
     *
     *
     *
     *
     *  The resulting URI string is then parsed as if by invoking the [ ][.URI] constructor and then invoking the [ ][.parseServerAuthority] method upon the result; this may cause a [ ] to be thrown.
     *
     * @param   scheme     Scheme name
     * @param   authority  Authority
     * @param   path       Path
     * @param   query      Query
     * @param   fragment   Fragment
     *
     * @throws URISyntaxException
     * If both a scheme and a path are given but the path is relative,
     * if the URI string constructed from the given components violates
     * RFC&nbsp;2396, or if the authority component of the string is
     * present but cannot be parsed as a server-based authority
     */
    constructor(
        scheme: String,
        authority: String,
        path: String, query: String, fragment: String
    ) {
        val s = toString(
            scheme, null,
            authority, null, null, -1,
            path, query, fragment
        )
        checkPath(s, scheme, path)
        Parser(s).parse(false)
    }

    /**
     * Constructs a hierarchical URI from the given components.
     *
     *
     *  A component may be left undefined by passing `null`.
     *
     *
     *  This convenience constructor works as if by invoking the
     * seven-argument constructor as follows:
     *
     * <blockquote>
     * `new` [ URI][.URI]`(scheme, null, host, -1, path, null, fragment);`
    </blockquote> *
     *
     * @param   scheme    Scheme name
     * @param   host      Host name
     * @param   path      Path
     * @param   fragment  Fragment
     *
     * @throws  URISyntaxException
     * If the URI string constructed from the given components
     * violates RFC&nbsp;2396
     */
    constructor(scheme: String, host: String, path: String, fragment: String) : this(
        scheme,
        null,
        host,
        -1,
        path,
        null,
        fragment
    ) {
    }

    /**
     * Constructs a URI from the given components.
     *
     *
     *  A component may be left undefined by passing `null`.
     *
     *
     *  This constructor first builds a URI in string form using the given
     * components as follows:
     *
     *
     *
     *  1.
     *
     * Initially, the result string is empty.
     *
     *  1.
     *
     * If a scheme is given then it is appended to the result,
     * followed by a colon character (`':'`).
     *
     *  1.
     *
     * If a scheme-specific part is given then it is appended.  Any
     * character that is not a [legal URI character](#legal-chars)
     * is [quoted](#quote).
     *
     *  1.
     *
     * Finally, if a fragment is given then a hash character
     * (`'#'`) is appended to the string, followed by the fragment.
     * Any character that is not a legal URI character is quoted.
     *
     *
     *
     *
     *  The resulting URI string is then parsed in order to create the new
     * URI instance as if by invoking the [.URI] constructor;
     * this may cause a [URISyntaxException] to be thrown.
     *
     * @param   scheme    Scheme name
     * @param   ssp       Scheme-specific part
     * @param   fragment  Fragment
     *
     * @throws  URISyntaxException
     * If the URI string constructed from the given components
     * violates RFC&nbsp;2396
     */
    constructor(scheme: String, ssp: String, fragment: String) {
        Parser(toString(scheme, ssp, null, null, null, -1, null, null, fragment))
            .parse(false)
    }


    // -- Operations --

    /**
     * Attempts to parse this URI's authority component, if defined, into
     * user-information, host, and port components.
     *
     *
     *  If this URI's authority component has already been recognized as
     * being server-based then it will already have been parsed into
     * user-information, host, and port components.  In this case, or if this
     * URI has no authority component, this method simply returns this URI.
     *
     *
     *  Otherwise this method attempts once more to parse the authority
     * component into user-information, host, and port components, and throws
     * an exception describing why the authority component could not be parsed
     * in that way.
     *
     *
     *  This method is provided because the generic URI syntax specified in
     * [RFC&nbsp;2396](http://www.ietf.org/rfc/rfc2396.txt)
     * cannot always distinguish a malformed server-based authority from a
     * legitimate registry-based authority.  It must therefore treat some
     * instances of the former as instances of the latter.  The authority
     * component in the URI string `"//foo:bar"`, for example, is not a
     * legal server-based authority but it is legal as a registry-based
     * authority.
     *
     *
     *  In many common situations, for example when working URIs that are
     * known to be either URNs or URLs, the hierarchical URIs being used will
     * always be server-based.  They therefore must either be parsed as such or
     * treated as an error.  In these cases a statement such as
     *
     * <blockquote>
     * `URI `*u*`= new URI(str).parseServerAuthority();`
    </blockquote> *
     *
     *
     *  can be used to ensure that *u* always refers to a URI that, if
     * it has an authority component, has a server-based authority with proper
     * user-information, host, and port components.  Invoking this method also
     * ensures that if the authority could not be parsed in that way then an
     * appropriate diagnostic message can be issued based upon the exception
     * that is thrown.
     *
     * @return  A URI whose authority field has been parsed
     * as a server-based authority
     *
     * @throws  URISyntaxException
     * If the authority component of this URI is defined
     * but cannot be parsed as a server-based authority
     * according to RFC&nbsp;2396
     */
    fun parseServerAuthority(): URI {
        // We could be clever and cache the error message and index from the
        // exception thrown during the original parse, but that would require
        // either more fields or a more-obscure representation.
        if (host != null || rawAuthority == null)
            return this
        defineString()
        Parser(string!!).parse(true)
        return this
    }

    /**
     * Normalizes this URI's path.
     *
     *
     *  If this URI is opaque, or if its path is already in normal form,
     * then this URI is returned.  Otherwise a new URI is constructed that is
     * identical to this URI except that its path is computed by normalizing
     * this URI's path in a manner consistent with [RFC&nbsp;2396](http://www.ietf.org/rfc/rfc2396.txt),
     * section&nbsp;5.2, step&nbsp;6, sub-steps&nbsp;c through&nbsp;f; that is:
     *
     *
     *
     *
     *  1.
     *
     * All `"."` segments are removed.
     *
     *  1.
     *
     * If a `".."` segment is preceded by a non-`".."`
     * segment then both of these segments are removed.  This step is
     * repeated until it is no longer applicable.
     *
     *  1.
     *
     * If the path is relative, and if its first segment contains a
     * colon character (`':'`), then a `"."` segment is
     * prepended.  This prevents a relative URI with a path such as
     * `"a:b/c/d"` from later being re-parsed as an opaque URI with a
     * scheme of `"a"` and a scheme-specific part of `"b/c/d"`.
     * ***(Deviation from RFC&nbsp;2396)***
     *
     *
     *
     *
     *  A normalized path will begin with one or more `".."` segments
     * if there were insufficient non-`".."` segments preceding them to
     * allow their removal.  A normalized path will begin with a `"."`
     * segment if one was inserted by step 3 above.  Otherwise, a normalized
     * path will not contain any `"."` or `".."` segments.
     *
     * @return  A URI equivalent to this URI,
     * but whose path is in normal form
     */
    fun normalize(): URI {
        return normalize(this)
    }

    /**
     * Resolves the given URI against this URI.
     *
     *
     *  If the given URI is already absolute, or if this URI is opaque, then
     * the given URI is returned.
     *
     *
     * <a name="resolve-frag"></a> If the given URI's fragment component is
     * defined, its path component is empty, and its scheme, authority, and
     * query components are undefined, then a URI with the given fragment but
     * with all other components equal to those of this URI is returned.  This
     * allows a URI representing a standalone fragment reference, such as
     * `"#foo"`, to be usefully resolved against a base URI.
     *
     *
     *  Otherwise this method constructs a new hierarchical URI in a manner
     * consistent with [RFC&nbsp;2396](http://www.ietf.org/rfc/rfc2396.txt),
     * section&nbsp;5.2; that is:
     *
     *
     *
     *  1.
     *
     * A new URI is constructed with this URI's scheme and the given
     * URI's query and fragment components.
     *
     *  1.
     *
     * If the given URI has an authority component then the new URI's
     * authority and path are taken from the given URI.
     *
     *  1.
     *
     * Otherwise the new URI's authority component is copied from
     * this URI, and its path is computed as follows:
     *
     *
     *
     *  1.
     *
     * If the given URI's path is absolute then the new URI's path
     * is taken from the given URI.
     *
     *  1.
     *
     * Otherwise the given URI's path is relative, and so the new
     * URI's path is computed by resolving the path of the given URI
     * against the path of this URI.  This is done by concatenating all but
     * the last segment of this URI's path, if any, with the given URI's
     * path and then normalizing the result as if by invoking the [     ][.normalize] method.
     *
     *
     *
     *
     *
     *
     *  The result of this method is absolute if, and only if, either this
     * URI is absolute or the given URI is absolute.
     *
     * @param  uri  The URI to be resolved against this URI
     * @return The resulting URI
     *
     * @throws  NullPointerException
     * If `uri` is `null`
     */
    fun resolve(uri: URI): URI {
        return resolve(this, uri)
    }

    /**
     * Constructs a new URI by parsing the given string and then resolving it
     * against this URI.
     *
     *
     *  This convenience method works as if invoking it were equivalent to
     * evaluating the expression [ resolve][.resolve]`(URI.`[create][.create]`(str))`.
     *
     * @param  str   The string to be parsed into a URI
     * @return The resulting URI
     *
     * @throws  NullPointerException
     * If `str` is `null`
     *
     * @throws  IllegalArgumentException
     * If the given string violates RFC&nbsp;2396
     */
    fun resolve(str: String): URI {
        return resolve(create(str))
    }

    /**
     * Relativizes the given URI against this URI.
     *
     *
     *  The relativization of the given URI against this URI is computed as
     * follows:
     *
     *
     *
     *  1.
     *
     * If either this URI or the given URI are opaque, or if the
     * scheme and authority components of the two URIs are not identical, or
     * if the path of this URI is not a prefix of the path of the given URI,
     * then the given URI is returned.
     *
     *  1.
     *
     * Otherwise a new relative hierarchical URI is constructed with
     * query and fragment components taken from the given URI and with a path
     * component computed by removing this URI's path from the beginning of
     * the given URI's path.
     *
     *
     *
     * @param  uri  The URI to be relativized against this URI
     * @return The resulting URI
     *
     * @throws  NullPointerException
     * If `uri` is `null`
     */
    fun relativize(uri: URI): URI {
        return relativize(this, uri)
    }

    /**
     * Returns the decoded scheme-specific part of this URI.
     *
     *
     *  The string returned by this method is equal to that returned by the
     * [getRawSchemeSpecificPart][.getRawSchemeSpecificPart] method
     * except that all sequences of escaped octets are [decoded](#decode).
     *
     * @return  The decoded scheme-specific part of this URI
     * (never `null`)
     */
    fun getSchemeSpecificPart(): String? {
        if (decodedSchemeSpecificPart == null)
            decodedSchemeSpecificPart = decode(rawSchemeSpecificPart)
        return decodedSchemeSpecificPart
    }


    // -- Equality, comparison, hash code, toString, and serialization --

    /**
     * Tests this URI for equality with another object.
     *
     *
     *  If the given object is not a URI then this method immediately
     * returns `false`.
     *
     *
     *  For two URIs to be considered equal requires that either both are
     * opaque or both are hierarchical.  Their schemes must either both be
     * undefined or else be equal without regard to case. Their fragments
     * must either both be undefined or else be equal.
     *
     *
     *  For two opaque URIs to be considered equal, their scheme-specific
     * parts must be equal.
     *
     *
     *  For two hierarchical URIs to be considered equal, their paths must
     * be equal and their queries must either both be undefined or else be
     * equal.  Their authorities must either both be undefined, or both be
     * registry-based, or both be server-based.  If their authorities are
     * defined and are registry-based, then they must be equal.  If their
     * authorities are defined and are server-based, then their hosts must be
     * equal without regard to case, their port numbers must be equal, and
     * their user-information components must be equal.
     *
     *
     *  When testing the user-information, path, query, fragment, authority,
     * or scheme-specific parts of two URIs for equality, the raw forms rather
     * than the encoded forms of these components are compared and the
     * hexadecimal digits of escaped octets are compared without regard to
     * case.
     *
     *
     *  This method satisfies the general contract of the [ ][java.lang.Object.equals] method.
     *
     * @param   ob   The object to which this object is to be compared
     *
     * @return  `true` if, and only if, the given object is a URI that
     * is identical to this URI
     */
    override fun equals(ob: Any?): Boolean {
        if (ob === this)
            return true
        if (ob !is URI)
            return false
        val that = ob as URI?
        if (this.isOpaque != that!!.isOpaque) return false
        if (!equalIgnoringCase(this.scheme, that.scheme)) return false
        if (!equal(this.rawFragment, that.rawFragment)) return false

        // Opaque
        if (this.isOpaque)
            return equal(this.schemeSpecificPart, that.schemeSpecificPart)

        // Hierarchical
        if (!equal(this.rawPath, that.rawPath)) return false
        if (!equal(this.rawQuery, that.rawQuery)) return false

        // Authorities
        if (this.rawAuthority === that.rawAuthority) return true
        if (this.host != null) {
            // Server-based
            if (!equal(this.rawUserInfo, that.rawUserInfo)) return false
            if (!equalIgnoringCase(this.host, that.host)) return false
            if (this.port != that.port) return false
        } else if (this.rawAuthority != null) {
            // Registry-based
            if (!equal(this.rawAuthority, that.rawAuthority)) return false
        } else if (this.rawAuthority !== that.rawAuthority) {
            return false
        }

        return true
    }

    /**
     * Returns a hash-code value for this URI.  The hash code is based upon all
     * of the URI's components, and satisfies the general contract of the
     * [Object.hashCode][java.lang.Object.hashCode] method.
     *
     * @return  A hash-code value for this URI
     */
    override fun hashCode(): Int {
        if (hash != 0)
            return hash
        var h = hashIgnoringCase(0, scheme)
        h = hash(h, rawFragment)
        if (isOpaque) {
            h = hash(h, schemeSpecificPart)
        } else {
            h = hash(h, rawPath)
            h = hash(h, rawQuery)
            if (host != null) {
                h = hash(h, rawUserInfo)
                h = hashIgnoringCase(h, host)
                h += 1949 * port
            } else {
                h = hash(h, rawAuthority)
            }
        }
        hash = h
        return h
    }

    /**
     * Compares this URI to another object, which must be a URI.
     *
     *
     *  When comparing corresponding components of two URIs, if one
     * component is undefined but the other is defined then the first is
     * considered to be less than the second.  Unless otherwise noted, string
     * components are ordered according to their natural, case-sensitive
     * ordering as defined by the [ String.compareTo][java.lang.String.compareTo] method.  String components that are subject to
     * encoding are compared by comparing their raw forms rather than their
     * encoded forms.
     *
     *
     *  The ordering of URIs is defined as follows:
     *
     *
     *
     *  *
     *
     * Two URIs with different schemes are ordered according the
     * ordering of their schemes, without regard to case.
     *
     *  *
     *
     * A hierarchical URI is considered to be less than an opaque URI
     * with an identical scheme.
     *
     *  *
     *
     * Two opaque URIs with identical schemes are ordered according
     * to the ordering of their scheme-specific parts.
     *
     *  *
     *
     * Two opaque URIs with identical schemes and scheme-specific
     * parts are ordered according to the ordering of their
     * fragments.
     *
     *  *
     *
     * Two hierarchical URIs with identical schemes are ordered
     * according to the ordering of their authority components:
     *
     *
     *
     *  *
     *
     * If both authority components are server-based then the URIs
     * are ordered according to their user-information components; if these
     * components are identical then the URIs are ordered according to the
     * ordering of their hosts, without regard to case; if the hosts are
     * identical then the URIs are ordered according to the ordering of
     * their ports.
     *
     *  *
     *
     * If one or both authority components are registry-based then
     * the URIs are ordered according to the ordering of their authority
     * components.
     *
     *
     *
     *  *
     *
     * Finally, two hierarchical URIs with identical schemes and
     * authority components are ordered according to the ordering of their
     * paths; if their paths are identical then they are ordered according to
     * the ordering of their queries; if the queries are identical then they
     * are ordered according to the order of their fragments.
     *
     *
     *
     *
     *  This method satisfies the general contract of the [ ][java.lang.Comparable.compareTo]
     * method.
     *
     * @param   that
     * The object to which this URI is to be compared
     *
     * @return  A negative integer, zero, or a positive integer as this URI is
     * less than, equal to, or greater than the given URI
     *
     * @throws  ClassCastException
     * If the given object is not a URI
     */
    override fun compareTo(that: URI): Int {
        var c: Int = compareIgnoringCase(this.scheme, that.scheme)

        if (c != 0)
            return c

        if (this.isOpaque) {
            return if (that.isOpaque) {
                // Both opaque
                c = compare(
                    this.schemeSpecificPart,
                    that.schemeSpecificPart
                )
                if (c != 0
                ) c else compare(this.rawFragment, that.rawFragment)
            } else +1
// Opaque > hierarchical
        } else if (that.isOpaque) {
            return -1                  // Hierarchical < opaque
        }

        // Hierarchical
        if (this.host != null && that.host != null) {
            // Both server-based
            c = compare(this.rawUserInfo, that.rawUserInfo)
            if (c != 0)
                return c
            c = compareIgnoringCase(this.host, that.host)
            if (c != 0)
                return c
            c = this.port - that.port
            if (c != 0)
                return c
        } else {
            // If one or both authorities are registry-based then we simply
            // compare them in the usual, case-sensitive way.  If one is
            // registry-based and one is server-based then the strings are
            // guaranteed to be unequal, hence the comparison will never return
            // zero and the compareTo and equals methods will remain
            // consistent.
            c = compare(this.rawAuthority, that.rawAuthority)
            if (c != 0) return c
        }

        c = compare(this.rawPath, that.rawPath)
        if (c != 0) return c
        c = compare(this.rawQuery, that.rawQuery)
        return if (c != 0) c else compare(
            this.rawFragment,
            that.rawFragment
        )
    }

    /**
     * Returns the content of this URI as a string.
     *
     *
     *  If this URI was created by invoking one of the constructors in this
     * class then a string equivalent to the original input string, or to the
     * string computed from the originally-given components, as appropriate, is
     * returned.  Otherwise this URI was created by normalization, resolution,
     * or relativization, and so a string is constructed from this URI's
     * components according to the rules specified in [RFC&nbsp;2396](http://www.ietf.org/rfc/rfc2396.txt),
     * section&nbsp;5.2, step&nbsp;7.
     *
     * @return  The string form of this URI
     */
    override fun toString(): String {
        defineString()
        return string!!
    }

    /**
     * Returns the content of this URI as a US-ASCII string.
     *
     *
     *  If this URI does not contain any characters in the *other*
     * category then an invocation of this method will return the same value as
     * an invocation of the [toString][.toString] method.  Otherwise
     * this method works as if by invoking that method and then [encoding](#encode) the result.
     *
     * @return  The string form of this URI, encoded as needed
     * so that it only contains characters in the US-ASCII
     * charset
     */
    fun toASCIIString(): String {
        defineString()
        return encode(string!!)
    }

    private fun appendAuthority(
        sb: StringBuffer,
        authority: String?,
        userInfo: String?,
        host: String?,
        port: Int
    ) {
        if (host != null) {
            sb.append("//")
            if (userInfo != null) {
                sb.append(quote(userInfo, L_USERINFO, H_USERINFO))
                sb.append('@')
            }
            val needBrackets = (host.indexOf(':') >= 0
                    && !host.startsWith("[")
                    && !host.endsWith("]"))
            if (needBrackets) sb.append('[')
            sb.append(host)
            if (needBrackets) sb.append(']')
            if (port != -1) {
                sb.append(':')
                sb.append(port)
            }
        } else if (authority != null) {
            sb.append("//")
            if (authority.startsWith("[")) {
                // authority should (but may not) contain an embedded IPv6 address
                val end = authority.indexOf("]")
                var doquote: String = authority
                var dontquote = ""
                if (end != -1 && authority.indexOf(":") != -1) {
                    // the authority contains an IPv6 address
                    if (end == authority.length) {
                        dontquote = authority
                        doquote = ""
                    } else {
                        dontquote = authority.substring(0, end + 1)
                        doquote = authority.substring(end + 1)
                    }
                }
                sb.append(dontquote)
                sb.append(
                    quote(
                        doquote,
                        L_REG_NAME or L_SERVER,
                        H_REG_NAME or H_SERVER
                    )
                )
            } else {
                sb.append(
                    quote(
                        authority,
                        L_REG_NAME or L_SERVER,
                        H_REG_NAME or H_SERVER
                    )
                )
            }
        }
    }

    private fun appendSchemeSpecificPart(
        sb: StringBuffer,
        opaquePart: String?,
        authority: String?,
        userInfo: String?,
        host: String?,
        port: Int,
        path: String?,
        query: String?
    ) {
        if (opaquePart != null) {
            /* check if SSP begins with an IPv6 address
             * because we must not quote a literal IPv6 address
             */
            if (opaquePart.startsWith("//[")) {
                val end = opaquePart.indexOf("]")
                if (end != -1 && opaquePart.indexOf(":") != -1) {
                    val doquote: String
                    val dontquote: String
                    if (end == opaquePart.length) {
                        dontquote = opaquePart
                        doquote = ""
                    } else {
                        dontquote = opaquePart.substring(0, end + 1)
                        doquote = opaquePart.substring(end + 1)
                    }
                    sb.append(dontquote)
                    sb.append(quote(doquote, L_URIC, H_URIC))
                }
            } else {
                sb.append(quote(opaquePart, L_URIC, H_URIC))
            }
        } else {
            appendAuthority(sb, authority, userInfo, host, port)
            if (path != null)
                sb.append(quote(path, L_PATH, H_PATH))
            if (query != null) {
                sb.append('?')
                sb.append(quote(query, L_URIC, H_URIC))
            }
        }
    }

    private fun appendFragment(sb: StringBuffer, fragment: String?) {
        if (fragment != null) {
            sb.append('#')
            sb.append(quote(fragment, L_URIC, H_URIC))
        }
    }

    private fun toString(
        scheme: String?,
        opaquePart: String?,
        authority: String?,
        userInfo: String?,
        host: String?,
        port: Int,
        path: String?,
        query: String?,
        fragment: String
    ): String {
        val sb = StringBuffer()
        if (scheme != null) {
            sb.append(scheme)
            sb.append(':')
        }
        appendSchemeSpecificPart(
            sb, opaquePart,
            authority, userInfo, host, port,
            path, query
        )
        appendFragment(sb, fragment)
        return sb.toString()
    }

    private fun defineSchemeSpecificPart() {
        if (schemeSpecificPart != null) return
        val sb = StringBuffer()
        appendSchemeSpecificPart(
            sb, null, authority, userInfo,
            host, port, path, query
        )
        if (sb.length == 0) return
        schemeSpecificPart = sb.toString()
    }

    private fun defineString() {
        if (string != null) return

        val sb = StringBuffer()
        if (scheme != null) {
            sb.append(scheme!!)
            sb.append(':')
        }
        if (isOpaque) {
            sb.append(schemeSpecificPart)
        } else {
            if (host != null) {
                sb.append("//")
                if (rawUserInfo != null) {
                    sb.append(rawUserInfo!!)
                    sb.append('@')
                }
                val needBrackets = (host!!.indexOf(':') >= 0
                        && !host!!.startsWith("[")
                        && !host!!.endsWith("]"))
                if (needBrackets) sb.append('[')
                sb.append(host!!)
                if (needBrackets) sb.append(']')
                if (port != -1) {
                    sb.append(':')
                    sb.append(port)
                }
            } else if (rawAuthority != null) {
                sb.append("//")
                sb.append(rawAuthority!!)
            }
            if (rawPath != null)
                sb.append(rawPath!!)
            if (rawQuery != null) {
                sb.append('?')
                sb.append(rawQuery!!)
            }
        }
        if (rawFragment != null) {
            sb.append('#')
            sb.append(rawFragment!!)
        }
        string = sb.toString()
    }


    // -- Parsing --

    // For convenience we wrap the input URI string in a new instance of the
    // following internal class.  This saves always having to pass the input
    // string as an argument to each internal scan/parse method.

    private inner class Parser internal constructor(
        private val input: String           // URI input string
    ) {
        private var requireServerAuthority = false


        // IPv6 address parsing, from RFC2373: IPv6 Addressing Architecture
        //
        // Bug: The grammar in RFC2373 Appendix B does not allow addresses of
        // the form ::12.34.56.78, which are clearly shown in the examples
        // earlier in the document.  Here is the original grammar:
        //
        //   IPv6address = hexpart [ ":" IPv4address ]
        //   hexpart     = hexseq | hexseq "::" [ hexseq ] | "::" [ hexseq ]
        //   hexseq      = hex4 *( ":" hex4)
        //   hex4        = 1*4HEXDIG
        //
        // We therefore use the following revised grammar:
        //
        //   IPv6address = hexseq [ ":" IPv4address ]
        //                 | hexseq [ "::" [ hexpost ] ]
        //                 | "::" [ hexpost ]
        //   hexpost     = hexseq | hexseq ":" IPv4address | IPv4address
        //   hexseq      = hex4 *( ":" hex4)
        //   hex4        = 1*4HEXDIG
        //
        // This covers all and only the following cases:
        //
        //   hexseq
        //   hexseq : IPv4address
        //   hexseq ::
        //   hexseq :: hexseq
        //   hexseq :: hexseq : IPv4address
        //   hexseq :: IPv4address
        //   :: hexseq
        //   :: hexseq : IPv4address
        //   :: IPv4address
        //   ::
        //
        // Additionally we constrain the IPv6 address as follows :-
        //
        //  i.  IPv6 addresses without compressed zeros should contain
        //      exactly 16 bytes.
        //
        //  ii. IPv6 addresses with compressed zeros should contain
        //      less than 16 bytes.

        private var ipv6byteCount = 0

        init {
            string = input
        }

        // -- Methods for throwing URISyntaxException in various ways --

        private fun fail(reason: String) {
            throw URISyntaxException(input, reason)
        }

        private fun fail(reason: String, p: Int) {
            throw URISyntaxException(input, reason, p)
        }

        private fun failExpecting(expected: String, p: Int) {
            fail("Expected $expected", p)
        }

        private fun failExpecting(expected: String, prior: String, p: Int) {
            fail("Expected $expected following $prior", p)
        }


        // -- Simple access to the input string --

        // Return a substring of the input string
        //
        private fun substring(start: Int, end: Int): String {
            return input.substring(start, end)
        }

        // Return the char at position p,
        // assuming that p < input.length()
        //
        private fun charAt(p: Int): Char {
            return input[p]
        }

        // Tells whether start < end and, if so, whether charAt(start) == c
        //
        private fun at(start: Int, end: Int, c: Char): Boolean {
            return start < end && charAt(start) == c
        }

        // Tells whether start + s.length() < end and, if so,
        // whether the chars at the start position match s exactly
        //
        private fun at(start: Int, end: Int, s: String): Boolean {
            var p = start
            val sn = s.length
            if (sn > end - p)
                return false
            var i = 0
            while (i < sn) {
                if (charAt(p++) != s[i]) {
                    break
                }
                i++
            }
            return i == sn
        }


        // -- Scanning --

        // The various scan and parse methods that follow use a uniform
        // convention of taking the current start position and end index as
        // their first two arguments.  The start is inclusive while the end is
        // exclusive, just as in the String class, i.e., a start/end pair
        // denotes the left-open interval [start, end) of the input string.
        //
        // These methods never proceed past the end position.  They may return
        // -1 to indicate outright failure, but more often they simply return
        // the position of the first char after the last char scanned.  Thus
        // a typical idiom is
        //
        //     int p = start;
        //     int q = scan(p, end, ...);
        //     if (q > p)
        //         // We scanned something
        //         ...;
        //     else if (q == p)
        //         // We scanned nothing
        //         ...;
        //     else if (q == -1)
        //         // Something went wrong
        //         ...;


        // Scan a specific char: If the char at the given start position is
        // equal to c, return the index of the next char; otherwise, return the
        // start position.
        //
        private fun scan(start: Int, end: Int, c: Char): Int {
            return if (start < end && charAt(start) == c) start + 1 else start
        }

        // Scan forward from the given start position.  Stop at the first char
        // in the err string (in which case -1 is returned), or the first char
        // in the stop string (in which case the index of the preceding char is
        // returned), or the end of the input string (in which case the length
        // of the input string is returned).  May return the start position if
        // nothing matches.
        //
        private fun scan(start: Int, end: Int, err: String, stop: String): Int {
            var p = start
            while (p < end) {
                val c = charAt(p)
                if (err.indexOf(c) >= 0)
                    return -1
                if (stop.indexOf(c) >= 0)
                    break
                p++
            }
            return p
        }

        // Scan a potential escape sequence, starting at the given position,
        // with the given first char (i.e., charAt(start) == c).
        //
        // This method assumes that if escapes are allowed then visible
        // non-US-ASCII chars are also allowed.
        //
        private fun scanEscape(start: Int, n: Int, first: Char): Int {
            if (first == '%') {
                // Process escape pair
                if (start + 3 <= n
                    && match(charAt(start + 1), L_HEX, H_HEX)
                    && match(charAt(start + 2), L_HEX, H_HEX)
                ) {
                    return start + 3
                }
                fail("Malformed escape pair", start)
            } else if (first.toInt() > 128
                && !first.isWhitespace()
                && !first.isISOControl()
            ) {
                // Allow unescaped but visible non-US-ASCII chars
                return start + 1
            }
            return start
        }

        // Scan chars that match the given mask pair
        //
        private fun scan(start: Int, n: Int, lowMask: Long, highMask: Long): Int {
            var p = start
            while (p < n) {
                val c = charAt(p)
                if (match(c, lowMask, highMask)) {
                    p++
                    continue
                }
                if (lowMask and L_ESCAPED != 0L) {
                    val q = scanEscape(p, n, c)
                    if (q > p) {
                        p = q
                        continue
                    }
                }
                break
            }
            return p
        }

        // Check that each of the chars in [start, end) matches the given mask
        //
        private fun checkChars(
            start: Int, end: Int,
            lowMask: Long, highMask: Long,
            what: String
        ) {
            val p = scan(start, end, lowMask, highMask)
            if (p < end)
                fail("Illegal character in $what", p)
        }

        // Check that the char at position p matches the given mask
        //
        private fun checkChar(
            p: Int,
            lowMask: Long, highMask: Long,
            what: String
        ) {
            checkChars(p, p + 1, lowMask, highMask, what)
        }


        // -- Parsing --

        // [<scheme>:]<scheme-specific-part>[#<fragment>]
        //
        internal fun parse(rsa: Boolean) {
            requireServerAuthority = rsa
            val ssp: Int                    // Start of scheme-specific part
            val n = input.length
            var p = scan(0, n, "/?#", ":")
            if (p >= 0 && at(p, n, ':')) {
                if (p == 0)
                    failExpecting("scheme name", 0)
                checkChar(0, L_ALPHA, H_ALPHA, "scheme name")
                checkChars(1, p, L_SCHEME, H_SCHEME, "scheme name")
                scheme = substring(0, p)
                p++                    // Skip ':'
                ssp = p
                if (at(p, n, '/')) {
                    p = parseHierarchical(p, n)
                } else {
                    val q = scan(p, n, "", "#")
                    if (q <= p)
                        failExpecting("scheme-specific part", p)
                    checkChars(p, q, L_URIC, H_URIC, "opaque part")
                    p = q
                }
            } else {
                ssp = 0
                p = parseHierarchical(0, n)
            }
            schemeSpecificPart = substring(ssp, p)
            if (at(p, n, '#')) {
                checkChars(p + 1, n, L_URIC, H_URIC, "fragment")
                rawFragment = substring(p + 1, n)
                p = n
            }
            if (p < n)
                fail("end of URI", p)
        }

        // [//authority]<path>[?<query>]
        //
        // DEVIATION from RFC2396: We allow an empty authority component as
        // long as it's followed by a non-empty path, query component, or
        // fragment component.  This is so that URIs such as "file:///foo/bar"
        // will parse.  This seems to be the intent of RFC2396, though the
        // grammar does not permit it.  If the authority is empty then the
        // userInfo, host, and port components are undefined.
        //
        // DEVIATION from RFC2396: We allow empty relative paths.  This seems
        // to be the intent of RFC2396, but the grammar does not permit it.
        // The primary consequence of this deviation is that "#f" parses as a
        // relative URI with an empty path.
        //
        private fun parseHierarchical(start: Int, n: Int): Int {
            var p = start
            if (at(p, n, '/') && at(p + 1, n, '/')) {
                p += 2
                val q = scan(p, n, "", "/?#")
                if (q > p) {
                    p = parseAuthority(p, q)
                } else if (q < n) {
                    // DEVIATION: Allow empty authority prior to non-empty
                    // path, query component or fragment identifier
                } else
                    failExpecting("authority", p)
            }
            var q = scan(p, n, "", "?#") // DEVIATION: May be empty
            checkChars(p, q, L_PATH, H_PATH, "path")
            rawPath = substring(p, q)
            p = q
            if (at(p, n, '?')) {
                p++
                q = scan(p, n, "", "#")
                checkChars(p, q, L_URIC, H_URIC, "query")
                rawQuery = substring(p, q)
                p = q
            }
            return p
        }

        // authority     = server | reg_name
        //
        // Ambiguity: An authority that is a registry name rather than a server
        // might have a prefix that parses as a server.  We use the fact that
        // the authority component is always followed by '/' or the end of the
        // input string to resolve this: If the complete authority did not
        // parse as a server then we try to parse it as a registry name.
        //
        private fun parseAuthority(start: Int, n: Int): Int {
            var q = start
            var ex: URISyntaxException? = null

            val serverChars: Boolean
            val regChars: Boolean

            if (scan(start, n, "", "]") > start) {
                // contains a literal IPv6 address, therefore % is allowed
                serverChars = scan(start, n, L_SERVER_PERCENT, H_SERVER_PERCENT) == n
            } else {
                serverChars = scan(start, n, L_SERVER, H_SERVER) == n
            }
            regChars = scan(start, n, L_REG_NAME, H_REG_NAME) == n

            if (regChars && !serverChars) {
                // Must be a registry-based authority
                rawAuthority = substring(start, n)
                return n
            }

            if (serverChars) {
                // Might be (probably is) a server-based authority, so attempt
                // to parse it as such.  If the attempt fails, try to treat it
                // as a registry-based authority.
                try {
                    q = parseServer(start, n)
                    if (q < n)
                        failExpecting("end of authority", q)
                    rawAuthority = substring(start, n)
                } catch (x: URISyntaxException) {
                    // Undo results of failed parse
                    rawUserInfo = null
                    host = null
                    port = -1
                    if (requireServerAuthority) {
                        // If we're insisting upon a server-based authority,
                        // then just re-throw the exception
                        throw x
                    } else {
                        // Save the exception in case it doesn't parse as a
                        // registry either
                        ex = x
                        q = start
                    }
                }

            }

            if (q < n) {
                if (regChars) {
                    // Registry-based authority
                    rawAuthority = substring(start, n)
                } else if (ex != null) {
                    // Re-throw exception; it was probably due to
                    // a malformed IPv6 address
                    throw ex
                } else {
                    fail("Illegal character in authority", q)
                }
            }

            return n
        }


        // [<userinfo>@]<host>[:<port>]
        //
        private fun parseServer(start: Int, n: Int): Int {
            var p = start
            var q: Int

            // userinfo
            q = scan(p, n, "/?#", "@")
            if (q >= p && at(q, n, '@')) {
                checkChars(p, q, L_USERINFO, H_USERINFO, "user info")
                rawUserInfo = substring(p, q)
                p = q + 1              // Skip '@'
            }

            // hostname, IPv4 address, or IPv6 address
            if (at(p, n, '[')) {
                // DEVIATION from RFC2396: Support IPv6 addresses, per RFC2732
                p++
                q = scan(p, n, "/?#", "]")
                if (q > p && at(q, n, ']')) {
                    // look for a "%" scope id
                    val r = scan(p, q, "", "%")
                    if (r > p) {
                        parseIPv6Reference(p, r)
                        if (r + 1 == q) {
                            fail("scope id expected")
                        }
                        checkChars(
                            r + 1, q, L_ALPHANUM, H_ALPHANUM,
                            "scope id"
                        )
                    } else {
                        parseIPv6Reference(p, q)
                    }
                    host = substring(p - 1, q + 1)
                    p = q + 1
                } else {
                    failExpecting("closing bracket for IPv6 address", q)
                }
            } else {
                q = parseIPv4Address(p, n)
                if (q <= p)
                    q = parseHostname(p, n)
                p = q
            }

            // port
            if (at(p, n, ':')) {
                p++
                q = scan(p, n, "", "/")
                if (q > p) {
                    checkChars(p, q, L_DIGIT, H_DIGIT, "port number")
                    try {
                        port = substring(p, q).toInt()
                    } catch (x: NumberFormatException) {
                        fail("Malformed port number", p)
                    }

                    p = q
                }
            }
            if (p < n)
                failExpecting("port number", p)

            return p
        }

        // Scan a string of decimal digits whose value fits in a byte
        //
        private fun scanByte(start: Int, n: Int): Int {
            val q = scan(start, n, L_DIGIT, H_DIGIT)
            if (q <= start) return q
            return if (substring(start, q).toInt() > 255) start else q
        }

        // Scan an IPv4 address.
        //
        // If the strict argument is true then we require that the given
        // interval contain nothing besides an IPv4 address; if it is false
        // then we only require that it start with an IPv4 address.
        //
        // If the interval does not contain or start with (depending upon the
        // strict argument) a legal IPv4 address characters then we return -1
        // immediately; otherwise we insist that these characters parse as a
        // legal IPv4 address and throw an exception on failure.
        //
        // We assume that any string of decimal digits and dots must be an IPv4
        // address.  It won't parse as a hostname anyway, so making that
        // assumption here allows more meaningful exceptions to be thrown.
        //
        private fun scanIPv4Address(start: Int, n: Int, strict: Boolean): Int {
            var p = start
            var q: Int
            val m = scan(p, n, L_DIGIT or L_DOT, H_DIGIT or H_DOT)
            if (m <= p || strict && m != n)
                return -1
            while (true) {
                // Per RFC2732: At most three digits per byte
                // Further constraint: Each element fits in a byte
                q = scanByte(p, m)
                if (q <= p) break
                p = q
                q = scan(p, m, '.')
                if (q <= p) break
                p = q
                q = scanByte(p, m)
                if (q <= p) break
                p = q
                q = scan(p, m, '.')
                if (q <= p) break
                p = q
                q = scanByte(p, m)
                if (q <= p) break
                p = q
                q = scan(p, m, '.')
                if (q <= p) break
                p = q
                q = scanByte(p, m)
                if (q <= p) break
                p = q
                if (q < m) break
                return q
            }
            fail("Malformed IPv4 address", q)
            return -1
        }

        // Take an IPv4 address: Throw an exception if the given interval
        // contains anything except an IPv4 address
        //
        private fun takeIPv4Address(start: Int, n: Int, expected: String): Int {
            val p = scanIPv4Address(start, n, true)
            if (p <= start)
                failExpecting(expected, start)
            return p
        }

        // Attempt to parse an IPv4 address, returning -1 on failure but
        // allowing the given interval to contain [:<characters>] after
        // the IPv4 address.
        //
        private fun parseIPv4Address(start: Int, n: Int): Int {
            var p: Int

            try {
                p = scanIPv4Address(start, n, false)
            } catch (x: URISyntaxException) {
                return -1
            } catch (nfe: NumberFormatException) {
                return -1
            }

            if (p > start && p < n) {
                // IPv4 address is followed by something - check that
                // it's a ":" as this is the only valid character to
                // follow an address.
                if (charAt(p) != ':') {
                    p = -1
                }
            }

            if (p > start)
                host = substring(start, p)

            return p
        }

        // hostname      = domainlabel [ "." ] | 1*( domainlabel "." ) toplabel [ "." ]
        // domainlabel   = alphanum | alphanum *( alphanum | "-" ) alphanum
        // toplabel      = alpha | alpha *( alphanum | "-" ) alphanum
        //
        private fun parseHostname(start: Int, n: Int): Int {
            var p = start
            var q: Int
            var l = -1                 // Start of last parsed label

            do {
                // domainlabel = alphanum [ *( alphanum | "-" ) alphanum ]
                q = scan(p, n, L_ALPHANUM, H_ALPHANUM)
                if (q <= p)
                    break
                l = p
                if (q > p) {
                    p = q
                    q = scan(p, n, L_ALPHANUM or L_DASH, H_ALPHANUM or H_DASH)
                    if (q > p) {
                        if (charAt(q - 1) == '-')
                            fail("Illegal character in hostname", q - 1)
                        p = q
                    }
                }
                q = scan(p, n, '.')
                if (q <= p)
                    break
                p = q
            } while (p < n)

            if (p < n && !at(p, n, ':'))
                fail("Illegal character in hostname", p)

            if (l < 0)
                failExpecting("hostname", start)

            // for a fully qualified hostname check that the rightmost
            // label starts with an alpha character.
            if (l > start && !match(charAt(l), L_ALPHA, H_ALPHA)) {
                fail("Illegal character in hostname", l)
            }

            host = substring(start, p)
            return p
        }

        private fun parseIPv6Reference(start: Int, n: Int): Int {
            var p = start
            val q: Int
            var compressedZeros = false

            q = scanHexSeq(p, n)

            if (q > p) {
                p = q
                if (at(p, n, "::")) {
                    compressedZeros = true
                    p = scanHexPost(p + 2, n)
                } else if (at(p, n, ':')) {
                    p = takeIPv4Address(p + 1, n, "IPv4 address")
                    ipv6byteCount += 4
                }
            } else if (at(p, n, "::")) {
                compressedZeros = true
                p = scanHexPost(p + 2, n)
            }
            if (p < n)
                fail("Malformed IPv6 address", start)
            if (ipv6byteCount > 16)
                fail("IPv6 address too long", start)
            if (!compressedZeros && ipv6byteCount < 16)
                fail("IPv6 address too short", start)
            if (compressedZeros && ipv6byteCount == 16)
                fail("Malformed IPv6 address", start)

            return p
        }

        private fun scanHexPost(start: Int, n: Int): Int {
            var p = start
            val q: Int

            if (p == n)
                return p

            q = scanHexSeq(p, n)
            if (q > p) {
                p = q
                if (at(p, n, ':')) {
                    p++
                    p = takeIPv4Address(p, n, "hex digits or IPv4 address")
                    ipv6byteCount += 4
                }
            } else {
                p = takeIPv4Address(p, n, "hex digits or IPv4 address")
                ipv6byteCount += 4
            }
            return p
        }

        // Scan a hex sequence; return -1 if one could not be scanned
        //
        private fun scanHexSeq(start: Int, n: Int): Int {
            var p = start
            var q: Int

            q = scan(p, n, L_HEX, H_HEX)
            if (q <= p)
                return -1
            if (at(q, n, '.'))
            // Beginning of IPv4 address
                return -1
            if (q > p + 4)
                fail("IPv6 hexadecimal digit sequence too long", p)
            ipv6byteCount += 2
            p = q
            while (p < n) {
                if (!at(p, n, ':'))
                    break
                if (at(p + 1, n, ':'))
                    break              // "::"
                p++
                q = scan(p, n, L_HEX, H_HEX)
                if (q <= p)
                    failExpecting("digits for an IPv6 address", p)
                if (at(q, n, '.')) {    // Beginning of IPv4 address
                    p--
                    break
                }
                if (q > p + 4)
                    fail("IPv6 hexadecimal digit sequence too long", p)
                ipv6byteCount += 2
                p = q
            }

            return p
        }

    }

    companion object {

        // Note: Comments containing the word "ASSERT" indicate places where a
        // throw of an InternalError should be replaced by an appropriate assertion
        // statement once asserts are enabled in the build.

        internal const val serialVersionUID = -6052424284110960213L

        /**
         * Creates a URI by parsing the given string.
         *
         *
         *  This convenience factory method works as if by invoking the [ ][.URI] constructor; any [URISyntaxException] thrown by the
         * constructor is caught and wrapped in a new [ ] object, which is then thrown.
         *
         *
         *  This method is provided for use in situations where it is known that
         * the given string is a legal URI, for example for URI constants declared
         * within in a program, and so it would be considered a programming error
         * for the string not to parse as such.  The constructors, which throw
         * [URISyntaxException] directly, should be used situations where a
         * URI is being constructed from user input or from some other source that
         * may be prone to errors.
         *
         * @param  str   The string to be parsed into a URI
         * @return The new URI
         *
         * @throws  NullPointerException
         * If `str` is `null`
         *
         * @throws  IllegalArgumentException
         * If the given string violates RFC&nbsp;2396
         */
        fun create(str: String): URI {
            try {
                return URI(str)
            } catch (x: URISyntaxException) {
                throw IllegalArgumentException(x.message, x)
            }

        }


        // -- End of public methods --


        // -- Utility methods for string-field comparison and hashing --

        // These methods return appropriate values for null string arguments,
        // thereby simplifying the equals, hashCode, and compareTo methods.
        //
        // The case-ignoring methods should only be applied to strings whose
        // characters are all known to be US-ASCII.  Because of this restriction,
        // these methods are faster than the similar methods in the String class.

        // US-ASCII only
        private fun toLower(c: Char): Int {
            return if (c >= 'A' && c <= 'Z') c.toInt() + ('a' - 'A') else c.toInt()
        }

        // US-ASCII only
        private fun toUpper(c: Char): Int {
            return if (c >= 'a' && c <= 'z') c.toInt() - ('a' - 'A') else c.toInt()
        }

        private fun equal(s: String?, t: String?): Boolean {
            if (s === t) return true
            if (s != null && t != null) {
                if (s.length != t.length)
                    return false
                if (s.indexOf('%') < 0)
                    return s == t
                val n = s.length
                var i = 0
                while (i < n) {
                    val c = s[i]
                    val d = t[i]
                    if (c != '%') {
                        if (c != d)
                            return false
                        i++
                        continue
                    }
                    if (d != '%')
                        return false
                    i++
                    if (toLower(s[i]) != toLower(t[i]))
                        return false
                    i++
                    if (toLower(s[i]) != toLower(t[i]))
                        return false
                    i++
                }
                return true
            }
            return false
        }

        // US-ASCII only
        private fun equalIgnoringCase(s: String?, t: String?): Boolean {
            if (s === t) return true
            if (s != null && t != null) {
                val n = s.length
                if (t.length != n)
                    return false
                for (i in 0 until n) {
                    if (toLower(s[i]) != toLower(t[i]))
                        return false
                }
                return true
            }
            return false
        }

        private fun hash(hash: Int, s: String?): Int {
            if (s == null) return hash
            return if (s.indexOf('%') < 0)
                hash * 127 + s.hashCode()
            else
                normalizedHash(hash, s)
        }


        private fun normalizedHash(hash: Int, s: String): Int {
            var h = 0
            var index = 0
            while (index < s.length) {
                val ch = s[index]
                h = 31 * h + ch.toInt()
                if (ch == '%') {
                    /*
                 * Process the next two encoded characters
                 */
                    for (i in index + 1 until index + 3)
                        h = 31 * h + toUpper(s[i])
                    index += 2
                }
                index++
            }
            return hash * 127 + h
        }

        // US-ASCII only
        private fun hashIgnoringCase(hash: Int, s: String?): Int {
            if (s == null) return hash
            var h = hash
            val n = s.length
            for (i in 0 until n)
                h = 31 * h + toLower(s[i])
            return h
        }

        private fun compare(s: String?, t: String?): Int {
            if (s === t) return 0
            return if (s != null) {
                if (t != null)
                    s.compareTo(t)
                else
                    +1
            } else {
                -1
            }
        }

        // US-ASCII only
        private fun compareIgnoringCase(s: String?, t: String?): Int {
            if (s === t) return 0
            if (s != null) {
                if (t != null) {
                    val sn = s.length
                    val tn = t.length
                    val n = if (sn < tn) sn else tn
                    for (i in 0 until n) {
                        val c = toLower(s[i]) - toLower(t[i])
                        if (c != 0)
                            return c
                    }
                    return sn - tn
                }
                return +1
            } else {
                return -1
            }
        }


        // -- String construction --

        // If a scheme is given then the path, if given, must be absolute
        //
        private fun checkPath(s: String, scheme: String?, path: String?) {
            if (scheme != null) {
                if (path != null && path.length > 0 && path[0] != '/')
                    throw URISyntaxException(
                        s,
                        "Relative path in absolute URI"
                    )
            }
        }


        // -- Normalization, resolution, and relativization --

        // RFC2396 5.2 (6)
        private fun resolvePath(
            base: String, child: String,
            absolute: Boolean
        ): String {
            val i = base.lastIndexOf('/')
            val cn = child.length
            var path = ""

            if (cn == 0) {
                // 5.2 (6a)
                if (i >= 0)
                    path = base.substring(0, i + 1)
            } else {
                val sb = StringBuffer(base.length + cn)
                // 5.2 (6a)
                if (i >= 0)
                    sb.append(base.substring(0, i + 1))
                // 5.2 (6b)
                sb.append(child)
                path = sb.toString()
            }

            // 5.2 (6c-f)

            // 5.2 (6g): If the result is absolute but the path begins with "../",
            // then we simply leave the path as-is

            return normalize(path)
        }

        // RFC2396 5.2
        private fun resolve(base: URI, child: URI): URI {
            // check if child if opaque first so that NPE is thrown
            // if child is null.
            if (child.isOpaque || base.isOpaque)
                return child

            // 5.2 (2): Reference to current document (lone fragment)
            if (child.scheme == null && child.rawAuthority == null
                && child.rawPath == "" && child.rawFragment != null
                && child.rawQuery == null
            ) {
                if (base.rawFragment != null && child.rawFragment == base.rawFragment) {
                    return base
                }
                val ru = URI()
                ru.scheme = base.scheme
                ru.rawAuthority = base.rawAuthority
                ru.rawUserInfo = base.rawUserInfo
                ru.host = base.host
                ru.port = base.port
                ru.rawPath = base.rawPath
                ru.rawFragment = child.rawFragment
                ru.rawQuery = base.rawQuery
                return ru
            }

            // 5.2 (3): Child is absolute
            if (child.scheme != null)
                return child

            val ru = URI()             // Resolved URI
            ru.scheme = base.scheme
            ru.rawQuery = child.rawQuery
            ru.rawFragment = child.rawFragment

            // 5.2 (4): Authority
            if (child.rawAuthority == null) {
                ru.rawAuthority = base.rawAuthority
                ru.host = base.host
                ru.rawUserInfo = base.rawUserInfo
                ru.port = base.port

                val cp = if (child.rawPath == null) "" else child.rawPath
                if (cp!!.length > 0 && cp[0] == '/') {
                    // 5.2 (5): Child path is absolute
                    ru.rawPath = child.rawPath
                } else {
                    // 5.2 (6): Resolve relative path
                    ru.rawPath = resolvePath(base.rawPath!!, cp, base.isAbsolute)
                }
            } else {
                ru.rawAuthority = child.rawAuthority
                ru.host = child.host
                ru.rawUserInfo = child.rawUserInfo
                ru.host = child.host
                ru.port = child.port
                ru.rawPath = child.rawPath
            }

            // 5.2 (7): Recombine (nothing to do here)
            return ru
        }

        // If the given URI's path is normal then return the URI;
        // o.w., return a new URI containing the normalized path.
        //
        private fun normalize(u: URI): URI {
            if (u.isOpaque || u.rawPath == null || u.rawPath!!.length == 0)
                return u

            val np = normalize(u.rawPath)
            if (np === u.rawPath)
                return u

            val v = URI()
            v.scheme = u.scheme
            v.rawFragment = u.rawFragment
            v.rawAuthority = u.rawAuthority
            v.rawUserInfo = u.rawUserInfo
            v.host = u.host
            v.port = u.port
            v.rawPath = np
            v.rawQuery = u.rawQuery
            return v
        }

        // If both URIs are hierarchical, their scheme and authority components are
        // identical, and the base path is a prefix of the child's path, then
        // return a relative URI that, when resolved against the base, yields the
        // child; otherwise, return the child.
        //
        private fun relativize(base: URI, child: URI): URI {
            // check if child if opaque first so that NPE is thrown
            // if child is null.
            if (child.isOpaque || base.isOpaque)
                return child
            if (!equalIgnoringCase(base.scheme, child.scheme) || !equal(base.rawAuthority, child.rawAuthority))
                return child

            var bp = normalize(base.rawPath)
            val cp = normalize(child.rawPath)
            if (bp != cp) {
                if (!bp.endsWith("/"))
                    bp = "$bp/"
                if (!cp.startsWith(bp))
                    return child
            }

            val v = URI()
            v.rawPath = cp.substring(bp.length)
            v.rawQuery = child.rawQuery
            v.rawFragment = child.rawFragment
            return v
        }


        // -- Path normalization --

        // The following algorithm for path normalization avoids the creation of a
        // string object for each segment, as well as the use of a string buffer to
        // compute the final result, by using a single char array and editing it in
        // place.  The array is first split into segments, replacing each slash
        // with '\0' and creating a segment-index array, each element of which is
        // the index of the first char in the corresponding segment.  We then walk
        // through both arrays, removing ".", "..", and other segments as necessary
        // by setting their entries in the index array to -1.  Finally, the two
        // arrays are used to rejoin the segments and compute the final result.
        //
        // This code is based upon src/solaris/native/java/io/canonicalize_md.c


        // Check the given path to see if it might need normalization.  A path
        // might need normalization if it contains duplicate slashes, a "."
        // segment, or a ".." segment.  Return -1 if no further normalization is
        // possible, otherwise return the number of segments found.
        //
        // This method takes a string argument rather than a char array so that
        // this test can be performed without invoking path.toCharArray().
        //
        private fun needsNormalization(path: String): Int {
            var normal = true
            var ns = 0                     // Number of segments
            val end = path.length - 1    // Index of last char in path
            var p = 0                      // Index of next char in path

            // Skip initial slashes
            while (p <= end) {
                if (path[p] != '/') break
                p++
            }
            if (p > 1) normal = false

            // Scan segments
            while (p <= end) {

                // Looking at "." or ".." ?
                if (path[p] == '.' && (p == end || path[p + 1] == '/' || path[p + 1] == '.' && (p + 1 == end || path[p + 2] == '/'))) {
                    normal = false
                }
                ns++

                // Find beginning of next segment
                while (p <= end) {
                    if (path[p++] != '/')
                        continue

                    // Skip redundant slashes
                    while (p <= end) {
                        if (path[p] != '/') break
                        normal = false
                        p++
                    }

                    break
                }
            }

            return if (normal) -1 else ns
        }


        // Split the given path into segments, replacing slashes with nulls and
        // filling in the given segment-index array.
        //
        // Preconditions:
        //   segs.length == Number of segments in path
        //
        // Postconditions:
        //   All slashes in path replaced by '\0'
        //   segs[i] == Index of first char in segment i (0 <= i < segs.length)
        //
        private fun split(path: CharArray, segs: IntArray) {
            val end = path.size - 1      // Index of last char in path
            var p = 0                      // Index of next char in path
            var i = 0                      // Index of current segment

            // Skip initial slashes
            while (p <= end) {
                if (path[p] != '/') break
                path[p] = '\u0000'
                p++
            }

            while (p <= end) {

                // Note start of segment
                segs[i++] = p++

                // Find beginning of next segment
                while (p <= end) {
                    if (path[p++] != '/')
                        continue
                    path[p - 1] = '\u0000'

                    // Skip redundant slashes
                    while (p <= end) {
                        if (path[p] != '/') break
                        path[p++] = '\u0000'
                    }
                    break
                }
            }

            if (i != segs.size)
                throw AssertionError("Internal Error in URI")  // ASSERT
        }


        // Join the segments in the given path according to the given segment-index
        // array, ignoring those segments whose index entries have been set to -1,
        // and inserting slashes as needed.  Return the length of the resulting
        // path.
        //
        // Preconditions:
        //   segs[i] == -1 implies segment i is to be ignored
        //   path computed by split, as above, with '\0' having replaced '/'
        //
        // Postconditions:
        //   path[0] .. path[return value] == Resulting path
        //
        private fun join(path: CharArray, segs: IntArray): Int {
            val ns = segs.size           // Number of segments
            val end = path.size - 1      // Index of last char in path
            var p = 0                      // Index of next path char to write

            if (path[p] == '\u0000') {
                // Restore initial slash for absolute paths
                path[p++] = '/'
            }

            for (i in 0 until ns) {
                var q = segs[i]            // Current segment
                if (q == -1)
                // Ignore this segment
                    continue

                if (p == q) {
                    // We're already at this segment, so just skip to its end
                    while (p <= end && path[p] != '\u0000')
                        p++
                    if (p <= end) {
                        // Preserve trailing slash
                        path[p++] = '/'
                    }
                } else if (p < q) {
                    // Copy q down to p
                    while (q <= end && path[q] != '\u0000')
                        path[p++] = path[q++]
                    if (q <= end) {
                        // Preserve trailing slash
                        path[p++] = '/'
                    }
                } else
                    throw AssertionError("Internal Error in URI")  // ASSERT
            }

            return p
        }


        // Remove "." segments from the given path, and remove segment pairs
        // consisting of a non-".." segment followed by a ".." segment.
        //
        private fun removeDots(path: CharArray, segs: IntArray) {
            val ns = segs.size
            val end = path.size - 1

            var i = 0
            while (i < ns) {
                var dots = 0               // Number of dots found (0, 1, or 2)

                // Find next occurrence of "." or ".."
                do {
                    val p = segs[i]
                    if (path[p] == '.') {
                        if (p == end) {
                            dots = 1
                            break
                        } else if (path[p + 1] == '\u0000') {
                            dots = 1
                            break
                        } else if (path[p + 1] == '.' && (p + 1 == end || path[p + 2] == '\u0000')) {
                            dots = 2
                            break
                        }
                    }
                    i++
                } while (i < ns)
                if (i > ns || dots == 0)
                    break

                if (dots == 1) {
                    // Remove this occurrence of "."
                    segs[i] = -1
                } else {
                    // If there is a preceding non-".." segment, remove both that
                    // segment and this occurrence of ".."; otherwise, leave this
                    // ".." segment as-is.
                    var j: Int
                    j = i - 1
                    while (j >= 0) {
                        if (segs[j] != -1) break
                        j--
                    }
                    if (j >= 0) {
                        val q = segs[j]
                        if (!(path[q] == '.'
                                    && path[q + 1] == '.'
                                    && path[q + 2] == '\u0000')
                        ) {
                            segs[i] = -1
                            segs[j] = -1
                        }
                    }
                }
                i++
            }
        }


        // DEVIATION: If the normalized path is relative, and if the first
        // segment could be parsed as a scheme name, then prepend a "." segment
        //
        private fun maybeAddLeadingDot(path: CharArray, segs: IntArray) {

            if (path[0] == '\u0000')
            // The path is absolute
                return

            val ns = segs.size
            var f = 0                      // Index of first segment
            while (f < ns) {
                if (segs[f] >= 0)
                    break
                f++
            }
            if (f >= ns || f == 0)
            // The path is empty, or else the original first segment survived,
            // in which case we already know that no leading "." is needed
                return

            var p = segs[f]
            while (p < path.size && path[p] != ':' && path[p] != '\u0000') p++
            if (p >= path.size || path[p] == '\u0000')
            // No colon in first segment, so no "." needed
                return

            // At this point we know that the first segment is unused,
            // hence we can insert a "." segment at that position
            path[0] = '.'
            path[1] = '\u0000'
            segs[0] = 0
        }


        // Normalize the given path string.  A normal path string has no empty
        // segments (i.e., occurrences of "//"), no segments equal to ".", and no
        // segments equal to ".." that are preceded by a segment not equal to "..".
        // In contrast to Unix-style pathname normalization, for URI paths we
        // always retain trailing slashes.
        //
        private fun normalize(ps: String?): String {

            // Does this path need normalization?
            val ns = needsNormalization(ps!!)        // Number of segments
            if (ns < 0)
            // Nope -- just return it
                return ps

            val path = ps.toCharArray()         // Path in char-array form

            // Split path into segments
            val segs = IntArray(ns)               // Segment-index array
            split(path, segs)

            // Remove dots
            removeDots(path, segs)

            // Prevent scheme-name confusion
            maybeAddLeadingDot(path, segs)

            // Join the remaining segments and return the result
            val s = String(path, 0, join(path, segs))
            return if (s == ps) {
                // string was already normalized
                ps
            } else s
        }


        // -- Character classes for parsing --

        // RFC2396 precisely specifies which characters in the US-ASCII charset are
        // permissible in the various components of a URI reference.  We here
        // define a set of mask pairs to aid in enforcing these restrictions.  Each
        // mask pair consists of two longs, a low mask and a high mask.  Taken
        // together they represent a 128-bit mask, where bit i is set iff the
        // character with value i is permitted.
        //
        // This approach is more efficient than sequentially searching arrays of
        // permitted characters.  It could be made still more efficient by
        // precompiling the mask information so that a character's presence in a
        // given mask could be determined by a single table lookup.

        // Compute the low-order mask for the characters in the given string
        private fun lowMask(chars: String): Long {
            val n = chars.length
            var m: Long = 0
            for (i in 0 until n) {
                val c = chars[i]
                if (c.toInt() < 64)
                    m = m or (1L shl c.toInt())
            }
            return m
        }

        // Compute the high-order mask for the characters in the given string
        private fun highMask(chars: String): Long {
            val n = chars.length
            var m: Long = 0
            for (i in 0 until n) {
                val c = chars[i]
                if (c.toInt() >= 64 && c.toInt() < 128)
                    m = m or (1L shl c.toInt() - 64)
            }
            return m
        }

        // Compute a low-order mask for the characters
        // between first and last, inclusive
        private fun lowMask(first: Char, last: Char): Long {
            var m: Long = 0
            val f = max(min(first.toInt(), 63), 0)
            val l = max(min(last.toInt(), 63), 0)
            for (i in f..l)
                m = m or (1L shl i)
            return m
        }

        // Compute a high-order mask for the characters
        // between first and last, inclusive
        private fun highMask(first: Char, last: Char): Long {
            var m: Long = 0
            val f = max(min(first.toInt(), 127), 64) - 64
            val l = max(min(last.toInt(), 127), 64) - 64
            for (i in f..l)
                m = m or (1L shl i)
            return m
        }

        // Tell whether the given character is permitted by the given mask pair
        private fun match(c: Char, lowMask: Long, highMask: Long): Boolean {
            if (c.toInt() == 0)
            // 0 doesn't have a slot in the mask. So, it never matches.
                return false
            if (c.toInt() < 64)
                return 1L shl c.toInt() and lowMask != 0L
            return if (c.toInt() < 128) 1L shl c.toInt() - 64 and highMask != 0L else false
        }

        // Character-class masks, in reverse order from RFC2396 because
        // initializers for static fields cannot make forward references.

        // digit    = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" |
        //            "8" | "9"
        private val L_DIGIT = lowMask('0', '9')
        private val H_DIGIT = 0L

        // upalpha  = "A" | "B" | "C" | "D" | "E" | "F" | "G" | "H" | "I" |
        //            "J" | "K" | "L" | "M" | "N" | "O" | "P" | "Q" | "R" |
        //            "S" | "T" | "U" | "V" | "W" | "X" | "Y" | "Z"
        private val L_UPALPHA = 0L
        private val H_UPALPHA = highMask('A', 'Z')

        // lowalpha = "a" | "b" | "c" | "d" | "e" | "f" | "g" | "h" | "i" |
        //            "j" | "k" | "l" | "m" | "n" | "o" | "p" | "q" | "r" |
        //            "s" | "t" | "u" | "v" | "w" | "x" | "y" | "z"
        private val L_LOWALPHA = 0L
        private val H_LOWALPHA = highMask('a', 'z')

        // alpha         = lowalpha | upalpha
        private val L_ALPHA = L_LOWALPHA or L_UPALPHA
        private val H_ALPHA = H_LOWALPHA or H_UPALPHA

        // alphanum      = alpha | digit
        private val L_ALPHANUM = L_DIGIT or L_ALPHA
        private val H_ALPHANUM = H_DIGIT or H_ALPHA

        // hex           = digit | "A" | "B" | "C" | "D" | "E" | "F" |
        //                         "a" | "b" | "c" | "d" | "e" | "f"
        private val L_HEX = L_DIGIT
        private val H_HEX = highMask('A', 'F') or highMask('a', 'f')

        // mark          = "-" | "_" | "." | "!" | "~" | "*" | "'" |
        //                 "(" | ")"
        private val L_MARK = lowMask("-_.!~*'()")
        private val H_MARK = highMask("-_.!~*'()")

        // unreserved    = alphanum | mark
        private val L_UNRESERVED = L_ALPHANUM or L_MARK
        private val H_UNRESERVED = H_ALPHANUM or H_MARK

        // reserved      = ";" | "/" | "?" | ":" | "@" | "&" | "=" | "+" |
        //                 "$" | "," | "[" | "]"
        // Added per RFC2732: "[", "]"
        private val L_RESERVED = lowMask(";/?:@&=+$,[]")
        private val H_RESERVED = highMask(";/?:@&=+$,[]")

        // The zero'th bit is used to indicate that escape pairs and non-US-ASCII
        // characters are allowed; this is handled by the scanEscape method below.
        private val L_ESCAPED = 1L
        private val H_ESCAPED = 0L

        // uric          = reserved | unreserved | escaped
        private val L_URIC = L_RESERVED or L_UNRESERVED or L_ESCAPED
        private val H_URIC = H_RESERVED or H_UNRESERVED or H_ESCAPED

        // pchar         = unreserved | escaped |
        //                 ":" | "@" | "&" | "=" | "+" | "$" | ","
        private val L_PCHAR = L_UNRESERVED or L_ESCAPED or lowMask(":@&=+$,")
        private val H_PCHAR = H_UNRESERVED or H_ESCAPED or highMask(":@&=+$,")

        // All valid path characters
        private val L_PATH = L_PCHAR or lowMask(";/")
        private val H_PATH = H_PCHAR or highMask(";/")

        // Dash, for use in domainlabel and toplabel
        private val L_DASH = lowMask("-")
        private val H_DASH = highMask("-")

        // Dot, for use in hostnames
        private val L_DOT = lowMask(".")
        private val H_DOT = highMask(".")

        // userinfo      = *( unreserved | escaped |
        //                    ";" | ":" | "&" | "=" | "+" | "$" | "," )
        private val L_USERINFO = L_UNRESERVED or L_ESCAPED or lowMask(";:&=+$,")
        private val H_USERINFO = H_UNRESERVED or H_ESCAPED or highMask(";:&=+$,")

        // reg_name      = 1*( unreserved | escaped | "$" | "," |
        //                     ";" | ":" | "@" | "&" | "=" | "+" )
        private val L_REG_NAME = L_UNRESERVED or L_ESCAPED or lowMask("$,;:@&=+")
        private val H_REG_NAME = H_UNRESERVED or H_ESCAPED or highMask("$,;:@&=+")

        // All valid characters for server-based authorities
        private val L_SERVER = L_USERINFO or L_ALPHANUM or L_DASH or lowMask(".:@[]")
        private val H_SERVER = H_USERINFO or H_ALPHANUM or H_DASH or highMask(".:@[]")

        // Special case of server authority that represents an IPv6 address
        // In this case, a % does not signify an escape sequence
        private val L_SERVER_PERCENT = L_SERVER or lowMask("%")
        private val H_SERVER_PERCENT = H_SERVER or highMask("%")
        private val L_LEFT_BRACKET = lowMask("[")
        private val H_LEFT_BRACKET = highMask("[")

        // scheme        = alpha *( alpha | digit | "+" | "-" | "." )
        private val L_SCHEME = L_ALPHA or L_DIGIT or lowMask("+-.")
        private val H_SCHEME = H_ALPHA or H_DIGIT or highMask("+-.")

        // uric_no_slash = unreserved | escaped | ";" | "?" | ":" | "@" |
        //                 "&" | "=" | "+" | "$" | ","
        private val L_URIC_NO_SLASH = L_UNRESERVED or L_ESCAPED or lowMask(";?:@&=+$,")
        private val H_URIC_NO_SLASH = H_UNRESERVED or H_ESCAPED or highMask(";?:@&=+$,")


        // -- Escaping and encoding --

        private val hexDigits =
            charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

        private fun appendEscape(sb: StringBuffer, b: Byte) {
            sb.append('%')
            sb.append(hexDigits[b.toInt() shr 4 and 0x0f])
            sb.append(hexDigits[b.toInt() shr 0 and 0x0f])
        }

        private fun appendEncoded(sb: StringBuffer, c: Char) {
            val bb: ByteBuffer = createByteBuffer(c.toString().encodeToByteArray())

            while (bb!!.hasRemaining()) {
                val b = bb.get().toInt() and 0xff
                if (b >= 0x80)
                    appendEscape(sb, b.toByte())
                else
                    sb.append(b.toChar())
            }
        }

        // Quote any characters in s that are not permitted
        // by the given mask pair
        //
        private fun quote(s: String, lowMask: Long, highMask: Long): String {
            val n = s.length
            var sb: StringBuffer? = null
            val allowNonASCII = lowMask and L_ESCAPED != 0L
            for (i in 0 until s.length) {
                val c = s[i]
                if (c < '\u0080') {
                    if (!match(c, lowMask, highMask)) {
                        if (sb == null) {
                            sb = StringBuffer()
                            sb.append(s.substring(0, i))
                        }
                        appendEscape(sb, c.toByte())
                    } else {
                        sb?.append(c)
                    }
                } else if (allowNonASCII && (c.isWhitespace() || c.isISOControl())) {
                    if (sb == null) {
                        sb = StringBuffer()
                        sb.append(s.substring(0, i))
                    }
                    appendEncoded(sb, c)
                } else {
                    sb?.append(c)
                }
            }
            return sb?.toString() ?: s
        }

        // Encodes all characters >= \u0080 into escaped, normalized UTF-8 octets,
        // assuming that s is otherwise legal
        //
        private fun encode(s: String): String {
            val n = s.length
            if (n == 0)
                return s

            // First check whether we actually need to encode
            var i = 0
            while (true) {
                if (s[i] >= '\u0080')
                    break
                if (++i >= n)
                    return s
            }

            val bb: ByteBuffer = createByteBuffer(s.encodeToByteArray())

            val sb = StringBuffer()
            while (bb.hasRemaining()) {
                val b = bb.get().toInt() and 0xff
                if (b >= 0x80)
                    appendEscape(sb, b.toByte())
                else
                    sb.append(b.toChar())
            }
            return sb.toString()
        }

        private fun decode(c: Char): Int {
            if (c >= '0' && c <= '9')
                return c - '0'
            if (c >= 'a' && c <= 'f')
                return c - 'a' + 10
            if (c >= 'A' && c <= 'F')
                return c - 'A' + 10
            return -1
        }

        private fun decode(c1: Char, c2: Char): Byte {
            return (decode(c1) and 0xf shl 4 or (decode(c2) and 0xf shl 0)).toByte()
        }

        // Evaluates all escapes in s, applying UTF-8 decoding if needed.  Assumes
        // that escapes are well-formed syntactically, i.e., of the form %XX.  If a
        // sequence of escaped octets is not valid UTF-8 then the erroneous octets
        // are replaced with '\uFFFD'.
        // Exception: any "%" found between "[]" is left alone. It is an IPv6 literal
        //            with a scope_id
        //
        private fun decode(s: String?): String? {
            if (s == null)
                return null
            return URLDecoder.decode(s)
        }
    }

}
