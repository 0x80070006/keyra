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

    /** Export chiffré (ADR-0028) : Argon2id 64 Mio réel sur l'appareil, aller-retour, mauvaise phrase, fichier tronqué. */
    @Test fun backupRoundTripThroughJni(){
        val payload=com.example.app_clavier.storage.Backup.Payload(mapOf("words" to mapOf("chouquette" to "3"),"snippets" to mapOf("adr" to "T	12 rue des Lilas")))
        val start=android.os.SystemClock.elapsedRealtime()
        val sealed=KeyraCore.sealBackup("une phrase de passe".toByteArray(),ByteArray(16){1},ByteArray(24){2},com.example.app_clavier.storage.Backup.encode(payload))
        android.util.Log.i("KeyraCoreTest","Argon2id export : ${android.os.SystemClock.elapsedRealtime()-start} ms") // journal-ok: durée seulement
        assertTrue(sealed!=null && sealed.size>61)
        val (result,opened)=com.example.app_clavier.storage.Backup.open("une phrase de passe".toCharArray(),sealed!!)
        assertEquals(com.example.app_clavier.storage.Backup.Result.OK,result)
        assertEquals(payload.stores,opened?.stores)
        assertEquals(com.example.app_clavier.storage.Backup.Result.WRONG_PASSWORD,com.example.app_clavier.storage.Backup.open("autre phrase".toCharArray(),sealed).first)
        assertEquals(com.example.app_clavier.storage.Backup.Result.BAD_FILE,com.example.app_clavier.storage.Backup.open("une phrase de passe".toCharArray(),sealed.copyOf(40)).first)
        assertEquals(com.example.app_clavier.storage.Backup.Result.NOT_AN_EXPORT,com.example.app_clavier.storage.Backup.open("x".toCharArray(),"PK".toByteArray()).first)
    }

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
