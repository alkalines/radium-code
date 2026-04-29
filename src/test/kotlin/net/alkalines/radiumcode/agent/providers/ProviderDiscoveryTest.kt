package net.alkalines.radiumcode.agent.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import net.alkalines.radiumcode.agent.config.ProviderSettings
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderDiscoveryTest {

    @Test
    fun `discovers concrete AgentProvider subclasses from a package`() {
        val providers = ProviderDiscovery.discover(
            packageName = "net.alkalines.radiumcode.agent.providers",
            classLoader = javaClass.classLoader,
        )

        assertTrue(providers.any { it is OpenRouterProvider })
        assertTrue(providers.any { it is ReflectionFixtureProvider })
        assertFalse(providers.any { it::class.java.simpleName.contains("Abstract") })
    }

    @Test
    fun `returns providers sorted by display name`() {
        val providers = ProviderDiscovery.discover(
            packageName = "net.alkalines.radiumcode.agent.providers",
            classLoader = javaClass.classLoader,
        )

        assertEquals(
            providers.map { it.displayName }.sortedBy { it.lowercase() },
            providers.map { it.displayName },
        )
    }
}

@Suppress("unused")
abstract class AbstractReflectionFixtureProvider : AgentProvider()

class ReflectionFixtureProvider : AgentProvider() {
    override val providerId: String = "reflection-fixture"
    override val displayName: String = "Reflection Fixture"

    override suspend fun fetchAvailableModels(settings: ProviderSettings): Result<List<IlModelDescriptor>> =
        Result.success(emptyList())

    override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = emptyFlow()
}
