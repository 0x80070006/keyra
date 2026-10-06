package com.example.app_clavier.engine

import android.content.Context
import com.example.app_clavier.storage.EncryptedKv
import java.util.Locale

/**
 * Prédiction du mot suivant (AnySoftKeyboard, SwiftKey) : paires de mots apprises sur l'appareil, chiffrées
 * (magasin « bigrams »), complétées par une petite table française embarquée. Écriture via LearningGate seulement.
 */
object NextWords {
    private const val SEPARATOR='\u0001'
    private const val MAX_PAIRS=20_000
    private fun store(c:Context)=EncryptedKv.of(c,"bigrams")
    private fun key(word:String)=word.lowercase(Locale.FRENCH).trim('\'','’','-')

    private val defaults=mapOf(
        "je" to listOf("suis","vais","veux"),"j" to listOf("ai","aime","arrive"),"tu" to listOf("es","as","peux"),
        "il" to listOf("est","a","faut"),"elle" to listOf("est","a","va"),"on" to listOf("se","va","peut"),
        "nous" to listOf("sommes","avons","allons"),"vous" to listOf("êtes","avez","pouvez"),"ils" to listOf("sont","ont","vont"),
        "le" to listOf("plus","temps","monde"),"la" to listOf("plus","même","vie"),"les" to listOf("plus","gens","autres"),
        "un" to listOf("peu","jour","moment"),"une" to listOf("fois","bonne","partie"),"de" to listOf("la","plus","faire"),
        "dans" to listOf("le","la","un"),"pour" to listOf("le","la","faire"),"avec" to listOf("le","toi","une"),
        "est" to listOf("un","une","pas"),"c" to listOf("est","était","est-à-dire"),"ça" to listOf("va","fait","peut"),
        "merci" to listOf("beaucoup","pour","à"),"bonjour" to listOf("à","tout","comment"),"bonne" to listOf("journée","soirée","nuit"),
        "à" to listOf("demain","bientôt","plus"),"comment" to listOf("ça","vas","allez"),"qu" to listOf("est-ce","il","on"),
    )

    /** Réservé à LearningGate. */
    fun record(c:Context,previous:String,next:String){
        val p=key(previous);val n=key(next)
        if(p.isEmpty() || n.isEmpty())return
        val store=store(c)
        val pair="$p$SEPARATOR$n"
        store.put(pair,((store.get(pair)?.toIntOrNull() ?: 0)+1).coerceAtMost(1_000_000).toString())
        store.view{all->if(all.size>MAX_PAIRS)all.entries.sortedBy{it.value.toIntOrNull() ?: 0}.take(all.size-MAX_PAIRS+1_000).map{it.key} else null}?.forEach{store.remove(it)}
    }

    /** Jusqu'à 3 mots probables après `previous` : appris d'abord (si autorisé), puis la table embarquée. */
    fun predict(c:Context,previous:String,learnedAllowed:Boolean):List<String> {
        val p=key(previous)
        if(p.isEmpty())return emptyList()
        val learned=if(!learnedAllowed)emptyList() else store(c).view{all->
            val prefix="$p$SEPARATOR"
            all.entries.asSequence().filter{it.key.startsWith(prefix)}.sortedByDescending{it.value.toIntOrNull() ?: 0}.take(3).map{it.key.substring(prefix.length)}.toList()
        } ?: emptyList()
        return (learned+defaults[p].orEmpty()).distinct().take(3)
    }

    /** Tableau de transparence (phase 6) et tests. */
    fun all(c:Context):Map<Pair<String,String>,Int>? = store(c).entries()?.entries?.associate{(k,v)->(k.substringBefore(SEPARATOR) to k.substringAfter(SEPARATOR)) to (v.toIntOrNull() ?: 0)}
    fun clear(c:Context)=store(c).clear()
}

/** Suggestions masquées par l'utilisateur (appui long sur une suggestion), chiffrées (magasin « blocked »). */
object BlockedWords {
    private fun store(c:Context)=EncryptedKv.of(c,"blocked")
    private fun key(word:String)=word.lowercase(Locale.FRENCH)
    fun add(c:Context,word:String)=store(c).put(key(word),"1")
    fun remove(c:Context,word:String)=store(c).remove(key(word))
    fun contains(c:Context,word:String)=store(c).view{key(word) in it} ?: false
    fun all(c:Context)=store(c).entries()?.keys?.sorted() ?: emptyList()
}
