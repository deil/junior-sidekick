# MCP Tools

Sidekick can add Koog MCP tool registries to each turn from `agent.mcp.servers` configuration.

## Configuration

Supported transports:

- `stdio` – starts a configured process and connects to its stdin/stdout.
- `sse` – connects to a running MCP server SSE endpoint.
- `streamable-http` – connects to a running MCP server streamable HTTP endpoint.

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

`ConfiguredMcpToolRegistryProvider` loads configured MCP servers through Koog's `McpToolRegistryProvider` and merges the resulting tool registries with native Sidekick tools.

The MCP registry is cached after first build so stdio servers are not respawned on every turn.

## Key Files

- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/mcp/McpTools.kt` – MCP configuration and Koog registry loading.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/TurnToolRegistryFactory.kt` – merges configured MCP tools into the turn tool registry.
