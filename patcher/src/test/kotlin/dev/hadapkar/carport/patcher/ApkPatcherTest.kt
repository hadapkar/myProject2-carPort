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

    /** Patch only (no full-table re-parse), so -PtestHeap measures what the phone needs. */
    @Test
    fun patchFitsInHeap() {
        val apk = requireFixture()
        val runtime = Runtime.getRuntime()
        val result = ApkPatcher(key).patch(apk, outputDir = tempDir())
        assertSignedWithKey(result.apks.single())
        println("Patched within a ${runtime.maxMemory() / (1024 * 1024)} MB heap")
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
    fun onlyEnabledLauncherEntriesGetACarIcon() {
        val apk = requireFixture()
        val enabled = launcherNames(apk) { it.enabled }
        val all = launcherNames(apk) { true }
        val expected = enabled.ifEmpty { all }
        val patched = ApkPatcher(key).patch(apk, outputDir = tempDir()).apks.single()
        assertEquals(expected, carLauncherNames(patched))
        println("Car icons: ${expected.size} of ${all.size} launcher entries")
    }

    @Test
    fun allLauncherEntriesOptionTagsEveryEntryAndCanBeUndone() {
        val apk = requireFixture()
        val all = launcherNames(apk) { true }
        val everything = ApkPatcher(key)
            .patch(apk, outputDir = tempDir(), options = PatchOptions(allLauncherEntries = true)).apks.single()
        assertEquals(all, carLauncherNames(everything))
        // Re-patching the patched copy with the option off removes the extra icons again.
        val enabled = launcherNames(apk) { it.enabled }.ifEmpty { all }
        val back = ApkPatcher(key).patch(everything, outputDir = tempDir()).apks.single()
        assertEquals(enabled, carLauncherNames(back))
    }

    @Test
    fun preferredLauncherWinsOnRepatch() {
        val apk = requireFixture()
        val all = launcherNames(apk) { true }
        val chosen = all.last()
        val patched = ApkPatcher(key).patch(
            apk, outputDir = tempDir(), options = PatchOptions(preferredLauncherComponents = setOf(chosen)),
        ).apks.single()
        assertEquals(setOf(chosen), carLauncherNames(patched))
    }

    @Test
    fun keepStateOptionAddsScreenSwitchFlags() {
        val apk = requireFixture()
        val patched = ApkPatcher(key)
            .patch(apk, outputDir = tempDir(), options = PatchOptions(keepStateOnScreenChange = true)).apks.single()
        for ((activity, attrs) in activityAttributes(patched)) {
            val flags = attrs[AndroidAttr.CONFIG_CHANGES]
            assertNotNull("$activity has no configChanges", flags)
            val data = flags!!.substringAfter(':').toInt()
            assertEquals("$activity flags", AndroidAttr.CONFIG_CHANGES_SCREEN_SWITCH, data and AndroidAttr.CONFIG_CHANGES_SCREEN_SWITCH)
        }
    }

    @Test
    fun turningOptionsOffRestoresTheOriginalAttributes() {
        val apk = requireFixture()
        val original = activityAttributes(apk)
        val allOn = PatchOptions(unlockOrientation = true, forceResizeable = true, keepStateOnScreenChange = true)
        val allOff = PatchOptions(unlockOrientation = false, forceResizeable = false, keepStateOnScreenChange = false)
        val first = ApkPatcher(key).patch(apk, outputDir = tempDir(), options = allOn).apks.single()
        val again = ApkPatcher(key).patch(first, outputDir = tempDir(), options = allOn).apks.single()
        val undone = ApkPatcher(key).patch(again, outputDir = tempDir(), options = allOff).apks.single()
        assertEquals(original, activityAttributes(undone))
        // Only the always-applied appCategory record may remain; every option's record is gone.
        assertEquals(1, countOriginalRecords(undone))
    }

    /** activity name -> (attr id -> "type:data") for the attributes CarPort may change. */
    private fun activityAttributes(apk: File): Map<String, Map<Int, String>> {
        val ids = listOf(AndroidAttr.SCREEN_ORIENTATION, AndroidAttr.RESIZEABLE_ACTIVITY, AndroidAttr.CONFIG_CHANGES)
        val module = ApkModule.loadApkFile(apk)
        try {
            val manifest = module.androidManifest
            return manifest.applicationElement.listElements("activity").associate { activity ->
                manifest.fullClassName(activity.androidName()) to ids.mapNotNull { id ->
                    activity.searchAttributeByResourceId(id)?.let { id to "${it.valueType.byte}:${it.data}" }
                }.toMap()
            }
        } finally {
            module.close()
        }
    }

    private fun countOriginalRecords(apk: File): Int {
        val module = ApkModule.loadApkFile(apk)
        try {
            val application = module.androidManifest.applicationElement
            return (listOf(application) + application.listElements("activity")).sumOf { element ->
                element.listElements("meta-data").count {
                    it.androidName()?.startsWith(ManifestPatcher.META_ORIGINAL_PREFIX) == true
                }
            }
        } finally {
            module.close()
        }
    }

    private class LauncherInfo(val name: String, val enabled: Boolean, val car: Boolean)

    private fun launchers(apk: File): List<LauncherInfo> {
        val module = ApkModule.loadApkFile(apk)
        try {
            val manifest = module.androidManifest
            val application = manifest.applicationElement
            return (application.listElements("activity") + application.listElements("activity-alias")).mapNotNull { c ->
                val filters = c.listElements("intent-filter").filter { f ->
                    f.listElements("category").any { it.androidName() == "android.intent.category.LAUNCHER" }
                }
                if (filters.isEmpty()) return@mapNotNull null
                val enabledAttr = c.searchAttributeByResourceId(AndroidAttr.ENABLED)
                LauncherInfo(
                    name = manifest.fullClassName(c.androidName()),
                    enabled = enabledAttr == null || enabledAttr.data != 0,
                    car = filters.any { f -> f.listElements("category").any { it.androidName() == ManifestPatcher.CATEGORY_CAR_LAUNCHER } },
                )
            }
        } finally {
            module.close()
        }
    }

    private fun launcherNames(apk: File, predicate: (LauncherInfo) -> Boolean): Set<String> =
        launchers(apk).filter(predicate).map { it.name }.toSet()

    private fun carLauncherNames(apk: File): Set<String> = launcherNames(apk) { it.car }

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
                    assertTrue("Category $category duplicated", count <= 1)
                }
            }
            assertTrue("No launcher filter got the car icon", launcherFilters.any { filter ->
                ManifestPatcher.CAR_CATEGORIES.all { c -> filter.listElements("category").any { it.androidName() == c } }
            })

            val category = application.searchAttributeByResourceId(AndroidAttr.APP_CATEGORY)
            assertNotNull("No android:appCategory", category)
            assertEquals("appCategory must be game", AndroidAttr.APP_CATEGORY_GAME, category!!.data)
            for (permission in ManifestPatcher.CAR_PERMISSIONS) {
                assertEquals("uses-permission $permission count", 1, manifest.usesPermissions.count { it == permission })
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
