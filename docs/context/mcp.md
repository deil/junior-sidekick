# MCP Tools

Sidekick connects configured MCP servers for each turn from `agent.mcp.servers` configuration and exposes their MCP tools as Koog tools.

## Configuration

Supported transports:

- `stdio` – starts a configured process and connects to its stdin/stdout.
- `sse` – connects to a running MCP server SSE endpoint.
- `streamable-http` – connects to a running MCP server streamable HTTP endpoint.

Each server defaults MCP request timeout handling to 30 seconds; override per server with `agent.mcp.servers[n].timeout-seconds`.

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

`DefaultMcpServers` connects configured MCP servers through the Kotlin MCP SDK. `SidekickAgent` opens those connections before Koog execution, adds their tool registries to the local Sidekick registry, and closes the connections after the turn.

Each MCP tool is exposed as a Koog tool named `${mcpServerId}__${toolName}`. The wrapper calls the original MCP tool name under the hood.

Each configured MCP server also gets a local status tool named `get_mcp_status_<server.id>` with description `Check whether the requester is already connected to <server.id> MCP server`. It returns `{ "server_id": "<server.id>", "connected": true|false }` by checking the turn context's connected MCP servers.

## Key Files

- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/koog/SidekickAgent.kt` – owns MCP connection lifecycle around Koog turn execution.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnContext.kt` – carries turn-scoped connected MCP servers.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/mcp/Mcp.kt` – MCP configuration, connection, and registry wiring.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/mcp/McpTools.kt` – Koog tool wrappers for MCP calls and MCP status.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/ToolRegistryFactory.kt` – builds local-only Sidekick tools.
