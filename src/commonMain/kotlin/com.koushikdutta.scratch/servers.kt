package com.koushikdutta.scratch

import kotlinx.coroutines.Job

/**
 * AsyncServerSocket accepts incoming AsyncSocket clients.
 */
interface AsyncServerSocket<T: AsyncSocket> : AsyncAffinity {
    fun accept(): AsyncIterable<out T>
    suspend fun close()
}

fun <T: AsyncSocket> AsyncServerSocket<T>.acceptAsync(block: suspend T.() ->Unit): Job {
    return launch {
        for (socket in accept()) {
            socket.launch(block)
        }
    }
}