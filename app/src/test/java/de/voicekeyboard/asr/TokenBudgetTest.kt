package de.voicekeyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenBudgetTest {

    @Test fun zeroSeconds() = assertEquals(256, TokenBudget.budgetTokens(0.0))

    @Test fun oneSecond() = assertEquals(256, TokenBudget.budgetTokens(1.0))

    @Test fun sixteenSeconds() = assertEquals(256, TokenBudget.budgetTokens(16.0))

    @Test fun twentySeconds() = assertEquals(304, TokenBudget.budgetTokens(20.0))

    @Test fun threeHundredSeconds() = assertEquals(3664, TokenBudget.budgetTokens(300.0))

    @Test fun saturatesAt4096() = assertEquals(4096, TokenBudget.budgetTokens(1000.0))

    @Test fun budgetExceededHelper() {
        assertEquals(true, TokenBudget.budgetExceeded(256, 256))
        assertEquals(false, TokenBudget.budgetExceeded(255, 256))
    }
}
