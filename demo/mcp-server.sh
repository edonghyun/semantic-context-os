#!/usr/bin/env bash
# Starts the demo SCOS MCP server over stdio, for tools that take one executable (harness `evaluate --server-command`).
# Needs `db` and `embedder` running (Quickstart step 2) and the corpus imported (step 4).
exec docker compose -f "$(dirname "$0")/compose.yaml" run --rm -T scos mcp
