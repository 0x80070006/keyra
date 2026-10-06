package com.example.app_clavier.engine

import android.content.Context
import com.example.app_clavier.core.KeyraCore

/**
 * Façade Kotlin du moteur de correction et de prédiction en Rust (rust/keyra-core/src/predict.rs).
 * Remplace FrenchCorrector : distance pondérée par le clavier AZERTY, complétions, une seule recherche par frappe.
 * Échec fermé : sans bibliothèque native, aucune suggestion ni correction (la frappe continue normalement).
 */
object Predictor {
    class Analysis(val correction:String?,val suggestions:List<String>,val completions:List<Boolean>)
    @Volatile var ready=false;private set

    /** Charge le dictionnaire embarqué (à appeler hors du fil principal). */
    fun load(c:Context):Boolean {
        if(ready)return true
        val bytes=runCatching{c.assets.open("fr_frequency.txt").use{it.readBytes()}}.getOrNull() ?: return false
        ready=KeyraCore.loadEngine(bytes)>0
        return ready
    }

    fun analyze(word:String,tolerance:Int,limit:Int=3):Analysis? {
        if(!ready)return null
        val text=KeyraCore.analyzeWord(word,tolerance,limit) ?: return null
        val lines=text.split('\n')
        val rows=lines.drop(1).filter{it.isNotEmpty()}.map{it.substringBefore('\t') to it.endsWith("\t1")}
        return Analysis(lines.first().ifEmpty{null},rows.map{it.first},rows.map{it.second})
    }
    fun correction(word:String,tolerance:Int)=analyze(word,tolerance,0)?.correction
    fun contains(word:String)=ready && KeyraCore.knowsWord(word)
    /** Probabilité (0 à 255) de chaque lettre a–z après `prefix`, pour les zones de toucher dynamiques. */
    fun nextLetters(prefix:String):ByteArray? = if(ready)KeyraCore.nextLetters(prefix) else null
}
