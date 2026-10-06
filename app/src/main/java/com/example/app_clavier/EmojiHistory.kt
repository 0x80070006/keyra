package com.example.app_clavier

import android.content.Context
import com.example.app_clavier.storage.EncryptedKv

/** Emoji fréquents, chiffrés (magasin « emoji » : emoji → « nombre,dernier usage »). Écriture via LearningGate. */
object EmojiHistory {
    private const val MAX_ENTRIES=256
    private fun store(c:Context)=EncryptedKv.of(c,"emoji")
    private fun parse(value:String)=value.split(',').let{(it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toLongOrNull() ?: 0L)}

    fun top(c:Context,allowed:Set<String>?=null):List<String> = store(c).view{all->all.entries.asSequence()
        .filter{allowed==null || it.key in allowed}
        .sortedWith(compareByDescending<Map.Entry<String,String>>{parse(it.value).first}.thenByDescending{parse(it.value).second})
        .take(18).map{it.key}.toList()} ?: emptyList()

    /** Réservé à LearningGate. */
    fun record(c:Context,emoji:String){
        val store=store(c)
        val count=store.get(emoji)?.let{parse(it).first} ?: 0
        store.put(emoji,"${(count+1).coerceAtMost(1_000_000)},${System.currentTimeMillis()}")
        store.view{all->if(all.size>MAX_ENTRIES)all.entries.sortedBy{parse(it.value).second}.take(all.size-MAX_ENTRIES).map{it.key} else null}?.forEach{store.remove(it)}
    }

    fun clear(c:Context)=store(c).clear()
}
