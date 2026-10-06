package com.example.app_clavier

import com.example.app_clavier.ime.ImeSettings
import com.example.app_clavier.ime.InputLogic
import com.example.app_clavier.ime.TextTarget
import com.example.app_clavier.security.SecurityPolicy
import org.junit.Assert.*
import org.junit.Test

/** Éditeur simulé : texte, curseur, région de composition et sélection, comme un EditText. */
class FakeTarget:TextTarget {
    val text=StringBuilder();var cursor=0;var selStart=0;var composingStart=-1
    override fun textBefore():CharSequence=text.substring(0,if(composingStart>=0)composingStart else selStart)
    override val hasSelection get()=selStart!=cursor
    override fun commit(text:CharSequence){finishComposing();replaceSelection(text)}
    private fun replaceSelection(t:CharSequence){text.replace(selStart,cursor,t.toString());cursor=selStart+t.length;selStart=cursor}
    override fun setComposing(text:CharSequence){
        if(composingStart<0)composingStart=selStart.also{replaceSelection("")}
        this.text.replace(composingStart,cursor,text.toString());cursor=composingStart+text.length;selStart=cursor
        if(text.isEmpty())composingStart=-1
    }
    override fun finishComposing(){composingStart=-1}
    override fun deleteBefore(count:Int){val from=text.offsetByCodePoints(selStart,-count);text.delete(from,selStart);cursor-=selStart-from;selStart=from}
    override fun selectBefore(chars:Int){selStart=cursor-chars}
    override fun deleteSelection(){replaceSelection("")}
    override fun moveCursor(delta:Int){cursor=(cursor+delta).coerceIn(0,text.length);selStart=cursor}
    override fun batch(block:()->Unit)=block()
    override fun toString()=text.toString()
}

class InputLogicTest {
    private class Host(var settings0:ImeSettings=ImeSettings(),var policy0:SecurityPolicy=SecurityPolicy.of(1,0,"org.example",false)):InputLogic.Host{
        val corrections=mutableMapOf("bonjuor" to "bonjour","vnir" to "venir");val learned=ArrayList<String>();var time=0L
        override val settings get()=settings0
        override val policy get()=policy0
        override fun correctionFor(word:String)=if(word.first().isUpperCase())null else corrections[word] // comme FrenchCorrector : pas de nom propre
        override fun isKnown(word:String)=word.lowercase() in setOf("bonjour","venir","salut","ça","va","je","vais")
        override fun isPersonal(word:String)=word.equals("keyra",true)
        override fun learn(word:String){learned+=word}
        override fun now()=time
    }
    private val target=FakeTarget();private val host=Host();private val logic=InputLogic(target,host)
    private fun type(s:String){for(c in s){when(c){' ','.',',','!','?',';',':','\n'->logic.onDelimiter(c.toString());else->logic.onText(c.toString())};host.time+=100}}

    @Test fun composesThenCorrectsAtSpace(){
        type("bonjuor");assertEquals("bonjuor",target.toString());assertEquals(0,target.composingStart)
        type(" ");assertEquals("bonjour ",target.toString());assertEquals(-1,target.composingStart)
    }
    @Test fun backspaceRestoresAndLearnsTheWord(){
        type("vnir ");assertEquals("venir ",target.toString())
        logic.onDelete();assertEquals("vnir ",target.toString())
        assertEquals(listOf("vnir"),host.learned)
        type("vnir ");assertEquals("plus corrigé dans ce champ","vnir vnir ",target.toString())
    }
    @Test fun personalWordsAreNeverCorrected(){host.corrections["keyra"]="kerya";type("Keyra ");assertEquals("Keyra ",target.toString())}
    @Test fun unknownWordsAreLearnedKnownOnesNot(){type("salut chouquette ");assertEquals(listOf("chouquette"),host.learned)}
    @Test fun frenchSpacingBeforeHighPunctuation(){
        type("ça va?");assertEquals("ça va ?",target.toString())
        type(" super !");assertEquals("espace tapée remplacée par une fine insécable","ça va ? super !",target.toString())
    }
    @Test fun frenchSpacingCanBeDisabledOrNonBreaking(){
        host.settings0=ImeSettings(frenchSpace=null);type("ok?");assertEquals("ok?",target.toString())
        host.settings0=ImeSettings(frenchSpace=ImeSettings.NBSP);type(" oui:");assertEquals("ok? oui :",target.toString())
    }
    @Test fun noTypographyInAddresses(){
        host.policy0=SecurityPolicy.of(1 or 0x20,0,null,false) // TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        type("a@b.fr?");assertEquals("a@b.fr?",target.toString())
    }
    @Test fun guillemetsGetNarrowSpaces(){logic.onText("«");type("oui");logic.onText("»");assertEquals("« oui »",target.toString())}
    @Test fun lowPunctuationSticksToTheWord(){logic.onSuggestion("venir");type(",");assertEquals("venir,",target.toString())}
    @Test fun doubleSpaceMakesAPeriodOnlyWhenQuick(){
        type("salut  ");assertEquals("salut. ",target.toString())
        type("va ");host.time+=1000;type(" ");assertEquals("trop lent : deux espaces","salut. va  ",target.toString())
    }
    @Test fun deleteInsideComposition(){type("bonj");logic.onDelete();assertEquals("bon",target.toString());type(" ")}
    @Test fun deleteWordAndSelectWords(){
        type("je vais bien ");logic.onDeleteWord();assertEquals("je vais ",target.toString())
        logic.selectWordsBefore(2);assertTrue(target.hasSelection);logic.onDelete();assertEquals("",target.toString())
    }
    @Test fun autoCapitalisation(){
        val sentences=1 or InputLogic.CAP_SENTENCES
        assertTrue("début du champ",logic.autoCaps(sentences))
        type("salut");assertFalse("au milieu d'un mot",logic.autoCaps(sentences))
        type(". ");assertTrue("après un point et une espace",logic.autoCaps(sentences))
        type("ça va ");assertFalse(logic.autoCaps(sentences))
        assertTrue("CAP_WORDS",logic.autoCaps(1 or InputLogic.CAP_WORDS))
        assertFalse("champ sans drapeau",logic.autoCaps(1))
        host.settings0=ImeSettings(autoCap=false);assertFalse(logic.autoCaps(1 or InputLogic.CAP_CHARACTERS))
    }
    @Test fun passwordsAreNeitherComposedNorCorrected(){
        host.policy0=SecurityPolicy.of(1 or 0x80,0,null,false) // TYPE_TEXT_VARIATION_PASSWORD
        type("bonjuor ");assertEquals("bonjuor ",target.toString());assertEquals(-1,target.composingStart)
        assertTrue(host.learned.isEmpty() || host.learned==listOf("bonjuor")) // LearningGate refuse ensuite (politique)
    }
    @Test fun suggestionKeepsCapitalAndAddsSpace(){type("Bonj");logic.onSuggestion("bonjour");assertEquals("Bonjour ",target.toString())}
    @Test fun sentenceStartIsCorrectedButNamesAreNot(){
        type("Bonjuor ");assertEquals("début de phrase : corrigé, majuscule gardée","Bonjour ",target.toString())
        type("Vnir ");assertEquals("milieu de phrase : nom propre probable, intact","Bonjour Vnir ",target.toString())
        type("ok. Vnir ");assertEquals("Bonjour Vnir ok. Venir ",target.toString())
    }
    @Test fun accentReplacementInComposition(){type("e");logic.replaceLast("e","é");assertEquals("é",target.toString())}
}
