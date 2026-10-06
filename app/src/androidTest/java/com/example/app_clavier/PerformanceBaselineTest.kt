package com.example.app_clavier

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Références de la phase 0, mesurées sur la vraie vue du clavier (largeur 1080 px, Pixel 9a).
 * Les valeurs sont écrites dans logcat sous l'étiquette KeyraBaseline. Les assertions sont des
 * planchers anti-régression : elles doivent rester vraies quand les phases suivantes améliorent les chiffres.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceBaselineTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val width=1080
    private fun laidOut(keyboard:MintKeyboard){
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(3000,View.MeasureSpec.AT_MOST))
        keyboard.layout(0,0,width,keyboard.measuredHeight)
    }
    private fun find(group:ViewGroup,label:String):View =
        (0 until group.childCount).map{group.getChildAt(it)}.firstOrNull{it.contentDescription?.toString()==label} ?: throw AssertionError("Touche absente : $label")
    private fun tapAt(keyboard:MintKeyboard,x:Float,y:Float){
        instrumentation.runOnMainSync{
            val t=SystemClock.uptimeMillis()
            MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0).also{keyboard.dispatchTouchEvent(it);it.recycle()}
            MotionEvent.obtain(t,t+30,MotionEvent.ACTION_UP,x,y,0).also{keyboard.dispatchTouchEvent(it);it.recycle()}
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync{laidOut(keyboard)}
    }
    private fun tap(keyboard:MintKeyboard,label:String){
        // Hors fenêtre, View.onTouchEvent met le clic en file sans jamais l'exécuter :
        // les touches à clic (Maj) sont donc déclenchées directement, comme dans FeatureTest.
        if(label=="Majuscules"){instrumentation.runOnMainSync{find(keyboard,label).performClick();laidOut(keyboard)};return}
        var x=0f;var y=0f
        instrumentation.runOnMainSync{val key=find(keyboard,label);x=(key.left+key.right)/2f;y=(key.top+key.bottom)/2f}
        tapAt(keyboard,x,y)
    }

    @Test fun rebuildsWhileTypingASentence(){
        val typed=ArrayList<String>()
        lateinit var keyboard:MintKeyboard
        instrumentation.runOnMainSync{keyboard=MintKeyboard(context){typed.add(it)};keyboard.reset(false,false);laidOut(keyboard)}
        val start=keyboard.rebuildCount
        listOf("Majuscules","B","o","n","j","o","u","r","Espace","Majuscules","M","a","m","a","n").forEach{tap(keyboard,it)}
        val rebuilds=keyboard.rebuildCount-start
        Log.i("KeyraBaseline","rebuilds pour « Bonjour Maman » : $rebuilds")
        assertEquals("Bonjour Maman",typed.joinToString(""))
        assertTrue("Plus de reconstructions qu'en phase 0 : $rebuilds",rebuilds<=BASELINE_REBUILDS)
    }

    @Test fun deadZonesBetweenKeys(){
        lateinit var keyboard:MintKeyboard
        val typed=ArrayList<String>()
        instrumentation.runOnMainSync{keyboard=MintKeyboard(context){typed.add(it)};keyboard.reset(false,false);laidOut(keyboard)}
        // Échelles réelles du clavier : sur un écran court, la hauteur est plafonnée (sy < sx).
        val scale=width/684f;val scaleY=keyboard.measuredHeight/612f
        fun deadFraction(top:Int,bottom:Int):Double {
            var dead=0;var total=0
            instrumentation.runOnMainSync{
                val keys=(0 until keyboard.childCount).map{keyboard.getChildAt(it)}
                for(ry in top until bottom step 2)for(rx in 0 until 684 step 2){
                    val x=rx*scale;val y=ry*scaleY;total++
                    if(keys.none{x>=it.left && x<it.right && y>=it.top && y<it.bottom})dead++
                }
            }
            return dead.toDouble()/total
        }
        // Repère 684 : rangées de lettres de y=95 à y=391, rangée du bas jusqu'à y=497.
        val letters=deadFraction(95,391);val all=deadFraction(95,497)
        Log.i("KeyraBaseline","zones mortes : rangées de lettres %.1f %%, quatre rangées %.1f %%".format(letters*100,all*100))
        // Un appui au milieu de chaque écart horizontal de la rangée AZERTY ne doit rien taper aujourd'hui (défaut R1).
        val gaps=listOf(71,138,206,274,342,410,477,545,613).map{it*scale to 137*scaleY}
        gaps.forEach{(x,y)->tapAt(keyboard,x,y)}
        Log.i("KeyraBaseline","appuis dans les écarts : ${gaps.size}, caractères tapés : ${typed.size}")
        assertTrue("Zones mortes en hausse : $letters",letters<=BASELINE_DEAD_LETTERS)
    }

    @Test fun correctorCostOnDevice(){
        val loadStart=System.nanoTime()
        val engine=FrenchCorrector(context.assets.open("fr_frequency.txt").reader())
        val loadMs=(System.nanoTime()-loadStart)/1_000_000.0
        // Préfixes d'une phrase synthétique, avec quelques fautes : ce que le service calcule à chaque frappe.
        val words="bonjour je tetse la frappe rapdie sur ce clavier pour mesurer chaque imgae dessinee pendnat une saisie normale".split(' ')
        val prefixes=words.flatMap{w->(2..w.length).map{w.take(it)}}
        repeat(3){prefixes.forEach{engine.candidates(it,55);engine.correction(it,55)}}
        val samples=prefixes.map{p->val s=System.nanoTime();engine.candidates(p,55);engine.correction(p,55);System.nanoTime()-s}.sorted()
        fun at(q:Double)=samples[((samples.size-1)*q).toInt()]/1_000_000.0
        Log.i("KeyraBaseline","dictionnaire chargé en %.0f ms ; coût par frappe p50 %.2f ms, p95 %.2f ms, max %.2f ms (%d préfixes)"
            .format(loadMs,at(.5),at(.95),at(1.0),samples.size))
    }

    private companion object {
        const val BASELINE_REBUILDS=4 // mesuré en phase 0 : 2 par appui sur Maj
        const val BASELINE_DEAD_LETTERS=0.27 // mesuré en phase 0 : 26,7 %
    }
}
