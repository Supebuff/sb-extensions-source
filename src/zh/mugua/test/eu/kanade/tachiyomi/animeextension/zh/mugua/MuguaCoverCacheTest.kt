package eu.kanade.tachiyomi.animeextension.zh.mugua

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Base64

class MuguaCoverCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val image = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQ2kAAAAASUVORK5CYII=",
    )
    private val url = "https://images.example.com/cover.jpg"

    @Test
    fun savesImageAsAnAppReadableLocalFile() {
        val directory = temporary.newFolder()
        val cache = MuguaCoverCache(directory)
        val local = cache.put(url, ByteArrayInputStream(image))
        assertTrue(local.startsWith("file://"))
        val file = File(local.removePrefix("file://"))
        assertEquals(directory.canonicalFile, file.parentFile.canonicalFile)
        assertArrayEquals(image, file.readBytes())
        assertEquals(local, cache.find(url))
        assertEquals(url, cache.originalUrl(local))
    }

    @Test
    fun survivesSourceRecreationAndKeepsTheSamePath() {
        val directory = temporary.newFolder()
        val first = MuguaCoverCache(directory).put(url, ByteArrayInputStream(image))
        val cache = MuguaCoverCache(directory)
        assertEquals(first, cache.find(url))
        assertEquals(url, cache.originalUrl(first))
    }

    @Test
    fun rejectsHtmlWithoutLeavingAFile() {
        val directory = temporary.newFolder()
        val cache = MuguaCoverCache(directory)
        expectIOException { cache.put(url, "<html>Forbidden</html>".byteInputStream()) }
        assertNull(cache.find(url))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsOversizedResponsesWithoutLeavingAFile() {
        val directory = temporary.newFolder()
        val cache = MuguaCoverCache(directory)
        expectIOException {
            cache.put(url, ByteArrayInputStream(ByteArray(MuguaCoverCache.MAX_IMAGE_BYTES + 1)))
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun evictionKeepsOriginalUrlSoTheImageCanBeDownloadedAgain() {
        val directory = temporary.newFolder()
        val cache = MuguaCoverCache(directory, maxBytes = image.size.toLong())
        val first = cache.put(url, ByteArrayInputStream(image))
        File(first.removePrefix("file://")).setLastModified(1L)
        val second = cache.put(url + "?v=2", ByteArrayInputStream(image))
        assertNull(cache.find(url))
        assertNotNull(cache.find(url + "?v=2"))
        assertFalse(File(first.removePrefix("file://")).exists())
        assertTrue(File(second.removePrefix("file://")).exists())
        assertEquals(url, cache.originalUrl(first))
        assertEquals(first, cache.put(cache.originalUrl(first), ByteArrayInputStream(image)))
    }

    @Test
    fun invalidResponseCannotReplaceAnExistingCover() {
        val cache = MuguaCoverCache(temporary.newFolder())
        val local = cache.put(url, ByteArrayInputStream(image))
        expectIOException { cache.put(url, "<html>Denied</html>".byteInputStream()) }
        assertArrayEquals(image, File(local.removePrefix("file://")).readBytes())
    }

    @Test
    fun doesNotReadMetadataOutsideItsOwnDirectory() {
        val cache = MuguaCoverCache(temporary.newFolder())
        val external = File(temporary.newFolder(), "a".repeat(64) + ".jpg")
        File(external.parentFile, external.nameWithoutExtension + ".url").writeText("https://other.example.com/")
        val local = "file://" + external.absolutePath
        assertEquals(local, cache.originalUrl(local))
    }

    @Test
    fun hashesUrlsAndDoesNotUseTheirPathAsALocalFilename() {
        val directory = temporary.newFolder()
        val cache = MuguaCoverCache(directory)
        val local = cache.put("https://images.example.com/../../cover.jpg?secret=token", ByteArrayInputStream(image))
        val file = File(local.removePrefix("file://"))
        assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.jpg")))
        assertEquals(directory.canonicalFile, file.parentFile.canonicalFile)
        assertEquals(0, directory.listFiles { file -> file.extension == "tmp" }.orEmpty().size)
    }

    private fun expectIOException(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected invalid image or I/O failure.
        }
    }
}
