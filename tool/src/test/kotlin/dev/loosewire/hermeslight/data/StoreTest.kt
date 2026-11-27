/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Tests for encrypted profile persistence.
 */
package dev.loosewire.hermeslight.data

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    @Test
    fun `profile round-trips through the injected cipher and storage`() {
        val storage = MemoryStorage()
        val cipher = XorCipher()
        val store = ProfileStore(storage, cipher)
        val profile =
            Profile(
                endpoint = "https://hermes.example/dash/",
                authMode = AuthMode.PASSWORD,
                token = "token",
                username = "june",
                password = "hunter2",
                provider = "basic",
                botProfile = "June",
                selectedSessionId = "s/1",
                readPositions = mapOf("s/1" to 42),
                readOffsets = mapOf("s/1" to 120),
            )

        store.save(profile)

        val ciphertext = requireNotNull(storage.blob)
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains("hunter2"))
        assertTrue(cipher.plaintextSeen?.decodeToString()?.contains("hunter2") == true)
        assertEquals(profile, store.load())
        assertEquals(42, store.readPosition("s/1"))
        assertEquals(120, store.readOffset("s/1"))
    }

    @Test
    fun `absent storage is a first run not an error`() {
        val store = ProfileStore(MemoryStorage(), XorCipher())
        assertNull(store.load())
    }

    @Test
    fun `corrupt storage raises a safe store exception`() {
        val storage = MemoryStorage().apply { blob = "not-an-encrypted-profile".toByteArray() }
        val store = ProfileStore(storage, XorCipher())
        assertFailsWith<ProfileStoreException> { store.load() }
    }

    @Test
    fun `decrypt failure raises a store exception`() {
        val storage = MemoryStorage().apply { blob = byteArrayOf(1, 2, 3) }
        val store = ProfileStore(storage, RejectingCipher())
        assertFailsWith<ProfileStoreException> { store.load() }
    }

    @Test
    fun `updates preserve and extend read anchors`() {
        val store = ProfileStore(MemoryStorage(), XorCipher())
        store.save(Profile(endpoint = "https://hermes.example/"))

        assertTrue(store.setReadPosition("s1", 10))
        assertTrue(store.setReadOffset("s1", 64))
        assertTrue(store.setReadAnchor("s2", 11, 32))

        val loaded = store.load()
        assertEquals(mapOf("s1" to 10, "s2" to 11), loaded?.readPositions)
        assertEquals(mapOf("s1" to 64, "s2" to 32), loaded?.readOffsets)
        assertEquals(10, store.readPosition("s1"))
        assertEquals(32, store.readOffset("s2"))
    }

    @Test
    fun `updates without a profile do nothing`() {
        val store = ProfileStore(MemoryStorage(), XorCipher())
        assertNull(store.update { it.copy(botProfile = "June") })
        assertFalse(store.setReadPosition("s1", 1))
        assertFalse(store.setReadOffset("s1", 1))
    }

    @Test
    fun `blank endpoints are never saved`() {
        val storage = MemoryStorage()
        val store = ProfileStore(storage, XorCipher())
        assertFailsWith<IllegalArgumentException> {
            store.save(Profile(endpoint = " "))
        }
        assertNull(storage.blob)
    }

    @Test
    fun `file storage replaces atomically and clears`() {
        val directory = createTempDirectory("hermes-profile").toFile()
        try {
            val storage = FileProfileStorage(directory)
            val store = ProfileStore(storage, XorCipher())
            store.save(Profile(endpoint = "http://127.0.0.1:9121/", token = "tok"))

            assertTrue(File(directory, FileProfileStorage.DEFAULT_FILE_NAME).isFile)
            assertFalse(File(directory, "${FileProfileStorage.DEFAULT_FILE_NAME}.tmp").exists())
            assertEquals("http://127.0.0.1:9121/", store.load()?.endpoint)

            store.clear()
            assertNull(store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `file storage damage is reported not treated as a first run`() {
        val directory = createTempDirectory("hermes-profile-damage").toFile()
        try {
            val storage = FileProfileStorage(directory)
            storage.write("garbage".toByteArray())
            val store = ProfileStore(storage, XorCipher())
            assertFailsWith<ProfileStoreException> { store.load() }
        } finally {
            directory.deleteRecursively()
        }
    }

    private class MemoryStorage : ProfileStorage {
        var blob: ByteArray? = null

        override fun read(): ByteArray? = blob?.copyOf()

        override fun write(bytes: ByteArray) {
            blob = bytes.copyOf()
        }

        override fun clear() {
            blob = null
        }
    }

    private class XorCipher : SecretCipher {
        var plaintextSeen: ByteArray? = null

        override fun encrypt(plain: ByteArray): ByteArray {
            plaintextSeen = plain.copyOf()
            return ByteArray(plain.size) { index -> (plain[index].toInt() xor KEY).toByte() }
        }

        override fun decrypt(encrypted: ByteArray): ByteArray {
            if (encrypted.isEmpty()) throw IllegalArgumentException("empty")
            return ByteArray(encrypted.size) { index -> (encrypted[index].toInt() xor KEY).toByte() }
        }

        companion object {
            private const val KEY = 0x5a
        }
    }

    private class RejectingCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(encrypted: ByteArray): ByteArray = throw IllegalStateException("keystore key is gone")
    }
}
