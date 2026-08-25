package com.pocketllm.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Locale

data class TlsKeystoreInfo(
    val file: File,
    val alias: String,
    val password: CharArray,
    val fingerprint: String,
)

object TlsCertManager {

    private const val ALIAS = "pocketllm"
    private const val PASSWORD = "pocketllm-local"

    fun ensureKeystore(filesDir: File, sanIps: List<String>): Result<TlsKeystoreInfo> = runCatching {
        val ksFile = File(filesDir, "tls.p12")
        val fpFile = File(filesDir, "tls.fingerprint")
        if (ksFile.isFile && fpFile.isFile) {
            return@runCatching TlsKeystoreInfo(
                file = ksFile,
                alias = ALIAS,
                password = PASSWORD.toCharArray(),
                fingerprint = fpFile.readText().trim(),
            )
        }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Calendar.getInstance().apply { timeInMillis = now - 24L * 3600 * 1000 }
        val notAfter = Calendar.getInstance().apply { timeInMillis = now + 3650L * 24 * 3600 * 1000 }

        val subject = X500Name("CN=PocketLLM Local Server")
        val sans = buildList {
            add(GeneralName(GeneralName.iPAddress, "127.0.0.1"))
            sanIps.filter { it.isNotBlank() && it != "127.0.0.1" }.forEach { ip ->
                runCatching { GeneralName(GeneralName.iPAddress, ip) }.getOrNull()?.let(::add)
            }
            add(GeneralName(GeneralName.dNSName, "localhost"))
        }

        val certHolder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            notBefore.time,
            notAfter.time,
            subject,
            keyPair.public,
        )
            .addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))
            .build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val certificate: X509Certificate =
            JcaX509CertificateConverter().getCertificate(certHolder)

        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry(ALIAS, keyPair.private, PASSWORD.toCharArray(), arrayOf<Certificate>(certificate))

        FileOutputStream(ksFile).use { out -> keyStore.store(out, PASSWORD.toCharArray()) }

        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val fingerprint = digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
        fpFile.writeText(fingerprint)

        TlsKeystoreInfo(
            file = ksFile,
            alias = ALIAS,
            password = PASSWORD.toCharArray(),
            fingerprint = fingerprint,
        )
    }

    fun readFingerprint(filesDir: File): String? {
        val fpFile = File(filesDir, "tls.fingerprint")
        return if (fpFile.isFile) fpFile.readText().trim() else null
    }

    fun loadKeystore(filesDir: File): KeyStore? {
        val ksFile = File(filesDir, "tls.p12")
        if (!ksFile.isFile) return null
        return KeyStore.getInstance("PKCS12").apply {
            FileInputStream(ksFile).use { load(it, PASSWORD.toCharArray()) }
        }
    }

    fun deleteTlsFiles(filesDir: File) {
        File(filesDir, "tls.p12").delete()
        File(filesDir, "tls.fingerprint").delete()
    }
}
