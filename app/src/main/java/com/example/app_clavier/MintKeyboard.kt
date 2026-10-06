package com.example.app_clavier

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.*
import java.util.concurrent.Executors
import com.example.app_clavier.keyboard.KeyboardLayouts
import com.example.app_clavier.keyboard.KeyboardView
import com.example.app_clavier.keyboard.LayoutState
import com.example.app_clavier.security.LearningGate
import kotlin.math.abs

/** Layout positions are in the user's 684 px reference coordinate system. */
class MintKeyboard(context:Context,private val action:(String)->Unit):ViewGroup(context) {
    companion object { private val emojiSearchWorker=Executors.newSingleThreadExecutor() }
    private data class Slot(val x:Int,val y:Int,val w:Int,val h:Int)
    private val slots=ArrayList<Slot>()
    private val keyCodes=HashMap<Key,String>()
    private var lastShiftTap=0L
    private val toolbarViews=ArrayList<View>()
    private var buildingToolbar=false
    private var sx=1f;private var sy=1f;private var offset=0f
    private var mode=0 // 0 letters, 1 symbols, 2 more symbols, 3 emoji, 4 numpad
    private var emojiSearch=false
    private var emojiQuery=""
    private var emojiSearchBar:TextView?=null
    private var emojiSearchCount:TextView?=null
    private var emojiSearchGrid:GridView?=null
    private var emojiSearchAdapter:BaseAdapter?=null
    private var emojiSearchResults:List<EmojiCatalog.Entry> = emptyList()
    @Volatile private var searchGeneration=0
    private var searchAction=false
    private var panel="";private var page=0
    private var shifted=false;private var locked=false;private var secure=false
    private var accents:String?=null
    private var suggestions=emptyList<String>()
    private var queuedSuggestions:List<String>?=null
    private var lastInputDown=0L
    private var touching=false
    private var category="Tous"
    private val emojiCategoryMembers by lazy { EmojiCatalog.all(context).groupBy{it.category}.mapValues{(_,items)->items.map{it.emoji}.toHashSet()} }
    private val recentViews=ArrayList<TextView>()
    private var recentRows=0
    private var translationDirection="fr-en"
    private var translationSource=""
    private var translationResult=""
    private var startX=0f;private var startY=0f
    private var palette=KeyboardPrefs.palette(context)
    private val prefs=KeyboardPrefs.of(context)
    private var artwork:ThemeBackground.Artwork?=null
    private val touchHandler=Handler(Looper.getMainLooper())
    private var stableRebuildPending=false
    /** Full view rebuilds since creation (performance diagnostic and tests). */
    var rebuildCount=0;private set
    /** Vue unique des touches de frappe (phase 2) ; null tant qu'aucun mode frappe n'a été affiché. */
    private var keyboardView:KeyboardView?=null
    /** Vrai quand l'écran courant est le mode frappe : un changement de Maj, de mode ou de suggestions ne reconstruit rien. */
    private var typingActive=false
    private var passwordField=false;private var pinPad=false;private var incognito=false
    private var pinDigits:List<String>?=null
    private var slideReturnMode=0
    private val keyListener=object:KeyboardView.Listener{
        override fun onKey(code:String,fromModeSlide:Boolean){
            val before=mode
            press(code)
            if(code=="symbols" || code=="moreSymbols" || code=="keypad" || code=="letters")slideReturnMode=before
            // Glissé depuis ?123 ou ABC : le caractère est tapé, puis le mode d'origine revient.
            if(fromModeSlide && mode!=slideReturnMode){mode=slideReturnMode;refresh()}
        }
        override fun onLongPress(code:String)=when(code){
            "shift"->{shifted=true;locked=true;lastShiftTap=0;refresh();true}
            " "->{action("picker");true}
            "menu"->{panel="panic";rebuild();true}
            else->false
        }
        override fun onReplace(base:String,choice:String){
            action("replaceLong:$base:${if(shifted && choice.length==1 && choice[0].isLetter())choice.uppercase() else choice}")
        }
        override fun onTouchDown(){lastInputDown=SystemClock.uptimeMillis()}
    }
    init {isMotionEventSplittingEnabled=true;rebuild()}
    override fun shouldDelayChildPressedState()=false
    override fun performClick():Boolean {super.performClick();return true}
    /** `privateInput` : ni historique ni surbrillance ; `password` : TalkBack dit « point » ; `pinPad` : pavé PIN. */
    fun reset(numeric:Boolean,privateInput:Boolean,password:Boolean=false,pinPad:Boolean=false,incognito:Boolean=false){
        passwordField=password;this.pinPad=pinPad;this.incognito=incognito
        pinDigits=if(pinPad && prefs.getBoolean("pin_shuffle",false))(0..9).map{it.toString()}.shuffled(java.security.SecureRandom()) else null
        mode=if(pinPad)4 else if(numeric)1 else 0;panel="";emojiSearch=false;emojiQuery="";shifted=false;locked=false;secure=privateInput;accents=null;suggestions=emptyList();queuedSuggestions=null;touchHandler.removeCallbacks(renderSuggestions);rebuild()}
    fun refreshTheme(){palette=KeyboardPrefs.palette(context);rebuild()}
    fun setSearchAction(value:Boolean){searchAction=value}
    fun refreshClipboardPanel(){if(panel=="clipboard")rebuild()}
    fun showTranslation(source:String,result:String){translationSource=source;translationResult=result;if(panel=="translate")stableRebuild()}
    private fun stableRebuild(){
        if(touching){stableRebuildPending=true;return}
        stableRebuildPending=false;rebuild()
    }
    private val renderSuggestions=object:Runnable {
        override fun run(){
            val queued=queuedSuggestions ?: return
            val remaining=90-(android.os.SystemClock.uptimeMillis()-lastInputDown)
            if(touching || remaining>0){touchHandler.postDelayed(this,remaining.coerceAtLeast(20));return}
            queuedSuggestions=null;applySuggestions(queued)
        }
    }
    fun showSuggestions(values:List<String>):Boolean{
        queuedSuggestions=values
        val remaining=90-(android.os.SystemClock.uptimeMillis()-lastInputDown)
        if(touching || remaining>0){touchHandler.removeCallbacks(renderSuggestions);touchHandler.postDelayed(renderSuggestions,remaining.coerceAtLeast(20));return true}
        queuedSuggestions=null
        return applySuggestions(values)
    }
    private fun applySuggestions(values:List<String>)=traced("Keyra.suggestionStrip"){replaceSuggestions(values)}
    private fun replaceSuggestions(values:List<String>):Boolean{
        if(values==suggestions)return true
        suggestions=values
        if(panel.isEmpty() && mode==0 && accents==null)refresh()
        return true
    }
    private fun releasePressed(cancel:Boolean){
        for(i in 0 until childCount)(getChildAt(i) as? Key)?.releaseTouch(cancel)
    }
    override fun dispatchTouchEvent(event:MotionEvent)=traced("Keyra.touch"){routeTouch(event)}
    private fun routeTouch(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN)touching=true
        if(event.actionMasked==MotionEvent.ACTION_CANCEL)releasePressed(true)
        val result=super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL){
            releasePressed(event.actionMasked==MotionEvent.ACTION_CANCEL);touching=false
            if(queuedSuggestions!=null){touchHandler.removeCallbacks(renderSuggestions);touchHandler.post(renderSuggestions)}
            if(stableRebuildPending){stableRebuildPending=false;touchHandler.post{rebuild()}}
        }
        return result
    }
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean {
        if(panel!="menu")return false
        if(e.actionMasked==MotionEvent.ACTION_DOWN){startX=e.x;startY=e.y}
        return e.actionMasked==MotionEvent.ACTION_MOVE && abs(e.x-startX)>80*sx && abs(e.x-startX)>abs(e.y-startY)*1.5
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(panel=="menu" && e.actionMasked==MotionEvent.ACTION_UP){page=(page+1)%3;rebuild();return true}
        return true
    }
    private fun add(v:View,x:Int,y:Int,w:Int,h:Int){addView(v);slots.add(Slot(x,y,w,h));if(buildingToolbar)toolbarViews.add(v)}
    private fun label(text:String,size:Float=18f)=TextView(context).apply {this.text=text;textSize=size;setTextColor(palette.text);gravity=Gravity.CENTER;isFocusable=false}
    private fun button(text:String,code:String,x:Int,y:Int,w:Int,h:Int,special:Boolean=false,round:Boolean=false,hint:String?=null,small:Boolean=false,transparent:Boolean=false) {
        val red=code.startsWith("hand:") && prefs.getInt("hand",0)==code.substringAfter(':').toIntOrNull()
        val texture=if(prefs.getString("theme","brown")=="image")artwork?.keys else null
        val view=Key(context,text,palette,special,round,hint,if(small)28f else 44f,transparent,code=="shift" && shifted,red,mode==3 && code=="emoji",prefs.getBoolean("haptic",true),texture,x,y,w,h)
        keyCodes[view]=code
        view.contentDescription=when(code){" "->"Espace";"symbols"->"Chiffres et symboles";"emoji"->"Emoji";"shift"->if(locked)"Majuscules verrouillées" else "Majuscules";"delete"->"Effacer";"menu"->"Panneaux de fonctions";"moreSymbols"->"Deuxième page de symboles";"enter"->"Entrée";else->text}
        view.setOnClickListener{press(code)}
        if(code.length==1 || code.startsWith("emojiQuery:")){
            view.setOnTouchListener{v,event->
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN->{InputLatency.down(event);lastInputDown=android.os.SystemClock.uptimeMillis();v.isPressed=true;press(code);if(prefs.getBoolean("haptic",true))v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)}
                    MotionEvent.ACTION_UP->{(v as Key).flash();v.isPressed=false}
                    MotionEvent.ACTION_CANCEL->v.isPressed=false
                }
                true
            }
        }
        if(code=="delete" || code=="emojiSearchDelete"){
            val erase={if(code=="delete")action("delete") else press("emojiSearchDelete")}
            val repeat=object:Runnable{override fun run(){erase();view.postDelayed(this,30)}}
            view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
                override fun onViewAttachedToWindow(v:View){}
                override fun onViewDetachedFromWindow(v:View){v.removeCallbacks(repeat)}
            })
            view.setOnTouchListener{v,event->
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN->{InputLatency.down(event);lastInputDown=android.os.SystemClock.uptimeMillis();v.isPressed=true;erase();if(prefs.getBoolean("haptic",true))v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);v.postDelayed(repeat,230)}
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{v.removeCallbacks(repeat);v.isPressed=false}
                    MotionEvent.ACTION_MOVE->{if(event.x<0 || event.y<0 || event.x>v.width || event.y>v.height){v.removeCallbacks(repeat);v.isPressed=false}}
                }
                true
            }
        }
        view.setOnLongClickListener {
            when {
                code=="shift" -> {shifted=true;locked=true;lastShiftTap=0;rebuild();true}
                code=="language" || code==" " -> {action("picker");true}
                else -> false
            }
        }
        add(view,x,y,w,h)
    }
    private fun press(code:String) {
        if(code!="shift")lastShiftTap=0
        when {
            code=="menu" -> {panel=if(panel.isEmpty())"menu" else "";accents=null;rebuild()}
            code=="letters" -> {panel="";mode=0;emojiSearch=false;emojiQuery="";accents=null;refresh()}
            code=="emoji" -> {panel="";mode=if(mode==3)0 else 3;emojiSearch=false;emojiQuery="";accents=null;rebuild()}
            code=="emojiSearch" -> {emojiSearch=true;emojiQuery="";rebuild()}
            code=="emojiSearchBack" -> {emojiSearch=false;emojiQuery="";rebuild()}
            code=="emojiSearchDelete" -> {if(emojiQuery.isNotEmpty()){emojiQuery=emojiQuery.dropLast(1);refreshEmojiSearch()}}
            code=="emojiSearchShift" -> {shifted=!shifted;rebuild()}
            code.startsWith("emojiQuery:") -> {emojiQuery+=code.removePrefix("emojiQuery:");refreshEmojiSearch()}
            code.startsWith("emojiCategory:") -> {category=code.removePrefix("emojiCategory:");emojiSearch=false;rebuild()}
            code=="keypad" -> {panel="";mode=4;refresh()}
            code=="symbols" -> {panel="";mode=if(mode==1 || mode==2)0 else 1;accents=null;refresh()}
            code=="moreSymbols" -> {mode=if(mode==2)1 else 2;refresh()}
            code=="shift" -> {
                val now=SystemClock.uptimeMillis()
                if(locked){locked=false;shifted=false;lastShiftTap=0}
                else if(shifted && now-lastShiftTap<400){locked=true;shifted=true;lastShiftTap=0}
                else{shifted=!shifted;lastShiftTap=if(shifted)now else 0}
                refresh()
            }
            code=="next" -> {page=(page+1)%3;rebuild()}
            code=="translationSwap" -> {translationDirection=if(translationDirection=="fr-en")"en-fr" else "fr-en";translationResult="";rebuild()}
            code=="translationRun" -> {translationResult="Traduction locale en cours…";stableRebuild();action("translate:$translationDirection")}
            code=="translationInsert" -> {action(code);translationSource="";translationResult="";panel="";rebuild()}
            code=="accents" -> {accents="àâäæéèêëîïôöœùûüçÿñ";panel="accents";rebuild()}
            code.startsWith("panel:") -> {panel=code.substringAfter(':');rebuild()}
            code.startsWith("theme:") -> {prefs.edit().putString("theme",code.substringAfter(':')).apply();refreshTheme()}
            code.startsWith("hand:") -> {val chosen=code.substringAfter(':').toInt();val next=if(prefs.getInt("hand",0)==chosen)0 else chosen;prefs.edit().putInt("hand",next).apply();rebuild()}
            code=="resetSize" -> {prefs.edit().putInt("height",100).putInt("hand",0).apply();rebuild()}
            code.startsWith("suggest:") -> {action(code);suggestions=emptyList();refresh()}
            code.startsWith("clip:") -> {action(code);panel="";rebuild()}
            code=="clearclips" -> {action(code);rebuild()}
            code.startsWith("clipPin:") || code.startsWith("clipDel:") -> {action(code);rebuild()}
            code=="panic" -> action(code)
            code=="language" -> action("picker")
            else -> {
                val literal=if(code.startsWith("char:"))code.removePrefix("char:") else code
                action(if(shifted && literal.length==1 && literal[0].isLetter())literal.uppercase() else literal)
                if(literal.length==1){val changed=shifted && !locked || accents!=null || panel=="accents";if(!locked)shifted=false;accents=null;if(panel=="accents")panel="";if(changed)refresh()}
            }
        }
    }
    private fun toolbar() {
        buildingToolbar=true
        button(if(panel.isEmpty())"menu" else "close","menu",8,15,58,62,true,true)
        if(panel.isEmpty() && mode==0 && accents!=null) {
            accents!!.forEachIndexed{i,ch->button(ch.toString(),"char:$ch",80+i*72,15,64,62)}
        } else if(panel.isEmpty() && mode==0 && suggestions.isNotEmpty()) {
            suggestions.take(3).forEachIndexed{i,s->button(s,"suggest:$s",80+i*176,15,166,62,small=true)}
        } else {
            button("clipboard","panel:clipboard",159,15,58,62,transparent=true)
            button("accents","accents",310,15,58,62,small=true,transparent=true)
            button("settings","settings",461,15,58,62,transparent=true)
        }
        button(if(panel.isEmpty())"next" else "close","menu",618,15,58,62,true,true)
        buildingToolbar=false
    }
    private fun rebuild(){rebuildCount++;traced("Keyra.rebuild"){rebuildViews()}}
    private fun rebuildViews() {
        stableRebuildPending=false
        typingActive=false;keyCodes.clear()
        emojiSearchBar=null;emojiSearchCount=null;emojiSearchGrid=null;emojiSearchAdapter=null
        recentViews.clear();recentRows=0
        palette=KeyboardPrefs.palette(context)
        artwork=if(prefs.getString("theme","brown")=="image"){
            ThemeBackground.getOrLoad(context){if(isAttachedToWindow)post{rebuild()}}
        }else null
        background=artwork?.let{BitmapDrawable(resources,it.background).apply{gravity=Gravity.FILL}}
            ?: if(palette.gradient)GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(palette.background,palette.special))else android.graphics.drawable.ColorDrawable(palette.background)
        removeAllViews();slots.clear();toolbarViews.clear()
        if(mode==3 && panel.isEmpty()){buildEmoji();requestLayout();return}
        if(panel.isNotEmpty()){toolbar();buildPanel();requestLayout();return}
        val view=keyboardView ?: KeyboardView(context,keyListener).also{keyboardView=it}
        view.palette=palette
        view.texture=if(prefs.getString("theme","brown")=="image")artwork?.keys else null
        view.settings=KeyboardView.Settings(
            commitOnDown=prefs.getBoolean("commit_on_down",false),
            longPressMs=prefs.getInt("long_press_ms",300).coerceIn(200,600).toLong(),
            haptic=prefs.getBoolean("haptic",true),
            quiet=secure || pinPad,
            password=passwordField)
        view.setKeys(typingKeys())
        add(view,0,0,684,612)
        typingActive=true
        requestLayout()
    }
    private fun typingKeys()=KeyboardLayouts.build(LayoutState(mode,shifted,locked,searchAction,suggestions,if(mode==0)accents else null,if(mode==4)pinDigits else null,incognito))
    /** Met à jour les touches sans reconstruire les vues, sauf si l'écran change de nature (panneau, emoji). */
    private fun refresh(){
        val view=keyboardView
        if(typingActive && view!=null && panel.isEmpty() && mode!=3)view.setKeys(typingKeys()) else stableRebuild()
    }
    /** Tests : rectangle, en pixels de cette vue, de la touche ou du bouton qui porte cette description. */
    fun keyRect(description:String):RectF? {
        val view=keyboardView
        if(typingActive && view!=null){val r=RectF();if(view.keyBounds(description,r)){r.offset(view.left.toFloat(),view.top.toFloat());return r}}
        for(i in 0 until childCount){val c=getChildAt(i);if(c.contentDescription?.toString()==description)return RectF(c.left.toFloat(),c.top.toFloat(),c.right.toFloat(),c.bottom.toFloat())}
        return null
    }
    /** Tests : description de la touche que viserait un appui en (x, y), en mode frappe. */
    fun descriptionAt(x:Float,y:Float):String?=keyboardView?.takeIf{typingActive}?.let{it.descriptionAt(x-it.left,y-it.top)}
    private fun refreshEmojiSearch(){
        emojiSearchBar?.text="⌕   ${if(emojiQuery.isEmpty())"Rechercher" else emojiQuery}"
        val query=emojiQuery;val version=++searchGeneration
        if(query.isBlank()){
            emojiSearchResults=emptyList();emojiSearchCount?.text=""
            emojiSearchAdapter?.notifyDataSetChanged();return
        }
        emojiSearchWorker.execute {
            if(version!=searchGeneration)return@execute
            val found=EmojiCatalog.search(context,query)
            post {
                if(version==searchGeneration && mode==3 && emojiSearch){
                    emojiSearchResults=found;emojiSearchCount?.text="${found.size} résultats"
                    emojiSearchAdapter?.notifyDataSetChanged();emojiSearchGrid?.setSelection(0)
                }
            }
        }
    }
    private fun recentFor():List<String> {
        if(secure)return emptyList()
        return EmojiHistory.top(context,if(category=="Tous" || emojiSearch)null else emojiCategoryMembers[category].orEmpty())
    }
    private fun addRecent(emoji:List<String>,startY:Int,rowStep:Int) {
        recentRows=(emoji.size+7)/8
        repeat(recentRows*8){i->
            if(i>=18)return@repeat
            val value=emoji.getOrNull(i).orEmpty()
            val cell=label(value,29f).apply{
                contentDescription=if(value.isEmpty())"" else "Récent $value"
                setOnClickListener{if(text.isNotEmpty()){
                    val chosen=text.toString();action(chosen)
                    LearningGate.emoji(context,!secure,chosen);if(!secure)refreshRecent()
                }}
            }
            recentViews.add(cell)
            add(cell,10+(i%8)*83,startY+(i/8)*rowStep,80,72)
        }
    }
    private fun refreshRecent(){
        val values=recentFor()
        if((values.size+7)/8!=recentRows){rebuild();return}
        recentViews.forEachIndexed{i,view->
            val value=values.getOrNull(i).orEmpty()
            if(view.text.toString()!=value){view.text=value;view.contentDescription=if(value.isEmpty())"" else "Récent $value"}
        }
    }
    private fun buildEmoji() {
        val all=EmojiCatalog.all(context)
        val categories=listOf("Tous")+all.map{it.category}.distinct()
        val symbols=listOf("◷","☺︎","♙","♧","♨","⌖","⚽︎","♫","♡","⚑")
        fun choose(emoji:String){action(emoji);LearningGate.emoji(context,!secure,emoji);if(!secure)refreshRecent()}
        fun grid(entries:List<EmojiCatalog.Entry>,x:Int,y:Int,w:Int,h:Int){
            val view=GridView(context).apply {
                numColumns=8;verticalSpacing=0;horizontalSpacing=0;isVerticalScrollBarEnabled=true
                clipToPadding=false;setBackgroundColor(Color.TRANSPARENT)
                adapter=object:BaseAdapter(){
                    private fun current()=if(emojiSearch)emojiSearchResults else entries
                    override fun getCount()=current().size
                    override fun getItem(position:Int)=current()[position]
                    override fun getItemId(position:Int)=position.toLong()
                    override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                        val e=current()[position]
                        return (convertView as? TextView ?: label("",29f)).apply {
                            text=e.emoji;contentDescription=e.name
                            layoutParams=AbsListView.LayoutParams(-1,(84*this@MintKeyboard.sy).toInt().coerceAtLeast(1))
                        }
                    }
                }
            }
            view.onItemClickListener=AdapterView.OnItemClickListener{_,_,position,_->
                val current=if(emojiSearch)emojiSearchResults else entries
                current.getOrNull(position)?.let{choose(it.emoji)}
            }
            if(emojiSearch){emojiSearchGrid=view;emojiSearchAdapter=view.adapter as BaseAdapter}
            add(view,x,y,w,h)
        }
        button("‹",if(emojiSearch)"emojiSearchBack" else "letters",12,15,55,58,true,true,small=true)
        if(emojiSearch){
            button("Rechercher des emoji","emojiSearchBack",75,15,590,58,small=true,transparent=true)
            add(label("Emoji récents",17f).apply{gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT},20,90,640,44)
            val frequent=recentFor()
            addRecent(frequent,135,76)
            val searchTop=if(recentRows==0)155 else 135+recentRows*76+10
            val searchBar=label("⌕   ${if(emojiQuery.isEmpty())"Rechercher" else emojiQuery}",22f).apply{
                gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT;setPadding(24,0,0,0)
                background=GradientDrawable().apply{setColor(palette.key);cornerRadius=28f}
                contentDescription="Rechercher des emoji"
            }
            emojiSearchBar=searchBar
            add(searchBar,12,searchTop,660,65)
            val found=if(emojiQuery.isBlank())emptyList() else EmojiCatalog.search(context,emojiQuery)
            emojiSearchResults=found
            val count=label(if(emojiQuery.isBlank())"" else "${found.size} résultats",14f)
            emojiSearchCount=count;add(count,20,searchTop+68,644,33)
            grid(found,8,searchTop+104,668,(555-searchTop-112).coerceAtLeast(50))
            val first="azertyuiop";val second="qsdfghjklm";val third="wxcvbn'"
            listOf(first,second,third).forEachIndexed { row,letters ->
                letters.forEachIndexed { i,ch ->
                    val x=if(row==2)110+i*68 else 8+i*68
                    button(if(shifted)ch.uppercase() else ch.toString(),"emojiQuery:${if(shifted)ch.uppercase() else ch}",x,555+row*72,58,63,small=true)
                }
            }
            button("⇧","emojiSearchShift",8,699,91,63,true,small=true)
            button("delete","emojiSearchDelete",584,699,91,63,true)
            button("?123","symbols",8,772,91,65,true,true,small=true)
            button(",","emojiQuery:,",110,772,58,65,true)
            button("Espace","emojiQuery: ",177,772,329,65,small=true)
            button(".","emojiQuery:.",517,772,57,65,true)
            button("⌕","emojiSearchBack",585,772,90,65,true,true,small=true)
        } else {
            button("⌕  Rechercher","emojiSearch",78,15,179,58,true,true,small=true)
            val strip=HorizontalScrollView(context).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}
            val row=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
            categories.forEachIndexed{i,name->
                val selected=name==category
                val item=label(symbols.getOrElse(i){"•"},25f).apply{
                    contentDescription=if(name=="Tous")"Emoji récents" else name
                    setTextColor(if(selected)Color.rgb(48,20,15) else palette.text)
                    if(selected)background=GradientDrawable().apply{setColor(Color.rgb(255,158,153));cornerRadius=50f}
                    setOnClickListener{press("emojiCategory:$name")}
                }
                row.addView(item,LinearLayout.LayoutParams((35*resources.displayMetrics.density).toInt(),(50*resources.displayMetrics.density).toInt()).apply{setMargins(3,0,3,0)})
            }
            strip.addView(row);add(strip,265,15,407,58)
            val currentIndex=categories.indexOf(category).coerceAtLeast(0)
            strip.post{strip.scrollTo((currentIndex*38*resources.displayMetrics.density).toInt().coerceIn(0,(row.width-strip.width).coerceAtLeast(0)),0)}
            val recent=recentFor()
            val showRecent=category=="Tous" || recent.isNotEmpty()
            if(showRecent){
                add(label("Emoji récents",17f).apply{gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT},20,90,644,44)
                if(recent.isEmpty())add(label(if(secure)"Historique masqué dans ce champ privé" else "Tes 18 emoji fréquents apparaîtront ici",14f),20,135,644,75)
                addRecent(recent,137,75)
            }
            val headerY=if(!showRecent)95 else if(recentRows==0)225 else 137+recentRows*75+12
            val header=if(category=="Tous")"Émoticônes et émotions" else category
            add(label(header,17f).apply{gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT},20,headerY,644,45)
            val gridY=headerY+49
            grid(if(category=="Tous")all else all.filter{it.category==category},8,gridY,668,757-gridY)
            button("ABC","letters",8,768,96,65,small=true,transparent=true)
            button("smile","emoji",115,768,112,65,true,true,small=true)
            button("GIF","media",235,768,101,65,true,true,small=true)
            button("▧","media",346,768,101,65,true,true,small=true)
            button(":-)","emojiSearch",456,768,99,65,true,true,small=true)
            button("delete","delete",575,768,100,65,true)
        }
    }
    private fun clipLabel(clip:ClipboardHistory.Clip)=(if(clip.pinned)"📌 " else "")+clip.text.replace('\n',' ').take(160)
    private fun card(text:String,code:String,index:Int){button(text,code,40+(index%2)*310,120+(index/2)*110,294,92,round=true,small=true)}
    private fun buildPanel() {
        when(panel) {
            "menu" -> {
                val entries=when(page){
                    0->listOf("Thème" to "panel:themes","Emoji" to "emoji","Presse-papiers" to "panel:clipboard","Édition du texte" to "panel:edit","Correction FR" to "correction","Confidentialité" to "panel:privacy")
                    1->listOf("Image / GIF local" to "media","Saisie vocale" to "voice","Annuler correction" to "undo","Changer de clavier" to "picker","Main gauche" to "hand:-1","Main droite" to "hand:1")
                    else->listOf("Traduction hors ligne" to "panel:translate","Notes de version" to "panel:patchnotes","Réglages avancés" to "settings")
                }
                entries.forEachIndexed{i,p->card(p.first,p.second,i)}
                button(if(page==0)"●  ○  ○     ›" else if(page==1)"‹     ●  ○     ›" else "‹     ○  ●","next",228,475,228,54,small=true,transparent=true)
            }
            "themes" -> {
                val scroll=ScrollView(context)
                val list=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
                KeyboardPrefs.themes.entries.chunked(2).forEach { pair ->
                    val row=LinearLayout(context)
                    pair.forEach{(id,title)->
                        val p=KeyboardPrefs.palette(context,id)
                        val b=TextView(context).apply{text=if(prefs.getString("theme","brown")==id)"✓ $title" else title;textSize=14f;gravity=Gravity.CENTER;setTextColor(p.text);background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(p.key,p.special)).apply{cornerRadius=18f};setOnClickListener{press("theme:$id")}}
                        row.addView(b,LinearLayout.LayoutParams(0,(72*resources.displayMetrics.density).toInt(),1f).apply{setMargins(5,5,5,5)})
                    };list.addView(row)
                };scroll.addView(list);add(scroll,30,100,624,365)
                button("Personnaliser les couleurs","themes",60,485,564,54,small=true)
            }
            "clipboard" -> {
                add(label(if(secure)"Historique masqué dans ce champ privé" else "Copies récentes, chiffrées et éphémères. Toucher : coller · appui long : épingler · × : supprimer.",15f),30,95,624,55)
                if(!secure){
                    val clips=ClipboardHistory.entries(context)
                    val scroll=ScrollView(context)
                    val list=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
                    if(clips.isEmpty())list.addView(label("Aucune copie enregistrée pendant l’utilisation du clavier",14f))
                    clips.forEachIndexed{i,entry->
                        val row=LinearLayout(context).apply{setBackgroundColor(palette.key)}
                        val item=TextView(context).apply {
                            text=clipLabel(entry);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
                            textSize=16f;setTextColor(palette.text);setPadding(18,12,18,12)
                            contentDescription="Coller copie ${i+1}"
                            setOnClickListener{press("clip:$i")}
                            setOnLongClickListener{press("clipPin:$i");true}
                        }
                        val remove=label("×",22f).apply{contentDescription="Supprimer copie ${i+1}";setOnClickListener{press("clipDel:$i")}}
                        row.addView(item,LinearLayout.LayoutParams(0,-1,1f))
                        row.addView(remove,LinearLayout.LayoutParams((48*resources.displayMetrics.density).toInt(),-1))
                        list.addView(row,LinearLayout.LayoutParams(-1,(66*resources.displayMetrics.density).toInt()).apply{setMargins(0,2,0,2)})
                    }
                    scroll.addView(list);add(scroll,30,157,624,303)
                    button("Effacer l’historique","clearclips",190,475,304,50,small=true)
                }
            }
            "edit" -> {
                listOf("Tout sélectionner" to "selectAll","Copier" to "copy","Couper" to "cut","Coller" to "paste","← Curseur" to "left","Curseur →" to "right").forEachIndexed{i,p->card(p.first,p.second,i)}
                button("Annuler correction","undo",160,475,364,54,small=true)
            }
            "size" -> {
                add(label("Hauteur du clavier",20f),30,105,624,60)
                val value=label("${prefs.getInt("height",100)} %",16f);add(value,30,170,624,50)
                val seek=SeekBar(context).apply{max=50;progress=prefs.getInt("height",100)-80}
                seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){value.text="${v+80} %"}
                    override fun onStartTrackingTouch(s:SeekBar?){}
                    override fun onStopTrackingTouch(s:SeekBar?){prefs.edit().putInt("height",80+seek.progress).apply();requestLayout()}
                });add(seek,50,235,584,65)
                button("Gauche","hand:-1",40,340,190,75,small=true);button("Centré","hand:0",247,340,190,75,small=true);button("Droite","hand:1",454,340,190,75,small=true)
                button("Hauteur normale","resetSize",160,460,364,60,small=true)
            }
            "accents" -> {
                val all=accents ?: "àâäæéèêëîïôöœùûüçÿñ"
                all.forEachIndexed{i,ch->button(ch.toString(),"char:$ch",20+(i%8)*82,110+(i/8)*105,68,85)}
            }
            "translate" -> {
                button(if(translationDirection=="fr-en")"Français  →  Anglais" else "Anglais  →  Français","translationSwap",70,92,544,58,true,true,small=true)
                val source=TextView(context).apply{
                    text=if(translationSource.isBlank())"Sélectionne du texte, ou place le curseur après une phrase." else translationSource
                    textSize=15f;setTextColor(palette.text);setPadding(16,10,16,10);gravity=Gravity.TOP or Gravity.LEFT
                    background=GradientDrawable().apply{setColor(palette.key);cornerRadius=14f}
                }
                add(ScrollView(context).apply{addView(source)},30,165,624,100)
                val result=TextView(context).apply{
                    text=if(translationResult.isBlank())"La traduction restera entièrement sur cet appareil." else translationResult
                    textSize=16f;setTextColor(palette.text);setPadding(16,10,16,10);gravity=Gravity.TOP or Gravity.LEFT
                    background=GradientDrawable().apply{setColor(palette.special);cornerRadius=14f}
                }
                add(ScrollView(context).apply{addView(result)},30,278,624,112)
                button("Traduire la sélection / phrase","translationRun",40,410,392,62,true,true,small=true)
                button("Remplacer","translationInsert",445,410,199,62,true,true,small=true)
                add(label("Modèle local léger FR ↔ EN · aucun réseau",14f),80,475,524,35)
            }
            "panic" -> {
                add(label("Geste panique\n\nEfface tout de suite et définitivement les mots appris, les mots personnels, les emoji récents, l’historique du presse-papiers et tes choix incognito. La clé de chiffrement est détruite : rien ne pourra être récupéré.",16f).apply{gravity=Gravity.CENTER_VERTICAL or Gravity.START;setPadding(18,0,18,0)},30,95,624,250)
                add(label("Glisse le curseur jusqu’au bout pour confirmer",15f),30,355,624,40)
                val confirm=SeekBar(context).apply{max=100;contentDescription="Glisser pour tout effacer"}
                confirm.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){}
                    override fun onStartTrackingTouch(s:SeekBar?){}
                    override fun onStopTrackingTouch(s:SeekBar?){if(confirm.progress>=confirm.max)press("panic") else confirm.progress=0}
                })
                add(confirm,60,400,564,70)
            }
            "privacy" -> {
                val privacy="""Confidentialité de Keyra

Keyra est conçu pour traiter la saisie sur cet appareil. Il ne déclare aucune permission réseau et n’envoie pas tes frappes, tes suggestions, tes copies ou tes emoji à un service externe.

Les suggestions, le dictionnaire personnel et les emoji récents sont stockés dans les données privées de l’application. Tu peux effacer les mots appris, les emoji et les 20 copies conservées depuis les réglages.

Le presse-papiers est lu uniquement lorsque le clavier est affiché, afin de proposer les dernières copies. Les champs de mot de passe et autres champs privés désactivent les suggestions, l’historique et le presse-papiers.

Une image de thème est lue localement à partir de l’emplacement que tu choisis. Elle reste sur le téléphone ; le flou est calculé localement.

Keyra ne conserve pas d’historique général de tes frappes."""
                val body=TextView(context).apply{text=privacy;textSize=16f;setTextColor(palette.text);setPadding(18,12,18,12);setLineSpacing(6f,1f)}
                add(ScrollView(context).apply{addView(body)},30,95,624,370)
                button("Ouvrir les réglages de confidentialité","settings",70,480,544,54,small=true)
            }
            "patchnotes" -> {
                val notes="""Notes de version — Keyra 11.0

• Traduction locale légère français ↔ anglais, sans accès réseau.
• Correction immédiate à l’espace avec complétions et prédictions du mot suivant.
• Suppression des reconstructions du clavier déclenchées par l’apprentissage local.
• Reconstruction visuelle différée tant que des doigts touchent le clavier.
• Nouveau logo Keyra transparent, sans bord noir.

Keyra 10.0

• Ajout de thèmes Ambre, Pourpre, Lavande, Lagon, Sable, Cobalt, Aubergine et Corail.
• Image de fond personnalisée avec flou séparé pour le fond et les touches.
• Les seconds appuis multi-doigts, notamment sur Espace, sont maintenant routés directement vers le champ actif.
• Renforcement des suggestions françaises et de la correction des mots avec apostrophe.
• Ajout du verrouillage des majuscules, de l’appui long accentué, du retour haptique et du presse-papiers local.

Keyra 9.0

• Panneau emoji avec recherche, catégories et récents dynamiques.
• Pavé numérique, thèmes dynamiques et modes une main."""
                val body=TextView(context).apply{text=notes;textSize=16f;setTextColor(palette.text);setPadding(18,12,18,12);setLineSpacing(6f,1f)}
                add(ScrollView(context).apply{addView(body)},30,95,624,370)
                button("Voir les réglages","settings",170,480,344,54,small=true)
            }
        }
        button("ABC","letters",8,505,91,45,true,true,small=true)
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val w=MeasureSpec.getSize(widthMeasureSpec)
        if(mode==3 && panel.isEmpty()){
            sx=w/684f;sy=(resources.displayMetrics.heightPixels*(if(emojiSearch).60f else .53f)/920f).coerceAtMost(sx)
            offset=0f;setMeasuredDimension(w,(920*sy).toInt())
            slots.forEachIndexed{i,s->getChildAt(i).measure(MeasureSpec.makeMeasureSpec((s.w*sx).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((s.h*sy).toInt(),MeasureSpec.EXACTLY))}
            return
        }
        val hand=prefs.getInt("hand",0)
        sx=w/684f*(if(hand==0)1f else .82f);sy=w/684f*(prefs.getInt("height",100)/100f)
        // Bound landscape height, keeping the reference ratio intact in portrait.
        sy=sy.coerceAtMost(resources.displayMetrics.heightPixels*.64f/612f)
        offset=if(hand==1)w-684*sx else 0f
        setMeasuredDimension(w,(612*sy).toInt())
        slots.forEachIndexed{i,s->getChildAt(i).measure(MeasureSpec.makeMeasureSpec((s.w*sx).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((s.h*sy).toInt(),MeasureSpec.EXACTLY))}
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){slots.forEachIndexed{i,s->val x=(offset+s.x*sx).toInt();val y=(s.y*sy).toInt();getChildAt(i).layout(x,y,x+(s.w*sx).toInt(),y+(s.h*sy).toInt())}}
    private class Key(c:Context,val label:String,val colors:KeyboardPrefs.Palette,val special:Boolean,val round:Boolean,val hint:String?,val size:Float,val transparent:Boolean,val active:Boolean,val red:Boolean,val coral:Boolean,val haptic:Boolean,texture:Bitmap?=null,private val referenceX:Int=0,private val referenceY:Int=0,private val referenceWidth:Int=1,private val referenceHeight:Int=1):View(c){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        private val textureShader=texture?.let{BitmapShader(it,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)}
        private val textureMatrix=Matrix()
        private val icon=label in setOf("shift","delete","enter","search","menu","close","next","settings","smile","clipboard","accents")
        private val typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        private val iconPath=Path()
        private var releaseHighlightUntil=0L
        private val clearHighlight=Runnable{invalidate()}
        fun flash(){releaseHighlightUntil=android.os.SystemClock.uptimeMillis()+85;removeCallbacks(clearHighlight);postDelayed(clearHighlight,90);invalidate()}
        fun releaseTouch(cancel:Boolean){isPressed=false;if(cancel){releaseHighlightUntil=0;removeCallbacks(clearHighlight)};invalidate()}
        init{isClickable=true;isFocusable=true}
        override fun performClick():Boolean=super.performClick()
        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(event.actionMasked==MotionEvent.ACTION_DOWN){InputLatency.down(event);if(haptic)performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);isPressed=true;invalidate()}
            if(event.actionMasked==MotionEvent.ACTION_UP)flash()
            if(event.actionMasked==MotionEvent.ACTION_CANCEL)releaseHighlightUntil=0
            return super.onTouchEvent(event)
        }
        override fun onDetachedFromWindow(){removeCallbacks(clearHighlight);super.onDetachedFromWindow()}
        override fun drawableStateChanged(){super.drawableStateChanged();invalidate()}
        override fun onDraw(canvas:Canvas){
            val u=height/85f
            p.style=Paint.Style.FILL;p.color=if(coral)Color.rgb(255,158,153) else if(red)Color.rgb(218,58,58) else if(active)colors.specialText else if(special)colors.special else colors.key
            val highlighted=isPressed || android.os.SystemClock.uptimeMillis()<releaseHighlightUntil
            if(isPressed)InputLatency.pressedDrawn()
            if(highlighted)p.color=colors.specialText
            val radius=if(round)height/2f else 12*u
            if(!transparent || highlighted){
                if(textureShader!=null && !highlighted && !special && !active && !red && !coral){
                    val scaleX=width.toFloat()/referenceWidth.coerceAtLeast(1)
                    val scaleY=height.toFloat()/referenceHeight.coerceAtLeast(1)
                    textureMatrix.setScale(scaleX,scaleY)
                    textureMatrix.postTranslate(-referenceX*scaleX,-referenceY*scaleY)
                    textureShader.setLocalMatrix(textureMatrix)
                    p.shader=textureShader
                    canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,p)
                    p.shader=null
                    p.color=Color.argb(74,Color.red(colors.key),Color.green(colors.key),Color.blue(colors.key))
                    canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,p)
                }else canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,p)
            }
            p.alpha=255;p.color=if(coral)Color.rgb(58,24,18) else if(red)Color.WHITE else if(active)colors.special else if(special)colors.specialText else colors.text
            if(highlighted)p.color=KeyboardPrefs.ink(colors.specialText)
            if(!icon){
                p.typeface=typeface;p.textSize=size*u
                if(p.measureText(label)>width-12*u)p.textSize*=((width-12*u)/p.measureText(label)).coerceAtMost(1f)
                p.textAlign=Paint.Align.CENTER;canvas.drawText(label,width/2f,height/2f-(p.ascent()+p.descent())/2f,p)
                if(hint!=null){p.textAlign=Paint.Align.RIGHT;p.textSize=17*u;canvas.drawText(hint,width-6*u,20*u,p)};return
            }
            canvas.save();canvas.translate(width/2f,height/2f);val iconScale=minOf(height/85f,width/58f)*2.55f;canvas.scale(iconScale,iconScale)
            p.style=Paint.Style.STROKE;p.strokeWidth=1.2f;p.strokeJoin=Paint.Join.ROUND;p.strokeCap=Paint.Cap.ROUND
            fun path(vararg pts:Float){iconPath.reset();iconPath.moveTo(pts[0],pts[1]);var i=2;while(i<pts.size){iconPath.lineTo(pts[i],pts[i+1]);i+=2};canvas.drawPath(iconPath,p)}
            when(label){
                "shift"->path(-6f,1f,0f,-6f,6f,1f,3f,1f,3f,6f,-3f,6f,-3f,1f,-6f,1f)
                "delete"->{path(-8f,0f,-4f,-6f,7f,-6f,7f,6f,-4f,6f,-8f,0f);path(-1f,-2f,3f,2f);path(3f,-2f,-1f,2f)}
                "enter"->{path(6f,-4f,6f,1f,-6f,1f);path(-2f,-3f,-6f,1f,-2f,5f)}
                "search"->{canvas.drawCircle(-1.5f,-1.5f,5f,p);path(2f,2f,7f,7f)}
                "next"->path(-2f,-5f,3f,0f,-2f,5f)
                "close"->{path(-4f,-4f,4f,4f);path(4f,-4f,-4f,4f)}
                "menu"->{p.style=Paint.Style.FILL;for(x in listOf(-6f,1f))for(y in listOf(-6f,1f))canvas.drawRoundRect(x,y,x+5,y+5,1f,1f,p)}
                "smile"->{canvas.drawCircle(0f,0f,6f,p);canvas.drawPoint(-2f,-2f,p);canvas.drawPoint(2f,-2f,p);canvas.drawArc(-3f,-2f,3f,3f,15f,150f,false,p)}
                "clipboard"->{path(-5f,-5f,5f,-5f,5f,7f,-5f,7f,-5f,-5f);path(-3f,-7f,3f,-7f,3f,-3f,-3f,-3f,-3f,-7f);path(-2f,1f,2f,1f);path(-2f,4f,2f,4f)}
                "accents"->{p.style=Paint.Style.FILL;p.textAlign=Paint.Align.CENTER;p.textSize=15f;canvas.drawText("é",0f,5f,p)}
                "settings"->{canvas.drawCircle(0f,0f,5f,p);canvas.drawCircle(0f,0f,1.5f,p);for(i in 0..7){canvas.save();canvas.rotate(i*45f);path(0f,-5f,0f,-7f);canvas.restore()}}
            };canvas.restore()
        }
    }
}
