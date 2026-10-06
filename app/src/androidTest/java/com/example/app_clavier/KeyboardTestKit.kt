package com.example.app_clavier

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry

/** Pilote MintKeyboard par de vrais MotionEvent (un ou plusieurs doigts), comme le ferait l'écran. */
class KeyboardTestKit(val width:Int=1080){
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val context=instrumentation.targetContext!!
    val typed=ArrayList<String>()
    lateinit var keyboard:MintKeyboard

    fun create(numeric:Boolean=false,privateInput:Boolean=false,password:Boolean=false,pinPad:Boolean=false):KeyboardTestKit {
        main{keyboard=MintKeyboard(context){typed.add(it)};keyboard.reset(numeric,privateInput,password,pinPad);layout()}
        return this
    }
    fun layout(){
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(3000,View.MeasureSpec.AT_MOST))
        keyboard.layout(0,0,width,keyboard.measuredHeight)
    }
    fun main(block:()->Unit){instrumentation.runOnMainSync(block)}
    fun center(description:String):Pair<Float,Float> {
        var point:Pair<Float,Float>?=null
        main{keyboard.keyRect(description)?.let{point=it.centerX() to it.centerY()}}
        return point ?: throw AssertionError("Touche absente : $description")
    }
    /** Coordonnées en pixels d'un point du repère de référence 684 × 612. */
    fun ref(x:Float,y:Float)=x*keyboard.width/684f to y*keyboard.height/612f

    private var downTime=0L
    private val pointers=LinkedHashMap<Int,Pair<Float,Float>>()
    fun send(action:Int,actionPointer:Int=0){
        val ids=pointers.keys.toList()
        val props=Array(ids.size){i->MotionEvent.PointerProperties().apply{id=ids[i];toolType=MotionEvent.TOOL_TYPE_FINGER}}
        val coords=Array(ids.size){i->MotionEvent.PointerCoords().apply{x=pointers.getValue(ids[i]).first;y=pointers.getValue(ids[i]).second;pressure=1f;size=1f}}
        val index=ids.indexOf(actionPointer).coerceAtLeast(0)
        val masked=if(action==MotionEvent.ACTION_POINTER_DOWN || action==MotionEvent.ACTION_POINTER_UP)action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),masked,ids.size,props,coords,0,0,1f,1f,0,0,0,0)
        main{keyboard.dispatchTouchEvent(event);layout()}
        event.recycle()
    }
    fun press(id:Int,at:Pair<Float,Float>){
        val first=pointers.isEmpty();if(first)downTime=SystemClock.uptimeMillis()
        pointers[id]=at;send(if(first)MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN,id)
    }
    fun move(id:Int,to:Pair<Float,Float>){pointers[id]=to;send(MotionEvent.ACTION_MOVE)}
    fun release(id:Int){
        send(if(pointers.size==1)MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP,id)
        pointers.remove(id)
    }
    fun tap(description:String)=tapAt(center(description))
    fun tapAt(at:Pair<Float,Float>){press(0,at);release(0)}
    fun sleep(ms:Long){Thread.sleep(ms);instrumentation.waitForIdleSync()}
}
