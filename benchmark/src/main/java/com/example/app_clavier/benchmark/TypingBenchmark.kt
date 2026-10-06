package com.example.app_clavier.benchmark

import android.graphics.Point
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tape 200 caractères avec Keyra dans le champ d'essai de MainActivity et mesure les images.
 * Le texte est synthétique, en minuscules sans accents (pas d'appui long ni de Maj) pour ne
 * mesurer que la frappe. Deux modes : sans compilation (pire cas, JIT à froid) et compilation
 * complète (ce qu'un Baseline Profile permettrait d'approcher).
 */
@RunWith(AndroidJUnit4::class)
class TypingBenchmark {
    @get:Rule val rule=MacrobenchmarkRule()

    @Test fun typingNoCompilation()=typing(CompilationMode.None())
    @Test fun typingFullCompilation()=typing(CompilationMode.Full())

    private fun typing(mode:CompilationMode)=rule.measureRepeated(
        packageName=TARGET,
        metrics=listOf(FrameTimingMetric()),
        compilationMode=mode,
        iterations=5,
        setupBlock={
            device.executeShellCommand("ime enable $IME")
            device.executeShellCommand("ime set $IME")
            startActivityAndWait()
            device.findObject(By.clazz("android.widget.EditText")).click()
            check(device.wait(Until.hasObject(By.desc("Espace")),5_000)){"Keyra ne s'est pas affiché"}
        }
    ){ typeText() }

    private fun MacrobenchmarkScope.typeText(){
        val centers=HashMap<Char,Point>()
        for(ch in TEXT){
            val point=centers.getOrPut(ch){
                val label=if(ch==' ')"Espace" else ch.toString()
                device.findObject(By.desc(label))?.visibleCenter ?: error("Touche introuvable : $label")
            }
            device.click(point.x,point.y)
        }
        device.waitForIdle()
    }

    private companion object {
        const val TARGET="com.example.app_clavier"
        const val IME="$TARGET/.MintInputService"
        val TEXT=("bonjour je teste la frappe rapide sur ce clavier pour mesurer chaque image dessinee " +
            "pendant une saisie normale avec des mots courants et des espaces entre eux jusqu a deux cents caracteres ok").take(200)
    }
}
