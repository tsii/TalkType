package de.voicekeyboard.asr.litert

import org.tensorflow.lite.Interpreter

/**
 * V11a: thin signature runner helper for the B pipeline.
 *
 * CRITICAL (R2 insight): the flat `Interpreter.run()` delivers `log_mel`
 * CONSTANTLY (−16.636 — artifact of the Invoke interface); only the SIGNATURE path
 * delivers living values. LiteRT 2.3.2 (Java) offers:
 *   Interpreter.runSignature(Map<String,Object>, Map<String,Object>[, signatureKey])
 *   Interpreter.getSignatureKeys()/getSignatureInputs(key)/getSignatureOutputs(key)
 *
 * Inputs/outputs are set/fetched BY NAME; unknown names ⇒ typed error
 * with the name list (nothing guessed).
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
                throw SignatureException("model has no signature")
            }
            return keys.first()
        }
    }

    /** Allowed input names (from the model signature, not guessed). */
    val inputNames: List<String> = interpreter.getSignatureInputs(signatureKey)?.toList()
        ?: throw SignatureException("no inputs for signature '$signatureKey'")

    /** Allowed output names. */
    val outputNames: List<String> = interpreter.getSignatureOutputs(signatureKey)?.toList()
        ?: throw SignatureException("no outputs for signature '$signatureKey'")

    /**
     * Runs the signature: inputs by name, outputs by name.
     * @throws SignatureException on an unknown name (with the name list).
     */
    fun run(inputs: Map<String, Any>): Map<String, Any> {
        val unknownIn = inputs.keys - inputNames.toSet()
        if (unknownIn.isNotEmpty()) {
            throw SignatureException(
                "unknown inputs $unknownIn — allowed: $inputNames",
            )
        }
        val outputs = mutableMapOf<String, Any>()
        for (name in outputNames) outputs[name] = Any()
        interpreter.runSignature(inputs, outputs, signatureKey)
        return outputs
    }
}
