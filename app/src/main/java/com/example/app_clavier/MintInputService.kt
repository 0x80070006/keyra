package com.example.app_clavier

import android.content.*
import android.inputmethodservice.InputMethodService
import android.os.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.*
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import com.example.app_clavier.security.SecretDetector
import com.example.app_clavier.security.SecurityPolicy

class MintInputService:InputMethodService(){
    private var keyboard:MintKeyboard?=null
    /** Politique du champ courant (SecurityPolicy), recalculée à chaque onStartInput et onStartInputView. */
    private var policy=SecurityPolicy.NONE
    private val secure get()=policy.isPassword
    private val canCorrect get()=policy.canSuggest
    private val keyguard by lazy{getSystemService(android.app.KeyguardManager::class.java)}
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val clipboard by lazy{getSystemService(CLIPBOARD_SERVICE) as ClipboardManager}
    private val clipboardListener=ClipboardManager.OnPrimaryClipChangedListener{if(policy.allowClipboardHistory){ClipboardHistory.capture(this,clipboard);keyboard?.refreshClipboardPanel()}}
    private var clipboardListening=false
    private val suggestionRunnable=Runnable{calculateSuggestions()}
    @Volatile private var corrector:FrenchCorrector?=null
    @Volatile private var translator:OfflineTranslator?=null
    @Volatile private var generation=0
    private var session=0
    private var currentCursor=-1
    private var suggestionWord=""
    private var suggestedCorrection:String?=null
    private var lastCompletedWord=""
    private data class PendingTranslation(val source:String,val result:String,val selected:Boolean,val session:Int)
    private var pendingTranslation:PendingTranslation?=null
    @Volatile private var destroyed=false
    private val prefs by lazy{KeyboardPrefs.of(this)}
    private data class Undo(val before:String,val after:String,val session:Int)
    private data class QueuedKey(val session:Int,val key:String)
    private var undo:Undo?=null
    private val queuedKeys=ArrayDeque<QueuedKey>(96)
    private val prefsListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->main.post{
        when(key){
            "theme","color1","color2","background_uri","background_blur","key_blur","height","hand"->keyboard?.refreshTheme()
            "correction","tolerance","personal"->scheduleSuggestions()
        }
    }}
    override fun onCreate(){
        super.onCreate();prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        worker.execute{
            traced("Keyra.emojiLoad"){EmojiCatalog.all(this)}
            corrector=traced("Keyra.dictionaryLoad"){FrenchCorrector(assets.open("fr_frequency.txt").reader())}
            translator=traced("Keyra.translatorLoad"){OfflineTranslator(assets.open("offline_translation_fr_en.tsv").reader())}
            if(!destroyed)main.post{scheduleSuggestions()}
        }
    }
    override fun onDestroy(){destroyed=true;if(clipboardListening)clipboard.removePrimaryClipChangedListener(clipboardListener);prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);main.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy()}
    override fun onCreateInputView():View=MintKeyboard(this,::handle).also{keyboard=it;configure(currentInputEditorInfo)}
    override fun onEvaluateFullscreenMode()=false
    override fun onStartInput(info:EditorInfo?,restarting:Boolean){super.onStartInput(info,restarting);session++;generation++;undo=null;queuedKeys.clear();suggestionWord="";suggestedCorrection=null;lastCompletedWord="";pendingTranslation=null;currentCursor=info?.initialSelEnd ?: -1;configure(info)}
    override fun onStartInputView(info:EditorInfo?,restarting:Boolean)=traced("Keyra.startInputView"){super.onStartInputView(info,restarting);configure(info);flushQueuedKeys();applyPending()}
    override fun onWindowShown()=traced("Keyra.windowShown"){super.onWindowShown();keyboard?.refreshTheme();if(!clipboardListening){clipboard.addPrimaryClipChangedListener(clipboardListener);clipboardListening=true};if(policy.allowClipboardHistory)ClipboardHistory.capture(this,clipboard);flushQueuedKeys();applyPending()}
    override fun onWindowHidden(){if(clipboardListening){clipboard.removePrimaryClipChangedListener(clipboardListener);clipboardListening=false};super.onWindowHidden()}
    override fun onFinishInput(){generation++;session++;undo=null;queuedKeys.clear();main.removeCallbacks(suggestionRunnable);super.onFinishInput()}
    override fun onUpdateSelection(oldSelStart:Int,oldSelEnd:Int,newSelStart:Int,newSelEnd:Int,candidatesStart:Int,candidatesEnd:Int){
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        currentCursor=newSelEnd
        if(newSelStart!=newSelEnd)undo=null
        scheduleSuggestions()
    }
    private fun configure(info:EditorInfo?){
        val cls=(info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        policy=SecurityPolicy.of(info,keyguard?.isKeyguardLocked ?: true)
        keyboard?.setSearchAction((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION==EditorInfo.IME_ACTION_SEARCH)
        keyboard?.reset(cls==InputType.TYPE_CLASS_NUMBER || cls==InputType.TYPE_CLASS_PHONE || cls==InputType.TYPE_CLASS_DATETIME,policy.noHistory)
    }
    private fun trailingWord(text:String):String=text.takeLastWhile{it.isLetter() || it=='\'' || it=='’' || it=='-'}
    private fun personal(word:String)=prefs.getString("personal","")!!.lineSequence().any{it.trim().equals(word,true)}
    private fun scheduleSuggestions(){
        if(destroyed)return
        generation++
        main.removeCallbacks(suggestionRunnable)
        // Avoid a cross-process cursor query and toolbar redraw between fast consecutive taps.
        main.postDelayed(suggestionRunnable,85)
    }
    private fun calculateSuggestions(){
        if(destroyed)return
        val id=generation
        if(!canCorrect){keyboard?.showSuggestions(emptyList());return}
        val text=currentInputConnection?.getTextBeforeCursor(80,0)?.toString() ?: ""
        val word=trailingWord(text)
        if(word.length<2){
            suggestionWord="";suggestedCorrection=null
            keyboard?.showSuggestions(if(word.isEmpty() && lastCompletedWord.isNotEmpty())corrector?.nextWords(lastCompletedWord).orEmpty() else emptyList())
            return
        }
        val engine=corrector ?: return
        val tolerance=prefs.getInt("tolerance",55)
        val learnedAllowed=policy.canUseLearnedWords
        worker.execute {
            if(id!=generation)return@execute
            val (choices,correction)=traced("Keyra.suggest"){
                val learned=(if(learnedAllowed)UserLexicon.suggestions(this,word) else emptyList()).map{if(word.contains('’'))it.replace('\'','’') else it}
                val candidates=engine.candidates(word,tolerance)
                (learned+candidates.map{it.word}).distinct().take(3) to engine.correction(word,tolerance)
            }
            main.post{if(id==generation && !destroyed && keyboard?.showSuggestions(choices)==true){suggestionWord=word;suggestedCorrection=correction}}
        }
    }
    private fun delimiter(value:String)=traced("Keyra.delimiter"){commitDelimiter(value)}
    private fun commitDelimiter(value:String){
        val ic=currentInputConnection ?: return
        val before=ic.getTextBeforeCursor(80,0)?.toString() ?: ""
        if(value==" " && before.endsWith(' ') && before.dropLast(1).lastOrNull()?.isLetter()==true && prefs.getBoolean("double_space_period",true)){
            ic.beginBatchEdit();ic.deleteSurroundingText(1,0);ic.commitText(". ",1);ic.endBatchEdit();lastCompletedWord="";return
        }
        if(value in listOf(".",",","!","?",";",":") && before.endsWith(' ')){
            ic.beginBatchEdit();ic.deleteSurroundingText(1,0);ic.commitText(value,1);ic.endBatchEdit();return
        }
        val word=trailingWord(before)
        val selected=!ic.getSelectedText(0).isNullOrEmpty()
        val engine=corrector
        val eligible=canCorrect && !selected && engine!=null && prefs.getBoolean("correction",true) && !personal(word) && word.length>=3
        val cached=eligible && suggestionWord.equals(word,true)
        val ready=if(cached)suggestedCorrection else null
        val shouldLearn=policy.canLearn && word.length>=2 && engine?.contains(word)!=true && SecretDetector.isLearnable(word)
        if(ready!=null && ready!=word && ic.getTextBeforeCursor(word.length,0)?.toString()?.equals(word,true)==true){
            ic.beginBatchEdit();ic.deleteSurroundingText(word.length,0);ic.commitText(ready+value,1);ic.endBatchEdit()
            undo=Undo(word+value,ready+value,session)
        }else ic.commitText(value,1) // The delimiter is always visible immediately.
        lastCompletedWord=(ready ?: word).lowercase(Locale.FRENCH)
        if(!eligible || cached){
            if(shouldLearn && ready==null)worker.execute{UserLexicon.record(this,word)}
            return
        }
        val correctionEngine=engine ?: return
        val inputSession=session;val expected=word+value;val tolerance=prefs.getInt("tolerance",55)
        worker.execute {
            val replacement=correctionEngine.correction(word,tolerance)
            if(replacement==null){if(shouldLearn)UserLexicon.record(this,word);return@execute}
            main.post {
                val connection=currentInputConnection
                if(!destroyed && session==inputSession && connection!=null && connection.getSelectedText(0).isNullOrEmpty() && connection.getTextBeforeCursor(expected.length,0)?.toString()==expected){
                    connection.beginBatchEdit();connection.deleteSurroundingText(expected.length,0);connection.commitText(replacement+value,1);connection.endBatchEdit()
                    undo=Undo(expected,replacement+value,session)
                    lastCompletedWord=replacement.lowercase(Locale.FRENCH)
                }
            }
        }
    }
    private fun undoCorrection():Boolean {
        val u=undo ?: return false;val ic=currentInputConnection ?: return false
        if(u.session!=session || ic.getTextBeforeCursor(u.after.length,0)?.toString()!=u.after){undo=null;return false}
        ic.beginBatchEdit();ic.deleteSurroundingText(u.after.length,0);ic.commitText(u.before,1);ic.endBatchEdit();undo=null;return true
    }
    private fun flushQueuedKeys(){
        if(currentInputConnection==null)return
        while(queuedKeys.isNotEmpty()){
            val next=queuedKeys.removeFirst()
            if(next.session==session)handle(next.key)
        }
    }
    private fun handle(key:String)=traced("Keyra.handleKey"){handleKey(key)}
    private fun handleKey(key:String){
        val ic=currentInputConnection
        if(ic==null){if(queuedKeys.size>=96)queuedKeys.removeFirst();queuedKeys.addLast(QueuedKey(session,key));return}
        when {
            key=="delete" -> {if(!undoCorrection()){if(!ic.getSelectedText(0).isNullOrEmpty())ic.commitText("",1)else ic.deleteSurroundingTextInCodePoints(1,0)}}
            key.startsWith("replaceLong:") -> {
                val parts=key.split(':',limit=3)
                if(parts.size==3 && ic.getSelectedText(0).isNullOrEmpty() && ic.getTextBeforeCursor(1,0)?.toString()?.equals(parts[1],true)==true){
                    ic.beginBatchEdit();ic.deleteSurroundingTextInCodePoints(1,0);ic.commitText(parts[2],1);ic.endBatchEdit()
                }
            }
            key=="undo" -> if(!undoCorrection())Toast.makeText(this,"Aucune correction à annuler ici",Toast.LENGTH_SHORT).show()
            key=="enter" -> {
                val options=currentInputEditorInfo?.imeOptions ?: 0;val a=options and EditorInfo.IME_MASK_ACTION
                if(options and EditorInfo.IME_FLAG_NO_ENTER_ACTION==0 && a!=EditorInfo.IME_ACTION_NONE && a!=EditorInfo.IME_ACTION_UNSPECIFIED)ic.performEditorAction(a)else delimiter("\n")
            }
            key=="back" -> requestHideSelf(0)
            key=="picker" -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            key in listOf("settings","themes","correction") -> startActivity(Intent(this,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section",key))
            key=="voice" || key=="media" -> {
                if(if(key=="voice")!policy.allowVoice else policy.isPassword){Toast.makeText(this,"Indisponible dans un champ privé",Toast.LENGTH_SHORT).show();return}
                startActivity(Intent(this,MediaInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("kind",key).putExtra("target",currentInputEditorInfo?.packageName).putExtra("field",currentInputEditorInfo?.fieldId ?: 0))
            }
            key.startsWith("translate:") -> requestTranslation(key.substringAfter(':'))
            key=="translationInsert" -> {
                if(!policy.allowTranslation)return
                pendingTranslation?.takeIf{it.result.isNotBlank() && it.session==session}?.let{pending->
                    val stillSelected=ic.getSelectedText(0)?.toString()==pending.source
                    val stillBefore=ic.getTextBeforeCursor(pending.source.length,0)?.toString()==pending.source
                    if((pending.selected && stillSelected) || (!pending.selected && stillBefore)){
                        ic.beginBatchEdit()
                        if(!pending.selected)ic.deleteSurroundingText(pending.source.length,0)
                        ic.commitText(pending.result,1)
                        ic.endBatchEdit()
                        pendingTranslation=null
                        keyboard?.showTranslation("","")
                    }else Toast.makeText(this,"Le texte a changé : relance la traduction",Toast.LENGTH_SHORT).show()
                }
            }
            key=="paste" -> if(!secure){val clip=(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip;if(clip!=null && clip.itemCount>0)ic.commitText(clip.getItemAt(0).coerceToText(this),1)}
            key.startsWith("clip:") -> if(policy.allowClipboardHistory){val index=key.substringAfter(':').toIntOrNull() ?: -1;ClipboardHistory.items(this).getOrNull(index)?.let{ic.commitText(it,1)}}
            key=="clearclips" -> {ClipboardHistory.clear(this);Toast.makeText(this,"Historique effacé",Toast.LENGTH_SHORT).show()}
            key=="selectAll" -> ic.performContextMenuAction(android.R.id.selectAll)
            key=="copy" -> if(policy.allowCopy)ic.performContextMenuAction(android.R.id.copy)
            key=="cut" -> if(policy.allowCopy)ic.performContextMenuAction(android.R.id.cut)
            key=="left" || key=="right" -> {undo=null;val code=if(key=="left")KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT;ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))}
            key.startsWith("suggest:") -> {
                val word=trailingWord(ic.getTextBeforeCursor(80,0)?.toString() ?: "")
                if(word.isNotEmpty() && word==suggestionWord && canCorrect){var new=key.substringAfter(':');if(word.first().isUpperCase())new=new.replaceFirstChar{it.uppercase()};ic.beginBatchEdit();ic.deleteSurroundingText(word.length,0);ic.commitText("$new ",1);ic.endBatchEdit();lastCompletedWord=new.lowercase(Locale.FRENCH);if(policy.canLearn)worker.execute{UserLexicon.record(this,new)}}
            }
            key in listOf(" ",".",",","!","?",";",":") -> delimiter(key)
            else -> {undo=null;ic.commitText(key,1)}
        }
        InputLatency.committed()
        scheduleSuggestions()
    }
    private fun requestTranslation(direction:String){
        if(!policy.allowTranslation){Toast.makeText(this,"Traduction indisponible dans un champ privé",Toast.LENGTH_SHORT).show();return}
        val ic=currentInputConnection ?: return
        val selected=ic.getSelectedText(0)?.toString().orEmpty()
        val source=selected.ifBlank{
            ic.getTextBeforeCursor(600,0)?.toString().orEmpty().substringAfterLast('\n').takeLast(400).trim()
        }
        if(source.isBlank()){Toast.makeText(this,"Sélectionne ou saisis d’abord un texte",Toast.LENGTH_SHORT).show();return}
        val inputSession=session
        worker.execute{
            val result=translator?.translate(source,direction=="en-fr").orEmpty()
            main.post{if(!destroyed && session==inputSession){pendingTranslation=PendingTranslation(source,result,selected.isNotEmpty(),inputSession);keyboard?.showTranslation(source,result)}}
        }
    }
    private fun applyPending(){
        val pending=PendingInput.result ?: return
        if(SystemClock.elapsedRealtime()-pending.createdAt>PendingInput.MAX_AGE_MS){PendingInput.result=null;return}
        val info=currentInputEditorInfo ?: return
        if(info.packageName!=pending.target || info.fieldId!=pending.field)return
        PendingInput.result=null
        if(secure)return
        pending.text?.let{currentInputConnection?.commitText(it,1)}
        pending.uri?.let{uri->
            val mime=contentResolver.getType(uri) ?: "image/*"
            if(info.contentMimeTypes?.any{ClipDescription.compareMimeTypes(mime,it)}==true){
                val content=InputContentInfo(uri,ClipDescription("Image choisie",arrayOf(mime)),null)
                val ok=runCatching{currentInputConnection?.commitContent(content,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)==true}.getOrDefault(false)
                if(!ok)Toast.makeText(this,"Cette application a refusé l’image",Toast.LENGTH_LONG).show()
            }else Toast.makeText(this,"Ce champ n’accepte pas les images ou GIF du clavier",Toast.LENGTH_LONG).show()
        }
    }
}
