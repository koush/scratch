package com.koushikdutta.scratch

/**
 * AsyncServerSocket accepts incoming AsyncSocket clients.
 */
interface AsyncServerSocket : AsyncAffinity {
    fun accept(): AsyncIterable<out AsyncSocket>
    suspend fun close()
}
