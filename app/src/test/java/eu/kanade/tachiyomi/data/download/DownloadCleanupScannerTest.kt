package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadCleanupScannerTest {

    @Test
    fun `chapter copies use the same name for folders and archives`() {
        canonicalChapterEntryName("Chapter 1") shouldBe canonicalChapterEntryName("Chapter 1.cbz")
        canonicalChapterEntryName(" Chapter 1 ").lowercase() shouldBe "chapter 1"
        canonicalChapterEntryName("CHAPTER 1.CBZ") shouldBe canonicalChapterEntryName("chapter 1")
    }

    @Test
    fun `only a trailing cbz extension is removed`() {
        canonicalChapterEntryName("chapter.cbz.old") shouldBe "chapter.cbz.old"
        canonicalChapterEntryName("chapter.cbz") shouldBe "chapter"
    }
}
