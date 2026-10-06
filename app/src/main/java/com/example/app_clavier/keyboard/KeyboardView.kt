package com.example.app_clavier.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import com.example.app_clavier.InputLatency
import com.example.app_clavier.KeyboardPrefs
import com.example.app_clavier.traced
import kotlin.math.min

/**
 * Vue unique des touches de frappe (lettres, symboles, pavé numérique), à la manière d'AOSP LatinIME :
 * dessin sur un seul Canvas, un suiveur par doigt, détection par proximité (KeyDetector).
 *
 * Comportement d'un doigt (ADR-0012) :
 *  - à l'appui : surbrillance et retour haptique immédiats ; Effacer, Maj et les touches de mode agissent tout de suite ;
 *  - en glissant : la touche visée suit le doigt (correction d'une frappe imprécise) ; depuis ?123 ou ABC,
 *    le caractère relâché est tapé puis le mode précédent revient ;
 *  - au relâchement : le caractère ou l'action est validé ;
 *  - un second doigt qui se pose valide aussitôt la frappe en attente du premier (« phantom up ») ;
 *  - réglage « valider à l'appui » : l'ancien comportement de Keyra 11.
 * Aucun objet n'est alloué par frappe : suiveurs et tâches différées sont créés une fois.
 */
// Créée uniquement par MintKeyboard (jamais depuis un fichier de mise en page) : le Listener est obligatoire.
@SuppressLint("ViewConstructor")
class KeyboardView(context:Context,private val listener:Listener):View(context){
    interface Listener {
        /** Caractère ou action. `fromModeSlide` : caractère glissé depuis une touche de mode. */
        fun onKey(code:String,fromModeSlide:Boolean)
        /** Appui long sans panneau (Maj : verrouillage ; Espace : sélecteur de clavier). Vrai si traité. */
        fun onLongPress(code:String):Boolean
        /** Mode « valider à l'appui » : remplacer le caractère déjà tapé par un choix du panneau. */
        fun onReplace(base:String,choice:String)
        fun onTouchDown()
    }

    /** `quiet` : champ sensible ou pavé PIN, sans surbrillance (ADR-0007). `password` : TalkBack dit « point ». */
    class Settings(val commitOnDown:Boolean=false,val longPressMs:Long=300,val quiet:Boolean=false,val password:Boolean=false,
                   val trackpad:Boolean=true,val swipeDownHide:Boolean=true,val swipeUpShift:Boolean=true,val swipeLeftDeleteWord:Boolean=false,
                   val haptic:KeyFeedback.Haptic=KeyFeedback.Haptic.SYSTEM,val hapticStrength:Float=0.6f,
                   val sound:Boolean=false,val soundVolume:Float=0.5f,
                   /** Bulle d'aperçu au-dessus du doigt (jamais dans un champ sensible). */
                   val preview:Boolean=false)

    var keys:List<KeyDef> = emptyList();private set
    /** Minuteries (appui long, répétition, surbrillance) : fil principal, même vue détachée (tests). */
    private val timers=android.os.Handler(android.os.Looper.getMainLooper())
    private var detector=KeyDetector(keys)
    var settings=Settings()
        set(value){field=value;feedback.configure(value.haptic,value.hapticStrength,value.sound,value.soundVolume,value.quiet)}
    private val feedback=KeyFeedback(context)
    var palette:KeyboardPrefs.Palette=KeyboardPrefs.palette(context)
        set(value){field=value;invalidate()}
    private var textureShader:BitmapShader?=null
    var texture:Bitmap?=null
        set(value){field=value;textureShader=value?.let{BitmapShader(it,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)};invalidate()}
    /** Zones dynamiques (phase 5) : probabilité de chaque touche, ignorée dans les champs sensibles. */
    var bias:((KeyDef)->Float)?=null

    fun setKeys(newKeys:List<KeyDef>){
        keys=newKeys;detector=KeyDetector(newKeys)
        if(a11yEnabled())sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        invalidate()
    }

    // ---------------------------------------------------------------- suivi des doigts
    private inner class Tracker(val id:Int){
        var active=false;var key:KeyDef?=null;var committed=false;var startedOnMode=false;var fromMode=false;var longPressed=false
        /** Point d'appui (repère de référence) et instant : gestes, pavé tactile, glisser depuis Retour arrière. */
        var downX=0f;var downY=0f;var downAt=0L;var repeats=0
        var trackpad=false;var anchorX=0f
        var selecting=false;var selectedWords=0
        /** Le doigt s'est posé sur un caractère ou sur Espace : seul cas où un balayage devient un geste. */
        var startedOnText=false
        val longPress=Runnable{onLongPressTimeout(this)}
        /** Retour arrière maintenu : de plus en plus vite, puis mot par mot après 1,5 s. */
        val repeat=object:Runnable{override fun run(){
            if(!active || key?.code!="delete" || selecting)return
            val wordMode=SystemClock.uptimeMillis()-downAt>WORD_DELETE_AFTER_MS
            listener.onKey(if(wordMode)"deleteWord" else "delete",false);repeats++
            timers.postDelayed(this,if(wordMode)WORD_REPEAT_MS else maxOf(REPEAT_MIN_MS,REPEAT_MS-repeats*5))
        }}
        fun reset(){active=false;key=null;committed=false;startedOnMode=false;fromMode=false;longPressed=false;repeats=0
            trackpad=false;selecting=false;selectedWords=0;startedOnText=false;timers.removeCallbacks(longPress);timers.removeCallbacks(repeat)}
    }
    private val trackers=Array(MAX_POINTERS){Tracker(it)}

    private class Popup(val base:KeyDef,val options:List<String>,val labels:List<String>,val x:Int,val y:Int,val pointer:Int){var selected=0
        val width get()=12+options.size*54+(options.size-1)*4}
    private var popup:Popup?=null

    private fun refX(px:Float)=px*REF_W/width.coerceAtLeast(1)
    private fun refY(py:Float)=py*REF_H/height.coerceAtLeast(1)
    private fun detect(px:Float,py:Float)=detector.keyAt(refX(px),refY(py),if(settings.quiet)null else bias)

    override fun onTouchEvent(e:MotionEvent):Boolean {
        traced("Keyra.keyboardTouch"){
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN->down(e,e.actionIndex)
                MotionEvent.ACTION_MOVE->for(i in 0 until e.pointerCount)move(e,i)
                MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP->{
                    lastX=refX(e.getX(e.actionIndex));lastY=refY(e.getY(e.actionIndex))
                    if(up(e.getPointerId(e.actionIndex)))performClick()
                }
                MotionEvent.ACTION_CANCEL->cancelAll()
            }
        }
        return true
    }

    private fun down(e:MotionEvent,index:Int){
        val id=e.getPointerId(index);if(id>=MAX_POINTERS)return
        listener.onTouchDown();InputLatency.down(e)
        // Phantom up : un nouveau doigt valide les caractères en attente des autres doigts, dans l'ordre de frappe.
        for(other in trackers)if(other.active && other.id!=id && !other.committed && popup?.pointer!=other.id){
            val k=other.key
            if(k!=null && k.printable){timers.removeCallbacks(other.longPress);other.committed=true;listener.onKey(k.code,other.fromMode)}
        }
        val t=trackers[id];t.reset();t.active=true
        t.downX=refX(e.getX(index));t.downY=refY(e.getY(index));t.downAt=SystemClock.uptimeMillis()
        val key=detect(e.getX(index),e.getY(index));t.key=key
        t.startedOnText=key!=null && (key.printable || key.code==" ")
        if(key!=null){
            feedback.press(this,key.code)
            when {
                key.code=="delete"->{t.committed=true;listener.onKey("delete",false);timers.postDelayed(t.repeat,REPEAT_START_MS)}
                key.code=="shift"->{t.committed=true;listener.onKey("shift",false);timers.postDelayed(t.longPress,settings.longPressMs)}
                key.isModeKey->{t.committed=true;t.startedOnMode=true;listener.onKey(key.code,false)}
                key.printable->{
                    if(settings.commitOnDown){t.committed=true;listener.onKey(key.code,false)}
                    if(key.popup.size>1)timers.postDelayed(t.longPress,settings.longPressMs)
                }
                key.code==" "->timers.postDelayed(t.longPress,settings.longPressMs)
                key.code=="menu"->timers.postDelayed(t.longPress,PANIC_HOLD_MS) // 3 s sur la touche menu : geste panique
                key.code.startsWith("suggest:")->timers.postDelayed(t.longPress,settings.longPressMs) // masquer cette suggestion
            }
        }
        invalidate()
    }

    private fun move(e:MotionEvent,index:Int){
        val id=e.getPointerId(index);if(id>=MAX_POINTERS)return
        val t=trackers[id];if(!t.active)return
        val p=popup
        if(p!=null && p.pointer==id){
            val selected=((refX(e.getX(index))-(p.x+6))/58f).toInt().coerceIn(0,p.options.size-1)
            if(selected!=p.selected){p.selected=selected;invalidate()}
            return
        }
        val x=refX(e.getX(index))
        // Retour arrière : glisser vers la gauche sélectionne des mots, relâcher les efface (Gboard, SwiftKey).
        if(t.key?.code=="delete"){
            val dx=x-t.downX
            if(dx<-DELETE_SWIPE_START){t.selecting=true;timers.removeCallbacks(t.repeat)}
            if(t.selecting){
                val words=if(dx<-DELETE_SWIPE_START)((-dx-DELETE_SWIPE_START)/DELETE_SWIPE_STEP).toInt()+1 else 0
                if(words!=t.selectedWords){t.selectedWords=words;listener.onKey("selectWordsBack:$words",false);tick()}
            }
            return
        }
        // Barre d'espace : glisser horizontalement déplace le curseur (FUTO, SwiftKey).
        if(t.key?.code==" " && settings.trackpad && !settings.quiet){
            if(!t.trackpad && kotlin.math.abs(x-t.downX)>TRACKPAD_START){t.trackpad=true;t.committed=true;t.anchorX=t.downX+(if(x>t.downX)TRACKPAD_START else -TRACKPAD_START);timers.removeCallbacks(t.longPress)}
            if(t.trackpad){
                while(x-t.anchorX>=TRACKPAD_STEP){listener.onKey("cursorRight",false);t.anchorX+=TRACKPAD_STEP;tick()}
                while(t.anchorX-x>=TRACKPAD_STEP){listener.onKey("cursorLeft",false);t.anchorX-=TRACKPAD_STEP;tick()}
                return
            }
        }
        val key=detect(e.getX(index),e.getY(index))
        if(key===t.key)return
        if(t.committed && !t.startedOnMode)return // déjà validée à l'appui : plus de glissement
        if(t.startedOnMode){t.fromMode=true;t.committed=false}
        timers.removeCallbacks(t.longPress);t.key=key
        if(key!=null && key.printable && key.popup.size>1 && !settings.commitOnDown)timers.postDelayed(t.longPress,settings.longPressMs)
        invalidate()
    }

    /** Geste de balayage (AnySoftKeyboard) : vers le bas masque, vers le haut met une majuscule, vers la gauche efface un mot. */
    private fun gestureFor(x0:Float,y0:Float,x1:Float,y1:Float):String? {
        val dx=x1-x0;val dy=y1-y0
        return when{
            settings.swipeDownHide && dy>SWIPE_MIN_Y && kotlin.math.abs(dx)<dy*0.6f -> "hide"
            settings.swipeUpShift && -dy>SWIPE_MIN_Y && kotlin.math.abs(dx)< -dy*0.6f -> "gestureShift"
            settings.swipeLeftDeleteWord && -dx>SWIPE_MIN_X && kotlin.math.abs(dy)< -dx*0.5f -> "deleteWord"
            else -> null
        }
    }
    private var lastX=0f;private var lastY=0f
    private fun tick()=feedback.tick(this)

    /** Vrai si le relâchement valide une touche (clic, pour l'accessibilité). */
    private fun up(id:Int):Boolean {
        if(id>=MAX_POINTERS)return false
        val t=trackers[id];if(!t.active)return false
        val key=t.key;val committed=t.committed;val fromMode=t.fromMode;val longPressed=t.longPressed
        val selecting=t.selecting;val selectedWords=t.selectedWords;val trackpad=t.trackpad
        val gesture=if(t.startedOnText && !fromMode && !trackpad && !longPressed)gestureFor(t.downX,t.downY,lastX,lastY) else null
        t.reset()
        if(selecting){if(selectedWords>0)listener.onKey("deleteSelection",false);invalidate();return false}
        if(trackpad){invalidate();return false}
        if(gesture!=null){listener.onKey(gesture,false);invalidate();return false}
        val p=popup
        if(p!=null && p.pointer==id){popup=null;choose(p);flash(p.base);invalidate();return true}
        val click=key!=null && !committed && !longPressed && !(key.isModeKey && fromMode)
        if(click){feedback.release(this);listener.onKey(key.code,fromMode && key.printable)}
        if(key!=null)flash(key)
        invalidate()
        return click
    }

    /** Appelé à chaque validation au relâchement : les services d'accessibilité observent les clics. */
    override fun performClick():Boolean=super.performClick()

    private fun cancelAll(){for(t in trackers)t.reset();popup=null;invalidate()}
    override fun onDetachedFromWindow(){cancelAll();super.onDetachedFromWindow()}

    private fun onLongPressTimeout(t:Tracker){
        val key=t.key ?: return
        if(!t.active)return
        if(key.popup.size>1){
            val n=key.popup.size;val width=12+n*54+(n-1)*4
            val upper=key.label!=key.code
            popup=Popup(key,key.popup,if(upper)key.popup.map{it.uppercase()} else key.popup,
                (key.x+key.w/2-width/2).coerceIn(4,REF_W.toInt()-4-width),(key.y-84).coerceAtLeast(4),t.id)
            t.longPressed=true
        }else if(listener.onLongPress(key.code)){t.longPressed=true;t.committed=true}
        else return
        feedback.longPress(this)
        invalidate()
    }

    private fun choose(p:Popup){
        val choice=p.options[p.selected]
        if(settings.commitOnDown){if(choice!=p.base.code)listener.onReplace(p.base.code,choice)}
        else listener.onKey(if(choice==p.base.code)p.base.code else "char:$choice",false)
    }

    // Brève surbrillance après le relâchement (85 ms), jamais dans un champ sensible.
    private val flashKeys=arrayOfNulls<KeyDef>(4);private val flashUntil=LongArray(4);private var flashNext=0
    private val clearFlash=Runnable{invalidate()}
    private fun flash(key:KeyDef){
        if(settings.quiet)return
        flashKeys[flashNext]=key;flashUntil[flashNext]=SystemClock.uptimeMillis()+FLASH_MS;flashNext=(flashNext+1)%flashKeys.size
        timers.removeCallbacks(clearFlash);timers.postDelayed(clearFlash,FLASH_MS+5)
    }
    private fun highlighted(key:KeyDef,now:Long):Boolean {
        if(settings.quiet)return false
        for(t in trackers)if(t.active && t.key===key)return true
        for(i in flashKeys.indices)if(flashKeys[i]===key && flashUntil[i]>now)return true
        return false
    }

    // ---------------------------------------------------------------- dessin
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
    private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.2f;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}
    private val box=RectF()
    private val shaderMatrix=Matrix()

    override fun onDraw(canvas:Canvas){
        traced("Keyra.keyboardDraw"){
            val now=SystemClock.uptimeMillis()
            val sx=width/REF_W;val sy=height/REF_H
            textureShader?.let{shaderMatrix.setScale(sx,sy);it.setLocalMatrix(shaderMatrix)}
            var anyPressed=false
            for(k in keys){
                val lit=highlighted(k,now);if(lit)anyPressed=true
                drawKey(canvas,k,lit,k.x*sx,k.y*sy,k.right*sx,k.bottom*sy,if(k.small)28f else 44f)
            }
            popup?.let{p->
                box.set(p.x*sx,p.y*sy,(p.x+p.width)*sx,(p.y+80)*sy)
                fill.shader=null;fill.color=palette.special;canvas.drawRoundRect(box,14f*sy,14f*sy,fill)
                for(i in p.options.indices){
                    val x=p.x+6+i*58;val y=p.y+4
                    drawLabelKey(canvas,p.labels[i],i==p.selected,x*sx,y*sy,(x+54)*sx,(y+72)*sy)
                }
            }
            if(settings.preview && !settings.quiet && popup==null)for(t in trackers){
                val k=t.key
                if(t.active && k!=null && k.printable && !t.trackpad && !t.longPressed)drawPreview(canvas,k,sx,sy)
            }
            if(anyPressed)InputLatency.pressedDrawn()
        }
    }

    private fun drawKey(canvas:Canvas,k:KeyDef,lit:Boolean,l:Float,t:Float,r:Float,b:Float,size:Float){
        box.set(l,t,r,b)
        val u=box.height()/85f
        val radius=if(k.round)box.height()/2f else 12*u
        if(!k.transparent || lit){
            val shader=textureShader
            if(shader!=null && !lit && !k.special && !k.active){
                fill.shader=shader;canvas.drawRoundRect(box,radius,radius,fill);fill.shader=null
                fill.color=Color.argb(74,Color.red(palette.key),Color.green(palette.key),Color.blue(palette.key))
            }else fill.color=if(lit)palette.specialText else if(k.active)palette.specialText else if(k.special)palette.special else palette.key
            canvas.drawRoundRect(box,radius,radius,fill)
        }
        val fg=if(lit)KeyboardPrefs.ink(palette.specialText) else if(k.active)palette.special else if(k.special)palette.specialText else palette.text
        val icon=ICONS[k.label]
        if(icon!=null){drawIcon(canvas,k.label,icon,fg);return}
        ink.isFakeBoldText=k.bold
        drawText(canvas,k.label,size*u,u,fg)
        ink.isFakeBoldText=false
        if(k.hint!=null){ink.textAlign=Paint.Align.RIGHT;ink.textSize=17*u;ink.color=fg;canvas.drawText(k.hint,box.right-6*u,box.top+20*u,ink)}
    }

    /** Bulle d'aperçu (SwiftKey, Gboard) : dessinée dans la même vue, au-dessus de la touche, sans fenêtre en plus. */
    private fun drawPreview(canvas:Canvas,k:KeyDef,sx:Float,sy:Float){
        val w=k.w*1.5f;val h=k.h*1.05f
        val left=(k.x+k.w/2f-w/2f).coerceIn(2f,REF_W-2f-w);val top=(k.y-h-6f).coerceAtLeast(2f)
        box.set(left*sx,top*sy,(left+w)*sx,(top+h)*sy)
        val u=box.height()/85f
        fill.shader=null;fill.color=palette.special;canvas.drawRoundRect(box,14f*u,14f*u,fill)
        drawText(canvas,k.label,56f*u,u,palette.specialText)
    }

    private fun drawLabelKey(canvas:Canvas,label:String,selected:Boolean,l:Float,t:Float,r:Float,b:Float){
        box.set(l,t,r,b);val u=box.height()/85f
        fill.shader=null;fill.color=if(selected)palette.specialText else palette.key
        canvas.drawRoundRect(box,12*u,12*u,fill)
        drawText(canvas,label,40*u,u,if(selected)KeyboardPrefs.ink(palette.specialText) else palette.text)
    }

    /** Texte centré, sur une ou plusieurs lignes (« 123\n456\n789 »), réduit pour tenir dans la touche. */
    private fun drawText(canvas:Canvas,label:String,size:Float,u:Float,color:Int){
        ink.textAlign=Paint.Align.CENTER;ink.color=color;ink.textSize=size
        var lines=1;var widest=0f;var start=0
        while(true){
            val end=label.indexOf('\n',start).let{if(it<0)label.length else it}
            widest=maxOf(widest,ink.measureText(label,start,end))
            if(end==label.length)break
            lines++;start=end+1
        }
        val maxWidth=box.width()-12*u
        var textSize=size
        if(widest>maxWidth)textSize*=maxWidth/widest
        textSize=min(textSize,(box.height()-8*u)/(lines*1.15f))
        ink.textSize=textSize
        val lineHeight=textSize*1.15f
        var y=box.centerY()-lineHeight*(lines-1)/2f-(ink.ascent()+ink.descent())/2f
        start=0
        while(true){
            val end=label.indexOf('\n',start).let{if(it<0)label.length else it}
            canvas.drawText(label,start,end,box.centerX(),y,ink)
            if(end==label.length)break
            y+=lineHeight;start=end+1
        }
    }

    private fun drawIcon(canvas:Canvas,name:String,path:Path,color:Int){
        canvas.save()
        canvas.translate(box.centerX(),box.centerY())
        val scale=min(box.height()/85f,box.width()/58f)*2.55f
        canvas.scale(scale,scale)
        when(name){
            "menu"->{fill.shader=null;fill.color=color;canvas.drawPath(path,fill)}
            "accents"->{ink.textAlign=Paint.Align.CENTER;ink.color=color;ink.textSize=15f;canvas.drawText("é",0f,5f,ink)}
            else->{stroke.color=color;canvas.drawPath(path,stroke)}
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------- accessibilité (sans AndroidX)
    private var hovered=-1
    private var a11yFocused=-1
    private val tmpRect=Rect()
    private val location=IntArray(2)
    private fun a11yEnabled()=context.getSystemService(AccessibilityManager::class.java)?.isEnabled==true
    private fun spoken(k:KeyDef)=if(settings.password && k.printable && k.code!=" ")"Point" else k.description

    override fun dispatchHoverEvent(e:MotionEvent):Boolean {
        val manager=context.getSystemService(AccessibilityManager::class.java)
        if(manager==null || !manager.isTouchExplorationEnabled)return super.dispatchHoverEvent(e)
        when(e.actionMasked){
            MotionEvent.ACTION_HOVER_ENTER,MotionEvent.ACTION_HOVER_MOVE->{
                val index=detect(e.x,e.y)?.let{keys.indexOf(it)} ?: -1
                if(index!=hovered){sendForKey(hovered,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);hovered=index;sendForKey(index,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)}
            }
            MotionEvent.ACTION_HOVER_EXIT->{sendForKey(hovered,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);hovered=-1}
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun sendForKey(index:Int,type:Int){
        val k=keys.getOrNull(index) ?: return
        val event=AccessibilityEvent.obtain(type)
        event.packageName=context.packageName;event.className=BUTTON;event.contentDescription=spoken(k)
        event.setSource(this,index)
        parent?.requestSendAccessibilityEvent(this,event)
    }

    private val provider=object:AccessibilityNodeProvider(){
        @Suppress("DEPRECATION")
        override fun createAccessibilityNodeInfo(virtualViewId:Int):AccessibilityNodeInfo? {
            if(virtualViewId==View.NO_ID){
                val info=AccessibilityNodeInfo.obtain(this@KeyboardView)
                onInitializeAccessibilityNodeInfo(info)
                for(i in keys.indices)info.addChild(this@KeyboardView,i)
                return info
            }
            val k=keys.getOrNull(virtualViewId) ?: return null
            val info=AccessibilityNodeInfo.obtain(this@KeyboardView,virtualViewId)
            info.packageName=context.packageName;info.className=BUTTON;info.contentDescription=spoken(k)
            info.setParent(this@KeyboardView)
            val sx=width/REF_W;val sy=height/REF_H
            tmpRect.set((k.x*sx).toInt(),(k.y*sy).toInt(),(k.right*sx).toInt(),(k.bottom*sy).toInt())
            info.setBoundsInParent(tmpRect)
            getLocationOnScreen(location);tmpRect.offset(location[0],location[1]);info.setBoundsInScreen(tmpRect)
            info.isEnabled=true;info.isClickable=true;info.isVisibleToUser=true;info.isAccessibilityFocused=a11yFocused==virtualViewId
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.addAction(if(a11yFocused==virtualViewId)AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            if(k.code==" " || k.code=="shift")info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
            return info
        }
        override fun performAction(virtualViewId:Int,action:Int,arguments:Bundle?):Boolean {
            if(virtualViewId==View.NO_ID)return performAccessibilityAction(action,arguments)
            val k=keys.getOrNull(virtualViewId) ?: return false
            return when(action){
                AccessibilityNodeInfo.ACTION_CLICK->{listener.onKey(k.code,false);sendForKey(virtualViewId,AccessibilityEvent.TYPE_VIEW_CLICKED);true}
                AccessibilityNodeInfo.ACTION_LONG_CLICK->listener.onLongPress(k.code)
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS->{a11yFocused=virtualViewId;sendForKey(virtualViewId,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);invalidate();true}
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS->{if(a11yFocused==virtualViewId)a11yFocused=-1;sendForKey(virtualViewId,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);true}
                else->false
            }
        }
    }
    override fun getAccessibilityNodeProvider():AccessibilityNodeProvider=provider

    // ---------------------------------------------------------------- outils de test
    /** Rectangle d'une touche en pixels de cette vue, par sa description (contentDescription historique). */
    fun keyBounds(description:String,out:RectF):Boolean {
        val k=keys.lastOrNull{it.description==description} ?: return false
        val sx=width/REF_W;val sy=height/REF_H
        out.set(k.x*sx,k.y*sy,k.right*sx,k.bottom*sy);return true
    }
    fun descriptionAt(px:Float,py:Float)=detect(px,py)?.description

    companion object {
        const val REF_W=684f
        const val REF_H=612f
        private const val MAX_POINTERS=10
        private const val REPEAT_START_MS=350L
        private const val REPEAT_MS=80L
        private const val REPEAT_MIN_MS=25L
        private const val WORD_DELETE_AFTER_MS=1_500L
        private const val WORD_REPEAT_MS=200L
        // Distances dans le repère 684 × 612 (une touche fait 58 × 85).
        private const val DELETE_SWIPE_START=30f
        private const val DELETE_SWIPE_STEP=45f
        private const val TRACKPAD_START=20f
        private const val TRACKPAD_STEP=12f
        private const val SWIPE_MIN_Y=150f
        private const val SWIPE_MIN_X=200f
        private const val FLASH_MS=85L
        private const val PANIC_HOLD_MS=3_000L
        private const val BUTTON="android.widget.Button"
        /** Icônes vectorielles, construites une fois dans un repère de ±8 unités autour du centre. */
        private val ICONS:Map<String,Path> by lazy {
            fun lines(vararg pts:Float)=Path().apply{moveTo(pts[0],pts[1]);var i=2;while(i<pts.size){lineTo(pts[i],pts[i+1]);i+=2}}
            fun Path.with(other:Path)=apply{addPath(other)}
            mapOf(
                "shift" to lines(-6f,1f,0f,-6f,6f,1f,3f,1f,3f,6f,-3f,6f,-3f,1f,-6f,1f),
                "delete" to lines(-8f,0f,-4f,-6f,7f,-6f,7f,6f,-4f,6f,-8f,0f).with(lines(-1f,-2f,3f,2f)).with(lines(3f,-2f,-1f,2f)),
                "enter" to lines(6f,-4f,6f,1f,-6f,1f).with(lines(-2f,-3f,-6f,1f,-2f,5f)),
                "search" to Path().apply{addCircle(-1.5f,-1.5f,5f,Path.Direction.CW)}.with(lines(2f,2f,7f,7f)),
                "next" to lines(-2f,-5f,3f,0f,-2f,5f),
                "close" to lines(-4f,-4f,4f,4f).with(lines(4f,-4f,-4f,4f)),
                "menu" to Path().apply{for(x in floatArrayOf(-6f,1f))for(y in floatArrayOf(-6f,1f))addRoundRect(x,y,x+5,y+5,1f,1f,Path.Direction.CW)},
                "smile" to Path().apply{addCircle(0f,0f,6f,Path.Direction.CW);addCircle(-2f,-2f,0.3f,Path.Direction.CW);addCircle(2f,-2f,0.3f,Path.Direction.CW);addArc(-3f,-2f,3f,3f,15f,150f)},
                "clipboard" to lines(-5f,-5f,5f,-5f,5f,7f,-5f,7f,-5f,-5f).with(lines(-3f,-7f,3f,-7f,3f,-3f,-3f,-3f,-3f,-7f)).with(lines(-2f,1f,2f,1f)).with(lines(-2f,4f,2f,4f)),
                "accents" to Path(),
                "incognito" to Path().apply{addCircle(-4f,2f,2.5f,Path.Direction.CW);addCircle(4f,2f,2.5f,Path.Direction.CW)}
                    .with(lines(-1.5f,2f,1.5f,2f)).with(lines(-7f,-2f,7f,-2f)).with(lines(-4.5f,-2f,-3f,-6f,3f,-6f,4.5f,-2f)),
                "settings" to Path().apply{
                    addCircle(0f,0f,5f,Path.Direction.CW);addCircle(0f,0f,1.5f,Path.Direction.CW)
                    for(i in 0..7){val a=Math.toRadians(i*45.0);val c=Math.cos(a).toFloat();val s=Math.sin(a).toFloat();moveTo(s*5f,-c*5f);lineTo(s*7f,-c*7f)}
                },
            )
        }
    }
}
