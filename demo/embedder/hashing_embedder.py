#!/usr/bin/env python3
"""Keyless demo embedder: answers the Gemini ``batchEmbedContents`` request shape with hashing vectors.

This is a stand-in for local demos only. The SCOS server can only call the Gemini embedding API today, so the
demo points ``SCOS_GEMINI_BASE_URL`` at this process and passes a placeholder key. Nothing leaves the machine.

Vectors are character-bigram hashes (768 dims, L2-normalised) -- the same algorithm as the backend test double
``io.scos.demo.DemoProviders``. They capture spelling overlap, not meaning: good enough to exercise the vector
channel end to end, not a substitute for a real embedding model.

Usage: hashing_embedder.py [--host 0.0.0.0] [--port 8000]
"""
import argparse
import json
import math
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIM = 768
EMBED_PATH = "/v1beta/models/gemini-embedding-001:batchEmbedContents"
JAVA_WHITESPACE = re.compile(r"[ \t\n\x0b\f\r]+")  # Java regex \s


def java_string_hash(s):
    """Java String.hashCode over UTF-16 code units (the backend double hashes with it)."""
    h = 0
    units = s.encode("utf-16-be")
    for i in range(0, len(units), 2):
        h = (31 * h + int.from_bytes(units[i : i + 2], "big")) & 0xFFFFFFFF
    return h - (1 << 32) if h >= (1 << 31) else h


def hashing_vector(text):
    vector = [0.0] * DIM
    compact = JAVA_WHITESPACE.sub("", text)
    for i in range(len(compact) - 1):
        vector[java_string_hash(compact[i : i + 2]) % DIM] += 1
    norm = math.sqrt(sum(x * x for x in vector))
    if norm == 0:
        vector[0] = 1.0
        return vector
    return [x / norm for x in vector]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":  # docker compose health check
            self._reply(200, {"status": "ok", "dim": DIM})
        else:
            self._reply(404, {})

    def do_POST(self):
        if self.path != EMBED_PATH:
            self._reply(404, {"error": {"code": 404, "message": "unknown path"}})
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        texts = [r["content"]["parts"][0]["text"] for r in body.get("requests", [])]
        self._reply(200, {"embeddings": [{"values": hashing_vector(t)} for t in texts]})

    def _reply(self, status, payload):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass  # request text is user content; do not log it


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"hashing embedder listening on {args.host}:{args.port} (dim {DIM})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
