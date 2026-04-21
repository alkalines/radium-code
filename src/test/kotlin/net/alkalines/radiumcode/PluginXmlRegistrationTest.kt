package net.alkalines.radiumcode

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PluginXmlRegistrationTest {

    @Test
    fun `registers the OpenRouter provider under the plugin extension namespace`() {
        val pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))

        assertTrue(
            pluginXml.contains("""<extensions defaultExtensionNs="net.alkalines.radiumcode">"""),
            "plugin.xml must register custom provider extensions under net.alkalines.radiumcode"
        )
        assertTrue(
            pluginXml.contains("""<agentProvider implementationClass="net.alkalines.radiumcode.agent.providers.OpenRouterProvider"/>"""),
            "plugin.xml must register OpenRouterProvider"
        )
    }

    @Test
    fun `registers notification group and removes marketplace placeholders`() {
        val pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))

        assertTrue(
            pluginXml.contains("""<notificationGroup id="Radium Code""""),
            "plugin.xml must register the Radium Code notification group"
        )
        assertTrue(
            !pluginXml.contains("YourCompany") && !pluginXml.contains("Enter short description for your plugin here."),
            "plugin.xml must not keep template placeholders"
        )
    }

    @Test
    fun `registers icon mapping and packages the expected new ui assets`() {
        val pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))
        val mapping = Files.readString(Path.of("src/main/resources/RadiumCodeIconMappings.json"))

        assertTrue(
            pluginXml.contains("""<iconMapper mappingFile="RadiumCodeIconMappings.json"/>"""),
            "plugin.xml must register the icon mapping file"
        )
        assertTrue(
            mapping.contains("META-INF/expui/toolWindowIcon.svg") &&
                mapping.contains("META-INF/expui/toolWindowIcon_dark.svg"),
            "icon mapping must point classic tool window icons to expui assets"
        )

        val expectedAssets = listOf(
            "src/main/resources/META-INF/toolWindowIcon.svg",
            "src/main/resources/META-INF/toolWindowIcon_dark.svg",
            "src/main/resources/META-INF/expui/toolWindowIcon.svg",
            "src/main/resources/META-INF/expui/toolWindowIcon_dark.svg",
            "src/main/resources/META-INF/expui/toolWindowIcon@20x20.svg",
            "src/main/resources/META-INF/expui/toolWindowIcon@20x20_dark.svg",
        )
        expectedAssets.forEach { asset ->
            assertTrue(Files.exists(Path.of(asset)), "missing icon asset: $asset")
        }
    }
}
