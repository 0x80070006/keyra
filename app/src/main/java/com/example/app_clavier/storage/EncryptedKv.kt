package com.example.app_clavier.storage

import android.content.Context
import android.util.AtomicFile
import com.example.app_clavier.core.KeyraCore
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Magasin clé → valeur chiffré (ADR-0005, ADR-0024). Rien n'est jamais écrit en clair.
 *  - `<nom>.snap` : instantané complet, écrit de façon atomique (AtomicFile) ;
 *  - `<nom>.jnl`  : journal en ajout seul des modifications depuis l'instantané, compacté au-delà de 256 Kio.
 * Chaque enregistrement est scellé par le cœur Rust avec un contexte qui nomme le magasin et le fichier :
 * on ne peut pas substituer un fichier à un autre. Une fin de journal tronquée (arrêt brutal) est ignorée.
 * Coffre verrouillé (appareil verrouillé, écran éteint) : lecture vide, écritures refusées.
 */
class EncryptedKv private constructor(private val dir:File,val name:String){
    enum class Issue { NONE, TRUNCATED_TAIL, CORRUPTED, LOCKED }
    private val snapshot=AtomicFile(File(dir,"$name.snap"))
    private val journal=File(dir,"$name.jnl")
    private val snapContext="keyra/v1/$name/snapshot"
    private val journalContext="keyra/v1/$name/journal"
    private var cache:LinkedHashMap<String,String>?=null
    /** Résultat du dernier chargement (tests, tableau de transparence). */
    @Volatile var lastIssue=Issue.NONE;private set

    /** Copie de toutes les entrées, ou null si le coffre est verrouillé. */
    @Synchronized fun entries():Map<String,String>? = load()?.let{LinkedHashMap(it)}
    @Synchronized fun get(key:String):String? = load()?.get(key)
    /** Lecture sans copie (suggestions à chaque frappe). Ne pas garder de référence à la table. */
    @Synchronized fun <R> view(block:(Map<String,String>)->R):R? = load()?.let(block)
    @Synchronized fun put(key:String,value:String):Boolean {
        val map=load() ?: return false
        if(map[key]==value)return true
        if(!append(encode(OP_SET,key,value)))return false
        map.remove(key);map[key]=value;compactIfNeeded();return true
    }
    @Synchronized fun remove(key:String):Boolean {
        val map=load() ?: return false
        if(!map.containsKey(key))return true
        if(!append(encode(OP_REMOVE,key,"")))return false
        map.remove(key);compactIfNeeded();return true
    }
    @Synchronized fun clear():Boolean {
        val map=load() ?: return false
        if(!writeSnapshot(emptyMap()))return false
        journal.delete();map.clear();return true
    }
    /** Remplace tout le contenu en une écriture atomique (migration, édition des mots personnels). */
    @Synchronized fun replaceAll(values:Map<String,String>):Boolean {
        val map=load() ?: return false
        if(!writeSnapshot(values))return false
        journal.delete();map.clear();map.putAll(values);return true
    }
    @Synchronized fun dropCache(){cache=null}

    private fun load():LinkedHashMap<String,String>? {
        cache?.let{return it}
        if(!KeyraCore.unlocked){lastIssue=Issue.LOCKED;return null}
        val map=LinkedHashMap<String,String>()
        var issue=Issue.NONE
        if(snapshot.baseFile.exists()){
            val read=KeyraCore.readJournal(snapContext,snapshot.readFully()) ?: return null
            read.records.forEach{apply(map,it)}
            if(read.end!=KeyraCore.JournalEnd.COMPLETE)issue=Issue.CORRUPTED
        }
        if(journal.exists()){
            val read=KeyraCore.readJournal(journalContext,journal.readBytes()) ?: return null
            read.records.forEach{apply(map,it)}
            when(read.end){
                KeyraCore.JournalEnd.TRUNCATED_TAIL->if(issue==Issue.NONE)issue=Issue.TRUNCATED_TAIL
                KeyraCore.JournalEnd.CORRUPTED->issue=Issue.CORRUPTED
                KeyraCore.JournalEnd.COMPLETE->{}
            }
            // Fin tronquée ou altérée : on réécrit un instantané propre pour repartir d'un état sain.
            if(read.end!=KeyraCore.JournalEnd.COMPLETE && writeSnapshot(map))journal.delete()
        }
        lastIssue=issue
        cache=map
        return map
    }

    private fun append(record:ByteArray):Boolean {
        val frame=KeyraCore.sealFrame(journalContext,nonce(),record) ?: return false
        dir.mkdirs()
        return runCatching{FileOutputStream(journal,true).use{it.write(frame)};true}.getOrDefault(false)
    }

    private fun compactIfNeeded(){
        val map=cache ?: return
        if(journal.length()>COMPACT_AT && writeSnapshot(map))journal.delete()
    }

    private fun writeSnapshot(values:Map<String,String>):Boolean {
        val bytes=ByteArrayOutputStream()
        for((k,v) in values){
            val frame=KeyraCore.sealFrame(snapContext,nonce(),encode(OP_SET,k,v)) ?: return false
            bytes.write(frame)
        }
        dir.mkdirs()
        val out=snapshot.startWrite()
        return try{out.write(bytes.toByteArray());snapshot.finishWrite(out);true}catch(_:Exception){snapshot.failWrite(out);false}
    }

    private fun nonce()=ByteArray(24).also{KeyManager.random.nextBytes(it)}

    private fun encode(op:Int,key:String,value:String):ByteArray {
        val out=ByteArrayOutputStream()
        DataOutputStream(out).use{data->
            val k=key.toByteArray(Charsets.UTF_8);val v=value.toByteArray(Charsets.UTF_8)
            data.writeByte(op);data.writeInt(k.size);data.write(k);data.writeInt(v.size);data.write(v)
        }
        return out.toByteArray()
    }

    private fun apply(map:LinkedHashMap<String,String>,record:ByteArray){
        try{
            val buffer=ByteBuffer.wrap(record)
            val op=buffer.get().toInt()
            val k=ByteArray(buffer.int).also{buffer.get(it)}.toString(Charsets.UTF_8)
            val v=ByteArray(buffer.int).also{buffer.get(it)}.toString(Charsets.UTF_8)
            when(op){OP_SET->{map.remove(k);map[k]=v};OP_REMOVE->map.remove(k)}
        }catch(_:RuntimeException){lastIssue=Issue.CORRUPTED}
        finally{record.fill(0)}
    }

    companion object {
        private const val OP_SET=1
        private const val OP_REMOVE=2
        private const val COMPACT_AT=256*1024L
        private val stores=HashMap<String,EncryptedKv>()
        @Synchronized fun of(c:Context,name:String)=stores.getOrPut(name){EncryptedKv(KeyManager.vaultDir(c.applicationContext),name)}
        /** Coffre verrouillé : on oublie aussi les données déchiffrées gardées en mémoire. */
        @Synchronized fun clearCaches(){stores.values.forEach{it.dropCache()}}
        /** Tests et geste panique : oublie aussi les instances (le dossier peut avoir changé). */
        @Synchronized fun forgetAll(){clearCaches();stores.clear()}
    }
}
