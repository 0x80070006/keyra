package com.example.app_clavier

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.View
import android.view.ViewGroup
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
    private fun find(group:ViewGroup,label:String):View {
        for(i in 0 until group.childCount){val v=group.getChildAt(i);if(v.contentDescription?.toString()==label)return v}
        throw AssertionError("Missing key: $label")
    }
    @Test fun lowercaseShiftSymbolsAndEmoji(){instrumentation.runOnMainSync {
        val typed=mutableListOf<String>()
        val keyboard=MintKeyboard(context){typed.add(it)}
        keyboard.reset(false,false)
        find(keyboard,"a").performClick();assertEquals("a",typed.last())
        find(keyboard,"Majuscules").performClick();find(keyboard,"A").performClick();assertEquals("A",typed.last())
        assertNotNull(find(keyboard,"a"))
        find(keyboard,"Chiffres et symboles").performClick();find(keyboard,"@").performClick();assertEquals("@",typed.last())
        find(keyboard,"Deuxième page de symboles").performClick();find(keyboard,"π").performClick();assertEquals("π",typed.last())
        find(keyboard,"Chiffres et symboles").performClick()
        find(keyboard,"Emoji").performClick()
        val grid=(0 until keyboard.childCount).map{keyboard.getChildAt(it)}.filterIsInstance<GridView>().first()
        assertEquals(3944,grid.adapter.count)
    }}
    @Test fun themesHaveTwoPalettes(){
        KeyboardPrefs.themes.keys.forEach{val p=KeyboardPrefs.palette(context,it);assertNotEquals("Theme $it",p.key,p.special)}
    }
    @Test fun frequentEmojiAreLimitedAndRanked(){
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
    @Test fun feedbackIsImmediateWithoutRebuildingLetterKeys(){instrumentation.runOnMainSync{
        val keyboard=MintKeyboard(context){}
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1500,View.MeasureSpec.AT_MOST));keyboard.layout(0,0,1080,keyboard.measuredHeight)
        val key=find(keyboard,"a")
        val now=android.os.SystemClock.uptimeMillis()
        val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,20f,20f,0)
        key.dispatchTouchEvent(down);assertTrue(key.isPressed);down.recycle()
        val up=android.view.MotionEvent.obtain(now,now+20,android.view.MotionEvent.ACTION_UP,20f,20f,0)
        key.dispatchTouchEvent(up);up.recycle()
        assertSame(key,find(keyboard,"a"))
    }}
    @Test fun rapidPressesAreNeverDropped(){instrumentation.runOnMainSync{
        val typed=ArrayList<String>()
        val keyboard=MintKeyboard(context){typed.add(it)}
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1500,View.MeasureSpec.AT_MOST))
        keyboard.layout(0,0,1080,keyboard.measuredHeight)
        val key=find(keyboard,"a")
        repeat(200){index->
            val time=android.os.SystemClock.uptimeMillis()+index
            val down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,20f,20f,0)
            key.dispatchTouchEvent(down);down.recycle()
            assertEquals(index+1,typed.size)
            val up=android.view.MotionEvent.obtain(time,time+1,android.view.MotionEvent.ACTION_UP,20f,20f,0)
            key.dispatchTouchEvent(up);up.recycle()
        }
        assertTrue(typed.all{it=="a"})
        assertSame(key,find(keyboard,"a"))
    }}
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
