package net.alkalines.radiumcode.agent.runtime

import net.alkalines.radiumcode.agent.il.IlToolDefinition

fun interface ToolCatalog {
    fun definitions(): List<IlToolDefinition>
}

object EmptyToolCatalog : ToolCatalog {
    override fun definitions(): List<IlToolDefinition> = emptyList()
}
