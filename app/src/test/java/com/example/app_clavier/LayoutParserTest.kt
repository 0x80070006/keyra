package com.example.app_clavier

import com.example.app_clavier.keyboard.KeyDetector
import com.example.app_clavier.keyboard.KeyboardLayouts
import com.example.app_clavier.keyboard.LayoutError
import com.example.app_clavier.keyboard.LayoutParser
import com.example.app_clavier.keyboard.LayoutState
import com.example.app_clavier.keyboard.LetterLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Dispositions JSON (phase 6, ADR-0027) : fichiers livrés, refus stricts, et fuzzing par mutations. */
class LayoutParserTest {
    private val files=File("src/main/assets/layouts").listFiles{f->f.extension=="json"}!!.sortedBy{it.name}
    private fun parse(text:String)=LayoutParser.parse(text.toByteArray(Charsets.UTF_8))
    private fun rejected(text:String){try{parse(text);fail("accepté : $text")}catch(_:LayoutError){}}
    private val valid="""{"format":1,"id":"t","name":"T","rows":[["a","b"],["c"],["d"]]}"""

    @Test fun shippedLayoutsParseAndMatchCatalog(){
        assertEquals(listOf("azerty","bepo","dvorak","qwerty","qwertz"),files.map{it.nameWithoutExtension})
        for(f in files){
            val layout=LayoutParser.parse(f.readBytes())
            assertEquals(f.nameWithoutExtension,layout.id)
            assertTrue(layout.rows.flatten().size>=26)
        }
        val azerty=LayoutParser.parse(File("src/main/assets/layouts/azerty.json").readBytes())
        assertEquals(LetterLayout.AZERTY.rows,azerty.rows)
    }

    @Test fun everyLayoutFitsWithoutOverlap(){
        for(f in files)for(numberRow in listOf(false,true)){
            val keys=KeyboardLayouts.build(LayoutState(mode=0,letters=LayoutParser.parse(f.readBytes()),numberRow=numberRow)).filter{it.proximity}
            for(k in keys)assertTrue("${f.name} $k hors cadre",k.x>=0 && k.right<=684 && k.y>=0 && k.bottom<=612)
            for(a in keys)for(b in keys)if(a!==b)
                assertTrue("${f.name} : $a chevauche $b",a.right<=b.x || b.right<=a.x || a.bottom<=b.y || b.bottom<=a.y)
            // Chaque touche reste atteignable en son centre.
            val detector=KeyDetector(keys)
            for(k in keys)assertEquals(k,detector.keyAt(k.x+k.w/2f,k.y+k.h/2f))
        }
    }

    @Test fun strictSchema(){
        parse(valid)
        rejected(valid.replace("\"format\":1","\"format\":2"))
        rejected(valid.replace("}","" ).plus(",\"extra\":1}"))
        rejected(valid.replace("\"id\":\"t\"","\"id\":\"T!\""))
        rejected(valid.replace("[\"d\"]","[\"d\"],[\"e\"]"))
        rejected(valid.replace("\"d\"","\"dd\""))
        rejected(valid.replace("\"d\"","\"a\""))
        rejected(valid.replace("\"d\"","\"D\""))
        rejected(valid.replace("\"d\"","\"<\""))
        rejected(valid.replace("\"d\"","1"))
        rejected(valid.replace("\"d\"","true"))
        rejected(valid.replace("\"format\":1","\"format\":1.0"))
        rejected(valid.replace("\"format\":1","\"format\":01"))
        rejected(valid.replace("{\"format\":1,","{\"format\":1,\"format\":1,"))
        rejected("$valid x")
        rejected(valid.dropLast(1))
        rejected("[[[[[]]]]]")
        rejected("{\"id\":\"${"a".repeat(40)}\"}")
        rejected(valid.replace("\"T\"","\"T\\u0007\""))
        rejected(" ".repeat(LayoutParser.MAX_BYTES)+valid)
        assertEquals("é",parse(valid.replace("\"d\"","\"\\u00e9\"")).rows[2][0])
    }

    /** Fuzzing déterministe : un document muté est soit refusé par LayoutError, soit une disposition valide. */
    @Test fun mutationsNeverEscapeTheParser(){
        val seeds=files.map{it.readBytes()}+valid.toByteArray()
        val random=Random(20261006)
        val alphabet="{}[]\":,\\u0123456789abcdeéz-'; \n".toByteArray(Charsets.UTF_8)
        repeat(30_000){
            val data=seeds[random.nextInt(seeds.size)].toMutableList()
            repeat(1+random.nextInt(4)){
                if(data.isEmpty())return@repeat
                val at=random.nextInt(data.size)
                when(random.nextInt(4)){
                    0->data[at]=alphabet[random.nextInt(alphabet.size)]
                    1->data.add(at,alphabet[random.nextInt(alphabet.size)])
                    2->data.removeAt(at)
                    else->data[at]=random.nextInt(256).toByte()
                }
            }
            try{
                val layout=LayoutParser.parse(data.toByteArray())
                assertEquals(3,layout.rows.size)
                assertTrue(layout.rows.flatten().let{it.size==it.toSet().size})
                KeyboardLayouts.build(LayoutState(mode=0,letters=layout))
            }catch(_:LayoutError){}
        }
    }
}
