package app.purse.core

/**
 * Case-insensitive wildcard matching for SMS senders.
 * `*` matches any run of characters, `?` a single character. Several patterns
 * can be separated by commas: "*HDFC*, *HDFCBK*".
 */
object Glob {
    fun matches(patterns: String, text: String): Boolean =
        patterns.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { toRegex(it).matches(text.trim()) }

    fun toRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (c in pattern) {
            when {
                c == '*' -> sb.append(".*")
                c == '?' -> sb.append('.')
                c.isLetterOrDigit() -> sb.append(c)
                else -> sb.append('\\').append(c)
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
