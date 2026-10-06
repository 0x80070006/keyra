package com.example.app_clavier

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FrenchCorrectorTest{
    private fun engine()=FrenchCorrector("""
        venir 90000
        avenir 200000
        maintenant 120000
        bonjour 110000
        bonsoir 70000
        j'arrive 80000
        maison 65000
    """.trimIndent().reader())

    @Test fun restoresMissingLetters(){
        val engine=engine()
        assertEquals("venir",engine.correction("vnir",70))
        assertEquals("maintenant",engine.correction("maintn",70))
    }

    @Test fun handlesApostrophesAndPrefixes(){
        val engine=engine()
        assertEquals("j'arrive",engine.correction("jarrive",70))
        assertTrue(engine.candidates("bonj",55).any{it.word=="bonjour"})
    }

    @Test fun keepsKnownWords(){assertNull(engine().correction("maison",70))}

    @Test fun missingLettersRankCorrectlyInBundledDictionary(){
        val engine=FrenchCorrector(File("src/main/assets/fr_frequency.txt").reader())
        assertEquals("venir",engine.correction("vnir",70))
        assertEquals("maintenant",engine.correction("maintn",70))
    }
}
