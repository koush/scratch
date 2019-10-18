package com.koushikdutta.scratch.tls

expect interface SSLSession

interface HostnameVerifier {
    /**
     * Verify that the host name is an acceptable match with
     * the server's authentication scheme.
     *
     * @param hostname the host name
     * @param session SSLSession used on the connection to host
     * @return true if the host name is acceptable
     */
    fun verify(hostname: String, session: SSLSession): Boolean
}

interface AsyncTlsTrustFailureCallback {
    fun handleOrRethrow(throwable: Throwable)
}

class AsyncTlsOptions(internal val hostnameVerifier: HostnameVerifier? = null, internal val trustFailureCallback: AsyncTlsTrustFailureCallback?)
