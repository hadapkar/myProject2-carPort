package dev.hadapkar.carport.patcher

import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.writer.ZipAligner
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import java.io.File
import java.util.regex.Pattern

data class PatchResult(
    val packageName: String,
    val versionCode: Int?,
    val versionName: String?,
    /** Signed APKs ready for one install session; the first one is the base APK. */
    val apks: List<File>,
    val changes: List<String>,
)

/**
 * Patches an app (base APK plus any split APKs) so Android Auto lists it, and re-signs
 * every APK with [key]. All splits must be re-signed with the same key, or Android
 * rejects the install.
 */
class ApkPatcher(private val key: SigningKey) {

    fun patch(
        base: File,
        splits: List<File> = emptyList(),
        outputDir: File,
        options: PatchOptions = PatchOptions(),
        progress: (String) -> Unit = {},
    ): PatchResult {
        if (!base.isFile) throw PatchException("APK not found: ${base.path}")
        outputDir.mkdirs()
        outputDir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }?.forEach { it.delete() }
        val work = File(outputDir, ".work").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            progress("Patching the app manifest")
            val unsignedBase = File(work, "base.apk")
            val info = patchBase(base, unsignedBase, options)

            val outputs = mutableListOf<File>()
            progress("Signing the base APK")
            outputs += File(outputDir, "base.apk").also { sign(unsignedBase, it) }

            val usedNames = mutableSetOf("base.apk")
            splits.forEachIndexed { index, split ->
                progress("Signing split APK ${index + 1} of ${splits.size}")
                val name = uniqueName(split.name, index, usedNames)
                val unsignedSplit = File(work, name)
                rewriteSplit(split, unsignedSplit)
                outputs += File(outputDir, name).also { sign(unsignedSplit, it) }
            }
            return PatchResult(info.packageName, info.versionCode, info.versionName, outputs, info.changes)
        } catch (e: PatchException) {
            throw e
        } catch (e: Exception) {
            throw PatchException("Patching failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            work.deleteRecursively()
        }
    }

    private class BaseInfo(
        val packageName: String,
        val versionCode: Int?,
        val versionName: String?,
        val changes: List<String>,
    )

    private fun patchBase(input: File, output: File, options: PatchOptions): BaseInfo {
        val module = ApkModule.loadApkFile(input)
        try {
            val manifest = module.androidManifest
                ?: throw PatchException("This file is not an Android app: AndroidManifest.xml is missing.")
            if (manifest.isSplit) {
                throw PatchException("This is a split APK. Pick the app itself, or its base APK.")
            }
            val packageName = manifest.packageName
                ?: throw PatchException("The manifest has no package name.")

            val manifestPatcher = ManifestPatcher(manifest, options)
            manifestPatcher.patch { addAutomotiveDescriptor(module) }

            stripSignatures(module)
            module.refreshTable()
            module.refreshManifest()
            writeUnsigned(module, output)
            return BaseInfo(packageName, manifest.versionCode, manifest.versionName, manifestPatcher.changes)
        } finally {
            module.close()
        }
    }

    /** Adds res/xml/carport_automotive_app_desc.xml and returns its resource id. */
    private fun addAutomotiveDescriptor(module: ApkModule): Int {
        val table = module.getTableBlock(false)
            ?: throw PatchException("This app has no resource table (resources.arsc), so its car declaration cannot be added.")
        val packageBlock = table.pickOne(APP_PACKAGE_ID) ?: table.pickOne()
            ?: throw PatchException("The app's resource table has no package.")
        val entry = packageBlock.getOrCreate("", "xml", AUTOMOTIVE_DESC_NAME)
        entry.setValueAsString(AUTOMOTIVE_DESC_PATH)
        module.removeInputSource(AUTOMOTIVE_DESC_PATH)
        module.add(ByteInputSource(automotiveDescriptorXml(), AUTOMOTIVE_DESC_PATH))
        return entry.resourceId
    }

    private fun rewriteSplit(input: File, output: File) {
        val module = ApkModule.loadApkFile(input)
        try {
            stripSignatures(module)
            writeUnsigned(module, output)
        } finally {
            module.close()
        }
    }

    /** Drops the old v1 (JAR) signature files, Play source stamp and v2+ signing block. */
    private fun stripSignatures(module: ApkModule) {
        module.listInputSources()
            .map { it.alias }
            .filter { SIGNATURE_FILE.matcher(it).matches() || it == SOURCE_STAMP_FILE }
            .forEach { module.removeInputSource(it) }
        module.apkSignatureBlock = null
    }

    private fun writeUnsigned(module: ApkModule, output: File) {
        output.delete()
        val writer = module.createApkFileWriter(output)
        writer.setZipAligner(ZipAligner().apply {
            setDefaultAlignment(4)
            setFileAlignment(Pattern.compile("^lib/.+\\.so$"), LIBRARY_PAGE_ALIGNMENT)
        })
        writer.write()
    }

    private fun sign(input: File, output: File) {
        output.delete()
        val signerConfig = ApkSigner.SignerConfig.Builder(SIGNER_NAME, key.privateKey, listOf(key.certificate)).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(SIGNING_MIN_SDK)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .setDebuggableApkPermitted(true)
            .setLibraryPageAlignmentBytes(LIBRARY_PAGE_ALIGNMENT)
            .build()
            .sign()
    }

    private fun uniqueName(original: String, index: Int, used: MutableSet<String>): String {
        val base = original.takeIf { it.endsWith(".apk") && it != "base.apk" } ?: "split_$index.apk"
        var name = base
        var n = 1
        while (!used.add(name)) {
            name = base.removeSuffix(".apk") + "_${n++}.apk"
        }
        return name
    }

    companion object {
        const val AUTOMOTIVE_DESC_NAME = "carport_automotive_app_desc"
        const val AUTOMOTIVE_DESC_PATH = "res/xml/$AUTOMOTIVE_DESC_NAME.xml"

        private const val APP_PACKAGE_ID = 0x7f
        private const val SIGNER_NAME = "CARPORT"
        /** v2/v3 only: CarPort itself needs Android 15, so v1 signatures are never needed. */
        private const val SIGNING_MIN_SDK = 28
        /** 16 KB covers both 4 KB and 16 KB page-size devices. */
        private const val LIBRARY_PAGE_ALIGNMENT = 16384
        private const val SOURCE_STAMP_FILE = "stamp-cert-sha256"
        private val SIGNATURE_FILE: Pattern =
            Pattern.compile("^META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))$", Pattern.CASE_INSENSITIVE)

        /** `<automotiveApp><uses name="media"/></automotiveApp>`, the descriptor AA Browser ships. */
        fun automotiveDescriptorXml(): ByteArray {
            val document = ResXmlDocument()
            val root = document.createRootElement("automotiveApp")
            root.createChildElement("uses")
                .getOrCreateAttribute("name", 0)
                .setValueAsString("media")
            document.refreshFull()
            return document.bytes
        }
    }
}
