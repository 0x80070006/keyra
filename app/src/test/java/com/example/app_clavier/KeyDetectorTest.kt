package com.example.app_clavier

import com.example.app_clavier.keyboard.KeyDetector
import com.example.app_clavier.keyboard.KeyboardLayouts
import com.example.app_clavier.keyboard.LayoutState
import org.junit.Assert.*
import org.junit.Test

class KeyDetectorTest {
    private val letters=KeyboardLayouts.build(LayoutState(mode=0))
    private val detector=KeyDetector(letters)

    /** Constat R1 : plus aucun point des quatre rangées de frappe ne tombe dans le vide, dans aucun mode. */
    @Test fun noDeadZoneInTypingRows(){
        for(mode in listOf(0,1,2,4)){
            val d=KeyDetector(KeyboardLayouts.build(LayoutState(mode=mode)))
            var y=95f
            while(y<497f){
                var x=0f
                while(x<684f){assertNotNull("mode $mode : rien en ($x, $y)",d.keyAt(x,y));x+=1f}
                y+=1f
            }
        }
    }

    @Test fun gapsGoToTheNearestKey(){
        assertEquals("a",detector.keyAt(70f,137f)?.code)   // écart a|z (66..76) : plus près de a
        assertEquals("z",detector.keyAt(73f,137f)?.code)   // plus près de z
        assertEquals("q",detector.keyAt(30f,192f)?.code)   // entre les rangées 1 et 2, plus près de q
        assertEquals("a",detector.keyAt(0f,100f)?.code)    // bord gauche de l'écran
        assertEquals("delete",detector.keyAt(683f,350f)?.code)
    }

    @Test fun toolbarButtonsNeedADirectHit(){
        assertEquals("menu",detector.keyAt(30f,40f)?.code)
        assertNull("espace vide de la barre d'outils",detector.keyAt(100f,40f))
        assertEquals("accents",detector.keyAt(300f,530f)?.code)
        assertNull("sous la dernière rangée, hors du bouton accents",detector.keyAt(100f,580f))
    }

    @Test fun dynamicZonesStealAtMostAQuarter(){
        val z=letters.first{it.code=="z"}
        val bias={k:com.example.app_clavier.keyboard.KeyDef->if(k===z)1f else 0f}
        // Au centre de « a », « a » reste choisi même si « z » est très probable.
        assertEquals("a",detector.keyAt(37f,137f,bias)?.code)
        // Dans l'écart, « z » probable gagne.
        assertEquals("z",detector.keyAt(68f,137f,bias)?.code)
        // Plafond : « z » ne déborde jamais de plus de 25 % de sa largeur (14,5 unités) au-delà de son bord.
        assertEquals("a",detector.keyAt(76f-0.25f*58f-1f,137f,bias)?.code)
    }

    @Test fun layoutKeepsHistoricDescriptionsAndShift(){
        assertTrue(letters.any{it.description=="Espace"})
        assertTrue(letters.any{it.description=="Majuscules"})
        assertEquals("A",KeyboardLayouts.build(LayoutState(mode=0,shifted=true)).first{it.code=="a"}.label)
        assertEquals("Majuscules verrouillées",KeyboardLayouts.build(LayoutState(mode=0,shifted=true,locked=true)).first{it.code=="shift"}.description)
        assertEquals(listOf("e","é","è","ê","ë","3"),letters.first{it.code=="e"}.popup)
        assertTrue(letters.first{it.code=="q"}.popup.isEmpty())
    }

    @Test fun shuffledPinPadUsesEveryDigitOnce(){
        val digits=listOf("7","3","0","9","1","5","2","8","6","4")
        val keys=KeyboardLayouts.build(LayoutState(mode=4,pinDigits=digits))
        val shown=keys.filter{it.code.length==1 && it.code[0].isDigit()}.map{it.code}
        assertEquals(digits.sorted(),shown.sorted())
        assertEquals("7",keys.first{it.x==110 && it.y==95}.code)
    }
}
