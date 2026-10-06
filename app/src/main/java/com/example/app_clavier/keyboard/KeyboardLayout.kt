package com.example.app_clavier.keyboard

/**
 * Une touche dans le repère de référence 684 × 612 de Keyra. Données pures : aucune vue Android.
 * `proximity` : la touche appartient aux rangées de frappe ; un appui dans un écart va à la plus proche
 * (KeyDetector). Les boutons isolés (barre d'outils) exigent un appui sur eux.
 */
class KeyDef(
    val code:String,
    val label:String,
    val x:Int,val y:Int,val w:Int,val h:Int,
    val special:Boolean=false,
    val round:Boolean=false,
    val hint:String?=null,
    val small:Boolean=false,
    val transparent:Boolean=false,
    val active:Boolean=false,
    val proximity:Boolean=false,
    val description:String=label,
    /** Choix de l'appui long : la touche elle-même d'abord, puis accents et chiffre. Vide : pas de choix. */
    val popup:List<String> = emptyList(),
    /** Texte en gras (suggestion que l'espace appliquera). */
    val bold:Boolean=false,
){
    val right get()=x+w
    val bottom get()=y+h
    val text get()=if(code.startsWith("char:"))code.removePrefix("char:") else code
    val printable get()=code.length==1 || code.startsWith("char:")
    val isModeKey get()=code=="symbols" || code=="moreSymbols" || code=="keypad" || code=="letters"
    override fun toString()="KeyDef($code)"
}

/** Une case du bandeau de suggestions. */
data class StripItem(val label:String,val code:String,val bold:Boolean=false)

/**
 * Bandeau à la SwiftKey : au centre la meilleure proposition (en gras si l'espace l'appliquera),
 * à gauche le mot tapé tel quel quand une correction est prévue, à droite la suivante (ou un emoji).
 */
data class Strip(val left:StripItem?=null,val center:StripItem?=null,val right:StripItem?=null){
    val isEmpty get()=left==null && center==null && right==null
    companion object { val EMPTY=Strip() }
}

/** Ce qui détermine les touches affichées en mode frappe (lettres, symboles, pavé numérique). */
data class LayoutState(
    val mode:Int, // 0 lettres, 1 symboles, 2 autres symboles, 4 pavé numérique
    val shifted:Boolean=false,
    val locked:Boolean=false,
    val searchAction:Boolean=false,
    val strip:Strip=Strip.EMPTY,
    val accents:String?=null,
    /** Ordre des 10 chiffres du pavé PIN mélangé, ou null pour l'ordre habituel. */
    val pinDigits:List<String>?=null,
    /** Navigation privée ou application incognito : un indicateur remplace l'icône des réglages. */
    val incognito:Boolean=false,
    /** Rangées de lettres (AZERTY, BÉPO…), lues depuis assets/layouts (LayoutParser). */
    val letters:LetterLayout=LetterLayout.AZERTY,
    /** Rangée de chiffres au-dessus des lettres (mode lettres seulement). */
    val numberRow:Boolean=false,
)

object KeyboardLayouts {
    val accentMap=mapOf("a" to "àâäæ","e" to "éèêë","i" to "îï","o" to "ôöœ","u" to "ùûü","c" to "ç","n" to "ñ","y" to "ÿ")
    private val xs=intArrayOf(8,76,143,211,279,347,414,482,550,618)
    private val row3=intArrayOf(110,177,245,312,380,448,516)
    private val rowY=intArrayOf(95,200,306)

    fun description(code:String,label:String,locked:Boolean)=when(code){
        " "->"Espace";"symbols"->"Chiffres et symboles";"emoji"->"Emoji"
        "shift"->if(locked)"Majuscules verrouillées" else "Majuscules"
        "delete"->"Effacer";"menu"->"Panneaux de fonctions";"moreSymbols"->"Deuxième page de symboles";"enter"->"Entrée"
        else->label
    }

    fun build(s:LayoutState):List<KeyDef> {
        val keys=ArrayList<KeyDef>(48)
        fun key(label:String,code:String,x:Int,y:Int,w:Int,h:Int,special:Boolean=false,round:Boolean=false,hint:String?=null,
                small:Boolean=false,transparent:Boolean=false,proximity:Boolean=true,popup:List<String> = emptyList()){
            keys+=KeyDef(code,label,x,y,w,h,special,round,hint,small,transparent,code=="shift" && s.shifted,proximity,description(code,label,s.locked),popup)
        }
        // Barre d'outils : boutons isolés, pas de proximité.
        key("menu","menu",8,15,58,62,special=true,round=true,proximity=false)
        when {
            s.mode==0 && s.accents!=null -> s.accents.forEachIndexed{i,ch->key(ch.toString(),"char:$ch",80+i*72,15,64,62,proximity=false)}
            s.mode==0 && !s.strip.isEmpty -> listOf(s.strip.left,s.strip.center,s.strip.right).forEachIndexed{i,item->
                if(item!=null)keys+=KeyDef(item.code,item.label,80+i*176,15,166,62,small=true,description=item.label,bold=item.bold)
            }
            else -> {
                key("clipboard","panel:clipboard",159,15,58,62,transparent=true,proximity=false)
                key("accents","accents",310,15,58,62,small=true,transparent=true,proximity=false)
                if(s.incognito)keys+=KeyDef("settings","incognito",461,15,58,62,transparent=true,description="Navigation privée : rien n’est appris")
                else key("settings","settings",461,15,58,62,transparent=true,proximity=false)
            }
        }
        key("next","menu",618,15,58,62,special=true,round=true,proximity=false)
        if(s.mode==4){numpad(s,::key);return keys}
        // Géométrie : rangées de 85 sans rangée de chiffres ; avec elle, une rangée de 64 et des rangées de 82.
        val digitsRow=s.mode==0 && s.numberRow
        val rowY=if(digitsRow)intArrayOf(164,256,348) else intArrayOf(95,200,306)
        val keyH=if(digitsRow)82 else 85
        val bottomY=if(digitsRow)440 else 412
        if(digitsRow)for(i in 0..9){val d=((i+1)%10).toString();key(d,d,xs[i],92,58,64)}
        val rows=when(s.mode){
            1->listOf("1234567890","@#€_&-+()/","*\"':;!?").map{r->r.map{it.toString()}}
            2->listOf("~`|•√π÷×¶∆","£¢$¥^°={}\\","%©®™✓[]").map{r->r.map{it.toString()}}
            else->s.letters.rows
        }
        for(r in 0..2){
            val row=rows[r];val n=row.size
            row.forEachIndexed{i,base->
                val hint=if(r==0 && s.mode==0 && !digitsRow && i<10)((i+1)%10).toString() else null
                val popup=if(s.mode!=0)emptyList() else buildList{add(base);accentMap[base]?.forEach{add(it.toString())};hint?.let{add(it)}}.distinct().takeIf{it.size>1}.orEmpty()
                val (x,w)=when{
                    r<2 && n==10->xs[i] to 58
                    r==2 && n==7->row3[i] to (if(i in 3..4)59 else 58)
                    r<2->{val pitch=minOf(67.8f,678f/n);(8+(678f-pitch*n)/2+i*pitch).toInt() to (pitch-9.8f).toInt()}
                    else->{val pitch=minOf(67.8f,474f/n);(110+(474f-pitch*n)/2+i*pitch).toInt() to (pitch-9.8f).toInt()}
                }
                key(if(s.mode==0 && s.shifted)base.uppercase() else base,base,x,rowY[r],w,keyH,hint=hint,popup=popup)
            }
        }
        key(if(s.mode==0)"shift" else if(s.mode==1)"=\\<" else "?123",if(s.mode==0)"shift" else "moreSymbols",8,rowY[2],91,keyH,special=true,small=s.mode!=0)
        key("delete","delete",584,rowY[2],91,keyH,special=true)
        key(if(s.mode==1 || s.mode==2)"ABC" else "?123","symbols",8,bottomY,91,85,special=true,round=true,small=true)
        val slash=s.mode==0 && !s.searchAction
        key(if(slash)"/" else ",",if(slash)"/" else ",",110,bottomY,58,85,special=true)
        key(if(s.mode==0)"smile" else "123\n456\n789",if(s.mode==0)"emoji" else "keypad",177,bottomY,58,85,small=s.mode!=0)
        key(""," ",245,bottomY,261,86,small=true)
        key(".",".",517,bottomY,57,85,special=true)
        key(if(s.searchAction)"search" else "enter","enter",585,bottomY,90,85,special=true,round=true)
        if(s.mode==0)key("à é ç  ·  accents","accents",220,bottomY+105,244,36,small=true,transparent=true,proximity=false)
        return keys
    }

    private fun numpad(s:LayoutState,key:(String,String,Int,Int,Int,Int,Boolean,Boolean,String?,Boolean,Boolean,Boolean,List<String>)->Unit){
        val ys=intArrayOf(95,200,306,412)
        val digits=s.pinDigits ?: listOf("1","2","3","4","5","6","7","8","9","0")
        val center=listOf(digits.subList(0,3),digits.subList(3,6),digits.subList(6,9),listOf(digits[9],"=","."))
        ys.forEachIndexed{row,y->
            val left=listOf("+","-","*","ABC")[row]
            key(left,if(left=="ABC")"letters" else left,8,y,91,85,true,false,null,left=="ABC",false,true,emptyList())
            center[row].forEachIndexed{col,value->key(value,value,110+col*131,y,120,85,false,false,null,false,false,true,emptyList())}
            val right=listOf("%"," ","delete","enter")[row]
            val label=if(right==" ")"Espace" else if(right=="enter" && s.searchAction)"search" else right
            key(label,right,503,y,172,85,true,false,null,right==" ",false,true,emptyList())
        }
        key("?123","symbols",584,505,91,45,true,true,null,true,false,false,emptyList())
    }
}
