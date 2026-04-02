package com.aichallenge.day2.agent.server

data class HttpApiServerConfig(
    val port: Int,
    val apiBaseUrl: String,
    val apiKey: String,
    val apiModel: String,
    val apiTemperature: Double?,
    val wireAppRagBaseUrl: String,
    val apiLogFilePath: String?,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): HttpApiServerConfig {
            val port = environment["PORT"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: 8080
            val apiBaseUrl = environment.requiredValue("AGENT_API_BASE_URL").trimEnd('/')
            val apiKey = environment.requiredValue("AGENT_API_KEY")
            val apiModel = environment.requiredValue("AGENT_API_MODEL")
            val apiTemperature = environment["AGENT_API_TEMPERATURE"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toDoubleOrNull()
                ?.also { value ->
                    require(value in 0.0..2.0) {
                        "AGENT_API_TEMPERATURE must be a number in the range 0..2."
                    }
                }
            val wireAppRagBaseUrl = environment.requiredValue("WIRE_APP_RAG_BASE_URL").trimEnd('/')
            val apiLogFilePath = environment["OPENAI_API_LOG_FILE"]
                ?.trim()
                ?.let { value -> value.ifEmpty { null } }

            return HttpApiServerConfig(
                port = port,
                apiBaseUrl = apiBaseUrl,
                apiKey = apiKey,
                apiModel = apiModel,
                apiTemperature = apiTemperature,
                wireAppRagBaseUrl = wireAppRagBaseUrl,
                apiLogFilePath = apiLogFilePath,
            )
        }

        private fun Map<String, String>.requiredValue(name: String): String {
            return this[name]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("$name must be set.")
        }
    }
}
