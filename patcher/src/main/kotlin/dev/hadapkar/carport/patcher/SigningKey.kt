package dev.hadapkar.carport.patcher

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The key every patched APK is re-signed with. Android only installs an update over an
 * app signed with the same key, so this key must survive for in-place re-patching:
 * it is stored as a PKCS#12 file and can be exported and imported.
 */
class SigningKey(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    /** Uppercase hex SHA-256 of the DER certificate, the same form Android reports. */
    val sha256: String by lazy { certificateSha256(certificate.encoded) }

    fun writeTo(output: OutputStream) {
        val store = KeyStore.getInstance(STORE_TYPE)
        store.load(null, null)
        store.setKeyEntry(ALIAS, privateKey, PASSWORD, arrayOf(certificate))
        store.store(output, PASSWORD)
    }

    fun saveTo(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { writeTo(it) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw PatchException("Could not save the signing key to ${file.path}.")
        }
    }

    companion object {
        /** Keystores exported by CarPort use this password; importing other keystores is not supported. */
        const val EXPORT_PASSWORD = "carport"
        private val PASSWORD = EXPORT_PASSWORD.toCharArray()
        private const val STORE_TYPE = "PKCS12"
        private const val ALIAS = "carport"

        fun generate(commonName: String = "CarPort"): SigningKey {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            val keyPair = generator.generateKeyPair()
            val subject = X500Name("CN=$commonName")
            val notBefore = Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
            val notAfter = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365L * 30))
            val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
            val holder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
            val certificate = JcaX509CertificateConverter().getCertificate(holder)
            return SigningKey(keyPair.private, certificate)
        }

        fun readFrom(input: InputStream): SigningKey {
            val store = KeyStore.getInstance(STORE_TYPE)
            try {
                store.load(input, PASSWORD)
            } catch (e: Exception) {
                throw PatchException("This is not a CarPort signing key (or it uses a different password).", e)
            }
            val alias = store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
                ?: throw PatchException("The keystore contains no private key.")
            val key = store.getKey(alias, PASSWORD) as? PrivateKey
                ?: throw PatchException("The keystore key is not a private key.")
            val certificate = store.getCertificate(alias) as? X509Certificate
                ?: throw PatchException("The keystore has no X.509 certificate.")
            return SigningKey(key, certificate)
        }

        fun loadFrom(file: File): SigningKey = file.inputStream().use { readFrom(it) }

        /** Loads the key at [file], creating and saving a new one if the file does not exist yet. */
        fun loadOrCreate(file: File): SigningKey {
            if (file.exists()) return loadFrom(file)
            return generate().also { it.saveTo(file) }
        }

        fun certificateSha256(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02X".format(it) }
    }
}
