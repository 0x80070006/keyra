package com.example.app_clavier

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-0008 : tout ce que Keyra conserve passe par LearningGate. Ce test lit le code source et échoue si une
 * écriture de magasin est appelée ailleurs, ou si un magasin chiffré est ouvert hors des fichiers prévus.
 */
class ArchitectureTest {
    private val sources=File("src/main/java").walkTopDown().filter{it.extension=="kt"}.toList()
    private fun callers(call:Regex)=sources.filter{f->f.readLines().any{line->call.containsMatchIn(line.substringBefore("//")) && !line.contains("fun ")}}.map{it.name}.toSet()

    @Test fun onlyLearningGateWritesWhatKeyraKeeps(){
        for(call in listOf("UserLexicon\\.record\\(","NextWords\\.record\\(","EmojiHistory\\.record\\(","ClipboardHistory\\.capture\\(","IncognitoApps\\.recordPasswordField\\(")){
            val found=callers(Regex(call))
            assertTrue("$call appelé hors de LearningGate : $found",found.all{it=="LearningGate.kt"})
        }
    }

    @Test fun encryptedStoresAreOpenedOnlyByStorageCode(){
        val allowed=setOf("EncryptedKv.kt","NextWords.kt","UserLexicon.kt","EmojiHistory.kt","ClipboardHistory.kt","IncognitoApps.kt","Migration11to12.kt","KeyManager.kt","Panic.kt")
        val found=callers(Regex("EncryptedKv\\.(of|forgetAll|clearCaches)\\("))
        assertTrue("magasin chiffré ouvert hors du code de stockage : ${found-allowed}",(found-allowed).isEmpty())
    }

    @Test fun noPlaintextLegacyStorageOutsideMigration(){
        val found=callers(Regex("\"(learned_words|clipboard_history|emoji_history)\""))
        assertTrue("ancien stockage en clair utilisé : $found",found.all{it=="Migration11to12.kt"})
    }
}
