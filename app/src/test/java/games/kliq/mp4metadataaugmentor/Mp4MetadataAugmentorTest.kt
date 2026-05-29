package games.kliq.mp4metadataaugmentor.test

import games.kliq.mp4metadataaugmentor.Mp4MetadataAugmentor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class Mp4MetadataAugmentorTest {

    private lateinit var augmentor: Mp4MetadataAugmentor
    private lateinit var realSourceVideo: File
    private var baselineFileSize: Long = 0L

    companion object {
        private const val DATA_HEADER_SIZE = 8
        // Reflects the full 29-byte footer footprint (12 base + 1 flag + 16 salt)
        private const val TOTAL_FOOTER_SIZE = 29
    }

    @Before
    fun setUp() {
        augmentor = Mp4MetadataAugmentor(dataAtomType = "kliQ", pointerAtomType = "kPTR")
        realSourceVideo = File("C:\\Users\\Administrator\\Dev\\Kliq\\test.mp4")

        assertTrue(
            "Test prerequisite failed: Real test asset must exist at ${realSourceVideo.absolutePath}",
            realSourceVideo.exists()
        )

        baselineFileSize = realSourceVideo.length()
    }

    @After
    fun tearDown() {
        // Kept intact and un-truncated so you can drop the password-protected files straight into VLC/WMP!
        if (realSourceVideo.exists()) {
            println("[TearDown] Leaving mutated file intact for external application player validation.")
            println("[TearDown] Final Absolute File Size: ${realSourceVideo.length()} bytes (Baseline was: $baselineFileSize bytes)")
        }
    }

    /**
     * Tests a password-secured injection lifecycle, verifies status flags,
     * and guarantees that accessing data requires the exact match credential.
     */
    @Test
    fun testPasswordProtectedInjectionAndExtraction() {
        val secretPayload = """{"matchId":"hidden_2026","secretNotes":"Top Secret Telemetry"}"""
        val password = "SecureCoachPassword123"

        println("\n=== Starting Password Protected Telemetry Test ===")

        // 1. Inject payload using PBKDF2 + AES encryption keys
        val injectSuccess = augmentor.appendMetadata(realSourceVideo, secretPayload, password)
        assertTrue("In-place encrypted injection should report success", injectSuccess)

        // 2. Query the file format to ensure it knows it is password protected
        val isProtected = augmentor.isMetadataPasswordProtected(realSourceVideo)
        assertTrue("File footer flags should explicitly register as password-protected", isProtected)

        // 3. Attempt extraction without providing the password string (Should throw exception)
        try {
            augmentor.extractMetadata(realSourceVideo, password = null)
            fail("Extraction should have blocked access and thrown an IllegalArgumentException when password was missing")
        } catch (e: IllegalArgumentException) {
            println("[Expected Check Pass] Extract rejected unauthorized null password access attempt.")
        }

        // 4. Attempt extraction with an invalid password string (Should fail payload verification/decryption tag checks)
        try {
            augmentor.extractMetadata(realSourceVideo, password = "WrongPassword")
            fail("Extraction should have failed decryption check when using a bad password")
        } catch (e: Exception) {
            println("[Expected Check Pass] Extract cleanly rejected data hydration under an invalid key.")
        }

        // 5. Extract with correct password credential string
        val decryptedData = augmentor.extractMetadata(realSourceVideo, password)
        assertNotNull("Decrypted extracted data shouldn't be null", decryptedData)
        assertEquals("Decrypted output must perfectly match the original plaintext input", secretPayload, decryptedData)

        println("=== Password Protection Integration Verified Successfully ===\n")
    }

    @Test
    fun testSuccessfulPlaintextInjectionAndExtraction() {
        val expectedTelemetryPayload = """{"matchId":"test_123","p1Name":"Player 1","events":[],"notes":{"streak":"9-0"}}"""

        // Injecting without a password writes a regular plaintext block
        val injectSuccess = augmentor.appendMetadata(realSourceVideo, expectedTelemetryPayload, password = null)
        assertTrue("Plaintext mutation should report true", injectSuccess)

        val isProtected = augmentor.isMetadataPasswordProtected(realSourceVideo)
        assertFalse("File should report that it is NOT password protected", isProtected)

        val expectedPayloadSizeBytes = expectedTelemetryPayload.toByteArray(Charsets.UTF_8).size
        val expectedTotalGrowth = DATA_HEADER_SIZE + expectedPayloadSizeBytes + TOTAL_FOOTER_SIZE

        val extractedPayload = augmentor.extractMetadata(realSourceVideo, password = null)
        assertNotNull("Extracted plaintext data should not be null", extractedPayload)
        assertEquals("Extracted data must match perfectly", expectedTelemetryPayload, extractedPayload)
    }

    @Test
    fun testMissingSourceFileReturnsFalse() {
        val nonExistentFile = File("C:\\Users\\Administrator\\Dev\\Kliq\\ghost_video.mp4")
        val success = augmentor.appendMetadata(nonExistentFile, "{}", "password")
        assertFalse("Should fail gracefully when target file is missing", success)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidDataFourCCThrowsException() {
        Mp4MetadataAugmentor(dataAtomType = "BAD_FOUR_CC", pointerAtomType = "kPTR")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidPointerFourCCThrowsException() {
        Mp4MetadataAugmentor(dataAtomType = "kliQ", pointerAtomType = "BAD_POINTER")
    }

    @Test
    fun testOverwriteExistingMetadataHandlesSizeChanges() {
        val initialPayload = """{"id":"1","status":"First Pass"}"""
        assertTrue(augmentor.appendMetadata(realSourceVideo, initialPayload, "pass1"))

        // Overwrite encrypted block with a larger encrypted block using a different password
        val largerPayload = """{"id":"1","status":"Second Pass with wider nested maps information"}"""
        assertTrue(augmentor.appendMetadata(realSourceVideo, largerPayload, "pass2"))

        val extractedLarger = augmentor.extractMetadata(realSourceVideo, "pass2")
        assertEquals(largerPayload, extractedLarger)

        // Drop back down cleanly to a tiny plaintext payload over the encrypted setup
        val tinyPayload = "{}"
        assertTrue(augmentor.appendMetadata(realSourceVideo, tinyPayload, password = null))

        val extractedTiny = augmentor.extractMetadata(realSourceVideo, password = null)
        assertEquals(tinyPayload, extractedTiny)
        assertFalse(augmentor.isMetadataPasswordProtected(realSourceVideo))
    }

    @Test
    fun printPermanentlyStoredData() {
        val password = "SecureCoachPassword123"

        println("\n=== FETCHING PERMANENTLY INJECTED ATOM DATA ===")

        // 1. Check if the file contains our custom trailer signature
        val isProtected = augmentor.isMetadataPasswordProtected(realSourceVideo)
        println("File Metadata Protection Status: [Password Protected = $isProtected]")

        // 2. Perform the secure extraction path
        try {
            val decryptedJson = augmentor.extractMetadata(realSourceVideo, password)

            if (decryptedJson != null) {
                println("\n=== DECRYPTED JSON PAYLOAD SUCCESS ===")
                println(decryptedJson)
                println("=======================================\n")
            } else {
                println("\n[Notice] No metadata atom found at the tail of this file. It is clean.")
            }
        } catch (e: Exception) {
            println("\n[CRITICAL ERROR] Failed to decrypt data block: ${e.message}")
            fail("Could not read back secure telemetry graph details.")
        }
    }

    @Test
    fun printPlaintextAppendAndReadback() {
        // 1. Create a clear, visible plaintext telemetry payload
        val openTelemetryPayload = """
        {
          "matchId": "public_squash_456",
          "visibility": "Unrestricted",
          "summary": {
            "totalRallies": 42,
            "matchDurationMinutes": 35
          }
        }
    """.trimIndent()

        println("\n=== STARTING PLAINTEXT APPEND & PRINT TEST ===")

        // 2. Append the metadata with no password parameter (defaults to null)
        val appendSuccess = augmentor.appendMetadata(realSourceVideo, openTelemetryPayload, password = null)
        assertTrue("Plaintext metadata append should return true", appendSuccess)

        // 3. Guarantee the format manager registers this block as unprotected
        val isProtected = augmentor.isMetadataPasswordProtected(realSourceVideo)
        assertFalse("File should report false for password protection status", isProtected)
        println("File Metadata Protection Status: [Password Protected = $isProtected]")

        // 4. Extract the payload using the fast-seek pointer without providing any password credentials
        try {
            val extractedJson = augmentor.extractMetadata(realSourceVideo, password = null)
            assertNotNull("Extracted plaintext string should not be null", extractedJson)

            println("\n=== EXTRACTED UNPROTECTED JSON PAYLOAD ===")
            println(extractedJson)
            println("==========================================\n")

            // 5. Final integrity verification
            assertEquals("Extracted data must match the source data layout exactly", openTelemetryPayload, extractedJson)

        } catch (e: Exception) {
            fail("Encountered unexpected error processing raw plaintext block extraction: ${e.message}")
        }
    }
}