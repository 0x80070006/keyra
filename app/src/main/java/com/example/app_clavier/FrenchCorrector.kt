package com.example.app_clavier

import java.io.Reader
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min

/** Local trie search with bounded Damerau-Levenshtein distance and corpus frequency ranking. */
class FrenchCorrector(reader: Reader) {
    data class Candidate(val word: String, val distance: Int, val frequency: Long, val score: Double, val sparse: Boolean = false, val completion: Boolean = false)
    private class Node {
        val children=HashMap<Char,Node>()
        val words=ArrayList<Pair<String,Long>>(1)
    }
    private val root=Node()
    private val exact=HashSet<String>()
    private val sparsePrefixes=HashMap<String,MutableList<Pair<String,Long>>>()
    private fun canonical(word:String)=word.lowercase(Locale.FRENCH).replace('’','\'')
    private fun presentation(word:String,template:String)=if(template.contains('’'))word.replace('\'','’') else word
    private fun skeleton(word:String)=fold(word).filter{it !in "aeiouy"}
    private fun subsequence(query:String,word:String):Boolean {
        var i=0;for(ch in word){if(i<query.length && query[i]==ch)i++};return i==query.length
    }
    val wordCount: Int get()=exact.size
    init {
        reader.buffered().useLines { lines -> lines.forEach { line ->
            val parts=line.trim().split(' ')
            val word=canonical(parts.firstOrNull() ?: "")
            val frequency=parts.lastOrNull()?.toLongOrNull() ?: 0L
            if(word.length in 2..30 && word.all { it.isLetter() || it=='\'' || it=='’' || it=='-' }) {
                exact.add(word)
                var node=root
                for(ch in fold(word)) node=node.children.getOrPut(ch){Node()}
                node.words.add(word to frequency)
                val compact=skeleton(word)
                if(compact.length>=3)for(size in 3..minOf(4,compact.length))sparsePrefixes.getOrPut(compact.take(size)){ArrayList()}.add(word to frequency)
            }
        } }
    }
    fun contains(word:String)=exact.contains(canonical(word))
    fun candidates(word:String, tolerance:Int):List<Candidate> {
        if(word.length !in 2..30 || word.any { !it.isLetter() && it!='\'' && it!='’' && it!='-' })return emptyList()
        val normalized=canonical(word)
        val q=fold(normalized)
        val limit=if(tolerance<40 || q.length<5)1 else 2
        val found=ArrayList<Candidate>()
        val initial=IntArray(q.length+1){it}
        fun walk(ch:Char,node:Node,prev:IntArray,older:IntArray?,previousChar:Char?) {
            val row=IntArray(q.length+1);row[0]=prev[0]+1
            var minimum=row[0]
            for(j in 1..q.length) {
                var d=min(min(row[j-1]+1,prev[j]+1),prev[j-1]+if(ch==q[j-1])0 else 1)
                if(older!=null && j>1 && ch==q[j-2] && previousChar==q[j-1])d=min(d,older[j-2]+1)
                row[j]=d;minimum=min(minimum,d)
            }
            if(row[q.length]<=limit)node.words.forEach { (w,f) ->
                val d=row[q.length]
                // When every typed letter appears in order, the user most likely skipped
                // a key while typing quickly. Prefer that signal over raw corpus frequency
                // (for example, "vnir" must rank "venir" ahead of the common word "voir").
                val missingLetters=d>0 && fold(w).length>q.length && subsequence(q,fold(w))
                val omissionBonus=if(missingLetters)-0.85 else 0.0
                found.add(Candidate(presentation(w,word),d,f,d*3.2-ln(f+1.0)*.18 + omissionBonus + if(w==normalized) -4.0 else 0.0,sparse=missingLetters))
            }
            if(minimum<=limit)node.children.forEach{(next,child)->walk(next,child,row,prev,ch)}
        }
        root.children.forEach{(ch,node)->walk(ch,node,initial,null,null)}
        // Prefix completions make the strip useful even when the typed prefix is valid.
        if(q.length>=2){
            var prefixNode:Node?=root
            for(ch in q)prefixNode=prefixNode?.children?.get(ch)
            if(prefixNode!=null){
                val completions=ArrayList<Pair<String,Long>>()
                fun collect(node:Node,depth:Int){
                    if(depth>6 || completions.size>=24)return
                    completions.addAll(node.words)
                    node.children.values.forEach{collect(it,depth+1)}
                }
                collect(prefixNode,0)
                completions.sortedByDescending{it.second}.take(8).forEach{(candidate,frequency)->
                    val shown=presentation(candidate,word);val gap=(fold(candidate).length-q.length).coerceAtLeast(0)
                    if(gap>0 && found.none{it.word==shown})found.add(Candidate(shown,gap,frequency,1.45+gap*.20-ln(frequency+1.0)*.16,completion=true))
                }
            }
        }
        // Missing-letter matching remains deliberately narrow: same consonant beginning,
        // typed letters in order, and at most four omitted characters.
        if(tolerance>=55 && skeleton(normalized).length>=3){
            val key=skeleton(normalized).take(4)
            sparsePrefixes[key].orEmpty().forEach{(candidate,frequency)->
                val gap=candidate.length-normalized.length
                val foldedCandidate=fold(candidate)
                if(gap in 2..4 && foldedCandidate.firstOrNull()==q.firstOrNull() && subsequence(q,foldedCandidate) && found.none{it.word==presentation(candidate,word)}){
                    found.add(Candidate(presentation(candidate,word),gap,frequency,gap*.55-ln(frequency+1.0)*.18,true))
                }
            }
        }
        return found.sortedWith(compareBy<Candidate>{it.score}.thenByDescending{it.frequency}).take(3)
    }
    fun correction(word:String,tolerance:Int):String? {
        if(tolerance==0 || word.length<3 || contains(word) || word.all{it.isUpperCase()} || word.first().isUpperCase())return null
        val choices=candidates(word,tolerance)
        val best=choices.firstOrNull() ?: return null
        val distanceLimit=when{best.sparse->4;best.completion && word.length>=4->3;tolerance>=70 && word.length>=6->2;else->1}
        if(best.distance>distanceLimit)return null
        val minimumFrequency=when {best.sparse->10_000L;best.completion->12_000L;tolerance<35->10000L;tolerance<70->1500L;else->200L}
        if(best.frequency<minimumFrequency)return null
        val alternative=choices.getOrNull(1)
        // An ordered missing-letter match is stronger evidence than a same-distance
        // replacement, even when the replacement is more frequent in the corpus.
        if(best.sparse && alternative?.sparse!=true)return presentation(best.word,word)
        val ratio=if(alternative!=null && alternative.distance==best.distance)best.frequency.toDouble()/(alternative.frequency+1) else 100.0
        val requiredRatio=when {tolerance<35->4.0;tolerance<70->1.5;else->1.05}
        return if(ratio>=requiredRatio)presentation(best.word,word) else null
    }
    fun nextWords(previous:String):List<String>{
        val key=fold(previous).trim('\'','-')
        return nextWordMap[key].orEmpty()
    }
    companion object {
        private val nextWordMap=mapOf(
            "je" to listOf("suis","vais","veux"),"j" to listOf("ai","aime","arrive"),
            "tu" to listOf("es","as","peux"),"il" to listOf("est","a","faut"),"elle" to listOf("est","a","va"),
            "nous" to listOf("sommes","avons","allons"),"vous" to listOf("êtes","avez","pouvez"),"ils" to listOf("sont","ont","vont"),
            "le" to listOf("plus","temps","monde"),"la" to listOf("plus","même","vie"),"les" to listOf("plus","gens","autres"),
            "un" to listOf("peu","jour","moment"),"une" to listOf("fois","bonne","partie"),"de" to listOf("la","plus","faire"),
            "dans" to listOf("le","la","un"),"pour" to listOf("le","la","faire"),"avec" to listOf("le","toi","une"),
            "est" to listOf("un","une","pas"),"c" to listOf("est","était","est-à-dire"),"ça" to listOf("va","fait","peut"),
            "merci" to listOf("beaucoup","pour","à"),"bonjour" to listOf("à","tout","comment"),"bonne" to listOf("journée","soirée","nuit")
        )
        fun fold(s:String):String=Normalizer.normalize(s.lowercase(Locale.FRENCH).replace('’','\'').replace("œ","oe"),Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"")
    }
}
