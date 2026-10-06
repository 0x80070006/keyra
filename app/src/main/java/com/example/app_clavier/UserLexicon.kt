package com.example.app_clavier

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Device-local vocabulary learned only from words completed with a separator. */
object UserLexicon {
    private const val key="learned_words"
    private fun prefs(c:Context)=c.getSharedPreferences("keyboard",Context.MODE_PRIVATE)
    private var cached:MutableMap<String,Int>?=null
    private fun words(c:Context):MutableMap<String,Int> = cached ?: runCatching {
        val json=JSONObject(prefs(c).getString(key,"{}") ?: "{}")
        json.keys().asSequence().mapNotNull { it }.associateWith { json.optInt(it,0) }.toMutableMap()
    }.getOrDefault(mutableMapOf()).also{cached=it}
    private fun valid(word:String)=word.length in 2..30 && word.all{it.isLetter() || it=='\'' || it=='’' || it=='-'}
    @Synchronized fun record(c:Context,word:String) {
        val value=word.lowercase(Locale.FRENCH).replace('’','\'')
        if(!valid(value))return
        val all=words(c);all[value]=(all[value]?:0).plus(1).coerceAtMost(1_000_000)
        if(all.size>2_000)all.entries.sortedBy{it.value}.take(all.size-2_000).forEach{all.remove(it.key)}
        val json=JSONObject();all.forEach{(name,count)->json.put(name,count)}
        prefs(c).edit().putString(key,json.toString()).apply()
    }
    @Synchronized fun suggestions(c:Context,prefix:String):List<String> {
        val folded=prefix.lowercase(Locale.FRENCH).replace('’','\'')
        if(folded.length<2)return emptyList()
        return words(c).asSequence().filter{(word,count)->count>=2 && word.startsWith(folded) && word!=folded}
            .sortedByDescending{it.value}.map{it.key}.take(3).toList()
    }
    @Synchronized fun clear(c:Context){cached=mutableMapOf();prefs(c).edit().remove(key).apply()}
}
