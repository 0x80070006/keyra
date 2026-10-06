package com.example.app_clavier.storage

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import com.example.app_clavier.core.KeyraCore
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement par enveloppe (ADR-0006, ADR-0024).
 *  - Clé maître (KEK) : AES-256-GCM dans Android Keystore, StrongBox si disponible,
 *    inutilisable tant que l'appareil est verrouillé (setUnlockedDeviceRequired).
 *  - Clé de données (DEK) : 32 octets aléatoires, enveloppés par la KEK dans vault/keys.bin,
 *    désenveloppés une fois par déverrouillage puis confiés au cœur Rust (la copie Kotlin est effacée).
 * Aucune opération Keystore pendant la frappe : tout passe par `executor`.
 */
object KeyManager {
    private const val ALIAS="keyra_kek_v1"
    private const val VERSION:Byte=1
    private val KEK_AAD="keyra/kek/v1".toByteArray()
    val random=SecureRandom()
    /** Fil unique pour le Keystore et les entrées-sorties du coffre. */
    val executor=Executors.newSingleThreadExecutor{Thread(it,"keyra-storage").apply{isDaemon=true}}

    fun vaultDir(c:Context)=File(c.noBackupFilesDir,"vault").also{it.mkdirs()}
    private fun keyFile(c:Context)=AtomicFile(File(vaultDir(c),"keys.bin"))
    private fun keyStore()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}

    /** Déverrouille le coffre. Faux si l'appareil est verrouillé ou si le Keystore refuse ; ne bloque jamais la frappe. */
    @Synchronized fun unlock(c:Context):Boolean {
        if(KeyraCore.unlocked)return true
        if(!KeyraCore.available)return false
        if(c.getSystemService(KeyguardManager::class.java)?.isDeviceLocked!=false)return false
        return try{
            val file=keyFile(c)
            val store=keyStore()
            val existing=store.getKey(ALIAS,null) as? SecretKey
            val dek=if(existing!=null && file.baseFile.exists()){
                try{unwrap(existing,file.readFully())}
                catch(_:AEADBadTagException){resetAll(c);null}
            }else{if(file.baseFile.exists())resetAll(c);null} ?: createDek(c)
            try{KeyraCore.unlock(dek)}finally{dek.fill(0)}
        }catch(_:Exception){false}
    }

    fun unlockAsync(c:Context,then:(Boolean)->Unit={}){val app=c.applicationContext;executor.execute{then(unlock(app))}}

    /** Efface la clé de données de la mémoire (écran éteint, destruction du service). */
    fun lock(){KeyraCore.lock();EncryptedKv.clearCaches()}

    /** Crypto-shredding : la KEK détruite, plus rien ne se relit, même si des copies des fichiers subsistent. */
    @Synchronized fun destroy(c:Context){
        lock()
        runCatching{keyStore().deleteEntry(ALIAS)}
        vaultDir(c).deleteRecursively()
    }

    /** Niveau de protection de la clé maître, pour la transparence et les tests. */
    fun securityLevel():String = runCatching{
        val key=keyStore().getKey(ALIAS,null) as? SecretKey ?: return "absente"
        val info=SecretKeyFactory.getInstance(key.algorithm,"AndroidKeyStore").getKeySpec(key,KeyInfo::class.java) as KeyInfo
        if(Build.VERSION.SDK_INT>=31)when(info.securityLevel){
            KeyProperties.SECURITY_LEVEL_STRONGBOX->"StrongBox";KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT->"TEE";else->"logicielle"
        } else @Suppress("DEPRECATION") if(info.isInsideSecureHardware)"matérielle" else "logicielle"
    }.getOrDefault("inconnue")

    /** Données illisibles (clé maître perdue) : on repart de zéro plutôt que de planter le clavier. */
    private fun resetAll(c:Context){
        runCatching{keyStore().deleteEntry(ALIAS)}
        vaultDir(c).listFiles()?.forEach{it.delete()}
        EncryptedKv.clearCaches()
    }

    private fun createDek(c:Context):ByteArray {
        val kek=generateKek()
        val dek=ByteArray(32).also{random.nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,kek);updateAAD(KEK_AAD)}
        val wrapped=cipher.doFinal(dek)
        val iv=cipher.iv
        val file=keyFile(c)
        val out=file.startWrite()
        try{out.write(byteArrayOf(VERSION,iv.size.toByte()));out.write(iv);out.write(wrapped);file.finishWrite(out)}
        catch(e:Exception){file.failWrite(out);dek.fill(0);throw e}
        return dek
    }

    private fun unwrap(kek:SecretKey,data:ByteArray):ByteArray {
        if(data.size<2 || data[0]!=VERSION)throw AEADBadTagException()
        val ivLength=data[1].toInt()
        if(ivLength !in 12..16 || data.size<2+ivLength+16)throw AEADBadTagException()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,kek,GCMParameterSpec(128,data,2,ivLength))
        cipher.updateAAD(KEK_AAD)
        return cipher.doFinal(data,2+ivLength,data.size-2-ivLength)
    }

    private fun generateKek():SecretKey {
        fun spec(strongBox:Boolean)=KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        return try{generator.init(spec(true));generator.generateKey()}
        catch(_:StrongBoxUnavailableException){generator.init(spec(false));generator.generateKey()}
    }
}
