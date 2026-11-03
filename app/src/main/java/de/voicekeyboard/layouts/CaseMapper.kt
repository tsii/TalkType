package de.voicekeyboard.layouts

/**
 * Language-sensitive case mapping. Turkish i→İ and ı→I are handled explicitly
 * (CASE_PATTERN must behave differently for tr than Locale.Upper).
 */
object CaseMapper {

    fun upper(text: String, caseLocale: String): String {
        val locale = parseLocale(caseLocale)
        return if (locale.language.equals("tr", ignoreCase = true) ||
            locale.language.equals("az", ignoreCase = true)
        ) {
            buildString {
                for (ch in text) {
                    when (ch) {
                        'i' -> append('İ')
                        'ı' -> append('I')
                        else -> append(ch.uppercaseChar())
                    }
                }
            }
        } else {
            text.uppercase(locale)
        }
    }

    fun lower(text: String, caseLocale: String): String {
        val locale = parseLocale(caseLocale)
        return if (locale.language.equals("tr", ignoreCase = true) ||
            locale.language.equals("az", ignoreCase = true)
        ) {
            buildString {
                for (ch in text) {
                    when (ch) {
                        'I' -> append('ı')
                        'İ' -> append('i')
                        else -> append(ch.lowercaseChar())
                    }
                }
            }
        } else {
            text.lowercase(locale)
        }
    }

    private fun parseLocale(tag: String): java.util.Locale {
        val parts = tag.split('-')
        return if (parts.size >= 2) java.util.Locale(parts[0], parts[1])
        else java.util.Locale(parts[0])
    }
}
