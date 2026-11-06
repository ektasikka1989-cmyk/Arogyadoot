import com.example.arfas.ArogyaDictationText
var df = 0; var dp = 0
fun d(c: Boolean, m: String) { if (c) { dp++; println("PASS $m") } else { df++; println("FAIL $m") } }
fun main() {
    val T = ArogyaDictationText
    var e = T.insertAtCursor("", 0, 0, "fever")
    d(e.text == "fever" && e.selStart == 5, "empty field takes dictation")
    e = T.insertAtCursor("fever", 5, 5, "since morning")
    d(e.text == "fever since morning" && e.selStart == 19, "append adds one space")
    e = T.insertAtCursor("fever since", 5, 5, "high")
    d(e.text == "fever high since" && e.selStart == 10, "middle insert spaces both sides")
    e = T.insertAtCursor("fever", 0, 0, "High")
    d(e.text == "High fever" && e.selStart == 5, "start insert spaces after")
    e = T.insertAtCursor("fever ", 6, 6, "cold")
    d(e.text == "fever cold", "no double space when one exists")
    e = T.insertAtCursor("fever cold", 0, 5, "cough")
    d(e.text == "cough cold" && e.selStart == 5, "selection is replaced")
    e = T.insertAtCursor("kept", 4, 4, "   ")
    d(e.text == "kept" && e.selStart == 4, "blank dictation preserves text")
    e = T.insertAtCursor("बुखार", 5, 5, "खांसी")
    d(e.text == "बुखार खांसी", "hindi insert spaced")
    e = T.insertAtCursor("a", 99, 99, "b")
    d(e.text == "a b" && e.selStart == 3, "out-of-range cursor clamped")
    // Whisper non-speech markers must never reach a field (a "[BLANK_AUDIO]" turn once started a pipeline).
    d(T.clean("[BLANK_AUDIO]") == "", "blank-audio marker removed")
    d(T.clean(" (coughing) fever [Music]  since  morning ") == "fever since morning", "tags removed, spaces collapsed")
    e = T.insertAtCursor("fever", 5, 5, "[BLANK_AUDIO]")
    d(e.text == "fever" && e.selStart == 5, "marker-only dictation leaves text untouched")
    e = T.insertAtCursor("fever", 5, 5, "[BLANK_AUDIO] since morning")
    d(e.text == "fever since morning", "marker stripped before insert")
    println(if (df == 0) "ALL $dp PASS" else "$df FAILED, $dp passed")
    if (df > 0) kotlin.system.exitProcess(1)
}
