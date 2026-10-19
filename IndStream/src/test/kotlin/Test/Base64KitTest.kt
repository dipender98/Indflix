package Test

import com.indstream.Base64Kit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Guards the runtime-independent base64 codec used by the encrypted sources. */
class Base64KitTest {

    @Test
    fun roundTripsEveryByteValue() {
        val data = ByteArray(256) { it.toByte() }
        val encoded = Base64Kit.encode(data)
        assertContentEquals(data, Base64Kit.decode(encoded))
    }

    @Test
    fun encodesLikeTheReferenceImplementation() {
        val samples = listOf(
            "" to "",
            "f" to "Zg==",
            "fo" to "Zm8=",
            "foo" to "Zm9v",
            "foob" to "Zm9vYg==",
            "fooba" to "Zm9vYmE=",
            "foobar" to "Zm9vYmFy",
        )
        for ((plain, expected) in samples) {
            assertEquals(expected, Base64Kit.encode(plain.toByteArray()), "encode '$plain'")
            assertEquals(plain, String(Base64Kit.decode(expected)), "decode '$expected'")
        }
    }

    @Test
    fun decodesWithoutPadding() {
        assertEquals("foobar", String(Base64Kit.decode("Zm9vYmFy")))
        assertEquals("foob", String(Base64Kit.decode("Zm9vYg")))
    }

    @Test
    fun ignoresWhitespaceInThePayload() {
        assertEquals("foobar", String(Base64Kit.decode("Zm9v\nYmFy")))
    }

    @Test
    fun rejectsCharactersOutsideTheAlphabet() {
        assertFailsWith<IllegalArgumentException> { Base64Kit.decode("!!!not-base64!!!") }
    }

    @Test
    fun customAlphabetMapsTheMultiCharTokenToOne() {
        // The '-_.' sequence is one symbol of the custom alphabet, '@' is another.
        assertEquals("/", Base64Kit.fromCustom("-_."))
        assertEquals("+", Base64Kit.fromCustom("@"))
        assertEquals("ab//+cd", Base64Kit.fromCustom("ab-_.-_.@cd"))
    }

    @Test
    fun customAlphabetPayloadDecodesLikeTheStandardOne() {
        val standard = Base64Kit.encode("a/b+c".toByteArray())
        val custom = standard.replace("/", "-_.").replace('+', '@')
        assertEquals("a/b+c", String(Base64Kit.decode(Base64Kit.fromCustom(custom))))
    }
}
