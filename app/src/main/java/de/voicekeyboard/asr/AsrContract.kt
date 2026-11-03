package de.voicekeyboard.asr

/**
 * Binding constants and tensor names from docs/ASR_CONTRACT.md (Rev 2).
 * NO guessed names — the only source is the measured contract.
 */
object AsrContract {

    // ---- Tensor names: nemo128.onnx (mel frontend) ----
    const val MEL_IN_WAVEFORMS = "waveforms"          // float32 [B, N], 16 kHz, [-1,1]
    const val MEL_IN_WAVEFORMS_LENS = "waveforms_lens" // int64 [B]
    const val MEL_OUT_FEATURES = "features"           // float32 [B, 128, T]
    const val MEL_OUT_FEATURES_LENS = "features_lens"  // int64 [B]

    // ---- Tensor names: encoder-model.int8.onnx (conformer encoder) ----
    const val ENC_IN_AUDIO_SIGNAL = "audio_signal"    // float32 [B, 128, T]
    const val ENC_IN_LENGTH = "length"                // int64 [B]
    const val ENC_OUT_OUTPUTS = "outputs"             // float32 [B, 1024, T/8]
    const val ENC_OUT_ENCODED_LENGTHS = "encoded_lengths" // int64 [B]

    // ---- Tensor names: decoder_joint-model.int8.onnx (PredNet + joint, TDT) ----
    const val DEC_IN_ENCODER_OUTPUTS = "encoder_outputs" // float32 [B, 1024, 1]
    const val DEC_IN_TARGETS = "targets"             // int32 [B, 1] (last label)
    const val DEC_IN_TARGET_LENGTH = "target_length" // int32 [B] (value 1)
    const val DEC_IN_INPUT_STATES_1 = "input_states_1" // float32 [2, B, 640] (LSTM h)
    const val DEC_IN_INPUT_STATES_2 = "input_states_2" // float32 [2, B, 640] (LSTM c)
    const val DEC_OUT_OUTPUTS = "outputs"             // float32 [B, T, U, 8198]
    const val DEC_OUT_PREDNET_LENGTHS = "prednet_lengths" // int32 [B] (unused)
    const val DEC_OUT_OUTPUT_STATES_1 = "output_states_1" // float32 [2, B, 640]
    const val DEC_OUT_OUTPUT_STATES_2 = "output_states_2" // float32 [2, B, 640]

    // ---- Logit split (§2): 8198 = 8193 vocab + 5 duration classes ----
    const val VOCAB_SIZE = 8193        // token logits index range [0..8192]
    const val NUM_DURATIONS = 5        // duration classes {0,1,2,3,4} encoder frames
    const val LOGIT_COUNT = 8198       // 8193 + 5

    // ---- Blank / frames ----
    const val BLANK = 8192            // <blk>
    const val SUBSAMPLING = 8          // config.json: subsampling_factor
    const val FRAME_MS = 80            // 1 encoder frame = 8 × 10 ms = 80 ms
    const val MEL_BINS = 128          // features_size

    // ---- Decoder dimensions ----
    const val ENCODER_DIM = 1024       // encoder output channels
    const val PRED_HIDDEN = 640        // LSTM state size ([2, B, 640])
    const val MAX_SYMBOLS_PER_FRAME = 10 // default max_tokens_per_step (missing in Config, §1)

    /** Special token IDs 0..4: <unk>, <pad>, <|nospeech|>, ``, <|startoftranscript|> (§2.4, row 1 of the table). */
    val SPECIAL_TOKEN_IDS: IntRange = 0..4

    /** ID range of the digits "0".."9" — regular vocabulary, must NEVER be filtered (§2.4). */
    val DIGIT_TOKEN_IDS: IntRange = 234..243
}
