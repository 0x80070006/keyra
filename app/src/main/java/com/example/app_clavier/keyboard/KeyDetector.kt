package com.example.app_clavier.keyboard

import kotlin.math.max
import kotlin.math.min

/**
 * Trouve la touche visée, dans le repère 684 × 612.
 *  1. Un appui sur un bouton isolé (avec une marge de `slop`) le désigne.
 *  2. Dans la bande des rangées de frappe, **chaque point appartient à la touche la plus proche** :
 *     plus de zone morte entre les touches (constat R1 de la phase 0).
 *  3. Zones dynamiques (optionnelles, à la manière de SwiftKey) : `bias(touche)` dans [0, 1] permet à une
 *     touche probable de prendre au plus 25 % de la largeur de sa voisine. Désactivées dans les champs sensibles.
 * Une quarantaine de touches au plus : un parcours linéaire coûte moins qu'une grille et n'alloue rien.
 */
class KeyDetector(private val keys:List<KeyDef>,private val slop:Float=6f){
    private val typing=keys.filter{it.proximity}
    private val bandTop=(typing.minOfOrNull{it.y} ?: 0)-10f
    private val bandBottom=(typing.maxOfOrNull{it.bottom} ?: 0)+10f

    fun keyAt(x:Float,y:Float,bias:((KeyDef)->Float)?=null):KeyDef? {
        for(i in keys.indices.reversed()){
            val k=keys[i]
            if(!k.proximity && x>=k.x-slop && x<k.right+slop && y>=k.y-slop && y<k.bottom+slop)return k
        }
        if(typing.isEmpty() || y<bandTop || y>bandBottom)return null
        var best:KeyDef?=null;var bestScore=Float.MAX_VALUE
        for(k in typing){
            val dx=max(max(k.x-x,0f),x-k.right);val dy=max(max(k.y-y,0f),y-k.bottom)
            // Distance au bord de la touche (0 à l'intérieur), diminuée du bonus de probabilité :
            // la zone d'une touche s'étend d'au plus 25 % de sa taille au-delà de son bord.
            val boost=if(bias==null)0f else bias(k).coerceIn(0f,1f)*MAX_STEAL*min(k.w,k.h)
            val score=kotlin.math.sqrt(dx*dx+dy*dy)-boost
            if(score<bestScore){bestScore=score;best=k}
        }
        return best
    }
    private companion object { const val MAX_STEAL=0.25f }
}
