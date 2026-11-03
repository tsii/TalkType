package de.voicekeyboard.layouts

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Strict validation per DESIGN.md 7.2 (no additional fields). Uses org.json
 * (available on Android and via Robolectric on the JVM).
 */
object LayoutParser {

    data class Result(val asset: LayoutAsset)

    class LayoutException(message: String) : Exception(message)

    fun parse(json: String): LayoutAsset {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw LayoutException("Not valid JSON: ${e.message}")
        }
        if (root.opt("schemaVersion") !is Int) {
            throw LayoutException("schemaVersion missing or not a number")
        }
        val schemaVersion = root.getInt("schemaVersion")
        if (schemaVersion != 1) {
            throw LayoutException("Unknown schemaVersion: $schemaVersion (expected 1)")
        }
        requireString(root, "id")
        val id = root.getString("id")
        if (!Regex("^[a-z0-9-]+$").matches(id)) {
            throw LayoutException("id '$id' does not match ^[a-z0-9-]+\$")
        }
        val language = parseLanguage(root.getJSONObjectOrThrow("language"))

        if (!root.has("layers")) throw LayoutException("layers missing")
        val layersObj = root.getJSONObject("layers")
        val known = layerKeys(layersObj)
        for (name in LayoutLayers.REQUIRED) {
            if (!known.contains(name)) {
                throw LayoutException("Layer '$name' missing in layers")
            }
        }
        if (known != LayoutLayers.REQUIRED.toSet()) {
            throw LayoutException("Unknown layers: ${(known - LayoutLayers.REQUIRED.toSet()).sorted()}")
        }
        val layers = LayoutLayers.REQUIRED.associateWith { name ->
            parseLayer(name, layersObj.getJSONObject(name))
        }

        val deadKeys = parseDeadKeys(root.optJSONObject("deadKeys") ?: JSONObject())

        validateSemantics(id, layers, deadKeys)
        return LayoutAsset(1, id, language, layers, deadKeys)
    }

    private fun layerKeys(obj: JSONObject): Set<String> =
        obj.keys().asSequence().toList().toSet()

    private fun parseLanguage(obj: JSONObject): LayoutLanguage {
        val allowed = setOf("tag", "label", "caseLocale", "direction")
        rejectUnknown(obj, allowed, "language")
        for (f in listOf("tag", "label", "caseLocale", "direction")) requireString(obj, f)
        val direction = obj.getString("direction")
        if (direction != "ltr" && direction != "rtl") {
            throw LayoutException("direction '$direction' is not ltr/rtl")
        }
        val tag = obj.getString("tag")
        if (!Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$").matches(tag)) {
            throw LayoutException("language.tag '$tag' is not a valid BCP-47 tag")
        }
        return LayoutLanguage(tag, obj.getString("label"), obj.getString("caseLocale"), direction)
    }

    private fun parseLayer(name: String, obj: JSONObject): LayoutLayer {
        rejectUnknown(obj, setOf("rows"), "layers.$name")
        if (!obj.has("rows")) throw LayoutException("layers.$name.rows missing")
        val rowsArr = obj.getJSONArray("rows")
        if (rowsArr.length() == 0) throw LayoutException("layers.$name.rows is empty")
        val rows = mutableListOf<List<LayoutKey>>()
        val ids = mutableSetOf<String>()
        for (r in 0 until rowsArr.length()) {
            val rowArr = rowsArr.getJSONArray(r)
            if (rowArr.length() == 0) throw LayoutException("layers.$name.rows[$r] is empty")
            val row = mutableListOf<LayoutKey>()
            for (k in 0 until rowArr.length()) {
                val key = parseKey("$name.rows[$r][$k]", rowArr.getJSONObject(k))
                if (!ids.add(key.id)) {
                    throw LayoutException("Duplicate key ID '${key.id}' in layer '$name'")
                }
                row.add(key)
            }
            rows.add(row)
        }
        return LayoutLayer(name, rows)
    }

    private fun parseKey(path: String, obj: JSONObject): LayoutKey {
        val allowed = setOf("id", "label", "width", "kind", "value", "caps", "alternates", "hint", "cellActions")
        rejectUnknown(obj, allowed, path)
        for (f in listOf("id", "kind")) requireString(obj, f, path)
        val kindStr = obj.getString("kind")
        // F27a1: MARGIN (stock geometry) — edge surface: width only, no content.
        // Tolerant like the other fields: label/value missing or empty;
        // a NON-empty label/value is rejected (no silent key).
        if (kindStr == "margin") {
            for (f in listOf("label", "value")) {
                if (obj.has(f) && obj.getString(f).isNotBlank()) {
                    throw LayoutException("$path: MARGIN key must not carry $f")
                }
            }
            if (obj.has("alternates") && obj.getJSONArray("alternates").length() > 0) {
                throw LayoutException("$path: MARGIN key must not carry alternates")
            }
            if (!obj.has("width")) throw LayoutException("$path.width missing")
            val mWidth = obj.getDouble("width")
            if (mWidth <= 0.0) throw LayoutException("$path.width must be > 0 (is $mWidth)")
            return LayoutKey(obj.getString("id"), "", mWidth, KeyKind.MARGIN, "", null, emptyList(), null, emptyList())
        }
        for (f in listOf("label", "value")) requireString(obj, f, path)
        if (!obj.has("width")) throw LayoutException("$path.width missing")
        val width = obj.getDouble("width")
        if (width <= 0.0) throw LayoutException("$path.width must be > 0 (is $width)")
        val kind = when (kindStr) {
            "text" -> KeyKind.TEXT
            "action" -> KeyKind.ACTION
            "dead" -> KeyKind.DEAD
            else -> throw LayoutException("$path.kind '$kindStr' is unknown (text/action/dead/margin)")
        }
        val value = obj.getString("value")
        if (kind == KeyKind.ACTION && value !in KeyActions.ALL) {
            throw LayoutException("$path: unknown action '$value' (allowlist: ${KeyActions.ALL.sorted()})")
        }
        val caps = if (obj.has("caps")) obj.getString("caps") else null
        val alternates = if (obj.has("alternates")) {
            val arr = obj.getJSONArray("alternates")
            (0 until arr.length()).map { arr.getString(it) }
        } else emptyList()
        // F23a1: optional hint — must NEVER kill the keyboard: missing/empty/
        // non-string/too long (> 2 characters) becomes null, no exception.
        val hint = if (obj.has("hint")) {
            val h = obj.optString("hint", "")
            if (h.isEmpty() || h.length > 2) null else h
        } else null
        // F24e1: cellActions — action names for menu cells, parallel to alternates
        // ("" = normal text cell). Tolerant like hint: missing/wrong type -> empty,
        // length is trimmed to the length of alternates; action names are
        // NOT validated — the parser deliberately stays silent, the service branch
        // decides whether an action does anything.
        val cellActions = if (obj.has("cellActions")) {
            val arr = obj.optJSONArray("cellActions")
            if (arr == null) emptyList()
            else (0 until minOf(arr.length(), alternates.size)).map { arr.optString(it, "") }
        } else emptyList()
        return LayoutKey(obj.getString("id"), obj.getString("label"), width, kind, value, caps, alternates, hint, cellActions)
    }

    private fun parseDeadKeys(obj: JSONObject): Map<String, DeadKey> {
        val out = mutableMapOf<String, DeadKey>()
        for (id in obj.keys().asSequence().toList()) {
            val entry = obj.getJSONObject(id)
            rejectUnknown(entry, setOf("spacing", "combining", "compose"), "deadKeys.$id")
            if (!entry.has("spacing")) throw LayoutException("deadKeys.$id.spacing missing")
            val compose = mutableMapOf<String, String>()
            if (entry.has("compose")) {
                val c = entry.getJSONObject("compose")
                for (base in c.keys().asSequence().toList()) compose[base] = c.getString(base)
            }
            out[id] = DeadKey(
                id,
                entry.getString("spacing"),
                if (entry.has("combining")) entry.getString("combining") else null,
                compose,
            )
        }
        return out
    }

    /**
     * Semantics: row sums identical across all layers per row index, and
     * text keys of the base layer have shift counterparts (same ID in the shift layer).
     */
    private fun validateSemantics(id: String, layers: Map<String, LayoutLayer>, deadKeys: Map<String, DeadKey>) {
        // Row count and width sums apply pairwise (schema: base/shift equal,
        // symbols/symbolsShift equal — the symbol layers may have a different row count and
        // width distribution than the letters, e.g. AZERTY variants).
        for ((a, b) in listOf("shift" to "base", "symbolsShift" to "symbols")) {
            val la = layers.getValue(a)
            val lb = layers.getValue(b)
            if (la.rows.size != lb.rows.size) {
                throw LayoutException(
                    "Layer '$a' has ${la.rows.size} rows, '$b' has ${lb.rows.size}",
                )
            }
            for (r in lb.rows.indices) {
                val sumA = la.rows[r].sumOf { it.width }
                val sumB = lb.rows[r].sumOf { it.width }
                if (kotlin.math.abs(sumA - sumB) > 1e-9) {
                    throw LayoutException(
                        "Row-sum deviation: layer '$a', row $r: $sumA != $sumB (layer '$b')",
                    )
                }
            }
        }
        // Text keys in base need a shift counterpart with the same ID.
        val shiftIds = layers.getValue("shift").rows.flatten().map { it.id }.toSet()
        for (key in layers.getValue("base").rows.flatten()) {
            if (key.kind == KeyKind.TEXT && key.id !in shiftIds) {
                throw LayoutException("Text key '${key.id}' in base has no shift counterpart in layer 'shift'")
            }
        }
        // Dead references must be resolvable.
        for (layer in layers.values) {
            for (row in layer.rows) for (key in row) {
                if (key.kind == KeyKind.DEAD && key.value !in deadKeys) {
                    throw LayoutException("Deadkey '${key.value}' (key '${key.id}') not defined in deadKeys")
                }
            }
        }
    }

    private fun requireString(obj: JSONObject, field: String, path: String = field) {
        if (!obj.has(field)) throw LayoutException("$path missing")
    }

    private fun rejectUnknown(obj: JSONObject, allowed: Set<String>, path: String) {
        val unknown = obj.keys().asSequence().toList().filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            throw LayoutException("Unknown fields in $path: $unknown (allowed: ${allowed.sorted()})")
        }
    }

    private fun JSONObject.getJSONObjectOrThrow(name: String): JSONObject {
        if (!has(name)) throw LayoutException("$name missing")
        return getJSONObject(name)
    }
}
