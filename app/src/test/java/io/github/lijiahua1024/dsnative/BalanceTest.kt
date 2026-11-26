package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.api.balanceCurrencySymbol
import io.github.lijiahua1024.dsnative.api.parseAccountBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceTest {

    @Test
    fun parsesBalanceInfosFirstItem() {
        val body = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.34","granted_balance":"2.00","topped_up_balance":"10.34"}]}"""
        val b = parseAccountBalance(body)
        assertTrue(b.isAvailable)
        assertEquals("CNY", b.currency)
        assertEquals("12.34", b.totalBalance)
        assertEquals("2.00", b.grantedBalance)
        assertEquals("10.34", b.toppedUpBalance)
    }

    @Test
    fun fallsBackToFlatFields() {
        val body = """{"is_available":false,"currency":"USD","total_balance":"1.5","granted_balance":"0.5","topped_up_balance":"1.0"}"""
        val b = parseAccountBalance(body)
        assertFalse(b.isAvailable)
        assertEquals("USD", b.currency)
        assertEquals("1.5", b.totalBalance)
        assertEquals("0.5", b.grantedBalance)
    }

    @Test
    fun blankAmountsDefaultZero() {
        val b = parseAccountBalance("""{"balance_infos":[{"currency":""}]}""")
        assertNull(b.currency)
        assertEquals("0.00", b.totalBalance)
        assertEquals("0.00", b.grantedBalance)
        assertEquals("0.00", b.toppedUpBalance)
    }

    @Test
    fun currencySymbol() {
        assertEquals("¥", balanceCurrencySymbol("CNY"))
        assertEquals("$", balanceCurrencySymbol("USD"))
        assertEquals("", balanceCurrencySymbol("EUR"))
        assertEquals("", balanceCurrencySymbol(null))
    }
}
