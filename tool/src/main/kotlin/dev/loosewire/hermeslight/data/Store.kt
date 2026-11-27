/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. The encrypted-profile shape follows Hermes Mobile's
 * connection settings, licensed under the Apache License, Version 2.0.
 * See NOTICE.
 */
package dev.loosewire.hermeslight.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A stored profile exists but cannot be read or written honestly. */
class ProfileStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Encrypt/decrypt bytes for persistence. Pure implementations make the store unit-testable. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(encrypted: ByteArray): ByteArray
}

/** Where the encrypted profile blob lives. Absent means a first run, not corruption. */
interface ProfileStorage {
    fun read(): ByteArray?

    fun write(bytes: ByteArray)

    fun clear()
}

val storeJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

/**
 * Encrypted-at-rest profile storage.
 *
 * [load] returns null only when no profile has ever been saved. A file that exists but cannot be
 * decrypted or decoded raises [ProfileStoreException], so the Setup screen can say the stored
 * profile is unreadable instead of silently pretending this is a first run. [save] replaces the
 * blob through a temporary file so a crash never leaves a half-written profile.
 */
class ProfileStore(
    private val storage: ProfileStorage,
    private val cipher: SecretCipher,
    private val json: Json = storeJson,
) {
    fun load(): Profile? {
        val blob =
            try {
                storage.read()
            } catch (exception: Exception) {
                throw ProfileStoreException("Stored profile could not be read", exception)
            } ?: return null
        val plain =
            try {
                cipher.decrypt(blob)
            } catch (exception: Exception) {
                throw ProfileStoreException("Stored profile could not be decrypted", exception)
            }
        try {
            return try {
                json.decodeFromString<Profile>(plain.decodeToString())
            } catch (exception: Exception) {
                throw ProfileStoreException("Stored profile is damaged", exception)
            }
        } finally {
            plain.fill(0)
        }
    }

    fun save(profile: Profile) {
        require(profile.endpoint.isNotBlank()) { "Profile endpoint is required" }
        val plain = json.encodeToString(Profile.serializer(), profile).encodeToByteArray()
        val encrypted =
            try {
                cipher.encrypt(plain)
            } catch (exception: Exception) {
                throw ProfileStoreException("Profile could not be encrypted", exception)
            } finally {
                plain.fill(0)
            }
        try {
            storage.write(encrypted)
        } catch (exception: Exception) {
            throw ProfileStoreException("Profile could not be saved", exception)
        }
    }

    fun update(transform: (Profile) -> Profile): Profile? {
        val current = load() ?: return null
        val next = transform(current)
        save(next)
        return next
    }

    fun clear() {
        try {
            storage.clear()
        } catch (exception: Exception) {
            throw ProfileStoreException("Stored profile could not be cleared", exception)
        }
    }

    /** Save the transcript row id for a session; false when no profile exists yet. */
    fun setReadPosition(
        sessionId: String,
        rowId: Int,
    ): Boolean {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        require(rowId >= 0) { "Row id must not be negative" }
        return update { profile ->
            profile.copy(readPositions = profile.readPositions + (sessionId to rowId))
        } != null
    }

    /** Save the pixel offset inside [sessionId]'s anchored row; false when no profile exists yet. */
    fun setReadOffset(
        sessionId: String,
        offsetPixels: Int,
    ): Boolean {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        require(offsetPixels >= 0) { "Scroll offset must not be negative" }
        return update { profile ->
            profile.copy(readOffsets = profile.readOffsets + (sessionId to offsetPixels))
        } != null
    }

    /** Save row anchor and pixel offset together so both survive one write. */
    fun setReadAnchor(
        sessionId: String,
        rowId: Int,
        offsetPixels: Int,
    ): Boolean {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        require(rowId >= 0) { "Row id must not be negative" }
        require(offsetPixels >= 0) { "Scroll offset must not be negative" }
        return update { profile ->
            profile.copy(
                readPositions = profile.readPositions + (sessionId to rowId),
                readOffsets = profile.readOffsets + (sessionId to offsetPixels),
            )
        } != null
    }

    fun readPosition(sessionId: String): Int? {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        return load()?.readPositions?.get(sessionId)
    }

    fun readOffset(sessionId: String): Int? {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        return load()?.readOffsets?.get(sessionId)
    }
}

/** File-backed profile blob under the app's private files directory. */
class FileProfileStorage(
    rootDirectory: File,
    fileName: String = DEFAULT_FILE_NAME,
) : ProfileStorage {
    private val directory = rootDirectory
    private val file = File(directory, fileName)
    private val temporary = File(directory, "$fileName.tmp")

    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun write(bytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create the profile directory")
        }
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Unable to replace the stored profile")
        }
    }

    override fun clear() {
        if (file.exists() && !file.delete()) {
            throw IOException("Unable to remove the stored profile")
        }
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Unable to remove the temporary profile")
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME: String = "hermes-profile.enc"
    }
}

/** AES/GCM key held by Android Keystore; the key never leaves the device. */
class KeystoreCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        if (encrypted.size <= IV_SIZE) {
            throw IllegalArgumentException("Encrypted profile is too short")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, encrypted, 0, IV_SIZE),
        )
        return cipher.doFinal(encrypted, IV_SIZE, encrypted.size - IV_SIZE)
    }

    /** Remove the Keystore key on sign-out; the profile file is cleared separately. */
    fun deleteKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS: String = "hermes-light-profile-v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }
}
