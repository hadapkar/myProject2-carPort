package dev.hadapkar.carport.patcher

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adds one file resource to a binary resources.arsc without parsing the whole table.
 *
 * Loading a large table (17.8 MB for Firefox) with a full parser needs over 200 MB of heap,
 * more than a phone app gets. Android Auto only needs one new `xml` entry, so this works on
 * raw bytes: every existing chunk is copied unchanged, and only these are appended:
 *  - the file path, to the end of the global string pool (no existing string index moves);
 *  - the type name "xml" and the entry key, to the end of the package's type/key pools;
 *  - a new type spec + type chunk holding the single entry, at the end of the package.
 *
 * Using a new type id (named "xml") instead of growing the existing xml type means no existing
 * resource id, type chunk or offset changes. Everything is written into one pre-sized buffer,
 * so memory use stays near twice the table size.
 * Layouts follow frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h.
 */
internal object ArscAppender {
    class Result(val table: ByteArray, val resourceId: Int)

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202

    private const val POOL_SORTED_FLAG = 0x1
    private const val POOL_UTF8_FLAG = 0x100
    private const val TYPE_STRING = 0x03
    private const val APP_PACKAGE_ID = 0x7f
    private const val CONFIG_SIZE = 64

    /** Growable output whose finished bytes can be taken without an extra copy when sized right. */
    private class Out(capacity: Int) : ByteArrayOutputStream(capacity) {
        fun putU32(at: Int, value: Int) = ArscAppender.putU32(buf, at, value)
        fun bytes(): ByteArray = if (count == buf.size) buf else buf.copyOf(count)
    }

    /** Adds `xml/[entryName]` whose value is the file [filePath]; returns the new table and resource id. */
    fun addXmlFile(table: ByteArray, entryName: String, filePath: String): Result {
        val tableHeaderSize = u16(table, 2)
        val tableSize = u32(table, 4)
        check(u16(table, 0) == RES_TABLE_TYPE && tableHeaderSize >= 12 && tableSize <= table.size) {
            "not a resource table"
        }

        // Children of the table: the global string pool, then packages (anything else is copied).
        val chunks = children(table, tableHeaderSize, tableSize)
        val pool = chunks.firstOrNull { u16(table, it.first) == RES_STRING_POOL_TYPE }
            ?: error("resource table has no global string pool")
        val target = pickPackage(table, chunks)
        // Appended last, so the new string's index is the current count.
        val valueStringIndex = u32(table, pool.first + 8)

        val out = Out(tableSize + 4096)
        out.write(table, 0, tableHeaderSize)
        var resourceId = 0
        for (chunk in chunks) {
            when (chunk) {
                pool -> writePoolWithString(table, chunk.first, out, filePath)
                target -> resourceId = writePackageWithEntry(table, chunk.first, out, entryName, valueStringIndex)
                else -> out.write(table, chunk.first, chunk.second)
            }
        }
        out.putU32(4, out.size())
        return Result(out.bytes(), resourceId)
    }

    private fun pickPackage(table: ByteArray, chunks: List<Pair<Int, Int>>): Pair<Int, Int> {
        val packages = chunks.filter { u16(table, it.first) == RES_TABLE_PACKAGE_TYPE }
        check(packages.isNotEmpty()) { "resource table has no package" }
        return packages.firstOrNull { u32(table, it.first + 8) == APP_PACKAGE_ID } ?: packages.first()
    }

    /** Writes the package chunk at [off] plus the new entry; returns the entry's resource id. */
    private fun writePackageWithEntry(src: ByteArray, off: Int, out: Out, entryName: String, valueStringIndex: Int): Int {
        val headerSize = u16(src, off + 2)
        val size = u32(src, off + 4)
        check(headerSize >= 284) { "unexpected package header" }
        val packageId = u32(src, off + 8)
        check(packageId in 1..0xff) { "unsupported package id $packageId" }
        val typeStringsOffset = u32(src, off + 268)
        val keyStringsOffset = u32(src, off + 276)
        val typeIdOffset = if (headerSize >= 288) u32(src, off + 284) else 0

        val chunks = children(src, off + headerSize, off + size)
        check(chunks.any { it.first == off + typeStringsOffset } && chunks.any { it.first == off + keyStringsOffset } &&
            typeStringsOffset != keyStringsOffset) { "package type/key string pools not found" }

        val start = out.size()
        out.write(src, off, headerSize)
        var typeNameIndex = -1
        var keyIndex = -1
        for (chunk in chunks) {
            when (chunk.first - off) {
                typeStringsOffset -> {
                    out.putU32(start + 268, out.size() - start)
                    typeNameIndex = writePoolWithString(src, chunk.first, out, "xml")
                }
                keyStringsOffset -> {
                    out.putU32(start + 276, out.size() - start)
                    keyIndex = writePoolWithString(src, chunk.first, out, entryName)
                }
                else -> out.write(src, chunk.first, chunk.second)
            }
        }
        val typeId = typeNameIndex + 1 + typeIdOffset
        check(typeId in 1..0xff) { "the app already uses every resource type id" }
        out.write(typeSpecChunk(typeId))
        out.write(typeChunk(typeId, keyIndex, valueStringIndex))
        out.putU32(start + 4, out.size() - start)
        return (packageId shl 24) or (typeId shl 16)
    }

    /** ResTable_typeSpec with one entry and one ResTable_type. */
    private fun typeSpecChunk(typeId: Int): ByteArray = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(RES_TABLE_TYPE_SPEC_TYPE.toShort())
        putShort(16) // headerSize
        putInt(20) // size
        put(typeId.toByte())
        put(0) // res0
        putShort(1) // typesCount
        putInt(1) // entryCount
        putInt(0) // flags[0]: no configuration variants
    }.array()

    /** Dense ResTable_type for the default configuration holding entry 0 = string [valueStringIndex]. */
    private fun typeChunk(typeId: Int, keyIndex: Int, valueStringIndex: Int): ByteArray {
        val headerSize = 20 + CONFIG_SIZE
        val entriesStart = headerSize + 4
        val size = entriesStart + 16
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(RES_TABLE_TYPE_TYPE.toShort())
            putShort(headerSize.toShort())
            putInt(size)
            put(typeId.toByte())
            put(0) // flags: dense, 32-bit offsets
            putShort(0) // reserved
            putInt(1) // entryCount
            putInt(entriesStart)
            putInt(CONFIG_SIZE) // ResTable_config.size; every other field 0 = default config
            position(headerSize)
            putInt(0) // offset of entry 0
            // ResTable_entry
            putShort(8)
            putShort(0)
            putInt(keyIndex)
            // Res_value
            putShort(8)
            put(0)
            put(TYPE_STRING.toByte())
            putInt(valueStringIndex)
        }.array()
    }

    /**
     * Writes the ResStringPool chunk at [off] with [value] appended and returns the string's index.
     * Existing strings, styles and their indices are untouched; the sorted flag is cleared
     * because the new string is not in order.
     */
    private fun writePoolWithString(src: ByteArray, off: Int, out: Out, value: String): Int {
        check(u16(src, off) == RES_STRING_POOL_TYPE) { "not a string pool" }
        val headerSize = u16(src, off + 2)
        val size = u32(src, off + 4)
        val stringCount = u32(src, off + 8)
        val styleCount = u32(src, off + 12)
        val flags = u32(src, off + 16)
        val stringsStart = u32(src, off + 20)
        val stylesStart = u32(src, off + 24)
        check(headerSize >= 28) { "unexpected string pool header" }
        val hasStyles = styleCount > 0 && stylesStart > 0
        val stringDataStart = if (stringCount > 0) stringsStart else headerSize + 4 * (stringCount + styleCount)
        val stringDataEnd = if (hasStyles) stylesStart else size
        val stringDataSize = (stringDataEnd - stringDataStart).coerceAtLeast(0)

        val encoded = if (flags and POOL_UTF8_FLAG != 0) encodeUtf8(value) else encodeUtf16(value)
        val newStringDataSize = pad4(stringDataSize + encoded.size)
        val newStringsStart = headerSize + 4 * (stringCount + 1 + styleCount)
        val newStylesStart = if (hasStyles) newStringsStart + newStringDataSize else 0
        val styleDataSize = if (hasStyles) size - stylesStart else 0
        val newSize = newStringsStart + newStringDataSize + styleDataSize

        val start = out.size()
        out.write(src, off, headerSize)
        out.putU32(start + 4, newSize)
        out.putU32(start + 8, stringCount + 1)
        out.putU32(start + 16, flags and POOL_SORTED_FLAG.inv())
        out.putU32(start + 20, newStringsStart)
        out.putU32(start + 24, newStylesStart)
        out.write(src, off + headerSize, 4 * stringCount) // string offsets, relative: unchanged
        out.write(le32(stringDataSize)) // offset of the new string
        out.write(src, off + headerSize + 4 * stringCount, 4 * styleCount) // style offsets: unchanged
        if (stringDataSize > 0) out.write(src, off + stringDataStart, stringDataSize)
        out.write(encoded)
        repeat(newStringDataSize - stringDataSize - encoded.size) { out.write(0) }
        if (hasStyles) out.write(src, off + stylesStart, styleDataSize)
        check(out.size() - start == newSize) { "string pool size mismatch" }
        return stringCount
    }

    private fun encodeUtf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            writeLength8(value.length)
            writeLength8(bytes.size)
            write(bytes)
            write(0)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLength8(length: Int) {
        check(length < 0x8000) { "string too long" }
        if (length > 0x7f) write((length shr 8) or 0x80)
        write(length and 0xff)
    }

    private fun encodeUtf16(value: String): ByteArray {
        check(value.length < 0x8000) { "string too long" }
        val buffer = ByteBuffer.allocate(2 + value.length * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(value.length.toShort())
        value.forEach { buffer.putChar(it) }
        buffer.putShort(0)
        return buffer.array()
    }

    private fun pad4(size: Int): Int = (size + 3) and 3.inv()

    /** (offset, size) of each chunk in [start, end). */
    private fun children(data: ByteArray, start: Int, end: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var p = start
        while (p + 8 <= end) {
            val chunkSize = u32(data, p + 4)
            check(chunkSize >= 8 && p + chunkSize <= end) { "corrupt chunk at $p" }
            result += p to chunkSize
            p += chunkSize
        }
        return result
    }

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun u16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or ((data[at + 3].toInt() and 0xff) shl 24)

    private fun putU32(data: ByteArray, at: Int, value: Int) {
        data[at] = value.toByte()
        data[at + 1] = (value shr 8).toByte()
        data[at + 2] = (value shr 16).toByte()
        data[at + 3] = (value shr 24).toByte()
    }
}
