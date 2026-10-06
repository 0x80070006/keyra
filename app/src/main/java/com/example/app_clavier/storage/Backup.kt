package com.example.app_clavier.storage

import android.content.Context
import com.example.app_clavier.core.KeyraCore
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * Export et import chiffrés (fonction innovante n° 10, sans synchronisation ; ADR-0028).
 * Le chiffrement (Argon2id puis XChaCha20-Poly1305) est fait par le cœur Rust ; ce fichier ne fait que
 * rassembler les magasins et les relire, avec un décodage borné.
 *
 * Contenu exporté : mots appris, mots personnels, paires de mots, suggestions masquées, emoji récents,
 * choix incognito et extraits de texte. **Pas le presse-papiers** : il est éphémère par conception.
 * Les extraits protégés par biométrie restent chiffrés par une clé du Keystore qui ne quitte pas le
 * téléphone : ils sont exclus de l'export (ils seraient illisibles ailleurs).
 */
object Backup {
    val STORES=listOf("words","personal","bigrams","blocked","emoji","incognito","snippets")
    private const val MAGIC=0x4B594442 // « KYDB »
    private const val MAX_ENTRIES=100_000
    private const val MAX_FIELD=64*1024

    /** Données d'un import, par magasin. */
    class Payload(val stores:Map<String,Map<String,String>>)

    enum class Result { OK, LOCKED, NOT_AN_EXPORT, WRONG_PASSWORD, BAD_FILE, ERROR }

    /** Sérialise les magasins ; null si le coffre est verrouillé. */
    fun collect(c:Context):Payload? {
        val out=LinkedHashMap<String,Map<String,String>>()
        for(name in STORES){
            var entries=EncryptedKv.of(c,name).entries() ?: return null
            if(name=="snippets")entries=entries.filterValues{!it.startsWith(PROTECTED_PREFIX)}
            out[name]=entries
        }
        return Payload(out)
    }

    fun encode(p:Payload):ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{d->
            d.writeInt(MAGIC);d.writeInt(p.stores.size)
            for((name,entries) in p.stores){
                write(d,name);d.writeInt(entries.size)
                for((k,v) in entries){write(d,k);write(d,v)}
            }
        }
        return bytes.toByteArray()
    }
    private fun write(d:DataOutputStream,s:String){val b=s.toByteArray(Charsets.UTF_8);d.writeInt(b.size);d.write(b)}

    /** Décodage strict : magasins connus seulement, tailles bornées. Null si le contenu est invalide. */
    fun decode(data:ByteArray):Payload? = try{
        val b=ByteBuffer.wrap(data)
        if(b.int!=MAGIC)null else {
            val count=b.int
            if(count !in 0..STORES.size)null else {
                val out=LinkedHashMap<String,Map<String,String>>()
                var total=0
                repeat(count){
                    val name=read(b)
                    if(name !in STORES || name in out)throw IllegalArgumentException()
                    val n=b.int
                    total+=n
                    if(n<0 || total>MAX_ENTRIES)throw IllegalArgumentException()
                    val entries=LinkedHashMap<String,String>()
                    repeat(n){entries[read(b)]=read(b)}
                    out[name]=entries
                }
                if(b.hasRemaining())null else Payload(out)
            }
        }
    }catch(_:RuntimeException){null}

    private fun read(b:ByteBuffer):String {
        val n=b.int
        if(n<0 || n>MAX_FIELD || n>b.remaining())throw IllegalArgumentException()
        val bytes=ByteArray(n);b.get(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun passwordBytes(password:CharArray):ByteArray {
        val encoded=Charsets.UTF_8.encode(CharBuffer.wrap(password))
        val bytes=ByteArray(encoded.remaining());encoded.get(bytes)
        if(encoded.hasArray())encoded.array().fill(0)
        return bytes
    }

    /** Fichier d'export, ou null (coffre verrouillé, erreur). Long : Argon2id 64 Mio, hors du fil principal. */
    fun export(c:Context,password:CharArray):ByteArray? {
        val payload=collect(c) ?: return null
        val salt=ByteArray(16).also{KeyManager.random.nextBytes(it)}
        val nonce=ByteArray(24).also{KeyManager.random.nextBytes(it)}
        return KeyraCore.sealBackup(passwordBytes(password),salt,nonce,encode(payload))
    }

    /** Déchiffre et décode un export ; l'écriture passe ensuite par LearningGate.restore. */
    fun open(password:CharArray,data:ByteArray):Pair<Result,Payload?> {
        val opened=KeyraCore.openBackup(passwordBytes(password),data)
        val result=when(opened.status){
            KeyraCore.BackupStatus.OK->Result.OK
            KeyraCore.BackupStatus.NOT_AN_EXPORT->Result.NOT_AN_EXPORT
            KeyraCore.BackupStatus.WRONG_PASSWORD->Result.WRONG_PASSWORD
            KeyraCore.BackupStatus.BAD_SIZE,KeyraCore.BackupStatus.BAD_PARAMS->Result.BAD_FILE
            else->Result.ERROR
        }
        val plain=opened.plaintext ?: return result to null
        try{return (decode(plain)?.let{Result.OK to it}) ?: (Result.BAD_FILE to null)}finally{plain.fill(0)}
    }

    /** Réservé à LearningGate : fusionne les entrées importées dans les magasins chiffrés. */
    fun write(c:Context,payload:Payload):Boolean {
        for((name,entries) in payload.stores){
            val store=EncryptedKv.of(c,name)
            val merged=LinkedHashMap(store.entries() ?: return false)
            merged.putAll(entries)
            if(!store.replaceAll(merged))return false
        }
        return true
    }

    const val PROTECTED_PREFIX="P\t"
}
