package com.example.app_clavier

import android.content.Context
import java.text.Normalizer
import java.util.Locale

object EmojiCatalog {
    data class Entry(val category:String,val emoji:String,val name:String,val search:String)
    @Volatile private var cache:List<Entry>?=null
    @Synchronized fun all(c:Context):List<Entry> {
        cache?.let{return it}
        val french=c.assets.open("emoji_search_fr.tsv").bufferedReader().useLines { rows -> rows.mapNotNull {
            val p=it.split('\t',limit=2);if(p.size==2)p[0] to p[1] else null
        }.toMap() }
        return c.assets.open("emoji.tsv").bufferedReader().useLines { rows -> rows.mapNotNull {
            val p=it.split('\t');if(p.size>=3)Entry(p[0],p[1],p[2],fold("${p[2]} ${french[p[1]].orEmpty()} ${p[0]}"))else null
        }.toList() }.also{cache=it}
    }
    fun search(c:Context,query:String):List<Entry> {
        val tokens=fold(query).split(Regex("\\s+")).filter{it.isNotBlank()}
        if(tokens.isEmpty())return all(c)
        return all(c).filter{entry->tokens.all{it in entry.search}}
    }
    /** Mot d'une seule annotation CLDR française → l'emoji le plus spécifique (celui qui a le moins d'annotations). */
    @Volatile private var byWord:Map<String,String>?=null
    @Synchronized private fun wordIndex(c:Context):Map<String,String> {
        byWord?.let{return it}
        val best=HashMap<String,Pair<String,Int>>()
        c.assets.open("emoji_search_fr.tsv").bufferedReader().useLines{rows->rows.forEach{row->
            val parts=row.split('\t',limit=2);if(parts.size<2)return@forEach
            val terms=parts[1].split(" | ")
            terms.filter{' ' !in it && it.length>=3}.forEach{term->
                val key=fold(term)
                if(best[key]?.second?.let{it<=terms.size}!=true)best[key]=parts[0] to terms.size
            }
        }}
        return best.mapValues{it.value.first}.also{byWord=it}
    }
    /** Suggestion d'emoji (SwiftKey) pour un mot tapé, ou null. */
    fun forWord(c:Context,word:String):String? = if(word.length<3)null else wordIndex(c)[fold(word)]
    private fun fold(value:String)=Normalizer.normalize(value.lowercase(Locale.FRENCH).replace("œ","oe").replace("æ","ae"),Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"")
}
