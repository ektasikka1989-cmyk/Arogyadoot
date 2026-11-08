import com.example.arfas.ArogyaSpeechLang
var sf = 0; var sp = 0
fun s(c: Boolean, m: String) { if (c) { sp++; println("PASS $m") } else { sf++; println("FAIL $m") } }
fun main() {
    val L = ArogyaSpeechLang
    val urduTop = L.parse("hi=0.300 ur=0.450 en=0.150 top=ur")
    s(urduTop != null && L.choose(urduTop) == "hi", "urdu-detected Hindi speech is written as Hindi")
    s(L.choose(L.parse("hi=0.700 ur=0.100 en=0.150 top=hi")) == "hi", "clear Hindi stays Hindi")
    s(L.choose(L.parse("hi=0.050 ur=0.020 en=0.900 top=en")) == "en", "clear English is English")
    s(L.choose(L.parse("hi=0.300 ur=0.050 en=0.600 top=en")) == "hi", "Hinglish leaning English still Hindi (no translation)")
    s(L.choose(L.parse("hi=0.080 ur=0.010 en=0.300 top=es")) == "en", "English share counts within hi+ur+en only")
    s(L.choose(null) == "hi", "no detection falls back to Hindi")
    s(L.parse("") == null && L.parse("top=hi") == null && L.parse("hi=0 ur=0 en=0") == null, "unusable detector lines rejected")
    s(L.hasArabicScript("بچے کو بخار ہے"), "Urdu script detected")
    s(!L.hasArabicScript("बच्चे को बुखार है, BP 120"), "Devanagari + English is not Urdu")
    s(!L.hasArabicScript("Fever since two days"), "English is not Urdu")
    s(L.hasArabicScript("बुखार ہے"), "mixed output with one Urdu word is caught")
    println(if (sf == 0) "ALL $sp PASS" else "$sf FAILED, $sp passed")
    if (sf > 0) kotlin.system.exitProcess(1)
}
