package com.example.arfas

object AndroVoiceModels {
    // The English-only streaming ASR model (voice/asr, 252 MB) was removed on 8 Nov 2026:
    // nothing loaded it. Dictation uses Whisper (voice/stt/whisper).

    const val TTS_MODEL = "voice/tts/model.onnx"
    const val TTS_TOKENS = "voice/tts/tokens.txt"
    const val TTS_LEXICON = "voice/tts/lexicon.txt"
    const val TTS_DATA_DIR = "voice/tts/espeak-ng-data"

    const val SAMPLE_RATE = 16_000
    const val FEATURE_DIM = 80
}
