#!/usr/bin/env python3
"""Adds embeddings to an import file (``{"units": [...]}``) by calling an embedding endpoint.

``scos import`` stores the vectors it is given and does not embed. This step embeds each unit the way the
projection pipeline does (input ``title + "\\n" + content``) through the same Gemini request shape the server
uses for queries, so documents and queries share one vector space.

Usage: embed_units.py <embedder base URL> <units.json> <out.json>
"""
import json
import sys
import urllib.request

MODEL = "gemini-embedding-001"
DIM = 768
BATCH = 100


def embed(base_url, texts):
    requests = [
        {"model": f"models/{MODEL}", "content": {"parts": [{"text": t}]}, "outputDimensionality": DIM}
        for t in texts
    ]
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/v1beta/models/{MODEL}:batchEmbedContents",
        data=json.dumps({"requests": requests}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        vectors = [e["values"] for e in json.load(response)["embeddings"]]
    if len(vectors) != len(texts):
        raise SystemExit(f"embedder returned {len(vectors)} vectors for {len(texts)} texts")
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
