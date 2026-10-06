package com.example.app_clavier

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Référence de qualité et de vitesse du correcteur (phase 0).
 * Données synthétiques : tools/generate_typos.py. Le rapport est écrit dans
 * build/reports/keyra/correction.txt. Les temps sont mesurés sur la JVM de l'hôte,
 * pas sur le téléphone : ils servent à comparer deux versions entre elles.
 */
class CorrectionQualityTest {
    private data class Typo(val typo:String,val expected:String,val kind:String,val level:String)
    private fun rows(name:String)=File("src/test/resources/$name").readLines().filter{it.isNotBlank() && !it.startsWith("#")}
    private val typos=rows("fr_typos.tsv").map{val p=it.split('\t');Typo(p[0],p[1],p[2],p[3])}
    private val oov=rows("fr_oov_valid.tsv")

    private fun percentile(values:LongArray,p:Double):Double {
        val sorted=values.sortedArray();return sorted[((sorted.size-1)*p).toInt()]/1_000_000.0
    }
    private fun pct(n:Int,total:Int)="%.1f %%".format(100.0*n/total.coerceAtLeast(1))

    @Test fun baselineQualityAndSpeed(){
        val loadStart=System.nanoTime()
        val engine=FrenchCorrector(File("src/main/assets/fr_frequency.txt").reader())
        val loadMs=(System.nanoTime()-loadStart)/1_000_000.0
        val report=StringBuilder("Keyra — référence du correcteur\n")
        report.append("Dictionnaire : ${engine.wordCount} mots, chargé en %.0f ms (JVM hôte)\n".format(loadMs))
        val okByTolerance=HashMap<Int,Int>()
        for(tolerance in listOf(55,70)){
            report.append("\n## Tolérance $tolerance\n")
            for(level in listOf("standard","difficile","tous")){
                val set=typos.filter{level=="tous" || it.level==level}
                var ok=0;var wrong=0;var none=0;var top3=0
                set.forEach{t->
                    val correction=engine.correction(t.typo,tolerance)
                    when{correction==t.expected->ok++;correction==null->none++;else->wrong++}
                    if(engine.candidates(t.typo,tolerance).any{it.word==t.expected})top3++
                }
                if(level=="tous")okByTolerance[tolerance]=ok
                report.append("$level (${set.size}) : corrigé juste ${pct(ok,set.size)}, corrigé faux ${pct(wrong,set.size)}, non corrigé ${pct(none,set.size)}, attendu dans le top 3 ${pct(top3,set.size)}\n")
            }
            for(kind in typos.map{it.kind}.distinct().sorted()){
                val set=typos.filter{it.kind==kind}
                report.append("  type $kind (${set.size}) : corrigé juste ${pct(set.count{engine.correction(it.typo,tolerance)==it.expected},set.size)}\n")
            }
            val over=oov.count{engine.correction(it,tolerance)!=null}
            report.append("Sur-correction de mots valides hors dictionnaire (${oov.size}) : ${pct(over,oov.size)}\n")
        }
        // Coût par frappe : le service appelle candidates() puis correction() sur chaque préfixe tapé.
        val prefixes=typos.flatMap{t->(2..t.typo.length).map{t.typo.take(it)}}
        repeat(2){prefixes.forEach{engine.candidates(it,55);engine.correction(it,55)}}
        val samples=LongArray(prefixes.size)
        prefixes.forEachIndexed{i,p->val s=System.nanoTime();engine.candidates(p,55);engine.correction(p,55);samples[i]=System.nanoTime()-s}
        report.append("\nCoût par frappe (candidates + correction, ${prefixes.size} préfixes) : p50 %.2f ms, p95 %.2f ms, max %.2f ms (JVM hôte)\n"
            .format(percentile(samples,.5),percentile(samples,.95),percentile(samples,1.0)))
        println(report)
        File("build/reports/keyra").mkdirs();File("build/reports/keyra/correction.txt").writeText(report.toString())
        // Plancher anti-régression : la référence mesurée en phase 0, moins une marge.
        assertTrue("Régression de la correction : ${okByTolerance[55]} / ${typos.size}",(okByTolerance[55] ?: 0)>=BASELINE_OK_55)
    }
    private companion object { const val BASELINE_OK_55=155 } // 159 mesurés en phase 0 (79,5 %)
}
