package net.alkalines.radiumcode

import kotlin.test.Test
import kotlin.test.assertNull

class AgentToolWindowFactoryTest {

    @Test
    fun `omits the compose tab title to avoid duplicating the stripe title`() {
        assertNull(AgentToolWindowChrome.composeTabTitle())
    }
}
