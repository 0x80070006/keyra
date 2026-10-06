package com.example.app_clavier

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build

object KeyboardPrefs {
    fun of(c: Context) = c.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
    val themes = linkedMapOf("brown" to "Brun & bleu", "dynamic" to "Couleurs du téléphone", "auto" to "Système clair / sombre", "light" to "Clair", "dark" to "Ardoise", "mint" to "Menthe", "blue" to "Océan", "purple" to "Prune", "rose" to "Rose", "green" to "Forêt", "gradient" to "Dégradé", "amber" to "Ambre", "crimson" to "Pourpre", "lavender" to "Lavande", "teal" to "Lagon", "sand" to "Sable", "cobalt" to "Cobalt", "plum" to "Aubergine", "coral" to "Corail", "image" to "Image personnalisée", "custom" to "Mes deux couleurs")
    data class Palette(val background: Int, val key: Int, val special: Int, val text: Int, val specialText: Int, val gradient: Boolean = false)
    private fun color(v: String) = Color.parseColor(v)
    private fun blend(a: Int, b: Int, t: Float) = Color.rgb((Color.red(a)*(1-t)+Color.red(b)*t).toInt(),(Color.green(a)*(1-t)+Color.green(b)*t).toInt(),(Color.blue(a)*(1-t)+Color.blue(b)*t).toInt())
    fun ink(c: Int): Int = if(Color.red(c)*.299 + Color.green(c)*.587 + Color.blue(c)*.114 > 155) color("#172024") else color("#FFF4EE")
    fun palette(c: Context, name: String = of(c).getString("theme", "brown")!!): Palette {
        val dark = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if(name=="dynamic" && Build.VERSION.SDK_INT>=31) {
            val bg=c.getColor(if(dark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50)
            val normal=c.getColor(if(dark) android.R.color.system_accent1_800 else android.R.color.system_accent1_100)
            var special=c.getColor(if(dark) android.R.color.system_accent3_700 else android.R.color.system_accent3_200)
            if(special==normal)special=c.getColor(if(dark) android.R.color.system_accent3_500 else android.R.color.system_accent3_400)
            return Palette(bg,normal,special,ink(normal),ink(special))
        }
        if(name=="auto") return palette(c,if(dark) "dark" else "light")
        if(name=="brown") return Palette(color("#2B120D"),color("#472118"),color("#12292F"),color("#FFDED5"),color("#AEC7CD"))
        val pair = when(name) {
            "light" -> "#E8EFF2" to "#BCE6E2"
            "mint" -> "#2E3933" to "#9FEFC4"
            "blue" -> "#183C61" to "#75CADD"
            "purple" -> "#422C56" to "#DBB8F1"
            "rose" -> "#FFD7DE" to "#92C8D5"
            "green" -> "#214939" to "#BADD91"
            "gradient" -> "#524277" to "#C883AF"
            "amber" -> "#4D3214" to "#F2B84B"
            "crimson" -> "#511B2A" to "#ED6C86"
            "lavender" -> "#443B65" to "#C5B5F7"
            "teal" -> "#123E42" to "#72D6CA"
            "sand" -> "#5B4330" to "#F0C77B"
            "cobalt" -> "#1A315B" to "#82B9FF"
            "plum" -> "#442035" to "#E99CCA"
            "coral" -> "#5A302C" to "#FF9B83"
            "image" -> "#342520" to "#BFD1D4"
            "custom" -> of(c).getString("color1","#472118")!! to of(c).getString("color2","#12292F")!!
            else -> "#343D42" to "#86B8D9"
        }
        val a=runCatching {color(pair.first)}.getOrDefault(color("#472118"))
        val b=runCatching {color(pair.second)}.getOrDefault(color("#12292F"))
        return Palette(blend(a,if(ink(a)==color("#172024")) Color.WHITE else Color.BLACK,.32f),a,b,ink(a),ink(b),name=="gradient")
    }
}
