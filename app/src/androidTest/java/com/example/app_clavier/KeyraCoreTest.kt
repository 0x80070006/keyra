package com.example.app_clavier

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.app_clavier.core.KeyraCore
import com.example.app_clavier.security.SecretDetector
import com.example.app_clavier.security.SecretDetector.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Teste le pont JNI réel (rust/keyra-jni), y compris ses chemins d'erreur, sur la JVM Android. */
@RunWith(AndroidJUnit4::class)
class KeyraCoreTest {
    @Test fun nativeLibraryLoadsWithMatchingAbi(){assertTrue(KeyraCore.available)}

    @Test fun classifiesThroughJni(){
        assertEquals(Kind.CARD_NUMBER,SecretDetector.classify("4111 1111 1111 1111"))
        assertEquals(Kind.IBAN,SecretDetector.classify("FR76 3000 6000 0112 3456 7890 189"))
        assertEquals(Kind.ONE_TIME_CODE,SecretDetector.classify("482913"))
        assertEquals(Kind.API_KEY,SecretDetector.classify("ghp_abcdefghijklmnopqrstuvwxyz0123456789"))
        assertEquals(Kind.EMAIL,SecretDetector.classify("camille@example.org"))
        assertEquals(Kind.NONE,SecretDetector.classify("Bonjour à tous, ça va ? 😀"))
    }

    @Test fun learningRules(){
        assertTrue(SecretDetector.isLearnable("bonjour"))
        assertTrue(SecretDetector.isLearnable("aujourd'hui"))
        assertFalse(SecretDetector.isLearnable("motdepasse2026"))
        assertFalse(SecretDetector.isLearnable("Tr0ub4dour&3xQ!"))
    }

    @Test fun failsClosedOnOversizedInput(){
        val huge="a".repeat(16*1024+1)
        assertEquals(KeyraCore.ERROR,KeyraCore.classify(huge))
        assertTrue(SecretDetector.isSecret(huge))
    }

    @Test fun handlesEmptyAndSupplementaryCharacters(){
        assertEquals(0,KeyraCore.classify(""))
        assertEquals(0,KeyraCore.classify("🇫🇷 👍🏽 ❤️‍🔥"))
    }
}
