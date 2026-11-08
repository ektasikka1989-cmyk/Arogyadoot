# A/B test: 6 Nov build vs today's build, same offline question, new chat each time.
# Saves screenshots, full logcat and a summary to C:\Arogyadoot\_work\tmp\ab for Claude to read.
# Run from C:\Arogyadoot:  powershell -ExecutionPolicy Bypass -File tools\ab_answer_test.ps1

$adb = "C:\Users\Vaibhav\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$out = "C:\Arogyadoot\_work\tmp\ab"
$pkg = "com.arogyadoot.app"
$question = "The baby is having high fever, what should I do?"
New-Item -ItemType Directory -Force $out | Out-Null

# Keep a copy of today's build so later builds cannot overwrite it during the test.
$today = "$out\today-app-debug.apk"
Copy-Item "C:\Arogyadoot\app\build\outputs\apk\debug\app-debug.apk" $today -Force
$apks = [ordered]@{
    "A_6nov"  = "C:\Arogyadoot-recovery-20261106\feature-audit-20261106-app-debug.apk"
    "B_today" = $today
}

& $adb devices
& $adb logcat -G 16M 2>$null   # bigger log buffer so the app's lines are not pruned

foreach ($name in $apks.Keys) {
    Write-Host ""
    Write-Host "=== $name : installing (keeps your data and models) ===" -ForegroundColor Cyan
    & $adb install -r -d $apks[$name]
    & $adb shell am force-stop $pkg
    & $adb logcat -c
    & $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
    Write-Host ""
    Write-Host "ON THE PHONE:" -ForegroundColor Yellow
    Write-Host "  1. Wait ~20 s for the app to finish loading."
    Write-Host "  2. Turn on Airplane mode (offline)."
    Write-Host "  3. Open 'Ask the ASHA guide', tap 'New' (fresh chat)."
    Write-Host "  4. TYPE (do not speak) exactly:"
    Write-Host "       $question" -ForegroundColor Green
    Write-Host "  5. Wait until the answer is completely finished, including any change after the check."
    Write-Host "  6. Scroll so the START of the answer is visible."
    Read-Host "Then press Enter here"
    & $adb shell screencap -p /sdcard/ab_1.png
    & $adb pull /sdcard/ab_1.png "$out\$name-1.png" | Out-Null
    Read-Host "Now scroll to the END of the answer on the phone, then press Enter"
    & $adb shell screencap -p /sdcard/ab_2.png
    & $adb pull /sdcard/ab_2.png "$out\$name-2.png" | Out-Null
    & $adb logcat -d -v time | Out-File -Encoding utf8 "$out\$name-logcat.txt"
    Select-String -Path "$out\$name-logcat.txt" -Pattern "POCKET_RAG|LOOP_GUARD|AROGYA_|WHISPER_|ANDROLLM_GROUNDED|FATAL|ANR in" |
        ForEach-Object { $_.Line } | Out-File -Encoding utf8 "$out\$name-summary.txt"
    Write-Host "Saved $name results." -ForegroundColor Cyan
}

Write-Host ""
Write-Host "Putting today's build back on the phone..." -ForegroundColor Cyan
& $adb install -r -d $today
Write-Host "DONE. Tell Claude: 'A/B finished'." -ForegroundColor Green
