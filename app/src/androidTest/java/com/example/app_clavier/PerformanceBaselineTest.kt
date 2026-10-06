package com.example.app_clavier

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.app_clavier.keyboard.KeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Références mesurées sur la vraie vue du clavier (largeur 1080 px). Valeurs écrites dans logcat
 * (étiquette KeyraBaseline). Phase 0 : 4 reconstructions, 26,7 % de zones mortes. Cible phase 2 : 0 et 0.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceBaselineTest {
    @Test fun rebuildsWhileTypingASentence(){
        val kit=KeyboardTestKit().create()
        val start=kit.keyboard.rebuildCount
        listOf("Majuscules","B","o","n","j","o","u","r","Espace","Majuscules","M","a","m","a","n").forEach{kit.tap(it)}
        val rebuilds=kit.keyboard.rebuildCount-start
        Log.i("KeyraBaseline","rebuilds pour « Bonjour Maman » : $rebuilds")
        assertEquals("Bonjour Maman",kit.typed.joinToString(""))
        assertEquals("Maj, lettres et espace ne reconstruisent plus aucune vue",0,rebuilds)
    }

    @Test fun deadZonesBetweenKeys(){
        val kit=KeyboardTestKit().create()
        var dead=0;var total=0
        kit.main{
            for(ry in 95 until 497 step 2)for(rx in 0 until 684 step 2){
                val (x,y)=kit.ref(rx.toFloat(),ry.toFloat());total++
                if(kit.keyboard.descriptionAt(x,y)==null)dead++
            }
        }
        Log.i("KeyraBaseline","zones mortes : quatre rangées %.1f %% (%d points)".format(dead*100.0/total,total))
        val gaps=listOf(71,138,206,274,342,410,477,545,613).map{kit.ref(it.toFloat(),137f)}
        gaps.forEach{kit.tapAt(it)}
        Log.i("KeyraBaseline","appuis dans les écarts : ${gaps.size}, caractères tapés : ${kit.typed.size}")
        assertEquals("aucun point mort",0,dead)
        assertEquals("chaque appui dans un écart tape un caractère",gaps.size,kit.typed.size)
    }

    /** Deux pouces qui alternent sans attendre le relâchement : l'ordre est conservé (phantom up). */
    @Test fun twoThumbsKeepTheOrder(){
        val kit=KeyboardTestKit().create()
        val a=kit.center("a");val p=kit.center("p")
        repeat(100){
            kit.press(0,a);kit.press(1,p) // le second doigt valide aussitôt le premier
            kit.release(0);kit.release(1)
        }
        assertEquals(200,kit.typed.size)
        assertTrue(kit.typed.chunked(2).all{it==listOf("a","p")})
    }

    @Test fun slidingCorrectsTheKey(){
        val kit=KeyboardTestKit().create()
        kit.press(0,kit.center("a"));kit.move(0,kit.center("z"));kit.release(0)
        assertEquals(listOf("z"),kit.typed)
    }

    @Test fun slideFromSymbolsTypesThenComesBack(){
        val kit=KeyboardTestKit().create()
        kit.press(0,kit.center("Chiffres et symboles"))
        kit.move(0,kit.center("1"))
        kit.release(0)
        assertEquals(listOf("1"),kit.typed)
        kit.main{assertTrue("retour aux lettres",kit.keyboard.keyRect("a")!=null)}
    }

    @Test fun longPressOffersAccents(){
        val kit=KeyboardTestKit().create()
        kit.press(0,kit.center("e"))
        kit.sleep(450)
        kit.move(0,kit.ref(95f,51f)) // deuxième choix du panneau : « é »
        kit.release(0)
        assertEquals(listOf("é"),kit.typed)
    }

    @Test fun deleteRepeatsWhileHeld(){
        val kit=KeyboardTestKit().create()
        kit.press(0,kit.center("Effacer"));kit.sleep(900);kit.release(0)
        assertTrue("répétition : ${kit.typed.size}",kit.typed.size>=5 && kit.typed.all{it=="delete"})
    }

    @Test fun accessibilityExposesEveryKey(){
        val kit=KeyboardTestKit().create(password=true)
        kit.main{
            val view=(0 until kit.keyboard.childCount).map{kit.keyboard.getChildAt(it)}.filterIsInstance<KeyboardView>().first()
            val provider=view.accessibilityNodeProvider
            val names=view.keys.indices.map{provider.createAccessibilityNodeInfo(it)!!.contentDescription.toString()}
            assertTrue(names.contains("Espace"));assertTrue(names.contains("Majuscules"));assertTrue(names.contains("Effacer"))
            assertTrue("champ mot de passe : TalkBack dit « point »",names.contains("Point") && !names.contains("a"))
            val space=names.indexOf("Espace")
            assertTrue(provider.performAction(space,AccessibilityNodeInfo.ACTION_CLICK,null))
        }
        assertEquals(listOf(" "),kit.typed)
    }

    @Test fun shuffledPinPadIsQuietAndComplete(){
        val prefs=KeyboardPrefs.of(KeyboardTestKit().context)
        prefs.edit().putBoolean("pin_shuffle",true).commit()
        try{
            val kit=KeyboardTestKit().create(numeric=true,privateInput=true,password=true,pinPad=true)
            kit.main{for(d in 0..9)assertTrue("chiffre $d",kit.keyboard.keyRect("$d")!=null)}
        }finally{prefs.edit().remove("pin_shuffle").commit()}
    }

    @Test fun correctorCostOnDevice(){
        val context=KeyboardTestKit().context
        val loadStart=System.nanoTime()
        val engine=FrenchCorrector(context.assets.open("fr_frequency.txt").reader())
        val loadMs=(System.nanoTime()-loadStart)/1_000_000.0
        val words="bonjour je tetse la frappe rapdie sur ce clavier pour mesurer chaque imgae dessinee pendnat une saisie normale".split(' ')
        val prefixes=words.flatMap{w->(2..w.length).map{w.take(it)}}
        repeat(3){prefixes.forEach{engine.candidates(it,55);engine.correction(it,55)}}
        val samples=prefixes.map{p->val s=System.nanoTime();engine.candidates(p,55);engine.correction(p,55);System.nanoTime()-s}.sorted()
        fun at(q:Double)=samples[((samples.size-1)*q).toInt()]/1_000_000.0
        Log.i("KeyraBaseline","dictionnaire chargé en %.0f ms ; coût par frappe p50 %.2f ms, p95 %.2f ms, max %.2f ms (%d préfixes)"
            .format(loadMs,at(.5),at(.95),at(1.0),samples.size))
    }
}
