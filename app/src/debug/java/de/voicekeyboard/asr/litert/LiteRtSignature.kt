package de.voicekeyboard.asr.litert

import org.tensorflow.lite.Interpreter

/**
 * V11a: Dünner Signature-Runner-Helfer für die B-Pipeline.
 *
 * KRITISCH (R2-Erkenntnis): Der flache `Interpreter.run()` liefert `log_mel`
 * KONSTANT (−16,636 — Artefakt des Invoke-Interfaces); nur der SIGNATURE-Pfad
 * liefert lebende Werte. LiteRT 2.3.2 (Java) bietet:
 *   Interpreter.runSignature(Map<String,Object>, Map<String,Object>[, signatureKey])
 *   Interpreter.getSignatureKeys()/getSignatureInputs(key)/getSignatureOutputs(key)
 *
 * Eingänge/Ausgänge werden NACH NAME gesetzt/geholt; unbekannte Namen ⇒
 * typisierter Fehler mit der Namensliste (nichts geraten).
 */
class LiteRtSignature(
    interpreter: Interpreter,
    private val signatureKey: String = firstKeyOf(interpreter),
) {

    class SignatureException(message: String) : IllegalStateException(message)

    private val interpreter = interpreter

    companion object {
        private fun firstKeyOf(it: Interpreter): String {
            val keys = it.signatureKeys ?: emptyArray()
            if (keys.isNullOrEmpty()) {
                throw SignatureException("Modell hat keine Signature")
            }
            return keys.first()
        }
    }

    /** Erlaubte Eingangsnamen (aus der Modell-Signatur, nicht geraten). */
    val inputNames: List<String> = interpreter.getSignatureInputs(signatureKey)?.toList()
        ?: throw SignatureException("Keine Eingänge für Signature '$signatureKey'")

    /** Erlaubte Ausgangsnamen. */
    val outputNames: List<String> = interpreter.getSignatureOutputs(signatureKey)?.toList()
        ?: throw SignatureException("Keine Ausgänge für Signature '$signatureKey'")

    /**
     * Führt die Signature aus: Eingänge nach Name, Ausgänge nach Name.
     * @throws SignatureException bei unbekanntem Namen (mit Namensliste).
     */
    fun run(inputs: Map<String, Any>): Map<String, Any> {
        val unknownIn = inputs.keys - inputNames.toSet()
        if (unknownIn.isNotEmpty()) {
            throw SignatureException(
                "Unbekannte Eingänge $unknownIn — erlaubt: $inputNames",
            )
        }
        val outputs = mutableMapOf<String, Any>()
        for (name in outputNames) outputs[name] = Any()
        interpreter.runSignature(inputs, outputs, signatureKey)
        return outputs
    }
}
