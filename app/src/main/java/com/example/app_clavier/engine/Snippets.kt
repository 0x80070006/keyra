package com.example.app_clavier.engine

import android.content.Context
import com.example.app_clavier.security.SnippetVault
import com.example.app_clavier.storage.Backup
import com.example.app_clavier.storage.EncryptedKv
import java.util.Locale

/**
 * Extraits de texte et expansion (fonction innovante n° 7), chiffrés dans le magasin « snippets ».
 * Un raccourci tapé (« adr ») propose son extrait dans le bandeau. Valeur : `T\t<texte>`, ou `P\t<chiffré>` pour un
 * extrait protégé, chiffré une seconde fois par une clé du Keystore qui exige la biométrie ou le code (SnippetVault).
 * Indisponibles quand l'appareil est verrouillé (le coffre est alors fermé) et dans les champs mot de passe.
 */
object Snippets {
    class Snippet(val shortcut:String,val protected:Boolean,private val value:String){
        /** Texte en clair d'un extrait non protégé ; null pour un extrait protégé. */
        val plain get()=if(protected)null else value
        internal val sealed get()=value
    }
    private const val PLAIN="T\t"
    private const val MAX_TEXT=4_000
    private fun store(c:Context)=EncryptedKv.of(c,"snippets")
    fun key(shortcut:String)=shortcut.trim().lowercase(Locale.FRENCH)
    fun validShortcut(shortcut:String)=key(shortcut).let{it.length in 2..16 && it.all{ch->ch.isLetterOrDigit()}}

    private fun parse(k:String,v:String)=when{
        v.startsWith(Backup.PROTECTED_PREFIX)->Snippet(k,true,v.removePrefix(Backup.PROTECTED_PREFIX))
        v.startsWith(PLAIN)->Snippet(k,false,v.removePrefix(PLAIN))
        else->null
    }
    fun all(c:Context):List<Snippet>? = store(c).entries()?.mapNotNull{(k,v)->parse(k,v)}?.sortedBy{it.shortcut}
    fun find(c:Context,word:String):Snippet? {
        val k=key(word)
        if(k.length<2)return null
        return store(c).view{it[k]}?.let{parse(k,it)}
    }
    /** Enregistre un extrait. Un extrait protégé exige une authentification récente (moins de 30 s). */
    fun put(c:Context,shortcut:String,text:String,protected:Boolean):Boolean {
        if(!validShortcut(shortcut) || text.isEmpty() || text.length>MAX_TEXT)return false
        val value=if(protected)Backup.PROTECTED_PREFIX+(SnippetVault.seal(text) ?: return false) else PLAIN+text
        return store(c).put(key(shortcut),value)
    }
    fun remove(c:Context,shortcut:String)=store(c).remove(key(shortcut))
    /** Texte d'un extrait protégé ; null si l'authentification n'est pas récente (voir UnlockActivity). */
    fun open(s:Snippet):String? = if(s.protected)SnippetVault.open(s.sealed) else s.plain
    fun clear(c:Context)=store(c).clear()
}
