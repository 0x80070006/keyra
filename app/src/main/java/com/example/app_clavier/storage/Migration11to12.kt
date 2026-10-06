package com.example.app_clavier.storage

import android.annotation.SuppressLint
import android.content.Context
import com.example.app_clavier.KeyboardPrefs
import com.example.app_clavier.core.KeyraCore
import com.example.app_clavier.security.SecretDetector
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Migration des données en clair de Keyra 11 vers les magasins chiffrés, puis suppression des originaux.
 * Les copies de presse-papiers classées secrètes ne sont pas reprises. L'effacement sur mémoire flash n'est pas
 * garanti (copies physiques possibles) : c'est un risque résiduel documenté, d'où le crypto-shredding du geste panique.
 */
object Migration11to12 {
    private const val DONE="migrated_v12"

    // commit() voulu : l'état « migré » ne doit être écrit qu'une fois les données chiffrées sur disque.
    @SuppressLint("ApplySharedPref")
    fun run(c:Context):Boolean {
        val prefs=KeyboardPrefs.of(c)
        if(prefs.getBoolean(DONE,false))return true
        if(!KeyraCore.unlocked)return false
        runCatching{
            val learned=JSONObject(prefs.getString("learned_words","{}") ?: "{}")
            val words=learned.keys().asSequence().associateWith{learned.optInt(it,0).toString()}
            if(words.isNotEmpty())EncryptedKv.of(c,"words").replaceAll(words)
        }
        prefs.getString("personal",null)?.let{text->
            EncryptedKv.of(c,"personal").replaceAll(text.lineSequence().map{it.trim().lowercase(Locale.FRENCH)}.filter{it.isNotEmpty()}.associateWith{"1"})
        }
        runCatching{
            val array=JSONArray(c.getSharedPreferences("clipboard_history",Context.MODE_PRIVATE).getString("items","[]"))
            val base=System.currentTimeMillis()*1000
            val clips=(0 until array.length()).map{array.getString(it)}.filter{!SecretDetector.isSecret(it)}
                .mapIndexed{i,text->(base-i).toString().padStart(19,'0') to "0\t$text"}.toMap()
            if(clips.isNotEmpty())EncryptedKv.of(c,"clipboard").replaceAll(clips)
        }
        runCatching{
            val array=JSONArray(c.getSharedPreferences("emoji_history",Context.MODE_PRIVATE).getString("usage","[]"))
            val usage=(0 until array.length()).map{array.getJSONObject(it)}
                .associate{it.getString("emoji") to "${it.optInt("count",1)},${it.optLong("last",0L)}"}
            if(usage.isNotEmpty())EncryptedKv.of(c,"emoji").replaceAll(usage)
        }
        eraseLegacy(c)
        prefs.edit().putBoolean(DONE,true).commit()
        return true
    }

    /** Supprime les données en clair de Keyra 11 (aussi appelé par le geste panique). */
    // commit() voulu : les données en clair doivent avoir quitté le disque avant de rendre la main (geste panique).
    @SuppressLint("ApplySharedPref")
    fun eraseLegacy(c:Context){
        KeyboardPrefs.of(c).edit().remove("learned_words").remove("personal").commit()
        c.deleteSharedPreferences("clipboard_history")
        c.deleteSharedPreferences("emoji_history")
    }
}
