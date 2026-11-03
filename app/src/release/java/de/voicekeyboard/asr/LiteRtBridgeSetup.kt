package de.voicekeyboard.asr

/**
 * V13 release: the [LiteRtBridge] provider stays null — documented reason:
 * variant B (LITERT_CPU) does not pass the quality gate
 * (docs/VARIANT_B_QUALITY.md: 50.2 % word identity, 33.2 % CED,
 * language tipping German→English at 400 ms of leading silence).
 * Therefore EngineFactory falls back, as documented, to ParakeetAsrEngine (ONNX_CPU);
 * ModelVariant.LITERT_CPU remains as a type.
 */
fun installLiteRtBridge() {
    // Deliberately NOTHING: the provider stays null (the release has no LiteRT classes).
}
