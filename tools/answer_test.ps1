# Answer test on the CURRENT build only. Saves screenshots, log and a summary per question
# to C:\Arogyadoot\_work\tmp\answers for Claude to read.
# Run from C:\Arogyadoot:  powershell -ExecutionPolicy Bypass -File tools\answer_test.ps1

$adb = "C:\Users\Vaibhav\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$out = "C:\Arogyadoot\_work\tmp\answers"
$pkg = "com.arogyadoot.app"
$apk = "C:\Arogyadoot\app\build\outputs\apk\debug\app-debug.apk"
# Fixed set: always the same 5 questions, typed exactly, each in a new chat (compare like with like).
$questions = @(
    "The baby is having high fever, what should I do?",
    "What are the danger signs in a newborn?",
    "bacche ko dast ho raha hai kya karein",
    "wat shud i do if pregnent lady has bleding",
    "ORS kaise banaye"
)
New-Item -ItemType Directory -Force $out | Out-Null

& $adb devices
Write-Host "Installing the current build (keeps your data and models)..." -ForegroundColor Cyan
& $adb install -r $apk
& $adb logcat -G 16M 2>$null
& $adb shell am force-stop $pkg
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "Wait ~20 s for the app to load, turn on Airplane mode, open 'Ask the ASHA guide'." -ForegroundColor Yellow
Read-Host "Press Enter when ready"

$i = 0
foreach ($q in $questions) {
    $i++
    & $adb logcat -c
    Write-Host ""
    Write-Host "=== Question $i ===" -ForegroundColor Cyan
    Write-Host "  Tap 'New' (fresh chat), then TYPE exactly:"
    Write-Host "     $q" -ForegroundColor Green
    Write-Host "  Wait until the answer is completely finished (can take ~2 min)."
    Read-Host "  Then scroll to the START of the answer and press Enter"
    & $adb shell screencap -p /sdcard/q.png; & $adb pull /sdcard/q.png "$out\q$i-1.png" | Out-Null
    Read-Host "  Scroll to the MIDDLE of the answer and press Enter"
    & $adb shell screencap -p /sdcard/q.png; & $adb pull /sdcard/q.png "$out\q$i-2.png" | Out-Null
    Read-Host "  Scroll to the END of the answer and press Enter"
    & $adb shell screencap -p /sdcard/q.png; & $adb pull /sdcard/q.png "$out\q$i-3.png" | Out-Null
    & $adb logcat -d -v time | Out-File -Encoding utf8 "$out\q$i-logcat.txt"
    Select-String -Path "$out\q$i-logcat.txt" -Pattern "POCKET_RAG|LOOP|KB_AGE|KB_SPELLING|KB_HINDI|KB_QUESTION_REWRITE|KB_PROOF|AROGYA_|FLIPPED|ANDROLLM|FATAL|ANR in" |
        ForEach-Object { $_.Line } | Out-File -Encoding utf8 "$out\q$i-summary.txt"
    Write-Host "  Saved question $i." -ForegroundColor Cyan
}
# --- 6: a question the guides do not cover (offline): expect the labelled "Not from your ASHA guides" answer
$i = 6
& $adb logcat -c
Write-Host ""
Write-Host "=== Question 6 (not in the guides, still offline) ===" -ForegroundColor Cyan
Write-Host "  Tap 'New', then TYPE exactly:"
Write-Host "     How can I make my phone battery last longer?" -ForegroundColor Green
Write-Host "  Expect: an answer under 'Not from your ASHA guides', and a highlighted Check online button."
Read-Host "  When finished, scroll to the START and press Enter"
& $adb shell screencap -p /sdcard/q.png; & $adb pull /sdcard/q.png "$out\q6-1.png" | Out-Null
& $adb logcat -d -v time | Out-File -Encoding utf8 "$out\q6-logcat.txt"

# --- 7: Check online (needs internet + Groq key in Settings)
$i = 7
& $adb logcat -c
Write-Host ""
Write-Host "=== Step 7: Check online ===" -ForegroundColor Cyan
Write-Host "  Turn Airplane mode OFF (internet on). Tap 'New', TYPE exactly:"
Write-Host "     The baby is having high fever, what should I do?" -ForegroundColor Green
Write-Host "  When the offline answer is done, tap 'Check online' under it and wait for the Online tab."
Read-Host "  Then scroll to the START of the Online answer and press Enter"
& $adb shell screencap -p /sdcard/q.png; & $adb pull /sdcard/q.png "$out\q7-1.png" | Out-Null
Read-Host "  Scroll to the END of the Online answer and press Enter"
& $adb shell screencap -p /sdcard/q.png; & $adb pull /sdcard/q.png "$out\q7-2.png" | Out-Null
& $adb logcat -d -v time | Out-File -Encoding utf8 "$out\q7-logcat.txt"

Write-Host "DONE. Tell Claude: 'answer test finished'." -ForegroundColor Green
