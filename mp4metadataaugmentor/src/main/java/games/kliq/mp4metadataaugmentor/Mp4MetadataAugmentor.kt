package games.kliq.mp4metadataaugmentor

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Mp4MetadataAugmentor(
    private val dataAtomType: String = "kliQ",
    private val pointerAtomType: String = "kPTR",
    private val logger: Logger = DefaultLogger
) {

    interface Logger {
        fun d(tag: String, msg: String)
        fun i(tag: String, msg: String)
        fun w(tag: String, msg: String)
        fun e(tag: String, msg: String, throwable: Throwable? = null)
    }

    private object DefaultLogger : Logger {
        override fun d(tag: String, msg: String) = println("[$tag][DEBUG] $msg")
        override fun i(tag: String, msg: String) = println("[$tag][INFO] $msg")
        override fun w(tag: String, msg: String) = println("[$tag][WARN] $msg")
        override fun e(tag: String, msg: String, throwable: Throwable?) {
            println("[$tag][ERROR] $msg")
            throwable?.printStackTrace()
        }
    }

    companion object {
        private const val TAG = "Mp4Augmentor"

        // Base structure size: 4 (Size) + 4 (Type) + 4 (Target Size Data) = 12
        private const val POINTER_BASE_SIZE = 12
        private const val SALT_SIZE = 16

        // Complete structural footer size calculation: 12 + 1 (Flag) + 16 (Salt) = 29 bytes
        private const val TOTAL_FOOTER_SIZE = POINTER_BASE_SIZE + 1 + SALT_SIZE

        private const val CRYPTO_ALGORITHM = "AES/CBC/PKCS5Padding"
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ITERATION_COUNT = 10000
        private const val KEY_LENGTH_BITS = 256
    }

    init {
        require(dataAtomType.length == 4 && pointerAtomType.length == 4) {
            "MP4 atom types must be exactly 4 characters (FourCC)."
        }
    }

    // ============================================================================================
    // REGULAR FILE API (For App-Private Internal Storage Cache Pathways)
    // ============================================================================================

    /**
     * Appends metadata onto the file. If [password] is provided, the payload is securely encrypted.
     */
    fun appendMetadata(videoFile: File, payload: String, password: String? = null): Boolean {
        if (!videoFile.exists()) {
            logger.e(TAG, "Target video file does not exist: ${videoFile.absolutePath}")
            return false
        }

        return try {
            val (dataBuffer, pointerBuffer) = buildBuffers(payload, password)

            RandomAccessFile(videoFile, "rw").use { raf ->
                val fileLength = raf.length()
                val existingDataAtomSize = locateExistingDataAtomSize(raf, fileLength)

                if (existingDataAtomSize != -1) {
                    val truncationOffset = fileLength - TOTAL_FOOTER_SIZE - existingDataAtomSize
                    logger.d(TAG, "Found existing layout data. Truncating file back to byte offset $truncationOffset")
                    raf.setLength(truncationOffset)
                    raf.seek(truncationOffset)
                } else {
                    raf.seek(fileLength)
                }

                raf.write(dataBuffer.array())
                raf.write(pointerBuffer.array())
            }

            logger.d(TAG, "Injected metadata wrapper cleanly into local file: ${videoFile.absolutePath}")
            true
        } catch (e: Exception) {
            logger.e(TAG, "Failed to append custom metadata structure safely.", e)
            false
        }
    }

    /**
     * Inspects the file trailer footer properties.
     * Returns true if the metadata payload demands a decryption password, false otherwise.
     */
    fun isMetadataPasswordProtected(augmentedVideo: File): Boolean {
        if (!augmentedVideo.exists()) return false
        return try {
            RandomAccessFile(augmentedVideo, "r").use { raf ->
                val footerInfo = readFooterMetadata(raf, raf.length())
                footerInfo?.isEncrypted ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extracts and processes the metadata layer payload from a local file.
     * Throws an [IllegalArgumentException] if a password is required but wrong or omitted.
     */
    fun extractMetadata(augmentedVideo: File, password: String? = null): String? {
        if (!augmentedVideo.exists()) return null

        return try {
            RandomAccessFile(augmentedVideo, "r").use { raf ->
                val fileLength = raf.length()
                val footerInfo = readFooterMetadata(raf, fileLength) ?: return null

                val dataAtomSize = footerInfo.dataAtomSize
                if (dataAtomSize <= 8) return null

                val absolutePayloadPos = fileLength - TOTAL_FOOTER_SIZE - dataAtomSize + 8
                val payloadSize = dataAtomSize - 8

                if (absolutePayloadPos >= 0 && absolutePayloadPos + payloadSize <= fileLength) {
                    raf.seek(absolutePayloadPos)
                    val rawAtomBytes = ByteArray(payloadSize)
                    raf.readFully(rawAtomBytes)

                    processExtractedBytes(rawAtomBytes, footerInfo, password)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            handleExtractionException(e)
            null
        }
    }

    /**
     * Truncates the file back to its baseline video-only length.
     */
    fun stripMetadata(videoFile: File): Boolean {
        if (!videoFile.exists()) {
            logger.e(TAG, "Target file does not exist for stripping: ${videoFile.absolutePath}")
            return false
        }

        return try {
            RandomAccessFile(videoFile, "rw").use { raf ->
                val fileLength = raf.length()
                val existingDataAtomSize = locateExistingDataAtomSize(raf, fileLength)

                if (existingDataAtomSize != -1) {
                    val baselineOffset = fileLength - TOTAL_FOOTER_SIZE - existingDataAtomSize
                    logger.i(TAG, "Metadata track verified. Stripping $existingDataAtomSize bytes. Reverting length to $baselineOffset.")

                    raf.setLength(baselineOffset)
                    true
                } else {
                    logger.w(TAG, "Strip request ignored: No custom tracking atoms detected in target file.")
                    false
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to strip metadata safely from file: ${videoFile.absolutePath}", e)
            false
        }
    }


    // ============================================================================================
    // ANDROID PUBLIC URI / SCOPED STORAGE API
    // ============================================================================================

    /**
     * Appends a JSON metadata string directly to a public Scoped Storage Uri.
     * Uses "wa" (Write-Append) mode to execute an instant end-of-file structural burn.
     */
    fun appendMetadataToUri(context: Context, targetUri: Uri, payload: String, password: String? = null): Boolean {
        return try {
            val (dataBuffer, pointerBuffer) = buildBuffers(payload, password)

            // Open the container slot in "wa" mode to safely attach bytes without truncating existing MP4 chunks
            context.contentResolver.openFileDescriptor(targetUri, "wa")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                    outputStream.write(dataBuffer.array())
                    outputStream.write(pointerBuffer.array())
                    outputStream.flush()
                }
            }
            logger.i(TAG, "Metadata successfully appended via Uri stream link: $targetUri")
            true
        } catch (e: Exception) {
            logger.e(TAG, "Failed to append metadata payload to MediaStore Uri stream pointer", e)
            false
        }
    }

    /**
     * Inspects a public Scoped Storage Uri footer to see if it is password encrypted.
     */
    fun isMetadataPasswordProtectedUri(context: Context, targetUri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(targetUri, "r")?.use { pfd ->
                val fileLength = pfd.statSize
                FileInputStream(pfd.fileDescriptor).use { inputStream ->
                    val footerInfo = readFooterMetadataFromStream(inputStream, fileLength)
                    footerInfo?.isEncrypted ?: false
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extracts and decrypts the metadata layout layer directly from a Scoped Storage Uri target location.
     */
    fun extractMetadataFromUri(context: Context, targetUri: Uri, password: String? = null): String? {
        return try {
            context.contentResolver.openFileDescriptor(targetUri, "r")?.use { pfd ->
                val fileLength = pfd.statSize
                FileInputStream(pfd.fileDescriptor).use { inputStream ->
                    val footerInfo = readFooterMetadataFromStream(inputStream, fileLength) ?: return null

                    val dataAtomSize = footerInfo.dataAtomSize
                    if (dataAtomSize <= 8) return null

                    val absolutePayloadPos = fileLength - TOTAL_FOOTER_SIZE - dataAtomSize + 8
                    val payloadSize = dataAtomSize - 8

                    if (absolutePayloadPos >= 0 && absolutePayloadPos + payloadSize <= fileLength) {
                        // Skip forward directly across the input stream to target the payload atom boundary location
                        val channel = inputStream.channel
                        channel.position(absolutePayloadPos)

                        val rawAtomBytes = ByteArray(payloadSize)
                        var totalBytesRead = 0
                        while (totalBytesRead < payloadSize) {
                            val read = inputStream.read(rawAtomBytes, totalBytesRead, payloadSize - totalBytesRead)
                            if (read == -1) break
                            totalBytesRead += read
                        }

                        processExtractedBytes(rawAtomBytes, footerInfo, password)
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            handleExtractionException(e)
            null
        }
    }


    // ============================================================================================
    // REFACTORED SHARED ENGINES & COMPONENT DRIVERS
    // ============================================================================================

    private fun buildBuffers(payload: String, password: String?): Pair<ByteBuffer, ByteBuffer> {
        val isEncrypted = !password.isNullOrEmpty()
        val salt = ByteArray(SALT_SIZE)

        val targetPayloadBytes = if (isEncrypted) {
            SecureRandom().nextBytes(salt)
            val secretKey = deriveKeyFromPassword(password!!, salt)
            encryptBytes(payload.toByteArray(StandardCharsets.UTF_8), secretKey)
        } else {
            payload.toByteArray(StandardCharsets.UTF_8)
        }

        val totalDataAtomSize = 8 + targetPayloadBytes.size

        // 1. Build Data Atom Buffer ('kliQ')
        val dataBuffer = ByteBuffer.allocate(totalDataAtomSize).apply {
            putInt(totalDataAtomSize)
            put(dataAtomType.toByteArray(StandardCharsets.US_ASCII))
            put(targetPayloadBytes)
            flip()
        }

        // 2. Build Advanced Pointer Footer Buffer ('kPTR')
        val pointerBuffer = ByteBuffer.allocate(TOTAL_FOOTER_SIZE).apply {
            putInt(TOTAL_FOOTER_SIZE)
            put(pointerAtomType.toByteArray(StandardCharsets.US_ASCII))
            putInt(totalDataAtomSize)
            put((if (isEncrypted) 1 else 0).toByte())
            put(salt)
            flip()
        }

        return Pair(dataBuffer, pointerBuffer)
    }

    private fun processExtractedBytes(rawAtomBytes: ByteArray, footerInfo: FooterMetadata, password: String?): String {
        return if (footerInfo.isEncrypted) {
            if (password == null) {
                throw IllegalArgumentException("Crypto Exception: Secure match data payload is password protected.")
            }
            val secretKey = deriveKeyFromPassword(password, footerInfo.salt)
            val decryptedBytes = decryptBytes(rawAtomBytes, secretKey)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } else {
            String(rawAtomBytes, StandardCharsets.UTF_8)
        }
    }

    private fun handleExtractionException(e: Exception) {
        if (e is IllegalArgumentException || e is javax.crypto.BadPaddingException) {
            logger.e(TAG, "Authentication/Decryption failed for secure metadata block.", e)
            throw e
        }
        logger.e(TAG, "General IO error executing fast-seek tracking extraction operations.", e)
    }

    private fun locateExistingDataAtomSize(raf: RandomAccessFile, fileLength: Long): Int {
        val footer = readFooterMetadata(raf, fileLength)
        return footer?.dataAtomSize ?: -1
    }

    private fun readFooterMetadata(raf: RandomAccessFile, fileLength: Long): FooterMetadata? {
        if (fileLength < TOTAL_FOOTER_SIZE + 8) return null
        try {
            raf.seek(fileLength - TOTAL_FOOTER_SIZE)
            val buffer = ByteArray(TOTAL_FOOTER_SIZE)
            raf.readFully(buffer)
            return parseFooterBytes(buffer)
        } catch (e: Exception) {
            // Noise parsing boundaries safely ignored
        }
        return null
    }

    private fun readFooterMetadataFromStream(inputStream: FileInputStream, fileLength: Long): FooterMetadata? {
        if (fileLength < TOTAL_FOOTER_SIZE + 8) return null
        try {
            val channel = inputStream.channel
            channel.position(fileLength - TOTAL_FOOTER_SIZE)

            val buffer = ByteArray(TOTAL_FOOTER_SIZE)
            var bytesRead = 0
            while (bytesRead < TOTAL_FOOTER_SIZE) {
                val read = inputStream.read(buffer, bytesRead, TOTAL_FOOTER_SIZE - bytesRead)
                if (read == -1) break
                bytesRead += read
            }
            return parseFooterBytes(buffer)
        } catch (e: Exception) {
            // Stream position tracking fault bounds protection
        }
        return null
    }

    private fun parseFooterBytes(buffer: ByteArray): FooterMetadata? {
        val wrap = ByteBuffer.wrap(buffer)
        val footerSize = wrap.int
        val typeBytes = ByteArray(4)
        wrap.get(typeBytes)
        val type = String(typeBytes, StandardCharsets.US_ASCII)
        val targetDataAtomSize = wrap.int

        val flagByte = wrap.get()
        val saltBytes = ByteArray(SALT_SIZE)
        wrap.get(saltBytes)

        if (footerSize == TOTAL_FOOTER_SIZE && type == pointerAtomType) {
            return FooterMetadata(targetDataAtomSize, flagByte.toInt() == 1, saltBytes)
        }
        return null
    }

    // --- Core Cryptographic Key Derivation and Transform Helpers ---

    private fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }

    private fun encryptBytes(plainBytes: ByteArray, secretKey: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance(CRYPTO_ALGORITHM)
        val iv = ByteArray(16)
        System.arraycopy(secretKey.encoded, 0, iv, 0, 16)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        return cipher.doFinal(plainBytes)
    }

    private fun decryptBytes(cipherBytes: ByteArray, secretKey: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance(CRYPTO_ALGORITHM)
        val iv = ByteArray(16)
        System.arraycopy(secretKey.encoded, 0, iv, 0, 16)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        return cipher.doFinal(cipherBytes)
    }

    private class FooterMetadata(val dataAtomSize: Int, val isEncrypted: Boolean, val salt: ByteArray)
}