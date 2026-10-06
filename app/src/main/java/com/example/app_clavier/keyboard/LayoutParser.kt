package com.example.app_clavier.keyboard

/** Les trois rangées de lettres d'une disposition (AZERTY, BÉPO…). Le reste du clavier est commun. */
class LetterLayout(val id:String,val name:String,val rows:List<List<String>>){
    companion object {
        /** Disposition intégrée : utilisée si un fichier livré était illisible (échec fermé vers l'AZERTY). */
        val AZERTY=LetterLayout("azerty","AZERTY",listOf("azertyuiop","qsdfghjklm","wxcvbn'").map{r->r.map{it.toString()}})
    }
}

class LayoutError(message:String):Exception(message)

/**
 * Parseur strict des dispositions JSON (phase 6, ADR-0027). Écrit à la main, sans bibliothèque : il ne sait lire
 * que le petit sous-ensemble de JSON dont le schéma a besoin, et refuse tout le reste.
 *
 * Schéma (tous les champs obligatoires, aucun autre accepté, pas de doublon) :
 * ```
 * { "format": 1, "id": "bepo", "name": "BÉPO", "rows": [["b","é",…], […], […]] }
 * ```
 *  - au plus 4 Kio, profondeur 3, chaînes de 32 caractères au plus ;
 *  - `id` : [a-z0-9_]{1,24} ; `name` : 1 à 32 caractères imprimables ;
 *  - exactement 3 rangées de 1 à 11, 1 à 11 et 1 à 9 touches ;
 *  - chaque touche : un seul caractère, lettre minuscule ou l'un de « ' - ; », sans doublon.
 * Aucune disposition n'est chargée depuis l'extérieur : seuls les fichiers livrés dans l'APK passent ici.
 */
object LayoutParser {
    const val MAX_BYTES=4_096
    private const val MAX_DEPTH=3
    private const val MAX_STRING=32
    private val ROW_LIMITS=intArrayOf(11,11,9)
    private val ALLOWED_SYMBOLS=setOf("'","-",";")
    private val FIELDS=setOf("format","id","name","rows")

    fun parse(bytes:ByteArray):LetterLayout {
        if(bytes.size>MAX_BYTES)throw LayoutError("fichier trop grand")
        val text=try{Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()}
            catch(_:java.nio.charset.CharacterCodingException){throw LayoutError("UTF-8 invalide")}
        return validate(Reader(text).document())
    }

    private fun validate(root:Any):LetterLayout {
        val obj=root as? Map<*,*> ?: throw LayoutError("objet attendu")
        if(obj.keys!=FIELDS)throw LayoutError("champs attendus : $FIELDS")
        if(obj["format"]!=1L)throw LayoutError("format non pris en charge")
        val id=obj["id"] as? String ?: throw LayoutError("id : chaîne attendue")
        if(!id.matches(Regex("[a-z0-9_]{1,24}")))throw LayoutError("id invalide")
        val name=obj["name"] as? String ?: throw LayoutError("name : chaîne attendue")
        if(name.isEmpty() || name.any{it.isISOControl()})throw LayoutError("name invalide")
        val rows=obj["rows"] as? List<*> ?: throw LayoutError("rows : tableau attendu")
        if(rows.size!=3)throw LayoutError("3 rangées attendues")
        val seen=HashSet<String>()
        val parsed=rows.mapIndexed{r,row->
            val keys=row as? List<*> ?: throw LayoutError("rangée ${r+1} : tableau attendu")
            if(keys.isEmpty() || keys.size>ROW_LIMITS[r])throw LayoutError("rangée ${r+1} : 1 à ${ROW_LIMITS[r]} touches")
            keys.map{k->
                val key=k as? String ?: throw LayoutError("touche : chaîne attendue")
                if(key.codePointCount(0,key.length)!=1)throw LayoutError("touche : un seul caractère")
                val cp=key.codePointAt(0)
                val letter=Character.isLetter(cp) && Character.isLowerCase(cp)
                if(!letter && key !in ALLOWED_SYMBOLS)throw LayoutError("touche non autorisée")
                if(!seen.add(key))throw LayoutError("touche en double")
                key
            }
        }
        return LetterLayout(id,name,parsed)
    }

    /** Lecteur JSON minimal : objets, tableaux, chaînes et entiers seulement. */
    private class Reader(private val s:String){
        private var i=0
        fun document():Any {
            val v=value(0);space()
            if(i!=s.length)throw LayoutError("contenu après le document")
            return v
        }
        private fun space(){while(i<s.length && (s[i]==' ' || s[i]=='\n' || s[i]=='\r' || s[i]=='\t'))i++}
        private fun peek():Char {space();if(i>=s.length)throw LayoutError("fin inattendue");return s[i]}
        private fun expect(c:Char){if(peek()!=c)throw LayoutError("« $c » attendu");i++}
        private fun value(depth:Int):Any {
            if(depth>MAX_DEPTH)throw LayoutError("trop d'imbrication")
            return when(val c=peek()){
                '{'->obj(depth+1)
                '['->array(depth+1)
                '"'->string()
                else->if(c=='-' || c in '0'..'9')integer() else throw LayoutError("valeur non autorisée")
            }
        }
        private fun obj(depth:Int):Map<String,Any> {
            expect('{')
            val out=LinkedHashMap<String,Any>()
            if(peek()=='}'){i++;return out}
            while(true){
                val key=string()
                expect(':')
                if(out.put(key,value(depth))!=null)throw LayoutError("champ en double")
                when(peek()){','->i++;'}'->{i++;return out};else->throw LayoutError("« , » ou « } » attendu")}
            }
        }
        private fun array(depth:Int):List<Any> {
            expect('[')
            val out=ArrayList<Any>()
            if(peek()==']'){i++;return out}
            while(true){
                if(out.size>=16)throw LayoutError("tableau trop long")
                out.add(value(depth))
                when(peek()){','->i++;']'->{i++;return out};else->throw LayoutError("« , » ou « ] » attendu")}
            }
        }
        private fun string():String {
            expect('"')
            val out=StringBuilder()
            while(true){
                if(i>=s.length)throw LayoutError("chaîne non terminée")
                val c=s[i++]
                when {
                    c=='"'->return out.toString()
                    c=='\\'->{
                        if(i>=s.length)throw LayoutError("échappement incomplet")
                        when(val e=s[i++]){
                            '"','\\','/'->out.append(e)
                            'u'->{
                                if(i+4>s.length)throw LayoutError("échappement incomplet")
                                val code=s.substring(i,i+4).toIntOrNull(16) ?: throw LayoutError("échappement invalide")
                                out.append(code.toChar());i+=4
                            }
                            else->throw LayoutError("échappement non autorisé")
                        }
                    }
                    c<' '->throw LayoutError("caractère de contrôle")
                    else->out.append(c)
                }
                if(out.length>MAX_STRING)throw LayoutError("chaîne trop longue")
            }
        }
        private fun integer():Long {
            val start=i
            if(s[i]=='-')i++
            while(i<s.length && s[i] in '0'..'9')i++
            val digits=s.substring(start,i)
            if(digits.length>9 || digits=="-" || (digits.length>1 && digits.trimStart('-').startsWith('0')))throw LayoutError("entier invalide")
            if(i<s.length && (s[i]=='.' || s[i]=='e' || s[i]=='E'))throw LayoutError("entier attendu")
            return digits.toLong()
        }
    }
}

/** Dispositions livrées dans l'APK (assets/layouts), lues une fois. Un fichier illisible donne l'AZERTY intégré. */
object LayoutCatalog {
    val ids=linkedMapOf("azerty" to "AZERTY","bepo" to "BÉPO","qwerty" to "QWERTY","qwertz" to "QWERTZ","dvorak" to "Dvorak")
    private val cache=HashMap<String,LetterLayout>()
    @Synchronized fun get(c:android.content.Context,id:String?):LetterLayout {
        val key=id?.takeIf{it in ids} ?: return LetterLayout.AZERTY
        return cache.getOrPut(key){
            runCatching{
                c.assets.open("layouts/$key.json").use{input->
                    val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                    while(true){val n=input.read(buffer);if(n<0)break;bytes.write(buffer,0,n);if(bytes.size()>LayoutParser.MAX_BYTES)break}
                    LayoutParser.parse(bytes.toByteArray())
                }
            }.getOrDefault(LetterLayout.AZERTY)
        }
    }
}
