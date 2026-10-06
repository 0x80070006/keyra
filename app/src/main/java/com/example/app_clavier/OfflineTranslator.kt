package com.example.app_clavier

import java.io.Reader
import java.text.Normalizer
import java.util.Locale

/** Small deterministic FR↔EN translator. It never opens a network connection. */
class OfflineTranslator(reader:Reader){
    private data class Entry(val source:String,val target:String)
    private val frToEn:Map<String,String>
    private val enToFr:Map<String,String>

    init{
        val forward=LinkedHashMap<String,String>()
        reader.buffered().useLines{lines->lines.forEach{line->
            if(line.isBlank() || line.startsWith('#'))return@forEach
            val parts=line.split('\t',limit=2)
            if(parts.size==2)forward[normalize(parts[0])]=parts[1].trim()
        }}
        frToEn=forward
        val reverse=LinkedHashMap<String,String>()
        forward.forEach{(fr,en)->reverse.putIfAbsent(normalize(en),fr)}
        enToFr=reverse
    }

    fun translate(text:String,englishToFrench:Boolean=false):String{
        if(text.isBlank())return ""
        val dictionary=if(englishToFrench)enToFr else frToEn
        val placeholders=ArrayList<String>()
        var work=text
        dictionary.entries.asSequence().filter{it.key.contains(' ')}.sortedByDescending{it.key.length}.forEach{(source,target)->
            val words=Regex.escape(source).replace("\\ ","\\s+")
            val pattern=Regex("(?iu)(?<!\\p{L})$words(?!\\p{L})")
            work=pattern.replace(work){match->
                val replacement=matchCase(target,match.value)
                val marker="\uE000${placeholders.size}\uE001";placeholders.add(replacement);marker
            }
        }
        work=Regex("[\\p{L}]+(?:['’\\-][\\p{L}]+)*").replace(work){match->
            dictionary[normalize(match.value)]?.let{matchCase(it,match.value)} ?: match.value
        }
        placeholders.forEachIndexed{i,value->work=work.replace("\uE000$i\uE001",value)}
        return work
    }

    private fun matchCase(value:String,model:String)=when{
        model.all{!it.isLetter() || it.isUpperCase()}->value.uppercase(Locale.getDefault())
        model.firstOrNull()?.isUpperCase()==true->value.replaceFirstChar{it.titlecase(Locale.getDefault())}
        else->value
    }
    private fun normalize(value:String)=Normalizer.normalize(value.lowercase(Locale.FRENCH).replace('’','\''),Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"),"").trim().replace(Regex("\\s+")," ")
}
