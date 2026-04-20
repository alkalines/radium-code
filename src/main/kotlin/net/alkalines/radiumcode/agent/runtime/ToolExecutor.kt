package net.alkalines.radiumcode.agent.runtime

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alkalines.radiumcode.agent.il.IlToolCallBlock

fun interface ToolExecutor {
    suspend fun execute(toolCall: IlToolCallBlock): ToolExecutionResult
}

data class ToolExecutionResult(
    val resultJson: String,
    val isError: Boolean,
)

class InMemoryToolExecutor : ToolExecutor {
    override suspend fun execute(toolCall: IlToolCallBlock): ToolExecutionResult {
        val payload = buildJsonObject {
            put("ok", true)
            put("tool", toolCall.toolName ?: "tool")
            put("arguments", toolCall.argumentsJson)
        }
        return ToolExecutionResult(payload.toString(), false)
    }
}
