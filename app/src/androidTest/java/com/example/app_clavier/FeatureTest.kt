package com.example.app_clavier

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.widget.GridView
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class FeatureTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    /** Moteur Rust (phase 5) : correction pondérée AZERTY, complétions, noms propres intacts, vitesse. */
    @Test fun frenchCorrectionAndLatency(){
        val engine=com.example.app_clavier.engine.Predictor
        assertTrue(engine.load(context))
        assertTrue(engine.contains("bonjour"))
        assertNull(engine.correction("bonjour",100))
        assertNull(engine.correction("bonjour",0))
        assertNull(engine.correction("Paris",100))
        assertEquals("bonjour",engine.correction("bonjor",80))
        assertEquals("bonjour",engine.correction("bonjuor",55))
        assertEquals("venir",engine.correction("vnir",55))
        assertTrue(engine.analyze("bonj",55)!!.suggestions.contains("bonjour"))
        assertEquals("emoji pour « cœur »",true,EmojiCatalog.forWord(context,"cœur")!=null)
        val start=System.nanoTime()
        repeat(50){engine.analyze("bonjor",55)}
        val ms=(System.nanoTime()-start)/1_000_000.0/50
        println("Moteur Rust : analyse moyenne ${ms} ms")
        assertTrue("Analyse trop lente sur l'émulateur : $ms ms",ms<20)
    }
    @Test fun completeEmojiDataset(){
        val list=EmojiCatalog.all(context)
        assertEquals(3944,list.size)
        assertTrue(list.any{it.emoji=="🇫🇷"})
        assertTrue(list.any{it.emoji=="👍🏽"})
        assertTrue(list.any{it.emoji.contains("\u200d")})
        assertEquals(list.size,list.map{it.emoji}.toSet().size)
    }
    @Test fun lowercaseShiftSymbolsAndEmoji(){
        val kit=KeyboardTestKit().create()
        kit.tap("a");assertEquals("a",kit.typed.last())
        kit.tap("Majuscules");kit.tap("A");assertEquals("A",kit.typed.last())
        kit.main{assertNotNull(kit.keyboard.keyRect("a"))}
        kit.tap("Chiffres et symboles");kit.tap("@");assertEquals("@",kit.typed.last())
        kit.tap("Deuxième page de symboles");kit.tap("π");assertEquals("π",kit.typed.last())
        kit.tap("Chiffres et symboles")
        kit.tap("Emoji")
        kit.main{
            val grid=(0 until kit.keyboard.childCount).map{kit.keyboard.getChildAt(it)}.filterIsInstance<GridView>().first()
            assertEquals(3944,grid.adapter.count)
        }
    }
    @Test fun themesHaveTwoPalettes(){
        KeyboardPrefs.themes.keys.forEach{val p=KeyboardPrefs.palette(context,it);assertNotEquals("Theme $it",p.key,p.special)}
    }
    @Test fun frequentEmojiAreLimitedAndRanked(){
        assertTrue("coffre ouvert",com.example.app_clavier.storage.KeyManager.unlock(context))
        EmojiHistory.clear(context)
        val entries=EmojiCatalog.all(context).take(24)
        entries.forEach{EmojiHistory.record(context,it.emoji)}
        repeat(3){EmojiHistory.record(context,"😀")}
        val top=EmojiHistory.top(context)
        assertEquals(18,top.size)
        assertEquals("😀",top.first())
        assertEquals(18,top.toSet().size)
        EmojiHistory.clear(context)
    }
    /** Phase 2 : appuyer et relâcher ne reconstruit aucune vue ; le caractère part au relâchement (ADR-0012). */
    @Test fun typingNeverRebuildsViews(){
        val kit=KeyboardTestKit().create()
        val before=kit.keyboard.rebuildCount
        kit.press(0,kit.center("a"));assertTrue("rien avant le relâchement",kit.typed.isEmpty())
        kit.release(0);assertEquals(listOf("a"),kit.typed)
        assertEquals(before,kit.keyboard.rebuildCount)
    }
    @Test fun rapidPressesAreNeverDropped(){
        val kit=KeyboardTestKit().create()
        val a=kit.center("a")
        repeat(200){index->kit.tapAt(a);assertEquals(index+1,kit.typed.size)}
        assertTrue(kit.typed.all{it=="a"})
    }
    @Test fun frenchEmojiSearchIncludesVariations(){
        val results=EmojiCatalog.search(context,"coeur")
        assertTrue(results.any{it.emoji=="❤️"})
        assertTrue(results.any{it.emoji=="❤️‍🔥"})
        assertTrue(EmojiCatalog.search(context,"chien").any{it.emoji=="🐶"})
    }
    @Test fun bundledTranslationWorksWithoutNetworkPermission(){
        val translator=OfflineTranslator(context.assets.open("offline_translation_fr_en.tsv").reader())
        assertEquals("Thank you very much !",translator.translate("Merci beaucoup !"))
        assertEquals("bonjour",translator.translate("hello",englishToFrench=true))
        val permissions=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(android.Manifest.permission.INTERNET))
    }
}
