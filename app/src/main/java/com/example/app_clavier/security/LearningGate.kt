package com.example.app_clavier.security

import android.content.ClipboardManager
import android.content.Context
import com.example.app_clavier.ClipboardHistory
import com.example.app_clavier.EmojiHistory
import com.example.app_clavier.UserLexicon
import com.example.app_clavier.engine.NextWords
import com.example.app_clavier.storage.Backup

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
    /**
     * Import d'un export (ADR-0028) : action explicite de l'utilisateur, mais mêmes filtres qu'à l'apprentissage —
     * un mot ou une paire qui ressemble à un secret n'est pas réécrit, ni un extrait marqué protégé.
     */
    fun restore(c:Context,payload:Backup.Payload):Boolean {
        val filtered=payload.stores.mapValues{(name,entries)->when(name){
            "words","personal","blocked"->entries.filterKeys{SecretDetector.isLearnable(it)}
            "bigrams"->entries.filterKeys{k->k.split('').let{it.size==2 && it.all(SecretDetector::isLearnable)}}
            "snippets"->entries.filterValues{!it.startsWith(Backup.PROTECTED_PREFIX)}
            else->entries
        }}
        return Backup.write(c,Backup.Payload(filtered))
    }
}
