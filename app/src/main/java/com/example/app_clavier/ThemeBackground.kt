package com.example.app_clavier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Loads and blurs user-selected artwork off the input thread. */
object ThemeBackground {
    data class Artwork(val background:Bitmap,val keys:Bitmap)
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var cachedKey=""
    @Volatile private var cached:Artwork?=null
    private var loadingKey=""
    private val callbacks=ArrayList<()->Unit>()

    @Synchronized fun getOrLoad(c:Context,onReady:()->Unit):Artwork? {
        val p=KeyboardPrefs.of(c)
        val uri=p.getString("background_uri",null) ?: return null
        val bg=p.getInt("background_blur",8).coerceIn(0,25)
        val keys=p.getInt("key_blur",18).coerceIn(0,25)
        val key="$uri|$bg|$keys"
        if(cachedKey==key) return cached
        if(loadingKey==key)return null
        // One rebuild is enough when the worker finishes. Keeping a callback per key tap
        // would turn a slow image decode into a burst of UI rebuilds.
        callbacks.clear()
        callbacks.add(onReady)
        loadingKey=key
        worker.execute {
            val artwork=runCatching { load(c.applicationContext,Uri.parse(uri),bg,keys) }.getOrNull()
            main.post {
                val ready:List<()->Unit>
                synchronized(this){
                    if(loadingKey!=key)return@post
                    cachedKey=key;cached=artwork;loadingKey=""
                    ready=callbacks.toList();callbacks.clear()
                }
                ready.forEach{it()}
            }
        }
        return null
    }
    private fun load(c:Context,uri:Uri,bg:Int,keys:Int):Artwork {
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        c.contentResolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,options)}
        val largest=maxOf(options.outWidth,options.outHeight).coerceAtLeast(1)
        val sample=generateSequence(1){it*2}.takeWhile{largest/it>1200}.lastOrNull() ?: 1
        val decoded=c.contentResolver.openInputStream(uri)!!.use { input->
            BitmapFactory.decodeStream(input,null,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})
        } ?: error("Image illisible")
        val base=cover(decoded,684,612)
        if(base!==decoded)decoded.recycle()
        return Artwork(gaussian(base,bg),gaussian(base,keys))
    }
    private fun cover(source:Bitmap,width:Int,height:Int):Bitmap {
        val scale=maxOf(width.toFloat()/source.width,height.toFloat()/source.height)
        val scaledW=(source.width*scale).toInt();val scaledH=(source.height*scale).toInt()
        val target=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        Canvas(target).drawBitmap(source,null,android.graphics.Rect((width-scaledW)/2,(height-scaledH)/2,(width+scaledW)/2,(height+scaledH)/2),Paint(Paint.FILTER_BITMAP_FLAG))
        return target
    }
    private fun gaussian(source:Bitmap,radius:Int):Bitmap {
        if(radius<=0)return source
        val w=source.width;val h=source.height;val size=w*h
        val src=IntArray(size);val horizontal=IntArray(size);val out=IntArray(size);source.getPixels(src,0,w,0,0,w,h)
        val weights=FloatArray(radius*2+1){i->kotlin.math.exp(-((i-radius)*(i-radius)).toFloat()/(2f*(radius/2f).coerceAtLeast(1f)*(radius/2f).coerceAtLeast(1f)))}
        fun blurLine(read:(Int)->Int,write:(Int,Int)->Unit,length:Int){
            for(i in 0 until length){var a=0f;var r=0f;var g=0f;var b=0f;var total=0f
                for(k in -radius..radius){val color=read((i+k).coerceIn(0,length-1));val weight=weights[k+radius];a+=android.graphics.Color.alpha(color)*weight;r+=android.graphics.Color.red(color)*weight;g+=android.graphics.Color.green(color)*weight;b+=android.graphics.Color.blue(color)*weight;total+=weight}
                write(i,android.graphics.Color.argb((a/total).toInt(),(r/total).toInt(),(g/total).toInt(),(b/total).toInt()))
            }
        }
        for(y in 0 until h)blurLine({x->src[y*w+x]},{x,color->horizontal[y*w+x]=color},w)
        for(x in 0 until w)blurLine({y->horizontal[y*w+x]},{y,color->out[y*w+x]=color},h)
        return Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888)
    }
}
