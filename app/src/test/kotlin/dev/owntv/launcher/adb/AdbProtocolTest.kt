package dev.owntv.launcher.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdbProtocolTest {
    @Test
    fun `messages encode with a little endian header and decode back`() {
        val message = AdbProtocol.open(1, "shell:echo hi")
        val bytes = AdbProtocol.encode(message)
        assertEquals(AdbProtocol.HEADER_SIZE + message.payload.size, bytes.size)
        assertContentEquals(byteArrayOf(0x4f, 0x50, 0x45, 0x4e), bytes.copyOfRange(0, 4)) // "OPEN"
        val header = AdbProtocol.decodeHeader(bytes)!!
        assertEquals(AdbProtocol.Header(AdbProtocol.OPEN, 1, 0, message.payload.size), header)
        assertContentEquals("shell:echo hi\u0000".toByteArray(), bytes.copyOfRange(AdbProtocol.HEADER_SIZE, bytes.size))
    }

    @Test
    fun `a header with the wrong magic is rejected`() {
        val bytes = AdbProtocol.encode(AdbProtocol.connect())
        bytes[20] = (bytes[20] + 1).toByte()
        assertNull(AdbProtocol.decodeHeader(bytes))
    }

    @Test
    fun `the public key uses adbd's format and carries the modulus`() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val text = String(AdbProtocol.publicKeyPayload(key, "OwnTV@tv"))
        assert(text.endsWith(" OwnTV@tv\u0000"))
        val raw = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(4 + 4 + 256 + 256 + 4, raw.size)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.getInt())
        val n0inv = buffer.getInt().toLong() and 0xffffffffL
        val modulus = ByteArray(256).also { buffer.get(it) }.reversedArray()
        assertEquals(key.modulus, BigInteger(1, modulus))
        // n0inv * n[0] == -1 mod 2^32
        val r32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(r32 - BigInteger.ONE, BigInteger.valueOf(n0inv).multiply(key.modulus).mod(r32))
        val rr = ByteArray(256).also { buffer.get(it) }.reversedArray()
        assertEquals(BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), key.modulus), BigInteger(1, rr))
        assertEquals(key.publicExponent.toInt(), buffer.getInt())
    }

    @Test
    fun `a signed token opens to the SHA-1 DigestInfo and the token`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val token = ByteArray(20) { it.toByte() }
        val signature = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, pair.private)
            doFinal(AdbProtocol.signaturePayload(token))
        }
        val opened = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, pair.public)
            doFinal(signature)
        }
        assertContentEquals(AdbProtocol.SHA1_DIGEST_INFO + token, opened)
    }

    @Test
    fun `shell output is split from its exit status`() {
        assertEquals(AdbProtocol.ShellResult("ok", 0), AdbProtocol.parseShellOutput("ok\n__owntv_exit=0\n"))
        assertEquals(AdbProtocol.ShellResult("Error: unknown", 255), AdbProtocol.parseShellOutput("Error: unknown\n__owntv_exit=255\r\n"))
        assertEquals(AdbProtocol.ShellResult("", 0), AdbProtocol.parseShellOutput("__owntv_exit=0"))
        assertEquals(AdbProtocol.ShellResult("cut off", null), AdbProtocol.parseShellOutput("cut off"))
        assertEquals("shell:pm list; echo __owntv_exit=\$?", AdbProtocol.shellService("pm list"))
    }

    @Test
    fun `only adb shell lines are runnable`() {
        assertEquals("pm enable com.example", AdbProtocol.shellCommandOf("adb shell pm enable com.example"))
        assertNull(AdbProtocol.shellCommandOf("adb push my-layout.json /sdcard/"))
        assertNull(AdbProtocol.shellCommandOf("adb shell "))
    }
}
