package com.example.app_clavier

import android.content.*
import android.inputmethodservice.InputMethodService
import android.os.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.*
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.example.app_clavier.core.KeyraCore
import com.example.app_clavier.engine.BlockedWords
import com.example.app_clavier.engine.NextWords
import com.example.app_clavier.engine.Predictor
import com.example.app_clavier.ime.ImeSettings
import com.example.app_clavier.keyboard.Strip
import com.example.app_clavier.keyboard.StripItem
import com.example.app_clavier.ime.InputLogic
import com.example.app_clavier.ime.RichInputConnection
import com.example.app_clavier.security.IncognitoApps
import com.example.app_clavier.security.LearningGate
import com.example.app_clavier.security.Panic
import com.example.app_clavier.security.SecurityPolicy
import com.example.app_clavier.storage.KeyManager
import com.example.app_clavier.storage.Migration11to12
import java.security.MessageDigest

/**
 * Service de saisie : orchestration seulement. Le texte passe par InputLogic (règles de saisie) et
 * RichInputConnection (cache local, aucune lecture IPC pendant la frappe). Voir docs/phase-4-rapport.md.
 */
class MintInputService:InputMethodService(){
    private var keyboard:MintKeyboard?=null
    /** Politique du champ courant (SecurityPolicy), recalculée à chaque onStartInput et onStartInputView. */
    private var policy=SecurityPolicy.NONE
    private var inputType=0
    private val keyguard by lazy{getSystemService(android.app.KeyguardManager::class.java)}
    private val worker=Executors.newSingleThreadExecutor()
    /** Correction à l'espace : calculée ici, attendue au plus CORRECTION_WAIT_MS (frappe jamais bloquée plus longtemps). */
    private val correctionWorker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val clipboard by lazy{getSystemService(CLIPBOARD_SERVICE) as ClipboardManager}
    private val clipboardListener=ClipboardManager.OnPrimaryClipChangedListener{captureClip()}
    /** Coffre : effacé de la mémoire quand l'écran s'éteint, rouvert quand l'appareil est déverrouillé (ADR-0006). */
    private val vaultReceiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){
            when(intent.action){
                Intent.ACTION_SCREEN_OFF->KeyManager.executor.execute{KeyManager.lock()}
                Intent.ACTION_USER_PRESENT->openVault()
            }
        }
    }
    private fun openVault(){KeyManager.unlockAsync(this){ok->if(ok)Migration11to12.run(this)}}
    private var clipboardListening=false
    private val suggestionRunnable=Runnable{calculateSuggestions()}
    @Volatile private var translator:OfflineTranslator?=null
    @Volatile private var generation=0
    private var session=0
    private var suggestionWord=""
    /** Corrections déjà calculées pendant la frappe (clé exacte, casse comprise) : l'espace n'attend presque jamais. */
    private val corrections=object:LinkedHashMap<String,String?>(16,0.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String?>?)=size>32}
    private data class PendingTranslation(val source:String,val result:String,val selected:Boolean,val session:Int)
    private var pendingTranslation:PendingTranslation?=null
    @Volatile private var destroyed=false
    private val prefs by lazy{KeyboardPrefs.of(this)}
    @Volatile private var settings=ImeSettings()
    private data class QueuedKey(val session:Int,val key:String)
    private val queuedKeys=ArrayDeque<QueuedKey>(96)

    private val target by lazy{RichInputConnection(this)}
    private val logic by lazy{InputLogic(target,object:InputLogic.Host{
        override val settings get()=this@MintInputService.settings
        override val policy get()=this@MintInputService.policy
        override fun correctionFor(word:String):String? {
            if(corrections.containsKey(word))return corrections[word]
            if(!Predictor.ready)return null
            val tolerance=settings.tolerance
            return runCatching{correctionWorker.submit<String?>{Predictor.correction(word,tolerance)}.get(CORRECTION_WAIT_MS,TimeUnit.MILLISECONDS)}.getOrNull()
                ?.takeIf{!BlockedWords.contains(this@MintInputService,it)}
        }
        override fun isKnown(word:String)=Predictor.contains(word)
        override fun isPersonal(word:String)=PersonalWords.contains(this@MintInputService,word)
        override fun learn(word:String){val p=policy;worker.execute{LearningGate.word(this@MintInputService,p,word)}}
        override fun learnPair(previous:String,word:String){val p=policy;worker.execute{LearningGate.pair(this@MintInputService,p,previous,word)}}
        override fun now()=SystemClock.uptimeMillis()
    })}

    private val prefsListener=SharedPreferences.OnSharedPreferenceChangeListener{p,key->main.post{
        settings=ImeSettings.from(p)
        when(key){
            "theme","color1","color2","background_uri","background_blur","key_blur","height","hand","haptic","commit_on_down","long_press_ms","pin_shuffle",
            "gesture_down","gesture_up","gesture_left","trackpad"->keyboard?.refreshTheme()
            "correction","tolerance"->scheduleSuggestions()
        }
    }}
    override fun onCreate(){
        super.onCreate();prefs.registerOnSharedPreferenceChangeListener(prefsListener);settings=ImeSettings.from(prefs)
        val filter=IntentFilter().apply{addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_USER_PRESENT)}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(vaultReceiver,filter,RECEIVER_NOT_EXPORTED) else registerReceiver(vaultReceiver,filter)
        openVault()
        worker.execute{
            traced("Keyra.emojiLoad"){EmojiCatalog.all(this)}
            traced("Keyra.dictionaryLoad"){Predictor.load(this)}
            translator=traced("Keyra.translatorLoad"){OfflineTranslator(assets.open("offline_translation_fr_en.tsv").reader())}
            if(!destroyed)main.post{scheduleSuggestions()}
        }
    }
    override fun onDestroy(){
        destroyed=true;runCatching{unregisterReceiver(vaultReceiver)};KeyManager.executor.execute{KeyManager.lock()}
        if(clipboardListening)clipboard.removePrimaryClipChangedListener(clipboardListener)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);main.removeCallbacksAndMessages(null)
        worker.shutdownNow();correctionWorker.shutdownNow();super.onDestroy()
    }
    override fun onCreateInputView():View=MintKeyboard(this,::handle).also{keyboard=it;configure(currentInputEditorInfo)}
    override fun onEvaluateFullscreenMode()=false
    override fun onStartInput(info:EditorInfo?,restarting:Boolean){
        super.onStartInput(info,restarting)
        session++;generation++;queuedKeys.clear();suggestionWord="";corrections.clear();pendingTranslation=null
        target.reset(info?.initialSelStart ?: -1,info?.initialSelEnd ?: -1);logic.startInput()
        configure(info)
    }
    override fun onStartInputView(info:EditorInfo?,restarting:Boolean)=traced("Keyra.startInputView"){
        super.onStartInputView(info,restarting);if(!KeyraCore.unlocked)openVault();configure(info);flushQueuedKeys();applyPending();updateAutoShift()
    }
    override fun onFinishInputView(finishingInput:Boolean){logic.finishComposing();super.onFinishInputView(finishingInput)}
    override fun onWindowShown()=traced("Keyra.windowShown"){super.onWindowShown();keyboard?.refreshTheme();if(!clipboardListening){clipboard.addPrimaryClipChangedListener(clipboardListener);clipboardListening=true};captureClip();flushQueuedKeys();applyPending()}
    override fun onWindowHidden(){if(clipboardListening){clipboard.removePrimaryClipChangedListener(clipboardListener);clipboardListening=false};super.onWindowHidden()}
    override fun onFinishInput(){generation++;session++;queuedKeys.clear();main.removeCallbacks(suggestionRunnable);super.onFinishInput()}
    override fun onUpdateSelection(oldSelStart:Int,oldSelEnd:Int,newSelStart:Int,newSelEnd:Int,candidatesStart:Int,candidatesEnd:Int){
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        if(target.onUpdateSelection(newSelStart,newSelEnd,candidatesStart,candidatesEnd)){logic.onExternalCursorMove();updateAutoShift()}
        scheduleSuggestions()
    }
    private fun configure(info:EditorInfo?){
        inputType=info?.inputType ?: 0
        val cls=inputType and InputType.TYPE_MASK_CLASS
        val pkg=info?.packageName
        policy=SecurityPolicy.of(info,keyguard?.isKeyguardLocked ?: true,if(IncognitoApps.isIncognito(this,pkg))setOf(pkg!!) else emptySet())
        val seen=policy
        if(seen.isPassword)KeyManager.executor.execute{LearningGate.passwordField(this,seen,pkg)}
        keyboard?.setSearchAction((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION==EditorInfo.IME_ACTION_SEARCH)
        keyboard?.reset(cls==InputType.TYPE_CLASS_NUMBER || cls==InputType.TYPE_CLASS_PHONE || cls==InputType.TYPE_CLASS_DATETIME,policy.noHistory,policy.isPassword,policy.isPinPad,
            incognito=policy.incognitoApp || policy.noPersonalizedLearning)
    }
    /** Majuscule automatique (début de champ, de phrase…) selon les drapeaux CAP_* du champ. */
    private fun updateAutoShift(){keyboard?.setAutoShift(currentInputConnection!=null && logic.autoCaps(inputType))}

    /** Copie : filtrée par LearningGate sur le fil de stockage ; une copie sensible peut être effacée après 30 s. */
    private fun captureClip(){
        val current=policy
        KeyManager.executor.execute{
            val result=LearningGate.clip(this,current,clipboard)
            main.post{
                if(result==ClipboardHistory.Capture.STORED)keyboard?.refreshClipboardPanel()
                if(result==ClipboardHistory.Capture.SENSITIVE)scheduleSensitiveClear()
            }
        }
    }
    private fun clipFingerprint():String? {
        val text=runCatching{clipboard.primaryClip?.takeIf{it.itemCount>0}?.getItemAt(0)?.text?.toString()}.getOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    }
    /** Option « effacer une copie sensible après 30 s » : seulement si elle est toujours la copie courante. */
    private fun scheduleSensitiveClear(){
        if(!prefs.getBoolean("clear_sensitive_clip",false))return
        val fingerprint=clipFingerprint() ?: return
        main.postDelayed({if(!destroyed && clipFingerprint()==fingerprint)runCatching{clipboard.clearPrimaryClip()}},30_000)
    }

    private fun scheduleSuggestions(){
        if(destroyed)return
        generation++
        main.removeCallbacks(suggestionRunnable)
        main.postDelayed(suggestionRunnable,SUGGESTION_DELAY_MS)
    }
    private fun calculateSuggestions(){
        if(destroyed)return
        val id=generation
        updateLetterBias()
        if(!policy.canSuggest){keyboard?.showSuggestions(Strip.EMPTY);return}
        val word=logic.currentWord() // cache local : aucune lecture IPC
        val learnedAllowed=policy.canUseLearnedWords
        if(word.length<2){
            suggestionWord=""
            val previous=logic.lastCompletedWord
            if(word.isNotEmpty() || previous.isEmpty()){keyboard?.showSuggestions(Strip.EMPTY);return}
            worker.execute{
                if(id!=generation)return@execute
                val next=NextWords.predict(this,previous,learnedAllowed).filter{!BlockedWords.contains(this,it)}
                main.post{if(id==generation && !destroyed)keyboard?.showSuggestions(strip(next,null,null,null))}
            }
            return
        }
        if(!Predictor.ready)return
        val tolerance=settings.tolerance
        val autocorrect=settings.autocorrect && tolerance>0
        val emojiAllowed=prefs.getBoolean("emoji_suggest",true) && !policy.noHistory
        worker.execute {
            if(id!=generation)return@execute
            val lower=word.lowercase(java.util.Locale.FRENCH)
            var shown:Strip=Strip.EMPTY
            val computed=HashMap<String,String?>(2)
            traced("Keyra.suggest"){
                val learned=(if(learnedAllowed)UserLexicon.suggestions(this,word) else emptyList()).map{if(word.contains('’'))it.replace('\'','’') else it}
                val analysis=Predictor.analyze(word,tolerance,4)
                // Début de phrase : InputLogic demandera la correction de la forme en minuscules ; on la prépare aussi.
                computed[word]=analysis?.correction
                if(lower!=word)computed[lower]=Predictor.correction(lower,tolerance)
                val correction=(computed[word] ?: computed[lower]?.replaceFirstChar{it.titlecase(java.util.Locale.FRENCH)})
                    ?.takeIf{autocorrect && !BlockedWords.contains(this,it)}
                val choices=(learned+analysis?.suggestions.orEmpty()).distinct().filter{!it.equals(word,true) && !BlockedWords.contains(this,it)}
                shown=strip(choices,word,correction,if(emojiAllowed)EmojiCatalog.forWord(this,word) else null)
            }
            main.post{if(id==generation && !destroyed){corrections.putAll(computed);if(keyboard?.showSuggestions(shown)==true)suggestionWord=word}}
        }
    }

    /**
     * Bandeau à la SwiftKey. Avec une correction prévue : « mot tapé » à gauche, correction en gras au centre,
     * proposition suivante à droite. Sinon : meilleure proposition au centre, puis gauche, puis droite.
     * Un emoji correspondant au mot prend la case de droite.
     */
    private fun strip(choices:List<String>,typed:String?,correction:String?,emoji:String?):Strip {
        fun item(word:String)=StripItem(capitalizeLike(word,typed),"suggest:$word")
        val base=if(correction!=null && typed!=null)
            Strip(StripItem("« $typed »","suggestTyped:$typed"),StripItem(correction,"suggest:$correction",bold=true),choices.firstOrNull{!it.equals(correction,true)}?.let(::item))
        else Strip(choices.getOrNull(1)?.let(::item),choices.getOrNull(0)?.let(::item),choices.getOrNull(2)?.let(::item))
        return if(emoji!=null)base.copy(right=StripItem(emoji,"emojiSuggest:$emoji")) else base
    }
    private fun capitalizeLike(word:String,typed:String?)=if(typed?.firstOrNull()?.isUpperCase()==true)word.replaceFirstChar{it.titlecase(java.util.Locale.FRENCH)} else word

    /** Zones de toucher dynamiques : la lettre suivante la plus probable s'agrandit un peu (jamais en champ sensible). */
    private fun updateLetterBias(){
        val on=prefs.getBoolean("dynamic_zones",true) && policy.canSuggest && Predictor.ready
        keyboard?.setLetterBias(if(on)Predictor.nextLetters(logic.currentWord()) else null)
    }

    private fun flushQueuedKeys(){
        if(currentInputConnection==null)return
        while(queuedKeys.isNotEmpty()){
            val next=queuedKeys.removeFirst()
            if(next.session==session)handle(next.key)
        }
    }
    /** Action qui touche au texte hors d'InputLogic : composition validée avant, cache relu après. */
    private inline fun external(block:(InputConnection)->Unit){
        val ic=currentInputConnection ?: return
        logic.finishComposing();block(ic);target.invalidate()
    }
    private fun handle(key:String)=traced("Keyra.handleKey"){handleKey(key)}
    private fun handleKey(key:String){
        val ic=currentInputConnection
        if(ic==null){if(queuedKeys.size>=96)queuedKeys.removeFirst();queuedKeys.addLast(QueuedKey(session,key));return}
        when {
            key=="delete" -> logic.onDelete()
            key=="deleteWord" -> logic.onDeleteWord()
            key.startsWith("selectWordsBack:") -> logic.selectWordsBefore(key.substringAfter(':').toIntOrNull() ?: 0)
            key=="deleteSelection" -> target.deleteSelection()
            key=="cursorLeft" -> logic.moveCursor(-1)
            key=="cursorRight" -> logic.moveCursor(1)
            key=="hide" -> requestHideSelf(0)
            key.startsWith("replaceLong:") -> {
                val parts=key.split(':',limit=3)
                if(parts.size==3)logic.replaceLast(parts[1],parts[2])
            }
            key=="undo" -> if(!logic.undoLastCorrection())Toast.makeText(this,"Aucune correction à annuler ici",Toast.LENGTH_SHORT).show()
            key=="enter" -> {
                val options=currentInputEditorInfo?.imeOptions ?: 0;val a=options and EditorInfo.IME_MASK_ACTION
                if(options and EditorInfo.IME_FLAG_NO_ENTER_ACTION==0 && a!=EditorInfo.IME_ACTION_NONE && a!=EditorInfo.IME_ACTION_UNSPECIFIED)external{it.performEditorAction(a)}
                else logic.onDelimiter("\n")
            }
            key=="back" -> requestHideSelf(0)
            key=="picker" -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            key in listOf("settings","themes","correction") -> startActivity(Intent(this,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section",key))
            key=="voice" || key=="media" -> {
                if(if(key=="voice")!policy.allowVoice else policy.isPassword){Toast.makeText(this,"Indisponible dans un champ privé",Toast.LENGTH_SHORT).show();return}
                logic.finishComposing()
                startActivity(Intent(this,MediaInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("kind",key).putExtra("target",currentInputEditorInfo?.packageName).putExtra("field",currentInputEditorInfo?.fieldId ?: 0))
            }
            key.startsWith("translate:") -> requestTranslation(key.substringAfter(':'))
            key=="translationInsert" -> {
                if(!policy.allowTranslation)return
                pendingTranslation?.takeIf{it.result.isNotBlank() && it.session==session}?.let{pending->
                    external{c->
                        val stillSelected=c.getSelectedText(0)?.toString()==pending.source
                        val stillBefore=c.getTextBeforeCursor(pending.source.length,0)?.toString()==pending.source
                        if((pending.selected && stillSelected) || (!pending.selected && stillBefore)){
                            c.beginBatchEdit()
                            if(!pending.selected)c.deleteSurroundingText(pending.source.length,0)
                            c.commitText(pending.result,1)
                            c.endBatchEdit()
                            pendingTranslation=null
                            keyboard?.showTranslation("","")
                        }else Toast.makeText(this,"Le texte a changé : relance la traduction",Toast.LENGTH_SHORT).show()
                    }
                }
            }
            key=="paste" -> if(!policy.isPassword)external{c->val clip=clipboard.primaryClip;if(clip!=null && clip.itemCount>0)c.commitText(clip.getItemAt(0).coerceToText(this),1)}
            key.startsWith("clip:") -> if(policy.allowClipboardHistory){val index=key.substringAfter(':').toIntOrNull() ?: -1;ClipboardHistory.items(this).getOrNull(index)?.let{text->external{c->c.commitText(text,1)}}}
            key=="clearclips" -> {ClipboardHistory.clear(this);Toast.makeText(this,"Historique effacé",Toast.LENGTH_SHORT).show()}
            key.startsWith("clipPin:") -> ClipboardHistory.togglePin(this,key.substringAfter(':').toIntOrNull() ?: -1)
            key.startsWith("clipDel:") -> ClipboardHistory.delete(this,key.substringAfter(':').toIntOrNull() ?: -1)
            key=="panic" -> {requestHideSelf(0);Panic.wipe(this)}
            key=="selectAll" -> external{it.performContextMenuAction(android.R.id.selectAll)}
            key=="copy" -> if(policy.allowCopy)external{it.performContextMenuAction(android.R.id.copy)}
            key=="cut" -> if(policy.allowCopy)external{it.performContextMenuAction(android.R.id.cut)}
            key=="left" -> logic.moveCursor(-1)
            key=="right" -> logic.moveCursor(1)
            key.startsWith("suggest:") -> {
                val word=logic.currentWord()
                if(policy.canSuggest && (word.isEmpty() || word.equals(suggestionWord,true)))logic.onSuggestion(key.substringAfter(':'))
            }
            key.startsWith("suggestTyped:") -> logic.onSuggestion(key.substringAfter(':'))
            key.startsWith("emojiSuggest:") -> {
                val emoji=key.substringAfter(':')
                logic.onDelimiter(" ");logic.onText(emoji)
                val allowed=!policy.noHistory;KeyManager.executor.execute{LearningGate.emoji(this,allowed,emoji)}
            }
            key.startsWith("block:") -> {
                val word=key.substringAfter(':')
                KeyManager.executor.execute{BlockedWords.add(this,word)}
                Toast.makeText(this,"Suggestion masquée",Toast.LENGTH_SHORT).show()
            }
            key in DELIMITERS -> logic.onDelimiter(key)
            else -> logic.onText(key)
        }
        InputLatency.committed()
        updateAutoShift()
        scheduleSuggestions()
    }
    private fun requestTranslation(direction:String){
        if(!policy.allowTranslation){Toast.makeText(this,"Traduction indisponible dans un champ privé",Toast.LENGTH_SHORT).show();return}
        logic.finishComposing()
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
        if(policy.isPassword)return
        pending.text?.let{text->external{c->c.commitText(text,1)}}
        pending.uri?.let{uri->
            val mime=contentResolver.getType(uri) ?: "image/*"
            if(info.contentMimeTypes?.any{ClipDescription.compareMimeTypes(mime,it)}==true){
                val content=InputContentInfo(uri,ClipDescription("Image choisie",arrayOf(mime)),null)
                val ok=runCatching{currentInputConnection?.commitContent(content,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)==true}.getOrDefault(false)
                if(!ok)Toast.makeText(this,"Cette application a refusé l’image",Toast.LENGTH_LONG).show()
            }else Toast.makeText(this,"Ce champ n’accepte pas les images ou GIF du clavier",Toast.LENGTH_LONG).show()
        }
    }
    private companion object {
        const val CORRECTION_WAIT_MS=30L
        const val SUGGESTION_DELAY_MS=40L
        val DELIMITERS=setOf(" ",".",",","!","?",";",":")
    }
}
