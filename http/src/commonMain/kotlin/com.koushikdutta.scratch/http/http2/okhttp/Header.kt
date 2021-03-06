/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.koushikdutta.scratch.http.http2.okhttp

import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.http2.ByteString
import com.koushikdutta.scratch.http.http2.encodeUtf8
import com.koushikdutta.scratch.http.http2.utf8

/** HTTP header: the name is an ASCII string, but the value can be UTF-8. */
internal data class Header(
        /** Name in case-insensitive ASCII encoding. */
  val name: ByteString,
        /** Value in UTF-8 encoding. */
  val value: ByteString
) {
  val hpackSize = 32 + name.size + value.size

  // TODO: search for toLowerCase and consider moving logic here.
  constructor(name: String, value: String) : this(name.encodeUtf8(), value.encodeUtf8())

  constructor(name: ByteString, value: String) : this(name, value.encodeUtf8())

  override fun toString(): String = "${name.utf8()}: ${value.utf8()}"

  companion object {
    // Special header names defined in HTTP/2 spec.
    val PSEUDO_PREFIX: ByteString = ":".encodeUtf8()

    const val RESPONSE_STATUS_UTF8 = ":status"
    const val TARGET_METHOD_UTF8 = ":method"
    const val TARGET_PATH_UTF8 = ":path"
    const val TARGET_SCHEME_UTF8 = ":scheme"
    const val TARGET_AUTHORITY_UTF8 = ":authority"

    val RESPONSE_STATUS: ByteString = RESPONSE_STATUS_UTF8.encodeUtf8()
    val TARGET_METHOD: ByteString = TARGET_METHOD_UTF8.encodeUtf8()
    val TARGET_PATH: ByteString = TARGET_PATH_UTF8.encodeUtf8()
    val TARGET_SCHEME: ByteString = TARGET_SCHEME_UTF8.encodeUtf8()
    val TARGET_AUTHORITY: ByteString = TARGET_AUTHORITY_UTF8.encodeUtf8()
  }
}
