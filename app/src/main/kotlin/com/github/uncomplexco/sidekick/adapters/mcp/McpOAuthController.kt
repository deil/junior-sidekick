package com.github.uncomplexco.sidekick.adapters.mcp

import com.github.uncomplexco.sidekick.application.tools.mcp.McpOAuthService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class McpOAuthController(
    private val oauth: McpOAuthService,
) {
    @GetMapping("/mcp/oauth/callback", produces = [MediaType.TEXT_HTML_VALUE])
    fun callback(
        @RequestParam state: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) error: String?,
    ): String {
        if (error != null) return html("MCP OAuth failed: $error")
        if (code.isNullOrBlank()) return html("MCP OAuth failed: missing authorization code")

        val serverId = oauth.completeCallback(state, code)
        return html("MCP server $serverId connected. You can return to chat.")
    }

    private fun html(message: String): String =
        """
        <!doctype html>
        <html>
        <body><p>${escapeHtml(message)}</p></body>
        </html>
        """.trimIndent()

    private fun escapeHtml(message: String): String =
        message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
