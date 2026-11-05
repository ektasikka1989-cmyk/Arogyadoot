@echo off
rem Logs only (no screen capture): ArogyaDoot AI / retrieval tags -> app_log.txt
cd /d "%~dp0"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
"%ADB%" logcat -d -v time -s PocketRagEngine:* GenericKnowledgeContext:* GenericKnowledgePack:* LlamaCppCore:* LocalModelManager:* ArogyaDemo:* MLKitProcessor:* ArogyaRegistry:* ArogyaCapture:* MetaModelApi:* MetaOnline:* PrivacyGate:* PocketRagLocalGenerator:* PocketRagEvidenceAssembler:* AndroBaselineRuntime:* FixSightFeatures:* MetaModelStore:* WhisperJNI:* AndroWhisperSTT:* WhisperLib:* AudioRecorder:* AndroidRuntime:E > app_log.txt 2>&1
echo DONE %date% %time% >> app_log.txt
