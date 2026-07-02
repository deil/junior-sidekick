# MCP Tools

Sidekick connects configured MCP servers from `agent.mcp.servers` and exposes their MCP tools as Koog tools.

## Configuration

Supported transports:

- `stdio` – starts a configured process and connects to its stdin/stdout.
- `sse` – connects to a running MCP server SSE endpoint.
- `streamable-http` – connects to a running MCP server streamable HTTP endpoint.

Each server defaults MCP request timeout handling to 30 seconds; override per server with `agent.mcp.servers[n].timeout-seconds`.

HTTP transports support static authorization header configuration with `auth=header`. Sidekick sends `auth-header.value` as the `Authorization` header value exactly as configured, so include the auth scheme yourself:

```properties
agent.mcp.servers[0].id=myserver
agent.mcp.servers[0].transport=streamable-http
agent.mcp.servers[0].url=https://example.com/mcp
agent.mcp.servers[0].auth=header
agent.mcp.servers[0].auth-header.value=Bearer your-token
```

For basic auth:

```properties
agent.mcp.servers[0].auth-header.value=Basic base64-user-pass
```

Example Grafana MCP over SSE:

```properties
agent.mcp.servers[0].id=grafana
agent.mcp.servers[0].transport=sse
agent.mcp.servers[0].url=http://localhost:8000/sse
```

Run Grafana MCP separately:

```bash
docker run --rm -p 8000:8000 \
  -e GRAFANA_URL \
  -e GRAFANA_SERVICE_ACCOUNT_TOKEN \
  grafana/mcp-grafana
```

Example Grafana MCP over stdio:

```properties
agent.mcp.servers[0].id=grafana
agent.mcp.servers[0].transport=stdio
agent.mcp.servers[0].command=uvx
agent.mcp.servers[0].args[0]=mcp-grafana
agent.mcp.servers[0].env.GRAFANA_URL=https://example.grafana.net
agent.mcp.servers[0].env.GRAFANA_SERVICE_ACCOUNT_TOKEN=...
```

## Runtime

Each MCP tool is exposed as a Koog tool named `${mcpServerId}__${toolName}`. The wrapper calls the original MCP tool name under the hood.

Each configured MCP server also gets local helper tools:

- `get_mcp_status_<server.id>` – checks whether the server is already connected.
- `connect_mcp_<server.id>` – starts the connection flow for that server.

## Workarounds

### Atlassian MCP

`createJiraIssue.additional_fields` and `editJiraIssue.fields` are free-form JSON objects. Koog currently mishandles those unconstrained object schemas and tends to emit `{}` instead of the intended custom fields.

Sidekick works around this by exposing those parameters to the model as JSON-encoded strings. Before forwarding the MCP call, Sidekick parses the string back into a JSON object so the Atlassian MCP server still receives the shape it expects.

### Jenkins MCP

For MCP servers whose `id` starts with `jenkins`, Sidekick excludes build-mutating tools from registration: `triggerBuild`, `updateBuild`, `rebuildBuild`, and `replayBuild`. Each excluded tool is logged with its server id and tool name.

## Key Files

- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/mcp/Mcp.kt` – MCP configuration, connection, and registry wiring.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/mcp/McpTools.kt` – Koog tool wrappers for MCP calls and MCP status.

## Related

- Kotlin MCP SDK - [io.modelcontextprotocol:kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)
