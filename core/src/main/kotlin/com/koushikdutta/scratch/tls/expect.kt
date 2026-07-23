package com.koushikdutta.scratch.tls

class SSLEngineResult constructor(val status: SSLEngineStatus, val handshakeStatus: SSLEngineHandshakeStatus)

enum class SSLEngineStatus {
    BUFFER_UNDERFLOW,
    OK,
    CLOSED,
}

enum class SSLEngineHandshakeStatus {
    FINISHED,
    NEED_TASK,
    NEED_UNWRAP,
    NEED_WRAP,
}
