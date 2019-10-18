package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.crypto.*
import com.koushikdutta.scratch.event.nanoTime
import kotlinx.cinterop.*

// store the keys and certs in pem form, and load them as necessary.
// kotlin does not have destructors to free native resources.
// so all usage needs to be scoped.

open class PemHolder internal constructor(private val pem: String) {
    protected fun usePem(block: (bio: CPointer<BIO>?) -> Unit) {
        val bioKey = BIO_new(BIO_s_mem())
        try {
            val bytes = pem.encodeToByteArray()
            bytes.usePinned {
                BIO_write(bioKey, it.addressOf(0), bytes.size)
            }
            block(bioKey)
        }
        finally {
            BIO_free(bioKey)
        }
    }
}

actual interface RSAPrivateKey {
    fun init(sslContext: SSLContext)
}
class RSAPrivateKeyImpl internal constructor(pem: String): PemHolder(pem), RSAPrivateKey {
    override fun init(sslContext: SSLContext) = memScoped {
        usePem {
            val pkey = PEM_read_bio_RSAPrivateKey(it, null, null, null)
            SSL_CTX_use_RSAPrivateKey(sslContext.ctx, pkey)
        }
    }
}

actual abstract class X509Certificate internal constructor(pem: String): PemHolder(pem) {
    internal abstract fun init(sslContext: SSLContext)
}

class X509CertificateImpl internal constructor(pem: String): X509Certificate(pem) {
    override fun init(sslContext: SSLContext) = memScoped {
        usePem {
            val cert = PEM_read_bio_X509(it, null, null, null)
            SSL_CTX_use_certificate(sslContext.ctx, cert)
        }
    }
}

actual fun createSelfSignedCertificate(subjectName: String): Pair<RSAPrivateKey, X509Certificate> {
    val pkey = EVP_PKEY_new()
    val bioKey = BIO_new(BIO_s_mem())
    val bioCert = BIO_new(BIO_s_mem())
    val x509 = X509_new()
    val keyPem = ByteBufferList()
    val certPem = ByteBufferList()

    try {

        val rsa = RSA_generate_key(2048, RSA_F4.toULong(), null, null)

        EVP_PKEY_assign(pkey, EVP_PKEY_RSA, rsa)

        ASN1_INTEGER_set(X509_get_serialNumber(x509), nanoTime())
        val oneYear = 365L * 24L * 60L * 60L
        X509_gmtime_adj(X509_get_notBefore!!.invoke(x509), 0)
        X509_gmtime_adj(X509_get_notAfter!!.invoke(x509), oneYear)

        X509_set_pubkey(x509, pkey)

        val x509Name = X509_get_subject_name(x509)
        subjectName.encodeToByteArray().usePinned {
            X509_NAME_add_entry_by_txt(
                x509Name,
                "CN",
                MBSTRING_ASC,
                it.addressOf(0).reinterpret(),
                -1,
                -1,
                0
            )
        }
        // self issued
        X509_set_issuer_name(x509, x509Name)
        X509_sign(x509, pkey, EVP_sha1())


        // pinning memory + addressOf does not work on an empty array.
        // openssl requires non null for the password otherwise
        // it will prompt in the console apparently. use empty pass, with a non empty array.
        // pass 0 as length.
        "not empty".encodeToByteArray().usePinned {
            PEM_write_bio_PrivateKey(bioKey, pkey, null, it.addressOf(0).reinterpret(), 0, null, null)
        }
        SSLEngineImpl.readBio(bioKey, keyPem)

        PEM_write_bio_X509(bioCert, x509)
        SSLEngineImpl.readBio(bioCert, certPem)
    } finally {
        BIO_free(bioCert)
    }

    return Pair(RSAPrivateKeyImpl(keyPem.readUtf8String()), X509CertificateImpl(certPem.readUtf8String()))
}

actual fun SSLContext.init(pk: RSAPrivateKey, certificate: X509Certificate): SSLContext {
    pk.init(this)
    certificate.init(this)
    SSL_CTX_check_private_key(this.ctx)
    return this
}

actual fun SSLContext.init(certificate: X509Certificate): SSLContext {
    certificate.init(this)
    return this
}