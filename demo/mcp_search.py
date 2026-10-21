#!/usr/bin/env python3
"""Minimal MCP stdio client: starts an MCP server command, calls ``search_context`` once, prints a summary.

Usage: mcp_search.py "<query>" [--limit N] [--raw] -- <server command...>
Example: python3 demo/mcp_search.py "duplicate charge root cause" -- docker compose -f demo/compose.yaml run --rm -T scos mcp

Server stderr (startup logs) goes to this process's stderr. ``--raw`` prints the tool result JSON as returned.
"""
import argparse
import json
import subprocess
import sys


def main():
    if "--" not in sys.argv:
        raise SystemExit(__doc__)
    split = sys.argv.index("--")
    parser = argparse.ArgumentParser()
    parser.add_argument("query")
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument("--raw", action="store_true")
    args = parser.parse_args(sys.argv[1:split])
    command = sys.argv[split + 1 :]

    server = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8")

    def send(message):
        server.stdin.write(json.dumps(message, ensure_ascii=False) + "\n")
        server.stdin.flush()

    def response(request_id):
        for line in server.stdout:
            message = json.loads(line)
            if message.get("id") == request_id:
                if "error" in message:
                    raise SystemExit(f"MCP error: {message['error']}")
                return message["result"]
        raise SystemExit("MCP server exited without a response (see its stderr above)")

    send({"jsonrpc": "2.0", "id": 1, "method": "initialize",
          "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                     "clientInfo": {"name": "scos-demo-client", "version": "1"}}})
    info = response(1)["serverInfo"]
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    tools = [t["name"] for t in response(2)["tools"]]
    send({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
          "params": {"name": "search_context", "arguments": {"query": args.query, "limit": args.limit}}})
    result = response(3)
    server.stdin.close()
    server.wait(timeout=60)

    text = result["content"][0]["text"]
    if args.raw:
        print(text)
        return
    payload = json.loads(text)
    print(f"server: {info['name']} {info['version']}  tools: {', '.join(tools)}")
    print(f'search_context("{args.query}", limit={args.limit}) -> {len(payload["hits"])} hits')
    for i, hit in enumerate(payload["hits"], 1):
        print(f"  {i}. [{hit['source']}] {hit['title']}  score={hit['score']}  doc={hit['documentId']}")
    print(f"freshness: {json.dumps(payload['freshness'])}")


if __name__ == "__main__":
    main()
