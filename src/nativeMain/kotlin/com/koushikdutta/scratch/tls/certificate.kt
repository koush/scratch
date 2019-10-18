package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.crypto.*
import com.koushikdutta.scratch.event.nanoTime
import kotlinx.cinterop.*

// store the keys and certs in pem form, and load them as necessary.
// kotlin does not have destructors to free native resources.
// so all usage needs to be scoped.

open class PemHolder internal constructor(protected val pem: String) {
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

val testKey = "-----BEGIN RSA PRIVATE KEY-----\n" +
        "MIIJKQIBAAKCAgEAzj/8JdQegCmmey8/SJElaAhlTzjnS0bL8flO+E+z190wDZ7Z\n" +
        "NQY5MYZVbXuLOvhX9IwA0VkPdlMUpLqLatuEUb5wA3zhJ6YqadjBXm6GVYqabh1V\n" +
        "gd8jR06DZXAa+ZQQJ8QKjfjCTCuiGJJleCp53IpnwdBJUk/u/xzaafMRImI3iJ7q\n" +
        "++0B1z9V1WJ7xxracBsUG9ay+xBG252dy3cn0EpkIYJw/wHhBW8z2ZuhECgsjmRZ\n" +
        "p27jDSKJN1WURZBBx5/gz6nvoFzf3EOgrt1a43yHaSFKsajH+rReVAxvr2XF6piu\n" +
        "BFF1ol/CSKQkvccbWwpZ7LjG2SenGCYcFy6Lq2Rf9mKdgru8jo4Q9RmFdn2vGOZJ\n" +
        "wsp5QhwwBtHTe0Wu53t0NpM7/zk1pJKhLvyYLXy5dwSTEFLmtB/fBFU0cXwy5vqd\n" +
        "4mhzpt2TuHaozfqjRiP/DQazTIIlJdoeawa7Xzpi8h9NCBFCbBdHJELxZ+CPJBYD\n" +
        "T11OiuNLSP86tHL4kz2wmxVhUMhFBAG3Suy9baosG37Wh0z7PX8xzgHDk1oXOsop\n" +
        "ZgqAzJmWCkkytRnSpPrFvIRSFYH6GLPEMaM9uq8O6RJADDAiiHloH5t+DJAdVbve\n" +
        "2Ni5vSi8GIK8mLTfADpNKV7skZE0gycNxKl/cgUnZxdkda3uUTeMFUkG57MCAwEA\n" +
        "AQKCAgAKSBBGxns6wljFPUa3VFz8AacjOt/01bOm/VmdcUOy2BjkJO4JAaVqPZsW\n" +
        "mRkIuIaR70S/KuRlbqDR0WbPzd+bv5WP5vLGajclDaQeE/5oVz95i8bOcZQtotFU\n" +
        "BjGiDWp8wP4Rs7vj1iz/cpTSV7O5dcDUXZC6JLfySbrtmytYfnLsQZfPNTJpS4P4\n" +
        "3i7zbvhCrGblOF+1ukr9+a57DRaUAJFbRkhhvfM8tR1tlJ+D0aze8Euz2AhDiFw0\n" +
        "wPRiP124GNU5JOyIHvkd3tUswmYcfwG1EO7LSnGlosYtBG2w3a7vEeti1bi6fnGN\n" +
        "83jNaRaJBEwVS+TFwH9I6eVulmhp7NPT0n7tQoOYg/+bGyswitzex0/kGWml2834\n" +
        "JwrQwnZWBisWU2OX094ObtZAuedjVA7X6TdJ/ulM+qycLF/S5YulA89kNbz0VMG4\n" +
        "T6iQFj7HvyWjfJ4Hy1eUQa0t6Dql/bruX+avPmXmQ7GWarqdI79IayDju29HqE53\n" +
        "GGKb82V1Uh2+iqwoTbgbP7j0WzFINWtPPNsUKGhQP+gYpBcvl0ltr8GT2jQMtqXZ\n" +
        "ClSTo41Cb4KnQmNxtrz5XRaZwtTL65sdLeSEfIR1bEL9bpaUrNuVw7LjV3SgUf1Q\n" +
        "T4mZYc5liiq95fMf8tiG2GDlzP0AQWAhsCNnCY6ICqQLgF+KOQKCAQEA/RPhnX4w\n" +
        "5XeltKN7OiZ6i0wgEPYtHSu/yJlMZU6KbJgdl9O1d+wMmPTAozbHHvgWD7KweyhK\n" +
        "kg0arcDbvxgY2rGifNqq2iBcCJvA8lGGTdLJt0GoKOsabk2y3DRIA0iGmiBizYVR\n" +
        "b3r+AHWB4PHJkAksBMeEEIu1Cnh1n/RG0MaCQiGj8FWH96+CEusuBK9Qadl3bW8H\n" +
        "qTzVt0Dwf8/eXU1t6S34alH3NzhJ6qDvUdcEU14UE8OXMoxiWy2WCdFdiLtJt4/m\n" +
        "PwNw1wVJYbPrdECeRsJdRroLUw+jmHdqVkflY93q0ITFhEpbrEXAfZZ3B+gPF6YV\n" +
        "U8VWDxe3qYgk/wKCAQEA0KGtT3I+Nm1rRWW11cm7c/XshEAejAUVcy5bX4OfzXv9\n" +
        "pSp02GZXcDpw/YuB8nXCXvyXXBwVRblm+/vtbSEGy3aRF1CncWXbqaHEKXQ269Ni\n" +
        "zpSY9ItHNJEJG7xANaIoccyK3bms/L41x9ghJLgWLRJgQMYH7LEKYVDf19CguTN/\n" +
        "vN2qPdTHLcyszwu8CvQRSSMGeXDSgXa7w9bOms4yo4kT26INBps2BmgeixuH3gKG\n" +
        "L75DWlNnBfOy5LKYCg9y+v3B6QMt7oo219+H0jKPy9Y+DnzcjWDMqeZDKyiqJT+g\n" +
        "8daYYVjWoZp8qeU69CI99P1IJjYEcJrKU5+v8ik5TQKCAQEA2r6CdiSKkux118qu\n" +
        "SjbUGO61kLOXju0vmW2XcMEQOIjKm16sLXlj8hkrIGfKeGQqfWsPy4Op4M9ezewy\n" +
        "g9uKMAz1y0EeB+eMZQxrG4eHtGCLZdBkTunXMJwMCvVNgTkAzttLSyT+nIMMZM0s\n" +
        "c4fJlqu21nK1HbADzYcM9DKpuu65y/tTvfnPyjBzF3MblYUK1lLXPUmIr6kbeIey\n" +
        "2GaNZsv0QmYvn4Y1Tg9jJF/yOyTEYmKgq+yVFWXt5a1jUB0c4YAwWW8RrkmFEPVK\n" +
        "1h4zV4o6xCJfiTBpdWPBHZmp2eXOyiEYQx9fsdaroh2wIV0aiUT1NpWhPYUFu4ne\n" +
        "f7RjdQKCAQEAuaj3Avkmdeb/jB1MKLlCMJqL5NDunio0AXEibShEAGezu9TBcIX6\n" +
        "j+vh+y+3711uIHtl8tkm8QphpCJ4EiJO2qdjzLzAlYBQb29+kmlSKqDNkAra5tw+\n" +
        "/H+I/H6VIWVx2ntspI4EbFKUp4glTnjneyqcL2hLSw3tr9Z5rKODM+ypYxhb1HRS\n" +
        "3+YBbqsBpzm1XEFoFJAbNIzF4Sx6ZXbTx4ZH/q8CKnvbIu5rJ93TFfVnFnuZknsf\n" +
        "9kL9Utm/xUwkRUmm00Y6DMpArfDE/IB0SLAaP2hMGSAoYZWA9ppUd5tdDXmr1w1o\n" +
        "yx0gjUxqHzGNs0PmfeKaJt8I6Ev2FmkrDQKCAQBOwc/LIR0kPeZZUkFL677e3qx4\n" +
        "QjCE2Rh0fBQ3Vzs/BcIzz4KRz/pHFKigl3JxDxVHaJfcD2t/K8N1Wl5s1+U50+is\n" +
        "se5LDzDOvL2zXBD261VxVwUq6XLdiypCL6ZDEx8I0oDncLEu+jGtRiwpq92zew4A\n" +
        "hJ1pfg0T68hYBTetnLZxpoL9uPYZcuxPOApC8sH4OVoMsVvcdFavpSnyTYWo7/1R\n" +
        "T07i1uydQVBT+TX7li5dhnxCZN4+V3NQeFTEfUURwHGee2sijsVUE7c0bTAzRD/O\n" +
        "dEHxW4x9Qxkf+W+Y21WO5rb7LXNznB0QU3pE5YAUqi2k9MBxAXgfRdJiuu9a\n" +
        "-----END RSA PRIVATE KEY-----\n"

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
    internal abstract fun init2(sslContext: SSLContext)
}

val testCert = "-----BEGIN CERTIFICATE-----\n" +
        "MIIEpjCCAo4CCQCenTDKPabnKDANBgkqhkiG9w0BAQsFADAVMRMwEQYDVQQDDApU\n" +
        "ZXN0U2VydmVyMB4XDTE5MTAxODA2NDAxNloXDTIwMTAxNzA2NDAxNlowFTETMBEG\n" +
        "A1UEAwwKVGVzdFNlcnZlcjCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIB\n" +
        "AM4//CXUHoAppnsvP0iRJWgIZU8450tGy/H5TvhPs9fdMA2e2TUGOTGGVW17izr4\n" +
        "V/SMANFZD3ZTFKS6i2rbhFG+cAN84SemKmnYwV5uhlWKmm4dVYHfI0dOg2VwGvmU\n" +
        "ECfECo34wkwrohiSZXgqedyKZ8HQSVJP7v8c2mnzESJiN4ie6vvtAdc/VdVie8ca\n" +
        "2nAbFBvWsvsQRtudnct3J9BKZCGCcP8B4QVvM9mboRAoLI5kWadu4w0iiTdVlEWQ\n" +
        "Qcef4M+p76Bc39xDoK7dWuN8h2khSrGox/q0XlQMb69lxeqYrgRRdaJfwkikJL3H\n" +
        "G1sKWey4xtknpxgmHBcui6tkX/ZinYK7vI6OEPUZhXZ9rxjmScLKeUIcMAbR03tF\n" +
        "rud7dDaTO/85NaSSoS78mC18uXcEkxBS5rQf3wRVNHF8Mub6neJoc6bdk7h2qM36\n" +
        "o0Yj/w0Gs0yCJSXaHmsGu186YvIfTQgRQmwXRyRC8WfgjyQWA09dTorjS0j/OrRy\n" +
        "+JM9sJsVYVDIRQQBt0rsvW2qLBt+1odM+z1/Mc4Bw5NaFzrKKWYKgMyZlgpJMrUZ\n" +
        "0qT6xbyEUhWB+hizxDGjPbqvDukSQAwwIoh5aB+bfgyQHVW73tjYub0ovBiCvJi0\n" +
        "3wA6TSle7JGRNIMnDcSpf3IFJ2cXZHWt7lE3jBVJBuezAgMBAAEwDQYJKoZIhvcN\n" +
        "AQELBQADggIBAGrKiciFv1UjDofcDpU6mLurEGkh8AAqnJ59GNTb/huBin7x/M6X\n" +
        "jROH58Z+dkr6abTbthzD3b3jc32Q56SL+O4DEmgulTLwA6IzMVJJm2LR1f6+7U7b\n" +
        "ShyBcY3xn4yfmzRXAA0EbdTFuTfkbhgLWVn5F83sJUXnHRGh44nH7rK3CUYLWgY/\n" +
        "l07oisARC6D0Ut4uJ74vKLlYfSLJfOaUfz1ylN2KopVUlhMQvkgAXk3yEqOSpN8Y\n" +
        "spwwr9MpKGlCNSRHJsHqDBUQEr4Cc3pB1seqrrXXqPQHFcgftYnF2hfa0kJMgyHk\n" +
        "rfBwf8IPFykbMA7e9DeiEiuLPUIKv/DZO7Gv8Ad30m0Q6q6jiS3KpeqXtblz6p/M\n" +
        "ZpmW/g6Rtno1D3KVuUFEaaqTYff2vAMmOsfGD5Oa5ZCHKoZbCF9hy8fjRExwx2UD\n" +
        "kmSzUBP+m1W8nm6OPSZ2EZuaTWcYJ02nXS9Lt5DI3hKpQkv/hEnYu1GtTOwfUcdV\n" +
        "Rt+f0zrJsjdJUlZtVGHiXfWedTwNhd8WtqaxOyr2Ih2vASqo2mpmJpSawIMw4gvb\n" +
        "pp605l0y0Dch18ntI9iWVwXRnPhTWH5D09zZXCJJTvwHaGWzG2eX1v9sdvLPyqwd\n" +
        "lVNjpoFUYskZ9qTYWjvEQmlXpygWYUVufL1/BaBufeVaSWKt+qMnxsbR\n" +
        "-----END CERTIFICATE-----\n"

class X509CertificateImpl internal constructor(pem: String): X509Certificate(pem) {
    override fun init(sslContext: SSLContext) = memScoped {
        usePem {
            val cert = PEM_read_bio_X509(it, null, null, null)
            var ret = SSL_CTX_use_certificate(sslContext.ctx, cert)
            println(ret)
            ret = SSL_CTX_ctrl(sslContext.ctx, SSL_CTRL_BUILD_CERT_CHAIN, 0, null).toInt()
            println(ret)
        }
    }

    override fun init2(sslContext: SSLContext) = memScoped{
        usePem {
            val cert = PEM_read_bio_X509(it, null, null, null)
            val x509store = SSL_CTX_get_cert_store(sslContext.ctx)
            X509_STORE_add_cert(x509store, cert)
        }
    }
}

private fun addExtension(x509: CPointer<X509>?, nid: Int, value: String): Boolean = memScoped{
    val ctx = alloc<X509V3_CTX>() {
        db = null
    }
    X509V3_set_ctx(ctx.ptr, x509, x509, null, null, 0)
    val ex = X509V3_EXT_conf_nid(null, ctx.ptr, nid, value) ?: return false
    X509_add_ext(x509, ex, -1)
    X509_EXTENSION_free(ex)
    return true
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


//        /* Add various extensions: standard extensions */
//        addExtension(x509, NID_basic_constraints, "critical,CA:TRUE");
//        addExtension(x509, NID_key_usage, "critical,keyCertSign,decipherOnly");
//
//        addExtension(x509, NID_subject_key_identifier, "hash");
//
//        /* Some Netscape specific extensions */
//        addExtension(x509, NID_netscape_cert_type, "sslCA");
//
//        addExtension(x509, NID_netscape_comment, "example comment extension");
       addExtension(x509, NID_subject_alt_name, subjectName);

        var ret = X509_sign(x509, pkey, EVP_sha1())
        println("sign $ret")


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
    val ret = SSL_CTX_check_private_key(this.ctx)
    println("check pk $ret")
    return this
}

actual fun SSLContext.init(certificate: X509Certificate): SSLContext {
    certificate.init(this)
    certificate.init2(this)
    return this
}