package com.example.app_clavier

import android.os.Build
import android.view.MotionEvent

/**
 * Local diagnostic, without any typed content. Two paths start at the touch event time
 * (MotionEvent.eventTime, so input dispatch delay is included):
 *  - down → first onDraw of the pressed key (UI thread, before RenderThread and display);
 *  - down → call that writes to the InputConnection.
 * System.nanoTime() and MotionEvent times share CLOCK_MONOTONIC on Android.
 * Main thread only. Samples live in fixed ring buffers: recording never allocates.
 */
object InputLatency {
    private const val SIZE=512
    private val toDraw=LongArray(SIZE);private var drawCount=0
    private val toCommit=LongArray(SIZE);private var commitCount=0
    private var commitStart=0L
    private var drawStart=0L
    private fun nanos(event:MotionEvent)=if(Build.VERSION.SDK_INT>=34)event.eventTimeNanos else event.eventTime*1_000_000
    fun down(event:MotionEvent){val start=nanos(event);commitStart=start;drawStart=start}
    fun pressedDrawn(){
        val start=drawStart;if(start==0L)return
        toDraw[drawCount%SIZE]=System.nanoTime()-start;drawCount++;drawStart=0L
    }
    fun committed(){
        val start=commitStart;if(start==0L)return
        toCommit[commitCount%SIZE]=System.nanoTime()-start;commitCount++;commitStart=0L
    }
    private fun percentiles(values:LongArray,count:Int):String {
        val sorted=values.copyOf(minOf(count,SIZE)).also{it.sort()}
        fun at(p:Double)="%.1f".format(sorted[((sorted.size-1)*p).toInt()]/1_000_000.0)
        return "p50 ${at(.5)} ms · p95 ${at(.95)} ms"
    }
    fun summary():String {
        if(commitCount==0 && drawCount==0)return "Aucune frappe mesurée pour l’instant"
        val parts=ArrayList<String>()
        if(drawCount>0)parts.add("Appui → touche dessinée : ${percentiles(toDraw,drawCount)}")
        if(commitCount>0)parts.add("Appui → envoi au champ : ${percentiles(toCommit,commitCount)}")
        return parts.joinToString("\n")+"\n(${minOf(commitCount,SIZE)} dernières frappes)"
    }
}
