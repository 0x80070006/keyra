package com.example.app_clavier.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Façade JNI du cœur Rust (rust/keyra-jni). Contrat : rust/keyra-jni/src/lib.rs.
 * Le texte est passé en UTF-8 dans un ByteArray, effacé après l'appel ; toute erreur
 * renvoie ERROR (ou null) et l'appelant doit alors « échouer fermé ».
 */
internal object KeyraCore {
    const val ERROR=-1
    private const val ABI_VERSION=3
    /** Faux si la bibliothèque native manque ou ne correspond pas à ce code Kotlin. */
    val available:Boolean by lazy {
        runCatching{System.loadLibrary("keyra_jni");abiVersion()==ABI_VERSION}.getOrDefault(false)
    }
    @JvmStatic private external fun abiVersion():Int
    @JvmStatic private external fun classify(utf8:ByteArray):Int
    @JvmStatic private external fun vaultUnlock(key:ByteArray):Int
    @JvmStatic private external fun vaultLock()
    @JvmStatic private external fun vaultIsUnlocked():Boolean
    @JvmStatic private external fun vaultSealFrame(context:ByteArray,nonce:ByteArray,plaintext:ByteArray):ByteArray?
    @JvmStatic private external fun vaultReadJournal(context:ByteArray,data:ByteArray):ByteArray?
    @JvmStatic private external fun engineLoad(dictionary:ByteArray):Int
    @JvmStatic private external fun engineAnalyze(word:ByteArray,tolerance:Int,limit:Int):ByteArray?
    @JvmStatic private external fun engineContains(word:ByteArray):Boolean
    @JvmStatic private external fun engineNextLetters(prefix:ByteArray):ByteArray?

    // ------------------------------------------------------------ moteur de prédiction (phase 5)
    fun loadEngine(dictionary:ByteArray):Int = if(!available)ERROR else runCatching{engineLoad(dictionary)}.getOrDefault(ERROR)
    private inline fun <T> withWord(word:String,block:(ByteArray)->T):T? {
        if(!available)return null
        val bytes=word.toByteArray(Charsets.UTF_8)
        return try{block(bytes)}catch(_:Throwable){null}finally{bytes.fill(0)}
    }
    /** Correction (première ligne, vide si aucune) puis suggestions « mot<tab>complétion », ou null. */
    fun analyzeWord(word:String,tolerance:Int,limit:Int):String? =
        withWord(word){bytes->engineAnalyze(bytes,tolerance,limit)?.let{out->try{out.toString(Charsets.UTF_8)}finally{out.fill(0)}}}
    fun knowsWord(word:String)=withWord(word){engineContains(it)} ?: false
    fun nextLetters(prefix:String)=withWord(prefix){engineNextLetters(it)}

    /** Code de keyra_core::secret::Sensitivity, ou ERROR. */
    fun classify(text:CharSequence):Int {
        if(!available)return ERROR
        val bytes=text.toString().toByteArray(Charsets.UTF_8)
        return try{classify(bytes)}catch(_:Throwable){ERROR}finally{bytes.fill(0)}
    }

    // ------------------------------------------------------------ coffre (ADR-0024)
    /** Charge la clé de données dans la mémoire Rust. La copie Kotlin est effacée par l'appelant. */
    fun unlock(key:ByteArray)=available && runCatching{vaultUnlock(key)==0}.getOrDefault(false)
    fun lock(){if(available)runCatching{vaultLock()}}
    val unlocked get()=available && runCatching{vaultIsUnlocked()}.getOrDefault(false)

    /** Trame de journal scellée, ou null (coffre verrouillé, erreur). `plaintext` est effacé. */
    fun sealFrame(context:String,nonce:ByteArray,plaintext:ByteArray):ByteArray? {
        if(!available){plaintext.fill(0);return null}
        return try{vaultSealFrame(context.toByteArray(Charsets.UTF_8),nonce,plaintext)}catch(_:Throwable){null}finally{plaintext.fill(0)}
    }

    enum class JournalEnd { COMPLETE, TRUNCATED_TAIL, CORRUPTED }
    class Journal(val records:List<ByteArray>,val end:JournalEnd)

    /** Lit un journal scellé ; null si le coffre est verrouillé ou en cas d'erreur. */
    fun readJournal(context:String,data:ByteArray):Journal? {
        if(!available)return null
        val encoded=try{vaultReadJournal(context.toByteArray(Charsets.UTF_8),data)}catch(_:Throwable){null} ?: return null
        try{
            val buffer=ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
            val end=JournalEnd.entries.getOrElse(buffer.get().toInt()){JournalEnd.CORRUPTED}
            val count=buffer.int
            val records=ArrayList<ByteArray>(count)
            repeat(count){val record=ByteArray(buffer.int);buffer.get(record);records.add(record)}
            return Journal(records,end)
        }catch(_:RuntimeException){return null}finally{encoded.fill(0)}
    }
}
