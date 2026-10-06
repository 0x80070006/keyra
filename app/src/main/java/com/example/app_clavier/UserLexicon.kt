package com.example.app_clavier

import android.content.Context
import com.example.app_clavier.storage.EncryptedKv
import java.util.Locale

/**
 * Mots appris sur l'appareil, chiffrés (magasin « words »). On n'y écrit que via LearningGate
 * (vérifié par ArchitectureTest) : jamais en navigation privée, dans un champ sensible ni pour un secret.
 */
object UserLexicon {
    private const val MAX_WORDS=2_000
    private fun store(c:Context)=EncryptedKv.of(c,"words")
    private fun valid(word:String)=word.length in 2..30 && word.all{it.isLetter() || it=='\'' || it=='’' || it=='-'}

    /** Réservé à LearningGate. */
    fun record(c:Context,word:String):Boolean {
        val value=word.lowercase(Locale.FRENCH).replace('’','\'')
        if(!valid(value))return false
        val store=store(c)
        val count=(store.get(value)?.toIntOrNull() ?: 0)+1
        if(!store.put(value,count.coerceAtMost(1_000_000).toString()))return false
        store.view{it.size}?.takeIf{it>MAX_WORDS}?.let{
            store.view{all->all.entries.sortedBy{e->e.value.toIntOrNull() ?: 0}.take(all.size-MAX_WORDS).map{e->e.key}}?.forEach{store.remove(it)}
        }
        return true
    }

    fun suggestions(c:Context,prefix:String):List<String> {
        val folded=prefix.lowercase(Locale.FRENCH).replace('’','\'')
        if(folded.length<2)return emptyList()
        return store(c).view{words->words.entries.asSequence()
            .filter{(word,count)->(count.toIntOrNull() ?: 0)>=2 && word.startsWith(folded) && word!=folded}
            .sortedByDescending{it.value.toIntOrNull() ?: 0}.map{it.key}.take(3).toList()} ?: emptyList()
    }

    /** Tableau de transparence et tests. Null si le coffre est verrouillé. */
    fun all(c:Context):Map<String,Int>? = store(c).entries()?.mapValues{it.value.toIntOrNull() ?: 0}
    fun clear(c:Context)=store(c).clear()
}

/** Mots personnels à ne jamais corriger (magasin chiffré « personal »). */
object PersonalWords {
    private fun store(c:Context)=EncryptedKv.of(c,"personal")
    private fun key(word:String)=word.trim().lowercase(Locale.FRENCH)
    fun contains(c:Context,word:String)=store(c).view{key(word) in it} ?: false
    fun asText(c:Context)=store(c).view{it.keys.sorted().joinToString("\n")} ?: ""
    fun replaceFromText(c:Context,text:String)=store(c).replaceAll(text.lineSequence().map(::key).filter{it.isNotEmpty()}.associateWith{"1"})
    fun clear(c:Context)=store(c).clear()
}
