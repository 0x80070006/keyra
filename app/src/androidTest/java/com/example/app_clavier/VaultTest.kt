package com.example.app_clavier

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clavier.security.IncognitoApps
import com.example.app_clavier.security.LearningGate
import com.example.app_clavier.security.Panic
import com.example.app_clavier.security.SecurityPolicy
import com.example.app_clavier.storage.EncryptedKv
import com.example.app_clavier.storage.KeyManager
import com.example.app_clavier.storage.Migration11to12
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/** Phase 3 : coffre chiffré, sur le vrai Keystore Android et le vrai cœur Rust. */
@RunWith(AndroidJUnit4::class)
class VaultTest {
    private val context:Context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun journal(name:String)=File(KeyManager.vaultDir(context),"$name.jnl")
    private fun reload(name:String):EncryptedKv{EncryptedKv.forgetAll();return EncryptedKv.of(context,name)}

    @Before fun fresh(){Panic.wipe(context,killProcess=false);assertTrue("coffre ouvert",KeyManager.unlock(context))}
    @After fun cleanup(){Panic.wipe(context,killProcess=false)}

    @Test fun roundTripSurvivesLockAndReload(){
        val kv=EncryptedKv.of(context,"test")
        assertTrue(kv.put("a","1"));assertTrue(kv.put("b","2"));assertTrue(kv.remove("a"))
        KeyManager.lock()
        assertNull("coffre verrouillé : rien n'est lisible",kv.get("b"))
        assertFalse("coffre verrouillé : rien n'est écrit",kv.put("c","3"))
        assertTrue(KeyManager.unlock(context))
        assertEquals(mapOf("b" to "2"),kv.entries())
        Log.i("KeyraVault","niveau de la clé maître : ${KeyManager.securityLevel()}")
    }

    @Test fun nothingIsWrittenInClear(){
        val marker="keyramarqueurenclair"
        assertTrue(EncryptedKv.of(context,"test").put(marker,marker))
        assertTrue(PersonalWords.replaceFromText(context,marker))
        assertTrue(LearningGate.word(context,SecurityPolicy.of(InputType.TYPE_CLASS_TEXT,0,"org.example",false),marker))
        val needle=marker.toByteArray()
        val leaks=context.dataDir.walkTopDown().filter{it.isFile && it.length()<5_000_000}.filter{f->
            val bytes=f.readBytes();(0..bytes.size-needle.size).any{i->needle.indices.all{bytes[i+it]==needle[it]}}
        }.map{it.relativeTo(context.dataDir).path}.toList()
        assertTrue("texte en clair trouvé dans : $leaks",leaks.isEmpty())
    }

    @Test fun interruptedWriteKeepsThePreviousState(){
        val kv=EncryptedKv.of(context,"crash")
        listOf("x1","x2","x3").forEach{assertTrue(kv.put(it,it))}
        RandomAccessFile(journal("crash"),"rw").use{it.setLength(it.length()-5)} // arrêt brutal au milieu de x3
        val again=reload("crash")
        assertEquals(setOf("x1","x2"),again.entries()?.keys)
        assertEquals(EncryptedKv.Issue.TRUNCATED_TAIL,again.lastIssue)
        assertEquals("instantané propre réécrit",EncryptedKv.Issue.NONE,reload("crash").also{it.entries()}.lastIssue)
    }

    @Test fun tamperedOrSwappedFilesAreRejected(){
        val kv=EncryptedKv.of(context,"alpha")
        listOf("m1","m2").forEach{assertTrue(kv.put(it,it))}
        val bytes=journal("alpha").readBytes()
        journal("beta").writeBytes(bytes) // un fichier ne peut pas passer pour un autre magasin
        bytes[bytes.size-3]=(bytes[bytes.size-3].toInt() xor 1).toByte()
        journal("alpha").writeBytes(bytes)
        val alpha=reload("alpha")
        assertEquals(setOf("m1"),alpha.entries()?.keys)
        assertEquals(EncryptedKv.Issue.CORRUPTED,alpha.lastIssue)
        val beta=EncryptedKv.of(context,"beta")
        assertEquals(emptySet<String>(),beta.entries()?.keys)
        assertEquals(EncryptedKv.Issue.CORRUPTED,beta.lastIssue)
    }

    @Test fun panicMakesEveryCopyUnreadable(){
        assertTrue(EncryptedKv.of(context,"secret").put("code","1234"))
        val copies=KeyManager.vaultDir(context).listFiles()!!.associate{it.name to it.readBytes()}
        Panic.wipe(context,killProcess=false)
        assertFalse("fichiers supprimés",journal("secret").exists())
        assertTrue("nouvelle clé",KeyManager.unlock(context))
        copies.filterKeys{it!="keys.bin"}.forEach{(name,bytes)->File(KeyManager.vaultDir(context),name).writeBytes(bytes)}
        val restored=reload("secret")
        assertNull("ancienne copie illisible",restored.entries()?.get("code"))
    }

    @Test fun migrationEncryptsThenErasesPlaintext(){
        val prefs=KeyboardPrefs.of(context)
        prefs.edit().putString("learned_words","{\"bonjourr\":3}").putString("personal","Keyra\nGrapheneOS").remove("migrated_v12").commit()
        context.getSharedPreferences("clipboard_history",Context.MODE_PRIVATE).edit().putString("items","[\"texte copié\",\"4111 1111 1111 1111\"]").commit()
        context.getSharedPreferences("emoji_history",Context.MODE_PRIVATE).edit().putString("usage","[{\"emoji\":\"😀\",\"count\":4,\"last\":1}]").commit()
        assertTrue(Migration11to12.run(context))
        assertNull(prefs.getString("learned_words",null));assertNull(prefs.getString("personal",null))
        assertFalse(File(context.dataDir,"shared_prefs/clipboard_history.xml").exists())
        assertFalse(File(context.dataDir,"shared_prefs/emoji_history.xml").exists())
        assertEquals(3,UserLexicon.all(context)?.get("bonjourr"))
        assertTrue(PersonalWords.contains(context,"grapheneos"))
        assertEquals("la carte bancaire n'est pas reprise",listOf("texte copié"),ClipboardHistory.items(context))
        assertEquals(listOf("😀"),EmojiHistory.top(context))
    }

    @Test fun learningGateRespectsPolicyAndSecrets(){
        val text=InputType.TYPE_CLASS_TEXT
        fun learns(policy:SecurityPolicy,word:String)=LearningGate.word(context,policy,word)
        assertTrue(learns(SecurityPolicy.of(text,0,"org.example",false),"chouquette"))
        assertFalse("navigation privée",learns(SecurityPolicy.of(text,EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,"org.example",false),"croissant"))
        assertFalse("appareil verrouillé",learns(SecurityPolicy.of(text,0,"org.example",true),"croissant"))
        assertFalse("application incognito",learns(SecurityPolicy.of(text,0,"com.x8bit.bitwarden",false,setOf("com.x8bit.bitwarden")),"croissant"))
        assertFalse("mot de passe probable",learns(SecurityPolicy.of(text,0,"org.example",false),"Tr0ub4dour&3xQ!"))
        assertFalse("chiffre",learns(SecurityPolicy.of(text,0,"org.example",false),"azerty123"))
        assertEquals(setOf("chouquette"),UserLexicon.all(context)?.keys)
    }

    @Test fun clipboardEntriesExpireUnlessPinned(){
        val store=EncryptedKv.of(context,"clipboard")
        val twoHoursAgo=(System.currentTimeMillis()-2*3_600_000L)*1000
        assertTrue(store.put(twoHoursAgo.toString().padStart(19,'0'),"0\tvieille copie"))
        assertTrue(store.put((twoHoursAgo+1).toString().padStart(19,'0'),"1\tcopie épinglée"))
        assertEquals(listOf("copie épinglée"),ClipboardHistory.items(context))
        assertEquals("copie expirée supprimée",1,store.entries()?.size)
    }

    @Test fun incognitoAppsCombineKnownListAndUserChoice(){
        assertTrue(IncognitoApps.isIncognito(context,"com.x8bit.bitwarden"))
        assertFalse(IncognitoApps.isIncognito(context,"org.example.chat"))
        IncognitoApps.set(context,"com.x8bit.bitwarden",false);IncognitoApps.set(context,"org.example.chat",true)
        assertFalse(IncognitoApps.isIncognito(context,"com.x8bit.bitwarden"))
        assertTrue(IncognitoApps.isIncognito(context,"org.example.chat"))
        LearningGate.passwordField(context,SecurityPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,0,"org.example.bank",false),"org.example.bank")
        assertEquals(listOf("org.example.bank"),IncognitoApps.suggestions(context))
    }
}
