package com.example.app_clavier.security

import android.content.Context
import android.os.Process
import com.example.app_clavier.storage.EncryptedKv
import com.example.app_clavier.storage.KeyManager
import com.example.app_clavier.storage.Migration11to12

/**
 * Geste panique (fonction innovante n° 6) : efface instantanément et définitivement tout ce que Keyra a appris
 * ou conservé — mots, mots personnels, emoji, presse-papiers, choix incognito.
 * Méthode : crypto-shredding. La clé maître est détruite dans le Keystore, ce qui rend illisible toute copie
 * des fichiers ; la clé de données est effacée de la mémoire Rust ; les fichiers sont supprimés ; puis le processus
 * est arrêté pour purger le tas (le système relance le clavier). Les réglages d'apparence sont conservés.
 */
object Panic {
    fun wipe(c:Context,killProcess:Boolean=true){
        KeyManager.destroy(c)
        SnippetVault.destroy()
        EncryptedKv.forgetAll()
        Migration11to12.eraseLegacy(c)
        if(killProcess)Process.killProcess(Process.myPid())
    }
}
