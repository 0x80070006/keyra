package com.example.app_clavier

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineTranslatorTest{
    private fun translator()=OfflineTranslator("""
        bonjour\thello
        merci\tthank you
        merci beaucoup\tthank you very much
        maison\thouse
    """.trimIndent().replace("\\t","\t").reader())

    @Test fun prioritizesLongPhrases(){assertEquals("Thank you very much !",translator().translate("Merci beaucoup !"))}
    @Test fun translatesBothDirections(){assertEquals("bonjour",translator().translate("hello",englishToFrench=true))}
    @Test fun preservesUnknownWords(){assertEquals("Keyra house",translator().translate("Keyra maison"))}
}
