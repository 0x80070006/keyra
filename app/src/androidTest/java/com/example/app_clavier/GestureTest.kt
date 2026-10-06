package com.example.app_clavier

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 4 : gestes du clavier (vrais MotionEvent sur la vraie vue). */
@RunWith(AndroidJUnit4::class)
class GestureTest {
    @Test fun spaceBarMovesTheCursor(){
        val kit=KeyboardTestKit().create()
        val (x,y)=kit.center("Espace")
        kit.press(0,x to y);kit.move(0,(x+kit.ref(80f,0f).first) to y);kit.release(0)
        assertTrue("curseur déplacé : ${kit.typed}",kit.typed.isNotEmpty() && kit.typed.all{it=="cursorRight"})
        assertTrue("pas d'espace tapée"," " !in kit.typed)
    }

    @Test fun deleteSwipeSelectsThenDeletesWords(){
        val kit=KeyboardTestKit().create()
        val (x,y)=kit.center("Effacer")
        kit.press(0,x to y);kit.move(0,(x-kit.ref(130f,0f).first) to y);kit.release(0)
        assertEquals("delete",kit.typed.first()) // un caractère à l'appui, comme avant
        assertTrue(kit.typed.any{it.startsWith("selectWordsBack:")})
        assertEquals("deleteSelection",kit.typed.last())
    }

    @Test fun swipeDownHidesAndSwipeUpShifts(){
        val kit=KeyboardTestKit().create()
        val (x,y)=kit.center("t")
        kit.press(0,x to y);kit.move(0,x to (y+kit.ref(0f,200f).second));kit.release(0)
        assertEquals(listOf("back"),kit.typed)
        val (bx,by)=kit.center("b")
        kit.press(0,bx to by);kit.move(0,bx to (by-kit.ref(0f,200f).second));kit.release(0)
        kit.tap("A");assertEquals("A",kit.typed.last())
    }

    @Test fun shortSlidesStayCorrections(){
        val kit=KeyboardTestKit().create()
        kit.press(0,kit.center("e"));kit.move(0,kit.center("r"));kit.release(0)
        assertEquals(listOf("r"),kit.typed)
    }
}
