package net.alkalines.radiumcode.agent.providers

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.jar.JarFile

internal object ProviderDiscovery {
    private val logger = Logger.getInstance(ProviderDiscovery::class.java)

    fun discover(
        packageName: String = AgentProvider::class.java.packageName,
        classLoader: ClassLoader = AgentProvider::class.java.classLoader,
    ): List<AgentProvider> {
        val packagePath = packageName.replace('.', '/')
        val classNames = classLoader.getResources(packagePath).toList()
            .flatMap { url ->
                when (url.protocol) {
                    "file" -> classNamesFromDirectory(packageName, url.path)
                    "jar" -> classNamesFromJarUrl(packagePath, url)
                    else -> emptyList()
                }
            }
            .distinct()

        return classNames
            .mapNotNull { instantiateProvider(it, classLoader) }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun classNamesFromDirectory(packageName: String, encodedPath: String): List<String> {
        val root = File(URLDecoder.decode(encodedPath, StandardCharsets.UTF_8))
        if (!root.isDirectory) {
            return emptyList()
        }
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "class" && '$' !in it.name }
            .map { file ->
                val relativeName = root.toPath().relativize(file.toPath()).toString()
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
                "$packageName.$relativeName"
            }
            .toList()
    }

    private fun classNamesFromJarUrl(packagePath: String, url: URL): List<String> {
        val external = url.toExternalForm()
        val jarPath = external
            .removePrefix("jar:")
            .substringBefore("!")
            .removePrefix("file:")
        val decodedPath = URLDecoder.decode(jarPath, StandardCharsets.UTF_8)
        return JarFile(decodedPath).use { jar ->
            classNamesFromJarEntries(packagePath, jar)
        }
    }

    private fun classNamesFromJarEntries(packagePath: String, jar: JarFile): List<String> =
        jar.entries().asSequence()
            .map { it.name }
            .filter { name ->
                name.startsWith("$packagePath/") &&
                    name.endsWith(".class") &&
                    '$' !in name.substringAfterLast('/')
            }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .toList()

    private fun instantiateProvider(className: String, classLoader: ClassLoader): AgentProvider? {
        val clazz = runCatching { Class.forName(className, false, classLoader) }
            .onFailure { logger.warn("Failed to inspect provider class $className", it) }
            .getOrNull()
            ?: return null

        if (!AgentProvider::class.java.isAssignableFrom(clazz) || clazz == AgentProvider::class.java) {
            return null
        }
        if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) {
            return null
        }

        return runCatching {
            val constructor = clazz.asSubclass(AgentProvider::class.java).getDeclaredConstructor()
            if (!constructor.canAccess(null)) {
                constructor.isAccessible = true
            }
            constructor.newInstance()
        }.onFailure {
            logger.warn("Failed to instantiate provider class $className", it)
        }.getOrNull()
    }
}
