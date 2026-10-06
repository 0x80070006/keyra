package com.example.app_clavier.ime

import com.example.app_clavier.security.SecurityPolicy
import java.util.Locale

/**
 * Logique de saisie (à la manière de LatinIME) : mot en cours en composition, correction synchrone à l'espace,
 * annulation par Retour arrière avec apprentissage du mot restauré, typographie française, double espace,
 * majuscule automatique, effacement par mot. Classe pure, testée sur la JVM (InputLogicTest).
 */
class InputLogic(private val target:TextTarget,private val host:Host){
    interface Host {
        val settings:ImeSettings
        val policy:SecurityPolicy
        /** Correction de `word`, calculée de façon synchrone mais bornée dans le temps ; null : aucune. */
        fun correctionFor(word:String):String?
        fun isKnown(word:String):Boolean
        fun isPersonal(word:String):Boolean
        /** Passe par LearningGate. */
        fun learn(word:String)
        /** Paire de mots consécutifs, via LearningGate. */
        fun learnPair(previous:String,word:String)
        fun now():Long
    }
    private class Undo(val original:String,val corrected:String,val delimiter:String)

    private val composing=StringBuilder()
    private var undo:Undo?=null
    /** Mots restaurés par l'utilisateur : plus corrigés pendant cette session de champ. */
    private val rejected=HashSet<String>()
    private var lastSpaceAt=0L
    var lastCompletedWord="";private set

    private val useComposing get()=host.settings.composing && host.policy.canSuggest
    private val typography get()=host.policy.isText && !host.policy.isAddress && !host.policy.isPassword

    fun startInput(){composing.clear();undo=null;rejected.clear();lastCompletedWord="";lastSpaceAt=0L}

    /** Mot à compléter : la composition, ou le mot qui précède le curseur. */
    fun currentWord():String = if(composing.isNotEmpty())composing.toString() else trailingWord(target.textBefore())

    /** Caractères ordinaires (lettres, chiffres, symboles). */
    fun onText(text:String){
        undo=null
        if(useComposing && text.all(::isWordChar) && !(composing.isEmpty() && text.all{it=='-' || it=='\'' || it=='’'})){
            composing.append(text);target.setComposing(composing);return
        }
        target.batch{
            finishComposing()
            when{
                typography && text=="«" -> target.commit("«"+(host.settings.frenchSpace ?: ""))
                typography && text=="»" -> {spaceBeforeHighPunctuation();target.commit("»")}
                else -> target.commit(text)
            }
        }
    }

    /** Espace, ponctuation, retour à la ligne : le mot en cours est validé (et corrigé) d'abord. */
    fun onDelimiter(d:String){
        val now=host.now()
        val word=composing.toString().ifEmpty{if(useComposing)"" else trailingWord(target.textBefore())}
        val settings=host.settings
        // Double espace : « mot  » → « mot. » (fenêtre de 600 ms, comme LatinIME).
        if(d==" " && word.isEmpty() && settings.doubleSpacePeriod && now-lastSpaceAt<DOUBLE_SPACE_MS){
            val before=target.textBefore()
            if(before.length>=2 && before[before.length-1]==' ' && before[before.length-2].isLetterOrDigit()){
                target.batch{target.deleteBefore(1);target.commit(". ")}
                undo=null;lastSpaceAt=0L;lastCompletedWord="";return
            }
        }
        target.batch{
            var finalWord=word
            val correction=if(word.length>=3 && settings.autocorrect && settings.tolerance>0 && host.policy.canSuggest
                && word.lowercase(Locale.FRENCH) !in rejected && !host.isPersonal(word))correct(word) else null
            if(correction!=null && correction!=word){
                if(composing.isNotEmpty())target.setComposing(correction)
                else{target.deleteBefore(word.codePointCount(0,word.length));target.commit(correction)}
                finalWord=correction
            }
            finishComposing()
            commitDelimiter(d)
            undo=if(finalWord!=word)Undo(word,finalWord,d) else null
        }
        if(word.isNotEmpty()){
            val completed=finalLower(undo?.corrected ?: word)
            if(lastCompletedWord.isNotEmpty())host.learnPair(lastCompletedWord,completed)
            lastCompletedWord=completed
            if(undo==null && word.length>=2 && !host.isKnown(word))host.learn(word)
        }
        // Fin de phrase : le mot suivant ne se rattache pas au précédent.
        if(d=="." || d=="!" || d=="?" || d=="\n")lastCompletedWord=""
        lastSpaceAt=if(d==" ")now else 0L
    }

    /**
     * Le correcteur ne touche pas aux mots à majuscule (noms propres). Exception : le premier mot d'une phrase,
     * dont la majuscule vient de la majuscule automatique ; on corrige sa forme en minuscules et on remet la majuscule.
     */
    private fun correct(word:String):String? {
        val capitalized=word.length>1 && word[0].isUpperCase() && word.substring(1).none{it.isUpperCase()}
        if(!capitalized || !isSentenceStart(target.textBefore()))return host.correctionFor(word)
        return host.correctionFor(word.lowercase(Locale.FRENCH))?.replaceFirstChar{it.titlecase(Locale.FRENCH)}
    }

    private fun commitDelimiter(d:String){
        val before=target.textBefore()
        val last=before.lastOrNull()
        when{
            // « mot ,» → « mot, » : la ponctuation basse colle au mot (espace laissée par une suggestion).
            (d=="." || d==",") && last==' ' -> {target.deleteBefore(1);target.commit(d)}
            typography && d.length==1 && d[0] in HIGH_PUNCTUATION -> {spaceBeforeHighPunctuation();target.commit(d)}
            else -> target.commit(d)
        }
    }

    /** Français : espace (fine) insécable avant « ? ! ; : » et « ». Rien si le réglage est coupé. */
    private fun spaceBeforeHighPunctuation(){
        val space=host.settings.frenchSpace ?: return
        val before=target.textBefore()
        val last=before.lastOrNull() ?: return
        when{
            last==' ' || last==ImeSettings.NBSP || last==ImeSettings.NARROW_NBSP -> if(last!=space){target.deleteBefore(1);target.commit(space.toString())}
            last.isWhitespace() || last in "([{«" -> {}
            else -> target.commit(space.toString())
        }
    }

    /** Retour arrière : composition, puis annulation de la dernière correction, puis sélection ou caractère. */
    fun onDelete(){
        if(composing.isNotEmpty()){
            val cut=composing.offsetByCodePoints(composing.length,-1)
            composing.setLength(cut)
            target.setComposing(composing)
            if(composing.isEmpty())target.finishComposing()
            return
        }
        if(undoLastCorrection())return
        if(target.hasSelection)target.deleteSelection() else target.deleteBefore(1)
    }

    /** Annule la dernière correction automatique si rien n'a été tapé depuis. Vrai si elle a été annulée. */
    fun undoLastCorrection():Boolean {
        val u=undo;undo=null
        if(u==null || composing.isNotEmpty() || !target.textBefore().endsWith(u.corrected+u.delimiter))return false
        target.batch{
            target.deleteBefore((u.corrected+u.delimiter).let{it.codePointCount(0,it.length)})
            target.commit(u.original+u.delimiter)
        }
        // L'utilisateur tenait à son mot : il n'est plus corrigé dans ce champ, et il est appris (si la politique l'autorise).
        rejected.add(u.original.lowercase(Locale.FRENCH))
        host.learn(u.original)
        return true
    }

    /** Efface le mot précédent (appui long prolongé sur Retour arrière, geste). */
    fun onDeleteWord(){
        undo=null
        if(composing.isNotEmpty()){composing.clear();target.setComposing("");target.finishComposing();return}
        if(target.hasSelection){target.deleteSelection();return}
        val chars=wordSpanBefore(target.textBefore(),1)
        if(chars>0)target.deleteBefore(target.textBefore().let{it.toString().codePointCount(it.length-chars,it.length)})
    }

    /** Glisser depuis Retour arrière : sélectionne les `words` mots précédents (0 : rien). */
    fun selectWordsBefore(words:Int){finishComposing();target.selectBefore(if(words<=0)0 else wordSpanBefore(target.textBefore(),words))}

    fun onSuggestion(chosen:String){
        undo=null
        val word=currentWord()
        val value=if(word.firstOrNull()?.isUpperCase()==true)chosen.replaceFirstChar{it.uppercase()} else chosen
        target.batch{
            if(composing.isNotEmpty()){target.setComposing(value);finishComposing()}
            else if(word.isNotEmpty()){target.deleteBefore(word.codePointCount(0,word.length));target.commit(value)}
            else target.commit(value)
            target.commit(" ")
        }
        lastCompletedWord=finalLower(value)
        host.learn(value)
    }

    /** Remplace le dernier caractère tapé (mode « valider à l'appui » : choix d'un accent après coup). */
    fun replaceLast(base:String,choice:String){
        if(composing.endsWith(base)){composing.setLength(composing.length-base.length);composing.append(choice);target.setComposing(composing);return}
        if(target.textBefore().endsWith(base))target.batch{target.deleteBefore(1);target.commit(choice)}
    }

    fun moveCursor(delta:Int){finishComposing();undo=null;target.moveCursor(delta)}

    /** À appeler avant toute action extérieure (coller, traduire, sélectionner…). */
    fun finishComposing(){if(composing.isNotEmpty()){target.finishComposing();composing.clear()}}

    /** Le curseur a bougé sans nous (toucher dans le texte, application) : on oublie l'état du mot. */
    fun onExternalCursorMove(){composing.clear();undo=null}

    /** Majuscule automatique selon les drapeaux CAP_* du champ (TextUtils.getCapsMode, sans Android). */
    fun autoCaps(inputType:Int):Boolean {
        if(!host.settings.autoCap || composing.isNotEmpty())return false
        if(inputType and CAP_CHARACTERS!=0)return true
        val sentences=inputType and CAP_SENTENCES!=0
        val words=inputType and CAP_WORDS!=0
        if(!sentences && !words)return false
        val before=target.textBefore()
        var i=before.length
        while(i>0 && (before[i-1]==' ' || before[i-1]==ImeSettings.NBSP || before[i-1]==ImeSettings.NARROW_NBSP))i--
        if(i==0)return true
        val spaced=i<before.length
        val last=before[i-1]
        if(last=='\n')return true
        if(!spaced)return false
        if(words)return true
        return isSentenceStart(before)
    }

    /** Le texte se termine-t-il par une fin de phrase suivie d'une espace (ou est-il vide, ou finit-il par une ligne) ? */
    private fun isSentenceStart(before:CharSequence):Boolean {
        var i=before.length
        while(i>0 && (before[i-1]==' ' || before[i-1]==ImeSettings.NBSP || before[i-1]==ImeSettings.NARROW_NBSP))i--
        if(i==0 || before[i-1]=='\n')return true
        if(i==before.length)return false
        var j=i
        while(j>0 && before[j-1] in "\"'’»)]")j--
        return j>0 && before[j-1] in ".!?…"
    }

    private fun finalLower(word:String)=word.lowercase(Locale.FRENCH)

    companion object {
        private const val DOUBLE_SPACE_MS=600L
        private const val HIGH_PUNCTUATION="?!;:"
        // Valeurs d'android.text.InputType, recopiées pour rester testables sur la JVM.
        const val CAP_CHARACTERS=0x1000
        const val CAP_WORDS=0x2000
        const val CAP_SENTENCES=0x4000
        fun isWordChar(c:Char)=c.isLetter() || c=='\'' || c=='’' || c=='-'
        fun trailingWord(text:CharSequence):String {
            var i=text.length
            while(i>0 && isWordChar(text[i-1]))i--
            return text.subSequence(i,text.length).toString().trimStart('-','\'','’')
        }
        /** Nombre de caractères couvrant les `words` derniers mots (et les espaces qui les suivent). */
        fun wordSpanBefore(text:CharSequence,words:Int):Int {
            var i=text.length;var n=0
            while(n<words && i>0){
                while(i>0 && text[i-1].isWhitespace())i--
                if(i>0 && !isWordChar(text[i-1]) && !text[i-1].isLetterOrDigit()){i--}
                else while(i>0 && (isWordChar(text[i-1]) || text[i-1].isDigit()))i--
                n++
            }
            return text.length-i
        }
    }
}
