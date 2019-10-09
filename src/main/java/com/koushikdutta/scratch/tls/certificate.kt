package com.koushikdutta.scratch.tls

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.OpenSSLX509Certificate
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@Throws(Exception::class)
private fun createSelfSignedCertificate(keyPair: KeyPair, subjectDN: String): Certificate {
    val bcProvider = BouncyCastleProvider()
//    Security.addProvider(bcProvider)

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

    val certBuilder = X509v3CertificateBuilder(dnName, certSerialNumber, startDate, endDate, dnName, SubjectPublicKeyInfo.getInstance(keyPair.public.encoded))

    val usage = KeyUsage(KeyUsage.keyCertSign or KeyUsage.digitalSignature)
    certBuilder.addExtension(Extension.keyUsage, true, usage.getEncoded())

    val subjectAlternativeNames = arrayOf<ASN1Encodable>(GeneralName(GeneralName.dNSName, subjectDN))
    val subjectAlternativeNamesExtension = DERSequence(subjectAlternativeNames)
    certBuilder.addExtension(Extension.subjectAlternativeName, false, subjectAlternativeNamesExtension)

    // Extensions --------------------------

    // Basic Constraints
    val basicConstraints = BasicConstraints(true) // <-- true for CA, false for EndEntity

    certBuilder.addExtension(Extension.basicConstraints, true, basicConstraints) // Basic Constraints is usually marked as critical.

    // -------------------------------------

//    val x509holder = certBuilder.build(contentSigner)
//     val certFactory = CertificateFactory.getInstance("X.509");
//        val x509c = certFactory.generateCertificate(
//                ByteArrayInputStream(x509holder.getEncoded()));
//        return x509c;

    if (true)
        return JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(contentSigner))


    val ret = JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(contentSigner))

     val certFactory = CertificateFactory.getInstance("X.509", bcProvider);
        val x509c = certFactory.generateCertificate(
                ByteArrayInputStream(ret.getEncoded()));
        return x509c;
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
    ks.load(null, null)

    ks.setKeyEntry("key", pk, "".toCharArray(), arrayOf(certificate))
    ks.setCertificateEntry("certificate", certificate)

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, "".toCharArray())

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), sslContext.provider)
    tmf.init(ks)

    sslContext.init(kmf.keyManagers, tmf.trustManagers, null)

    return sslContext
}

fun initializeSSLContext(sslContext: SSLContext, certificate: Certificate): SSLContext {
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    ks.load(null, null)

    ks.setCertificateEntry("certificate", certificate)

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), sslContext.provider)
    tmf.init(ks)

    sslContext.init(null, tmf.trustManagers, null)

    return sslContext
}