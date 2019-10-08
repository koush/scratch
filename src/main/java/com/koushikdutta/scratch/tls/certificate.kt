package com.koushikdutta.scratch.tls

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.Certificate
import java.util.*
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory


@Throws(Exception::class)
private fun createSelfSignedCertificate(keyPair: KeyPair, subjectDN: String): Certificate {
    val bcProvider = BouncyCastleProvider()
    Security.addProvider(bcProvider)

    val now = System.currentTimeMillis()
    val startDate = Date(now)

    val dnName = X500Name("CN=$subjectDN")
    val certSerialNumber = BigInteger(now.toString()) // <-- Using the current timestamp as the certificate serial number

    val calendar = Calendar.getInstance()
    calendar.time = startDate
    calendar.add(Calendar.YEAR, 1) // <-- 1 Yr validity

    val endDate = calendar.time

    val signatureAlgorithm = "SHA256WithRSA" // <-- Use appropriate signature algorithm based on your keyPair algorithm.

    val contentSigner = JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.private)

    val certBuilder = JcaX509v3CertificateBuilder(dnName, certSerialNumber, startDate, endDate, dnName, keyPair.public)


    val subjectAlternativeNames = arrayOf<ASN1Encodable>(GeneralName(GeneralName.dNSName, subjectDN))
    val subjectAlternativeNamesExtension = DERSequence(subjectAlternativeNames)
    certBuilder.addExtension(Extension.subjectAlternativeName, false, subjectAlternativeNamesExtension)

    // Extensions --------------------------

    // Basic Constraints
    val basicConstraints = BasicConstraints(true) // <-- true for CA, false for EndEntity

    certBuilder.addExtension(ASN1ObjectIdentifier("2.5.29.19"), true, basicConstraints) // Basic Constraints is usually marked as critical.

    // -------------------------------------

    return JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(contentSigner))
}

fun createSelfSignedCertificate(subjectName: String): Pair<KeyPair, Certificate> {
    val keyGen = KeyPairGenerator.getInstance("RSA")
    keyGen.initialize(2048)
    val pair = keyGen.generateKeyPair()
    val cert = createSelfSignedCertificate(pair, subjectName)

    return Pair(pair, cert)
}

fun initializeSSLContext(sslContext: SSLContext, keyPair: KeyPair, certificate: Certificate): SSLContext {
    val pk = keyPair.private

    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    ks.load(null)

    ks.setKeyEntry("key", pk, "".toCharArray(), arrayOf(certificate))

    val kmf = KeyManagerFactory.getInstance("PKIX")
    kmf.init(ks, "".toCharArray())

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(ks)

    sslContext.init(kmf.keyManagers, tmf.trustManagers, null)

    return sslContext
}

fun initializeSSLContext(sslContext: SSLContext, certificate: Certificate): SSLContext {
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    ks.load(null)

    ks.setCertificateEntry("cert", certificate)

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(ks)

    sslContext.init(null, tmf.trustManagers, null)

    return sslContext
}