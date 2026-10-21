# SCOS demo: quickstart with a synthetic corpus

> The commands below were run end to end on 2026-10-18 (macOS, Apple silicon, Docker Desktop 28.5, Compose v2.40).

This demo runs SCOS locally with Docker. It needs no API key and makes no external calls. You get:

- PostgreSQL 17 with pgvector
- a local stand-in embedder (no key needed)
- the SCOS server, built from this repository
- a synthetic corpus of 52 documents from **Ottercart**, a made-up grocery-delivery company. The corpus has Slack-style threads, Notion-style pages and Jira-style issues, in Korean and English.

All names, people, numbers and events in the corpus are invented.

## Requirements

| Tool | Notes |
| --- | --- |
| Docker with Compose v2 | Builds the server image. Runs PostgreSQL, the embedder and the server. No host ports are published. |
| Python 3 | Only for `demo/mcp_search.py`, the minimal MCP client used below. Any MCP client works instead. |

You do not need a JDK. The image build runs Gradle inside Docker.

## Quickstart

Run these from the repository root.

```bash
# 1. Build the server image. The first build downloads Gradle and dependencies, which takes a few minutes.
docker compose -f demo/compose.yaml build scos

# 2. Start PostgreSQL and the embedder.
docker compose -f demo/compose.yaml up -d --wait

# 3. Embed the corpus. The result goes into a compose volume.
docker compose -f demo/compose.yaml run --rm corpus

# 4. Load it into SCOS. This runs the Flyway migrations on first use.
docker compose -f demo/compose.yaml run --rm scos import --file=/work/units.embedded.json --space=demo

# 5. Ask a question over MCP stdio.
python3 demo/mcp_search.py "결제 중복 승인 장애 원인" -- docker compose -f demo/compose.yaml run --rm -T scos mcp
```

Observed output (the server's startup logs go to stderr and are left out):

```text
# step 3
embedded units=153 documents=52 -> /work/units.embedded.json

# step 4 (last log line)
INFO  io.scos.server.Commands - import done: units=153 vectors=153 space=demo

# step 5
server: scos-mcp-server 0.0.1  tools: search_context, read_document, related_documents, assemble_context
search_context("결제 중복 승인 장애 원인", limit=5) -> 5 hits
  1. [jira] OTC-311 결제 승인 재시도 시 원래 멱등 키를 재사용 > 설명  score=0.031  doc=jira/OTC-311
  2. [notion] 포스트모템: 2026-09-07 결제 중복 승인 (SEV1) > 근본 원인  score=0.031  doc=notion/postmortem-2026-09-07-duplicate-charges
  3. [notion] 포스트모템: 2026-09-07 결제 중복 승인 (SEV1) > 타임라인  score=0.031  doc=notion/postmortem-2026-09-07-duplicate-charges
  4. [jira] OTC-318 중복 승인 건 환불 스크립트 > 설명  score=0.029  doc=jira/OTC-318
  5. [jira] OTC-311 결제 승인 재시도 시 원래 멱등 키를 재사용  score=0.029  doc=jira/OTC-311
freshness: {"latestCapturedAt": "2026-10-14T07:10:00Z", "staleHours": 99.8, "stale": true}
```

An English query works too:

```text
$ python3 demo/mcp_search.py "How do clients send an idempotency key to the Order API?" -- docker compose -f demo/compose.yaml run --rm -T scos mcp
search_context("How do clients send an idempotency key to the Order API?", limit=5) -> 5 hits
  1. [notion] Order API v2 design > Idempotency  score=0.047  doc=notion/order-api-v2-design
  ...
```

Each hit is one unit, which is a section of a document, so the same document can show up more than once. The score is a reciprocal-rank fusion of the keyword and vector channels. Reranking is off in the demo. `stale` is `true` because the newest demo document is older than the staleness window. Pass `--raw` to see the exact JSON that `search_context` returned.

### Use it from an MCP client

Each session starts the server over stdio with `docker compose run`. For a client that takes the usual `mcpServers` JSON, use this (put in the absolute path to your checkout):

```json
{
  "mcpServers": {
    "scos-demo": {
      "command": "docker",
      "args": ["compose", "-f", "/absolute/path/to/repo/demo/compose.yaml", "run", "--rm", "-T", "scos", "mcp"]
    }
  }
}
```

Keep `db` and `embedder` running (step 2) while the client is in use. The server starts in about 2 seconds.

### Clean up

```bash
# --profile tools is needed so that the `work` volume (used only by the profiled services) is removed too
docker compose -f demo/compose.yaml --profile tools down -v   # containers, network and both volumes (pgdata, work)
docker image rm scos-demo-server:local               # optional: the built server image
```

## How the keyless mode works, and its limits

The server can only call the Gemini embedding API today, and it needs an embedder to start (`search_context` always embeds the query). It has no keyword-only fallback. The demo works around this without changing any code:

- `embedder` (`demo/embedder/hashing_embedder.py`) is a small Python program that uses only the standard library. It answers the Gemini `batchEmbedContents` request with character-bigram hashing vectors (768 dimensions).
- The `scos` service points `SCOS_GEMINI_BASE_URL` at it. It passes a placeholder `GEMINI_API_KEY`, because the server will not start with a blank key. The placeholder never leaves the compose network.
- `scos import` stores the vectors it is given and does not compute any. So step 3 (`demo/embedder/embed_units.py`) embeds every unit through the same endpoint first. It embeds `title + "\n" + content`, the same input the projection pipeline uses.

Hashing vectors capture spelling overlap, not meaning. Search finds documents that share words with the query, in either language, but misses paraphrases across languages. One example is "애플 실리콘" in a query versus "Apple silicon" in the document. Use a real embedding model for meaningful vectors. This demo is not a quality baseline.

If you stop the embedder, the server returns this error for every search: `Gemini embeddings request failed: dependency_unavailable`.

## What is in this directory

| Path | What it is |
| --- | --- |
| `corpus/docs/{slack,notion,jira}/**.md` | Corpus source. One Markdown file per document. The document id is the path without `.md`, for example `jira/OTC-311`. Front matter holds `updated` and `tags`. The first `# heading` is the title. |
| `corpus/units.json` | Generated. The `import` input: units built by the product ingest pipeline (heading split, token counts, content hashes, links), with no embeddings. |
| `eval/queries.json` | 18 synthetic judgment queries with `expectedDocumentIds`. |
| `eval/ottercart-demo-pack.json` | Generated. The same queries plus the documents, in the pack format (`name`, `version`, `docs[{id,title,body}]`, `queries[{query,expectedDocumentIds,slice,evidenceText}]`). This is the format the harness comparison tool (`o2Compare`, see [harness/README.md](../harness/README.md)) reads. |
| `embedder/` | The keyless stand-in embedder and the corpus embedding step. |
| `Dockerfile`, `Dockerfile.dockerignore` | Server image: Gradle `bootJar` on a JDK 21 image, run on a JRE 21 image as a non-root user. |
| `compose.yaml` | Services `db`, `embedder`, plus `corpus` and `scos` under the `tools` profile. Because of the profile, `up` does not start them; you run them with `docker compose run`. |
| `mcp_search.py` | A minimal MCP stdio client: `initialize`, then `tools/list`, then one `search_context` call. |

## Editing the corpus

Edit or add Markdown under `corpus/docs/`. Link other documents with relative Markdown links, for example `[postmortem](../notion/postmortem-2026-09-07-duplicate-charges)`. Then regenerate the two generated files:

```bash
cd backend
./gradlew -q writeDemoClasspath
"$(cat build/demo/java.txt)" -cp "$(cat build/demo/classpath.txt)" io.scos.demo.DemoCorpus ../demo
```

The backend tests keep the demo honest:

- `DemoCorpusTest` (fast tier) fails if the generated files are out of date with the Markdown. It also checks that every link and every expected document id points into the corpus.
- `DemoCorpusLoadTest` (`db` tier, Testcontainers) loads the corpus the way this quickstart does. It checks the document count, one known query, and a minimum number of judgment queries that hit (17 of 18 within the top 5).
