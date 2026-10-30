package de.voicekeyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyTest {

    private fun vocabText(extra: String = "") = """
        <unk> 0
        <pad> 1
        <|nospeech|> 2
         3
        <|startoftranscript|> 4
        <|de|> 78
        0 234
        1 235
        2 236
        3 237
        4 238
        5 239
        6 240
        7 241
        8 242
        9 243
        <|spltoken0|> 244
        Hallo 1000
        ▁Welt 1001
        ▁ein 2000
        ▁Test 2001
        ▁Zeichen 3000
        <blk> 8192
        $extra
    """.trimIndent()

    @Test fun wordBoundaryBecomesSpace() {
        val v = Vocabulary.parse(vocabText())
        assertEquals("ein Test", v.detokenize(intArrayOf(2000, 2001)))
        assertEquals("Hallo Welt", v.detokenize(intArrayOf(1000, 1001)))
        assertEquals("Hallo Welt ein Test", v.detokenize(intArrayOf(1000, 1001, 2000, 2001)))
    }

    @Test fun specialTokensFiltered() {
        val v = Vocabulary.parse(vocabText())
        for (id in 0..4) assertTrue("id $id should be special", v.isSpecial(id))
        assertTrue("<|de|> (78)", v.isSpecial(78))       // Muster <...>
        assertTrue("<unk>", v.isSpecial(0))
        assertTrue("<|spltoken0|> (244)", v.isSpecial(244))
        assertTrue("<blk> (8192)", v.isSpecial(8192))
        assertFalse("normal token", v.isSpecial(1000))
    }

    @Test fun digitIds234to243NotFiltered() {
        val v = Vocabulary.parse(vocabText())
        for ((digit, id) in ('0'..'9').zip(234..243)) {
            assertFalse("digit $digit (id $id) must not be special", v.isSpecial(id))
            assertEquals(digit.toString(), v.token(id))
        }
        assertEquals("4711", v.detokenize(intArrayOf(238, 241, 235, 235)))
    }

    @Test fun crlfAndBlankLineTolerant() {
        val v = Vocabulary.parse("Hallo 1000\r\n\r\n▁Welt 1001\r\n\r\n")
        assertEquals("Hallo Welt", v.detokenize(intArrayOf(1000, 1001)))
    }

    @Test fun tokenMayContainSpaces() {
        val v = Vocabulary.parse("New York 500\nHallo 1000\n")
        assertEquals("New York", v.token(500))
    }

    @Test fun duplicateIdKeepsFirstDefinition() {
        val v = Vocabulary.parse("Erst 100\nZweit 100\n")
        assertEquals("Erst", v.token(100))
    }
}
