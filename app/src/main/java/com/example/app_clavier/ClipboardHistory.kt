package com.example.app_clavier

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.example.app_clavier.security.SecretDetector
import com.example.app_clavier.storage.EncryptedKv

/**
 * Historique éphémère du presse-papiers (fonction innovante n° 3), chiffré (magasin « clipboard »).
 *  - une copie sensible (EXTRA_IS_SENSITIVE ou détectée par SecretDetector) n'est jamais écrite ;
 *  - les copies expirent après `clip_ttl_min` minutes (60 par défaut ; 0 = jamais), sauf si elles sont épinglées ;
 *  - 20 copies non épinglées au plus.
 * Écriture via LearningGate seulement.
 */
object ClipboardHistory {
    private const val MAX_ITEMS=20
    private const val MAX_PINNED=30
    private const val MAX_CHARS=10_000
    enum class Capture { STORED, SENSITIVE, IGNORED }
    class Clip(val id:String,val text:String,val pinned:Boolean,val time:Long)

    private fun store(c:Context)=EncryptedKv.of(c,"clipboard")
    private var lastId=0L
    @Synchronized private fun nextId():String {val now=System.currentTimeMillis();lastId=maxOf(now*1000,lastId+1);return lastId.toString().padStart(19,'0')}
    private fun ttlMillis(c:Context)=KeyboardPrefs.of(c).getInt("clip_ttl_min",60).toLong()*60_000L

    /** Copies non expirées, la plus récente d'abord ; les copies expirées sont supprimées au passage. */
    fun entries(c:Context):List<Clip> {
        val store=store(c);val ttl=ttlMillis(c);val now=System.currentTimeMillis()
        val all=store.view{map->map.entries.map{(id,v)->Clip(id,v.substringAfter('\t'),v.startsWith("1\t"),(id.toLongOrNull() ?: 0L)/1000)}} ?: return emptyList()
        val (alive,expired)=all.partition{it.pinned || ttl<=0 || now-it.time<ttl}
        expired.forEach{store.remove(it.id)}
        return alive.sortedByDescending{it.id}
    }
    fun items(c:Context)=entries(c).map{it.text}

    /** Réservé à LearningGate. */
    fun capture(c:Context,manager:ClipboardManager):Capture {
        val description=manager.primaryClipDescription ?: return Capture.IGNORED
        val clip=manager.primaryClip ?: return Capture.IGNORED
        if(clip.itemCount<1)return Capture.IGNORED
        val value=clip.getItemAt(0).text?.toString()?.take(MAX_CHARS)?.takeIf{it.isNotBlank()} ?: return Capture.IGNORED
        val flagged=Build.VERSION.SDK_INT>=33 && description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE,false)==true
        // Carte, IBAN, code, clé d'API, mot de passe probable : jamais écrit sur disque (échec fermé si le cœur Rust échoue).
        if(flagged || SecretDetector.isSecret(value))return Capture.SENSITIVE
        val store=store(c)
        val current=entries(c)
        current.filter{it.text==value}.forEach{store.remove(it.id)}
        if(!store.put(nextId(),"0\t$value"))return Capture.IGNORED
        current.filter{!it.pinned && it.text!=value}.drop(MAX_ITEMS-1).forEach{store.remove(it.id)}
        return Capture.STORED
    }

    fun togglePin(c:Context,index:Int){
        val clip=entries(c).getOrNull(index) ?: return
        if(!clip.pinned && entries(c).count{it.pinned}>=MAX_PINNED)return
        store(c).put(clip.id,"${if(clip.pinned)0 else 1}\t${clip.text}")
    }
    fun delete(c:Context,index:Int){entries(c).getOrNull(index)?.let{store(c).remove(it.id)}}
    fun clear(c:Context)=store(c).clear()
}
