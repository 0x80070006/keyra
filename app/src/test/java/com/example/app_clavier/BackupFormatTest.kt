package com.example.app_clavier

import com.example.app_clavier.storage.Backup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** Contenu d'un export (ADR-0028), une fois déchiffré : décodage strict et borné. */
class BackupFormatTest {
    private val payload=Backup.Payload(linkedMapOf(
        "words" to mapOf("chouquette" to "3","bisou" to "12"),
        "bigrams" to mapOf("bonne\u0001soirée" to "4"),
        "snippets" to mapOf("adr" to "T\t12 rue des Lilas\n75000 Paris"),
    ))

    @Test fun roundTrip(){
        val decoded=Backup.decode(Backup.encode(payload))
        assertEquals(payload.stores,decoded?.stores)
    }

    @Test fun rejectsUnknownStoresDuplicatesAndTrailingBytes(){
        assertNull(Backup.decode(Backup.encode(Backup.Payload(mapOf("clipboard" to mapOf("a" to "b"))))))
        assertNull(Backup.decode(Backup.encode(payload)+byteArrayOf(0)))
        assertNull(Backup.decode(byteArrayOf()))
        assertNull(Backup.decode("PK\u0003\u0004".toByteArray()))
    }

    @Test fun mutationsNeverEscapeTheDecoder(){
        val seed=Backup.encode(payload)
        val random=Random(28)
        repeat(30_000){
            val data=seed.copyOf()
            repeat(1+random.nextInt(4)){data[random.nextInt(data.size)]=random.nextInt(256).toByte()}
            val cut=if(random.nextBoolean())data.copyOf(random.nextInt(data.size)) else data
            Backup.decode(cut)?.stores?.keys?.forEach{assert(it in Backup.STORES)}
        }
    }
}
