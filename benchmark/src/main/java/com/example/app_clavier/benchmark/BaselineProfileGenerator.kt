package com.example.app_clavier.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Génère le Baseline Profile de Keyra (ADR-0030) : ouverture du clavier, frappe, bandeau de suggestions,
 * symboles et retour aux lettres. Résultat à copier dans app/src/main/baseline-prof.txt :
 * ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.app_clavier.benchmark.BaselineProfileGenerator
 * Le texte tapé est synthétique : le profil ne contient que des noms de classes et de méthodes.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule=BaselineProfileRule()

    @Test fun generate()=rule.collect(packageName=TARGET,includeInStartupProfile=true){
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        pressHome()
        startActivityAndWait()
        device.findObject(By.clazz("android.widget.EditText")).click()
        check(device.wait(Until.hasObject(By.desc("Espace")),5_000)){"Keyra ne s'est pas affiché"}
        for(ch in "bonjuor ca va bien merci "){
            val label=if(ch==' ')"Espace" else ch.toString()
            device.findObject(By.desc(label))?.click()
        }
        device.findObject(By.desc("Chiffres et symboles"))?.click()
        device.findObject(By.desc("Chiffres et symboles"))?.click()
        device.findObject(By.desc("Effacer"))?.click()
        device.waitForIdle()
    }

    private companion object {
        const val TARGET="com.example.app_clavier"
        const val IME="$TARGET/.MintInputService"
    }
}
