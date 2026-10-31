#!/usr/bin/env python3
"""Adds embeddings to an import file (``{"units": [...]}``) by calling an OpenAI-compatible embedding endpoint.

``scos import`` stores the vectors it is given and does not embed. This step embeds each unit the way the
projection pipeline does (input ``title + "\\n" + content``) through the same OpenAI-compatible request the server
uses for queries, so documents and queries share one vector space.

The base URL includes ``/v1``, the same rule as the server setting ``SCOS_EMBEDDER_BASE_URL``: ``/embeddings`` is
appended to it.

Usage: embed_units.py <embedder base URL, e.g. http://embedder:8000/v1> <units.json> <out.json>
"""
import json
import sys
import urllib.request

MODEL = "scos-demo-hashing"
BATCH = 100


def embed(base_url, texts):
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/embeddings",
        data=json.dumps({"model": MODEL, "input": texts}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        data = json.load(response)["data"]
    if len(data) != len(texts):
        raise SystemExit(f"embedder returned {len(data)} vectors for {len(texts)} texts")
    vectors = [None] * len(texts)
    for item in data:  # servers may return data out of index order
        vectors[item["index"]] = item["embedding"]
    return vectors


def main():
    base_url, source, target = sys.argv[1:4]
    with open(source, encoding="utf-8") as f:
        corpus = json.load(f)
    units = corpus["units"]
    for offset in range(0, len(units), BATCH):
        batch = units[offset : offset + BATCH]
        for unit, vector in zip(batch, embed(base_url, [u["title"] + "\n" + u["content"] for u in batch])):
            unit["embedding"] = vector
    with open(target, "w", encoding="utf-8") as f:
        json.dump(corpus, f, ensure_ascii=False)
    documents = len({u["provenance"]["documentId"] for u in units})
    print(f"embedded units={len(units)} documents={documents} -> {target}")


if __name__ == "__main__":
    main()
