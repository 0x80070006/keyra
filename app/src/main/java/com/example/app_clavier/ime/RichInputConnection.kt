package com.example.app_clavier.ime

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection

/**
 * TextTarget Android, à la manière du RichInputConnection de LatinIME (constat R3 de la phase 0) :
 *  - le texte avant le curseur est gardé en cache local (MAX caractères) ; on ne relit le champ qu'au début
 *    de la saisie, ou quand le curseur a bougé sans nous ;
 *  - les positions attendues du curseur sont mémorisées : une notification `onUpdateSelection` en retard
 *    sur la frappe n'est pas prise pour un déplacement de l'utilisateur ;
 *  - les réponses de l'application cible (non fiable) sont bornées en taille.
 */
class RichInputConnection(private val service:InputMethodService):TextTarget {
    private val before=StringBuilder(MAX+64)
    private var composing:CharSequence=""
    private var valid=false
    /** Position absolue du curseur (début et fin de sélection), -1 si inconnue. */
    private var selStart=-1;private var selEnd=-1
    private val expected=IntArray(16);private var expectedCount=0
    private fun ic():InputConnection?=service.currentInputConnection

    /** Début d'un champ : relecture unique du texte avant le curseur. */
    fun reset(initialSelStart:Int,initialSelEnd:Int){
        composing="";selStart=initialSelStart;selEnd=initialSelEnd;expectedCount=0;valid=false
    }
    private fun ensureLoaded(){
        if(valid)return
        before.setLength(0)
        val text=runCatching{ic()?.getTextBeforeCursor(MAX,0)}.getOrNull()
        if(text!=null)before.append(if(text.length>MAX)text.subSequence(text.length-MAX,text.length) else text)
        valid=true
    }
    private fun trim(){if(before.length>MAX+32)before.delete(0,before.length-MAX)}
    private fun expect(position:Int){if(position<0)return;expected[expectedCount%expected.size]=position;expectedCount++}
    private fun cursorAfterComposing()=if(selStart<0)-1 else selStart

    /**
     * Notification du système. Renvoie vrai si le curseur a bougé sans nous (toucher dans le texte,
     * modification par l'application) : l'appelant oublie alors le mot en cours.
     */
    fun onUpdateSelection(newStart:Int,newEnd:Int,candidatesStart:Int,candidatesEnd:Int):Boolean {
        val ours=newStart==newEnd && (0 until minOf(expectedCount,expected.size)).any{expected[it]==newEnd}
        val composingConsistent=composing.isEmpty() || candidatesEnd==newEnd
        if(ours && composingConsistent){selStart=newStart-composing.length;selEnd=selStart;return false}
        selStart=newStart;selEnd=newEnd
        if(composing.isNotEmpty()){runCatching{ic()?.finishComposingText()};composing=""}
        expectedCount=0;valid=false
        return true
    }

    override fun textBefore():CharSequence{ensureLoaded();return before}
    override val hasSelection get()=selStart>=0 && selEnd>selStart

    override fun commit(text:CharSequence){
        finishComposing()
        ensureLoaded()
        ic()?.commitText(text,1)
        before.append(text);trim()
        if(selStart>=0){selStart+=text.length;selEnd=selStart;expect(selStart)}
    }

    override fun setComposing(text:CharSequence){
        ensureLoaded()
        ic()?.setComposingText(text,1)
        composing=text.toString()
        if(selStart>=0)expect(cursorAfterComposing()+composing.length)
        if(text.isEmpty() && selStart>=0)expect(selStart)
    }

    override fun finishComposing(){
        if(composing.isEmpty())return
        ensureLoaded()
        ic()?.finishComposingText()
        before.append(composing);trim()
        if(selStart>=0){selStart+=composing.length;selEnd=selStart;expect(selStart)}
        composing=""
    }

    override fun deleteBefore(count:Int){
        ensureLoaded()
        ic()?.deleteSurroundingTextInCodePoints(count,0)
        var cut=before.length
        repeat(count){if(cut>0)cut=Character.offsetByCodePoints(before,cut,-1)}
        val removed=before.length-cut
        before.setLength(cut)
        if(selStart>=0){selStart-=removed;selEnd=selStart;expect(selStart)}
    }

    override fun selectBefore(chars:Int){
        if(selEnd<0)return
        val start=(selEnd-chars).coerceAtLeast(0)
        ic()?.setSelection(start,selEnd)
        selStart=start;expect(selEnd)
    }

    override fun deleteSelection(){
        if(!hasSelection)return
        ic()?.commitText("",1)
        valid=false
        selEnd=selStart;expect(selStart)
    }

    override fun moveCursor(delta:Int){
        finishComposing()
        if(selEnd<0){valid=false;return}
        val target=(selEnd+delta).coerceAtLeast(0)
        ic()?.setSelection(target,target)
        selStart=target;selEnd=target;expect(target);valid=false
    }

    override fun batch(block:()->Unit){
        val connection=ic()
        connection?.beginBatchEdit()
        try{block()}finally{connection?.endBatchEdit()}
    }

    /** Une action extérieure (coller, traduire…) a changé le texte : relecture à la prochaine demande. */
    fun invalidate(){composing="";valid=false;expectedCount=0}

    companion object { const val MAX=1000 }
}
