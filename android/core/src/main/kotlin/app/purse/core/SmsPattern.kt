package app.purse.core

/** Values pulled out of an SMS by a template. */
data class SmsMatch(
    val amountMinor: Long,
    val account: String?,
    val merchant: String?,
)

/**
 * A body template is the SMS text with the variable parts replaced by placeholders:
 *
 *     Sent Rs.{amount}
 *     From HDFC Bank A/C *{account}
 *     To {merchant}
 *
 * - `{amount}`   the transaction amount (required)
 * - `{account}`  account / card digits, used to pick the account (optional)
 * - `{merchant}` payee or payer, saved as the note (optional)
 * - `{*}`        anything (dates, reference numbers, ...)
 *
 * Literal text must appear in the SMS; any run of whitespace matches any run of
 * whitespace and case is ignored. The template only has to match a part of the
 * message, so trailing boilerplate can be left out.
 */
class SmsPattern private constructor(
    val template: String,
    private val regex: Regex,
    private val groups: List<String>,
) {
    fun match(body: String): SmsMatch? {
        val m = regex.find(body) ?: return null
        var amount: Long? = null
        var account: String? = null
        var merchant: String? = null
        groups.forEachIndexed { i, name ->
            val v = m.groupValues[i + 1].trim()
            when (name) {
                AMOUNT -> if (amount == null) amount = Money.parseMinor(v)
                ACCOUNT -> if (account == null) account = v.ifEmpty { null }
                MERCHANT -> if (merchant == null) merchant = v.ifEmpty { null }
            }
        }
        return SmsMatch(amount ?: return null, account, merchant)
    }

    companion object {
        const val AMOUNT = "amount"
        const val ACCOUNT = "account"
        const val MERCHANT = "merchant"

        private val PLACEHOLDER = Regex("""\{([A-Za-z*]+)\}""")
        private val WHITESPACE = Regex("""\s+""")
        private const val AMOUNT_RE = """(\d[\d,]*(?:\.\d{1,2})?)"""
        private const val ACCOUNT_RE = """([A-Za-z0-9*]+)"""

        /** Throws [IllegalArgumentException] when the template has no `{amount}`. */
        fun compile(template: String): SmsPattern {
            val t = template.trim()
            val matches = PLACEHOLDER.findAll(t).toList()
            require(matches.any { it.groupValues[1].lowercase() == AMOUNT }) { "Template needs an {amount} placeholder" }
            val sb = StringBuilder()
            val groups = mutableListOf<String>()
            var last = 0
            matches.forEachIndexed { idx, m ->
                sb.append(literal(t.substring(last, m.range.first)))
                val atEnd = idx == matches.lastIndex && t.substring(m.range.last + 1).isBlank()
                when (val name = m.groupValues[1].lowercase()) {
                    AMOUNT -> {
                        sb.append(AMOUNT_RE); groups += name
                    }
                    ACCOUNT -> {
                        sb.append(ACCOUNT_RE); groups += name
                    }
                    MERCHANT -> {
                        // Lazy when followed by more text, otherwise take the rest of the line.
                        sb.append(if (atEnd) "(.+)" else "(.+?)"); groups += name
                    }
                    else -> if (!atEnd) sb.append("""[\s\S]*?""")
                }
                last = m.range.last + 1
            }
            sb.append(literal(t.substring(last)))
            return SmsPattern(t, Regex(sb.toString(), RegexOption.IGNORE_CASE), groups)
        }

        fun compileOrNull(template: String): SmsPattern? =
            try {
                compile(template)
            } catch (e: IllegalArgumentException) {
                null
            }

        private fun literal(s: String): String =
            s.split(WHITESPACE).joinToString("""\s+""") { if (it.isEmpty()) "" else Regex.escape(it) }

        /**
         * Builds a template from a sample SMS and the values the user says are in it.
         * Returns the template, or an error message describing what was not found.
         */
        fun fromSample(sample: String, amount: String, account: String?, merchant: String?): Result<String> {
            val text = sample.replace("\r\n", "\n").trim()
            val marks = mutableListOf<Triple<Int, Int, String>>()

            fun mark(value: String?, token: String, label: String): String? {
                val v = value?.trim().orEmpty()
                if (v.isEmpty()) return null
                var idx = text.indexOf(v)
                while (idx >= 0 && marks.any { idx < it.second && idx + v.length > it.first }) {
                    idx = text.indexOf(v, idx + 1)
                }
                if (idx < 0) return "$label \"$v\" was not found in the sample"
                marks += Triple(idx, idx + v.length, token)
                return null
            }

            if (amount.isBlank()) return Result.failure(IllegalArgumentException("Enter the amount as it appears in the sample"))
            listOfNotNull(
                mark(amount, AMOUNT, "Amount"),
                mark(account, ACCOUNT, "Account"),
                mark(merchant, MERCHANT, "Merchant"),
            ).firstOrNull()?.let { return Result.failure(IllegalArgumentException(it)) }

            marks.sortBy { it.first }
            // Keep everything up to the end of the line holding the last value; the rest
            // is usually boilerplate that banks change often.
            val lastEnd = marks.last().second
            val cut = text.indexOf('\n', lastEnd).let { if (it < 0) text.length else it }

            val out = StringBuilder()
            var pos = 0
            for ((start, end, token) in marks) {
                out.append(wildcardVolatile(text.substring(pos, start)))
                out.append('{').append(token).append('}')
                pos = end
            }
            out.append(wildcardVolatile(text.substring(pos, cut)))
            val result = out.toString().replace(Regex("""\{\*\}(\s*\{\*\})+"""), "{*}").trim()
            return Result.success(result)
        }

        private val VOLATILE = Regex(
            listOf(
                """\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4}""",            // 28/09/26
                """\d{1,2}[\- ][A-Za-z]{3}[\- ]\d{2,4}""",          // 28-Sep-26
                """\d{1,2}:\d{2}(?::\d{2})?""",                     // 14:32[:10]
                """\d{5,}""",                                       // reference & phone numbers
            ).joinToString("|"),
        )

        private fun wildcardVolatile(s: String): String = s.replace(VOLATILE, "{*}")

        /** Best-effort guesses to prefill the "where is the amount/account/merchant" fields. */
        fun guess(sample: String): Triple<String?, String?, String?> {
            val amount = Regex("""(?:rs\.?|inr|₹)\s*(\d[\d,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
                .find(sample)?.groupValues?.get(1)
            val account = Regex(
                """(?:a/?c|acct|account|card)\s*(?:no\.?)?\s*(?:ending\s*(?:with|in)?)?\s*[*xX]*(\d{3,})""",
                RegexOption.IGNORE_CASE,
            ).find(sample)?.groupValues?.get(1)
            val merchant = Regex(
                """(?:^|\s)(?:to|at|towards|from\s+vpa)\s+([^\n]+?)(?=\s+on\s|\s+ref|\.\s|\n|$)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
            ).findAll(sample)
                .map { it.groupValues[1].trim() }
                .firstOrNull { cand -> !Regex("""\d{5,}""").containsMatchIn(cand) && account?.let { cand.contains(it) } != true }
            return Triple(amount, account, merchant)
        }
    }
}

/** Matches extracted account digits ("XX6254", "*6254") against an account's hints ("6254, 4321"). */
object AccountHint {
    fun matches(extracted: String, hints: String): Boolean {
        val a = extracted.filter { it.isDigit() }
        if (a.length < 3) return false
        return hints.split(',', ';', ' ').map { h -> h.filter { it.isDigit() } }.any { b ->
            b.length >= 3 && (a.endsWith(b) || b.endsWith(a))
        }
    }
}
