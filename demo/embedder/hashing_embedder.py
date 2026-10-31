#!/usr/bin/env python3
"""Keyless demo embedder: answers the OpenAI-compatible ``POST /v1/embeddings`` request with hashing vectors.

This is a stand-in for local demos only. The demo points the SCOS server's OpenAI-compatible embedder
(``SCOS_EMBEDDER_BASE_URL``) at this process. No key is needed and nothing leaves the machine.

Vectors are character-bigram hashes (768 dims, L2-normalised) -- the same algorithm as the backend test double
``io.scos.demo.HashingEmbedder``; ``HashingEmbedderParityTest`` pins the two to the same vectors. They capture
spelling overlap, not meaning: good enough to exercise the vector channel end to end, not a substitute for a real
embedding model.

Usage: hashing_embedder.py [--host 0.0.0.0] [--port 8000]   (--port 0 picks a free port)
"""
import argparse
import json
import math
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIM = 768
MODEL = "scos-demo-hashing"
EMBED_PATH = "/v1/embeddings"
JAVA_WHITESPACE = re.compile(r"[ \t\n\x0b\f\r]+")  # Java regex \s


def utf16_units(s):
    """UTF-16 code units, as a Java String holds them: a character outside the BMP (an emoji) is two units."""
    raw = s.encode("utf-16-be", "surrogatepass")
    return [int.from_bytes(raw[i : i + 2], "big") for i in range(0, len(raw), 2)]


def hashing_vector(text):
    vector = [0.0] * DIM
    units = utf16_units(JAVA_WHITESPACE.sub("", text))
    for first, second in zip(units, units[1:]):
        # Java String.hashCode of the two-unit bigram; it is below 2^31, so floorMod is plain %
        vector[(31 * first + second) % DIM] += 1
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
            self._reply(404, {"error": {"code": "not_found", "message": "unknown path"}})
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        texts = body.get("input", [])
        if isinstance(texts, str):  # OpenAI accepts a single string as well as a list
            texts = [texts]
        data = [{"object": "embedding", "index": i, "embedding": hashing_vector(t)} for i, t in enumerate(texts)]
        self._reply(200, {"object": "list", "model": body.get("model", MODEL), "data": data})

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
    host, port = server.server_address[:2]  # --port 0 picks a free port; print the one actually bound
    print(f"hashing embedder listening on {host}:{port} (dim {DIM})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
