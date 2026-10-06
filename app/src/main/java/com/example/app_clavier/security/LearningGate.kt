package com.example.app_clavier.security

import android.content.ClipboardManager
import android.content.Context
import com.example.app_clavier.ClipboardHistory
import com.example.app_clavier.EmojiHistory
import com.example.app_clavier.UserLexicon
import com.example.app_clavier.engine.NextWords

/**
 * Seul point d'entrée vers tout ce que Keyra conserve (ADR-0008). Ordre des vérifications :
 * SecurityPolicy (mot de passe, navigation privée, sans suggestions, incognito, appareil verrouillé),
 * puis SecretDetector. ArchitectureTest vérifie qu'aucun autre fichier n'appelle les écritures des magasins.
 */
object LearningGate {
    fun word(c:Context,policy:SecurityPolicy,word:String):Boolean {
        if(!policy.canLearn || !SecretDetector.isLearnable(word))return false
        return UserLexicon.record(c,word)
    }
    fun emoji(c:Context,allowed:Boolean,emoji:String){if(allowed)EmojiHistory.record(c,emoji)}
    /** Paire de mots consécutifs (prédiction du mot suivant) : mêmes règles qu'un mot, pour les deux mots. */
    fun pair(c:Context,policy:SecurityPolicy,previous:String,next:String){
        if(!policy.canLearn || !SecretDetector.isLearnable(previous) || !SecretDetector.isLearnable(next))return
        NextWords.record(c,previous,next)
    }
    fun clip(c:Context,policy:SecurityPolicy,manager:ClipboardManager):ClipboardHistory.Capture =
        if(policy.allowClipboardHistory)ClipboardHistory.capture(c,manager) else ClipboardHistory.Capture.IGNORED
    fun passwordField(c:Context,policy:SecurityPolicy,pkg:String?){if(policy.isPassword && !pkg.isNullOrEmpty())IncognitoApps.recordPasswordField(c,pkg)}
}
