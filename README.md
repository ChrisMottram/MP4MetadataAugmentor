# MP4MetadataAugmentor

`MP4MetadataAugmentor` is a high-performance, pure-Kotlin Android library designed to append custom metadata directly onto MP4 video files without altering the original video or audio streams.

By utilizing low-overhead tail-end injections, the SDK allows developers to embed bespoke data directly within the video file container itself, keeping it 100% playable in standard media players like VLC or Windows Media Player.

### ✨ Key Features

* 🚀 **Fast-Seek Architecture:** Employs a custom atom pointer logic (`kPTR`) at the absolute tail of the file for instant $O(1)$ metadata lookups without parsing the entire MP4 atom tree.
* 🔒 **Cryptographic Security:** Built-in support for password-protected metadata configurations utilizing PBKDF2 for key derivation and AES-256 encryption to prevent unauthorized data extraction.
* 🎬 **Zero Stream Disruption:** Appends data as a non-destructive footer trailer, maintaining absolute video integrity and compliance for standard players.
* 📦 **Pure Logic SDK:** Zero external third-party dependencies, optimized directly for lightweight integration into Android production applications.

---
Structure of augmented MP4 file:

+-------------------------------------------------------------+
|                                                             |
|              Original MP4 Video Data Container              |
|           (Maintained completely untouched/valid)           |
|                                                             |
+-------------------------------------------------------------+
|  [kliQ Atom] Size (4B) + Type (4B)                          | <-- Custom Data Box
|  -> Raw JSON String payload OR Encrypted AES Binary Block   |
+-------------------------------------------------------------+
|  [kPTR Atom] Size (4B) + Type (4B)                          | <-- Fast-Seek Footer
|  -> Int (Data Atom Size Block Offset)                       |
|  -> Byte (Encryption Status Flag Toggle: 0 or 1)            |
|  -> 16 Bytes Cryptographic KDF Random Salt String           |
+-------------------------------------------------------------+ <-- EOF (Absolute End)

## 📦 Installation

Add the JitPack repository to your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("[https://jitpack.io](https://jitpack.io)") }
    }
}