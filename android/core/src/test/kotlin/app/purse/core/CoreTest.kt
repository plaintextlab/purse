package app.purse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun parses() {
        assertEquals(213500, Money.parseMinor("2135.00"))
        assertEquals(213550, Money.parseMinor("Rs.2,135.5"))
        assertEquals(150, Money.parseMinor("1.5"))
        assertEquals(-4200, Money.parseMinor("-42"))
        assertNull(Money.parseMinor("abc"))
    }

    @Test
    fun formats() {
        assertEquals("₹12,34,567", Money.format(123456700, "₹", true))
        assertEquals("$1,234,567.05", Money.format(123456705, "$", false))
        assertEquals("-₹500", Money.format(-50000, "₹", true))
        assertEquals("+₹5", Money.format(500, "₹", true, signed = true))
        assertEquals("2.1k", Money.compact(213500, true))
        assertEquals("2.5L", Money.compact(25000000, true))
        assertEquals("999", Money.compact(99900, true))
        assertEquals("2135.5", Money.toPlain(213550))
    }
}

class GlobTest {
    @Test
    fun wildcards() {
        assertTrue(Glob.matches("*HDFC*", "AD-HDFCBK-T"))
        assertTrue(Glob.matches("*hdfc*", "VM-HDFCBK"))
        assertTrue(Glob.matches("*ICICI*, *HDFC*", "JD-HDFCBK"))
        assertFalse(Glob.matches("*SBI*", "AD-HDFCBK-T"))
        assertFalse(Glob.matches("HDFC", "AD-HDFCBK-T"))
        assertTrue(Glob.matches("??-HDFCBK", "AX-HDFCBK"))
    }
}

class SmsPatternTest {
    private val hdfc = """
        Sent Rs.2135.00
        From HDFC Bank A/C *6254
        To Valve Corporation
        On 28/09/26
        Ref 627107954271
        Not You?
        Call 18002586161/SMS BLOCK UPI to 7308080808
    """.trimIndent()

    @Test
    fun generatesTemplateFromSample() {
        val t = SmsPattern.fromSample(hdfc, "2135.00", "6254", "Valve Corporation").getOrThrow()
        assertEquals("Sent Rs.{amount}\nFrom HDFC Bank A/C *{account}\nTo {merchant}", t)
        val m = assertNotNull(SmsPattern.compile(t).match(hdfc))
        assertEquals(213500, m.amountMinor)
        assertEquals("6254", m.account)
        assertEquals("Valve Corporation", m.merchant)
    }

    @Test
    fun matchesOtherMessagesOfSameShape() {
        val p = SmsPattern.compile("Sent Rs.{amount}\nFrom HDFC Bank A/C *{account}\nTo {merchant}")
        val other = "Sent Rs.49.50\r\nFrom HDFC Bank A/C *6254\r\nTo ZOMATO LTD\r\nOn 01/10/26\r\nRef 1234567"
        val m = assertNotNull(p.match(other))
        assertEquals(4950, m.amountMinor)
        assertEquals("ZOMATO LTD", m.merchant)
        assertNull(p.match("Received Rs.100 in HDFC Bank A/C *6254"))
    }

    @Test
    fun wildcardInMiddle() {
        val p = SmsPattern.compile("INR {amount} debited from A/c XX{account} on {*} Info: {merchant}. Avl")
        val m = assertNotNull(p.match("INR 1,250.00 debited from A/c XX1234 on 05-Oct-26 Info: UPI-SWIGGY. Avl Bal INR 5,000"))
        assertEquals(125000, m.amountMinor)
        assertEquals("1234", m.account)
        assertEquals("UPI-SWIGGY", m.merchant)
    }

    @Test
    fun volatileBitsBecomeWildcards() {
        val sample = "Rs 500 credited to a/c XX9876 on 12/10/26 by NEFT ref 998877665544. Bal Rs 10,000"
        val t = SmsPattern.fromSample(sample, "500", "9876", null).getOrThrow()
        assertEquals("Rs {amount} credited to a/c XX{account} on {*} by NEFT ref {*}. Bal Rs 10,000", t)
        assertNotNull(SmsPattern.compile(t).match(sample))
    }

    @Test
    fun reportsMissingValues() {
        assertTrue(SmsPattern.fromSample(hdfc, "999", null, null).isFailure)
        assertNull(SmsPattern.compileOrNull("no placeholders"))
    }

    @Test
    fun guessesValues() {
        val (a, acc, mer) = SmsPattern.guess(hdfc)
        assertEquals("2135.00", a)
        assertEquals("6254", acc)
        assertEquals("Valve Corporation", mer)
    }

    @Test
    fun accountHints() {
        assertTrue(AccountHint.matches("XX6254", "6254"))
        assertTrue(AccountHint.matches("*6254", "1234, 6254"))
        assertTrue(AccountHint.matches("6254", "50100012346254"))
        assertFalse(AccountHint.matches("6254", "1234"))
        assertFalse(AccountHint.matches("54", "6254"))
    }
}

class SyncTest {
    @Test
    fun roundTripsAndMerges() {
        val s = Snapshot(
            accounts = listOf(AccountRec("a1", "Cash", "CASH", updatedAt = 1)),
            categories = listOf(CategoryRec("c1", "Food", "EXPENSE", "🍔", updatedAt = 1)),
            transactions = listOf(
                TxnRec("t1", "EXPENSE", 100, "a1", categoryId = "c1", timestamp = 1_790_000_000_000, updatedAt = 5),
                TxnRec("t2", "INCOME", 200, "a1", timestamp = 1_700_000_000_000, updatedAt = 5),
            ),
        )
        val files = SyncCodec.encode(s)
        assertTrue(files.keys.containsAll(listOf("meta.json", "accounts.json", "transactions/2026-09.json", "transactions/2023-11.json")))
        val back = SyncCodec.decode(files)
        assertEquals(s.accounts, back.accounts)
        assertEquals(s.transactions.toSet(), back.transactions.toSet())
        assertEquals(s, SyncCodec.decodeBundle(SyncCodec.encodeBundle(s)))

        val local = listOf(TxnRec("t1", "EXPENSE", 100, "a1", timestamp = 0, updatedAt = 10))
        val remote = listOf(
            TxnRec("t1", "EXPENSE", 999, "a1", timestamp = 0, updatedAt = 9),
            TxnRec("t3", "EXPENSE", 1, "a1", timestamp = 0, updatedAt = 1),
        )
        assertEquals(listOf("t3"), SyncMerge.incoming(local, remote).map { it.uuid })
    }
}
