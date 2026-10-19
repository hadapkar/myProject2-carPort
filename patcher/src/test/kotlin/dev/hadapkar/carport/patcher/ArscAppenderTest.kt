package dev.hadapkar.carport.patcher

import com.reandroid.arsc.chunk.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds small resource tables byte by byte, runs [ArscAppender] on them, and checks the
 * result with ARSCLib's independent parser: the existing resource must be untouched and
 * the new xml entry must resolve to the file path.
 */
class ArscAppenderTest {

    @Test
    fun utf8PoolWithStyles() = check(utf8 = true, withStyle = true)

    @Test
    fun utf8PoolWithoutStyles() = check(utf8 = true, withStyle = false)

    @Test
    fun utf16PoolWithStyles() = check(utf8 = false, withStyle = true)

    @Test
    fun utf16PoolWithoutStyles() = check(utf8 = false, withStyle = false)

    private fun check(utf8: Boolean, withStyle: Boolean) {
        val original = buildTable(utf8, withStyle)
        // Sanity: the hand-built table parses and has the existing string resource.
        val before = TableBlock.load(ByteArrayInputStream(original))
        val first = before.getResource(EXISTING_ID).get().resValue.valueAsString
        // With a style, ARSCLib renders the span as markup around "He".
        assertEquals(if (withStyle) "<World>He</World>llo" else "Hello", first)

        val result = ArscAppender.addXmlFile(original, "carport_desc", "res/xml/carport_desc.xml")
        assertEquals(0x7f020000, result.resourceId) // type 2 = the new "xml" type, entry 0

        val after = TableBlock.load(ByteArrayInputStream(result.table))
        assertEquals(first, after.getResource(EXISTING_ID).get().resValue.valueAsString)
        assertEquals("World", after.getResource(EXISTING_ID + 1).get().resValue.valueAsString)
        val added = after.getResource(result.resourceId)
        assertEquals("res/xml/carport_desc.xml", added.get().resValue.valueAsString)
        assertEquals("carport_desc", added.name)
        assertEquals("xml", added.type)
        if (withStyle) {
            // The styled string keeps its style span after the pool grew.
            assertNotEquals(0, after.stringPool.styleArray.size())
        }
    }

    companion object {
        const val EXISTING_ID = 0x7f010000

        /** Table: global pool ["Hello", "World"], package 0x7f with type "string" = 2 entries. */
        fun buildTable(utf8: Boolean, withStyle: Boolean): ByteArray {
            val global = stringPool(listOf("Hello", "World"), utf8, withStyle)
            val pkg = packageChunk(utf8)
            val size = 12 + global.size + pkg.size
            return ByteArrayOutputStream().apply {
                write(le(0x0002, 2)); write(le(12, 2)); write(le(size, 4)); write(le(1, 4))
                write(global)
                write(pkg)
            }.toByteArray()
        }

        private fun packageChunk(utf8: Boolean): ByteArray {
            val typePool = stringPool(listOf("string"), utf8, false)
            val keyPool = stringPool(listOf("greeting", "subject"), utf8, false)
            val spec = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0x0202); putShort(16); putInt(24); put(1); put(0); putShort(1); putInt(2)
                putInt(0); putInt(0)
            }.array()
            val type = typeChunk()
            val headerSize = 288
            val size = headerSize + typePool.size + keyPool.size + spec.size + type.size
            val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0x0200); putShort(headerSize.toShort()); putInt(size); putInt(0x7f)
                "com.example".forEach { putChar(it) }
                position(268)
                putInt(headerSize) // typeStrings
                putInt(1) // lastPublicType
                putInt(headerSize + typePool.size) // keyStrings
                putInt(2) // lastPublicKey
                putInt(0) // typeIdOffset
            }.array()
            return header + typePool + keyPool + spec + type
        }

        /** Default-config type 1 with entries 0 -> string 0, 1 -> string 1. */
        private fun typeChunk(): ByteArray {
            val headerSize = 20 + 64
            val entriesStart = headerSize + 8
            val size = entriesStart + 32
            return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0x0201); putShort(headerSize.toShort()); putInt(size)
                put(1); put(0); putShort(0); putInt(2); putInt(entriesStart); putInt(64)
                position(headerSize)
                putInt(0); putInt(16)
                for (i in 0..1) {
                    putShort(8); putShort(0); putInt(i) // entry, key i
                    putShort(8); put(0); put(0x03); putInt(i) // string i
                }
            }.array()
        }

        /** A ResStringPool; with [withStyle], string 0 gets one bold span. */
        fun stringPool(strings: List<String>, utf8: Boolean, withStyle: Boolean): ByteArray {
            val data = ByteArrayOutputStream()
            val offsets = strings.map { s ->
                val at = data.size()
                if (utf8) {
                    val bytes = s.toByteArray(Charsets.UTF_8)
                    data.write(s.length); data.write(bytes.size); data.write(bytes); data.write(0)
                } else {
                    data.write(le(s.length, 2)); s.forEach { data.write(le(it.code, 2)) }; data.write(le(0, 2))
                }
                at
            }
            while (data.size() % 4 != 0) data.write(0)
            // Style for string 0: span name = string index 1 ("World" used as the tag name), chars 0..1.
            val styles = if (withStyle) {
                ByteArrayOutputStream().apply {
                    write(le(1, 4)); write(le(0, 4)); write(le(1, 4)); write(le(-1, 4)) // span + END
                    write(le(-1, 4)); write(le(-1, 4)) // pool terminator
                }.toByteArray()
            } else {
                ByteArray(0)
            }
            val styleCount = if (withStyle) 1 else 0
            val headerSize = 28
            val stringsStart = headerSize + 4 * (strings.size + styleCount)
            val stylesStart = if (withStyle) stringsStart + data.size() else 0
            val size = stringsStart + data.size() + styles.size
            return ByteArrayOutputStream().apply {
                write(le(0x0001, 2)); write(le(headerSize, 2)); write(le(size, 4))
                write(le(strings.size, 4)); write(le(styleCount, 4))
                write(le(if (utf8) 0x100 else 0, 4)); write(le(stringsStart, 4)); write(le(stylesStart, 4))
                offsets.forEach { write(le(it, 4)) }
                if (withStyle) write(le(0, 4))
                write(data.toByteArray())
                write(styles)
            }.toByteArray()
        }

        private fun le(value: Int, bytes: Int) = ByteArray(bytes) { (value shr (8 * it)).toByte() }
    }
}
