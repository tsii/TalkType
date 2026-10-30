package de.voicekeyboard.asr

/**
 * Verbindliche Konstanten und Tensornamen aus docs/ASR_CONTRACT.md (Rev 2).
 * KEINE geratenen Namen — einzige Quelle ist der gemessene Vertrag.
 */
object AsrContract {

    // ---- Tensornamen: nemo128.onnx (Mel-Frontend) ----
    const val MEL_IN_WAVEFORMS = "waveforms"          // float32 [B, N], 16 kHz, [-1,1]
    const val MEL_IN_WAVEFORMS_LENS = "waveforms_lens" // int64 [B]
    const val MEL_OUT_FEATURES = "features"           // float32 [B, 128, T]
    const val MEL_OUT_FEATURES_LENS = "features_lens"  // int64 [B]

    // ---- Tensornamen: encoder-model.int8.onnx (Conformer-Encoder) ----
    const val ENC_IN_AUDIO_SIGNAL = "audio_signal"    // float32 [B, 128, T]
    const val ENC_IN_LENGTH = "length"                // int64 [B]
    const val ENC_OUT_OUTPUTS = "outputs"             // float32 [B, 1024, T/8]
    const val ENC_OUT_ENCODED_LENGTHS = "encoded_lengths" // int64 [B]

    // ---- Tensornamen: decoder_joint-model.int8.onnx (PredNet + Joint, TDT) ----
    const val DEC_IN_ENCODER_OUTPUTS = "encoder_outputs" // float32 [B, 1024, 1]
    const val DEC_IN_TARGETS = "targets"             // int32 [B, 1] (letztes Label)
    const val DEC_IN_TARGET_LENGTH = "target_length" // int32 [B] (Wert 1)
    const val DEC_IN_INPUT_STATES_1 = "input_states_1" // float32 [2, B, 640] (LSTM h)
    const val DEC_IN_INPUT_STATES_2 = "input_states_2" // float32 [2, B, 640] (LSTM c)
    const val DEC_OUT_OUTPUTS = "outputs"             // float32 [B, T, U, 8198]
    const val DEC_OUT_PREDNET_LENGTHS = "prednet_lengths" // int32 [B] (unbenutzt)
    const val DEC_OUT_OUTPUT_STATES_1 = "output_states_1" // float32 [2, B, 640]
    const val DEC_OUT_OUTPUT_STATES_2 = "output_states_2" // float32 [2, B, 640]

    // ---- Logit-Aufteilung (§2): 8198 = 8193 Vokabeln + 5 Duration-Klassen ----
    const val VOCAB_SIZE = 8193        // Token-Logits-Indexbereich [0..8192]
    const val NUM_DURATIONS = 5        // Duration-Klassen {0,1,2,3,4} Encoderframes
    const val LOGIT_COUNT = 8198       // 8193 + 5

    // ---- Blank / Frames ----
    const val BLANK = 8192            // <blk>
    const val SUBSAMPLING = 8          // config.json: subsampling_factor
    const val FRAME_MS = 80            // 1 Encoderframe = 8 × 10 ms = 80 ms
    const val MEL_BINS = 128          // features_size

    // ---- Decoder-Dimensionen ----
    const val ENCODER_DIM = 1024       // Encoder-Output-Kanäle
    const val PRED_HIDDEN = 640        // LSTM-State-Größe ([2, B, 640])
    const val MAX_SYMBOLS_PER_FRAME = 10 // Default max_tokens_per_step (fehlt in Config, §1)

    /** Sondertoken-IDs 0..4: <unk>, <pad>, <|nospeech|>, ``, <|startoftranscript|> (§2.4, Zeile 1 der Tabelle). */
    val SPECIAL_TOKEN_IDS: IntRange = 0..4

    /** ID-Bereich der Ziffern "0".."9" — reguläres Vokabular, darf NIEMALS gefiltert werden (§2.4). */
    val DIGIT_TOKEN_IDS: IntRange = 234..243
}
