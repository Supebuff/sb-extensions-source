package eu.kanade.tachiyomi.animeextension.zh.mugua

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal class MuguaCoverCache(
    private val directory: File,
    private val maxBytes: Long = 64L * 1024 * 1024,
) {
    @Synchronized
    fun find(url: String): String? {
        val file = imageFile(url)
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return localUrl(file)
    }

    @Synchronized
    fun originalUrl(url: String): String {
        if (!url.startsWith("file://")) return url
        val image = File(url.removePrefix("file://"))
        if (image.parentFile?.canonicalFile != directory.canonicalFile) return url
        if (!image.name.matches(Regex("[0-9a-f]{64}\\.jpg"))) return url
        val metadata = File(directory, image.nameWithoutExtension + ".url")
        return metadata.takeIf { it.isFile }?.readText()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: url
    }

    fun put(url: String, input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (output.size() + count > MAX_IMAGE_BYTES) throw IOException("Cover image exceeds size limit")
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        if (!isImage(bytes)) throw IOException("Cover response is not a supported image")

        synchronized(this) {
            find(url)?.let { return it }
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create cover cache")
            val destination = imageFile(url)
            val temporary = File.createTempFile("cover-", ".tmp", directory)
            try {
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(destination)) throw IOException("Unable to save cover")
                File(directory, destination.nameWithoutExtension + ".url").writeText(url)
            } finally {
                temporary.delete()
            }
            trim(destination)
            return localUrl(destination)
        }
    }

    private fun trim(current: File) {
        val images = directory.listFiles { file -> file.extension == "jpg" }.orEmpty()
        var total = images.sumOf { it.length() }
        for (image in images.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (image == current) continue
            val length = image.length()
            if (image.delete()) total -= length
        }
    }

    private fun imageFile(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }
        return File(directory, "$key.jpg")
    }

    private fun localUrl(file: File): String = "file://" + file.absolutePath

    private fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val jpeg = bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val png = bytes.take(8).toByteArray().contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        )
        val gif = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in listOf("GIF87a", "GIF89a")
        val webp = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
        return jpeg || png || gif || webp
    }

    companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
