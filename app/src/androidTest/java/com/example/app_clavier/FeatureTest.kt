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
    @Test fun frenchCorrectionAndLatency(){
        val engine=FrenchCorrector(context.assets.open("fr_frequency.txt").reader())
        assertTrue(engine.wordCount>40000)
        assertTrue(engine.contains("bonjour"))
        assertNull(engine.correction("bonjour",100))
        assertNull(engine.correction("bonjour",0))
        assertNull(engine.correction("Paris",100))
        assertTrue(engine.candidates("bonjor",55).any{it.word=="bonjour"})
        assertEquals("bonjour",engine.correction("bonjor",80))
        assertTrue(engine.candidates("bonjuor",55).any{it.word=="bonjour" && it.distance==1})
        val start=System.nanoTime()
        repeat(20){engine.candidates("bonjor",55)}
        val ms=(System.nanoTime()-start)/1_000_000.0/20
        println("Corrector words=${engine.wordCount}; average lookup=${ms}ms")
        assertTrue("Lookup should stay below 150 ms on emulator, got $ms",ms<150)
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
