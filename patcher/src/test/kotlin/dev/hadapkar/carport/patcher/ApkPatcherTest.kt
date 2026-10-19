package dev.hadapkar.carport.patcher

import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ApkPatcherTest {

    companion object {
        private lateinit var key: SigningKey
        private var fixture: File? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            key = SigningKey.generate()
            fixture = System.getProperty("carport.testApk")?.let(::File)?.takeIf { it.isFile }
        }
    }

    private fun requireFixture(): File {
        assumeTrue("Pass -PtestApk=<path to an APK> to run APK patch tests", fixture != null)
        return fixture!!
    }

    private fun tempDir(): File = Files.createTempDirectory("carport-test").toFile().apply { deleteOnExit() }

    @Test
    fun signingKeyRoundTrips() {
        val bytes = ByteArrayOutputStream().also { key.writeTo(it) }.toByteArray()
        val loaded = SigningKey.readFrom(ByteArrayInputStream(bytes))
        assertEquals(key.sha256, loaded.sha256)
        assertEquals(64, key.sha256.length)
    }

    @Test
    fun loadOrCreateKeepsTheSameKey() {
        val file = File(tempDir(), "key.p12")
        val first = SigningKey.loadOrCreate(file)
        val second = SigningKey.loadOrCreate(file)
        assertEquals(first.sha256, second.sha256)
    }

    @Test(expected = PatchException::class)
    fun rejectsForeignKeystore() {
        SigningKey.readFrom(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun automotiveDescriptorParses() {
        val document = ResXmlDocument()
        document.readBytes(ByteArrayInputStream(ApkPatcher.automotiveDescriptorXml()))
        val root = document.documentElement
        assertEquals("automotiveApp", root.name)
        val uses = root.listElements("uses").single()
        assertEquals("media", uses.searchAttributeByName("name").valueAsString)
    }

    @Test
    fun patchedApkIsSignedAndDeclaresCarLauncher() {
        val apk = requireFixture()
        val out = tempDir()
        val result = ApkPatcher(key).patch(apk, outputDir = out)
        println("Patched ${result.packageName} ${result.versionName}: ${result.changes}")

        assertEquals(1, result.apks.size)
        val patched = result.apks.single()
        assertSignedWithKey(patched)
        assertCarDeclarations(patched)
        // Kept for inspection with aapt2 / apksigner (CI dumps it).
        patched.copyTo(File("build/patched-fixture.apk"), overwrite = true)
    }

    @Test
    fun repatchingIsIdempotent() {
        val apk = requireFixture()
        val first = ApkPatcher(key).patch(apk, outputDir = tempDir()).apks.single()
        val second = ApkPatcher(key).patch(first, outputDir = tempDir()).apks.single()
        assertSignedWithKey(second)
        assertCarDeclarations(second)
    }

    @Test
    fun splitsAreResignedWithTheSameKey() {
        val apk = requireFixture()
        // Any APK works as a stand-in split: splits are only re-signed, not edited.
        val result = ApkPatcher(key).patch(apk, splits = listOf(apk, apk), outputDir = tempDir())
        assertEquals(3, result.apks.size)
        assertEquals(result.apks.size, result.apks.map { it.name }.toSet().size)
        result.apks.forEach { assertSignedWithKey(it) }
    }

    private fun assertSignedWithKey(apk: File) {
        val verification = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(28).build().verify()
        assertTrue("Signature invalid: ${verification.errors}", verification.isVerified)
        // The verifier skips v2 when v3 already covers every checked platform version.
        assertTrue(verification.isVerifiedUsingV2Scheme || verification.isVerifiedUsingV3Scheme)
        val signer = verification.signerCertificates.single()
        assertEquals(key.sha256, SigningKey.certificateSha256(signer.encoded))
    }

    private fun assertCarDeclarations(apk: File) {
        val module = ApkModule.loadApkFile(apk)
        try {
            val manifest = module.androidManifest
            val application = manifest.applicationElement

            val launcherFilters = (application.listElements("activity") + application.listElements("activity-alias"))
                .flatMap { it.listElements("intent-filter") }
                .filter { filter -> filter.listElements("category").any { it.androidName() == "android.intent.category.LAUNCHER" } }
            assertTrue("No launcher filter", launcherFilters.isNotEmpty())
            for (filter in launcherFilters) {
                for (category in ManifestPatcher.CAR_CATEGORIES) {
                    val count = filter.listElements("category").count { it.androidName() == category }
                    assertEquals("Category $category count", 1, count)
                }
            }

            val metas = application.listElements("meta-data")
            for (name in listOf(
                ManifestPatcher.META_GMS_CAR_APPLICATION,
                ManifestPatcher.META_DISTRACTION_OPTIMIZED,
                ManifestPatcher.META_CARPORT_PATCHED,
            )) {
                assertEquals("meta-data $name count", 1, metas.count { it.androidName() == name })
            }

            val carMeta = metas.single { it.androidName() == ManifestPatcher.META_GMS_CAR_APPLICATION }
            val resourceAttr = carMeta.searchAttributeByResourceId(AndroidAttr.RESOURCE)
            assertNotNull("gms.car.application has no android:resource", resourceAttr)
            val resourceId = resourceAttr!!.data
            val entry = module.getTableBlock(false).getResource(resourceId).get()
            val path = entry.resValue.valueAsString
            assertTrue("Descriptor file $path missing", module.containsFile(path))
        } finally {
            module.close()
        }
    }

    private fun ResXmlElement.androidName(): String? =
        searchAttributeByResourceId(AndroidAttr.NAME)?.valueAsString
}
