# SCOS demo: quickstart with a synthetic corpus

> Steps 2 to 5 below were last run on 2026-10-24 (macOS, colima on amd64, Docker Engine 29.5, Compose 5.1); the observed output is from that run. CI runs all five steps on every change (see [CI](#ci)).

This demo runs SCOS locally with Docker. It needs no API key and makes no external calls. You get:

- PostgreSQL 17 with pgvector
- a local stand-in embedder (no key needed)
- the SCOS server, built from this repository
- a synthetic corpus of 52 documents from **Ottercart**, a made-up grocery-delivery company. The corpus has Slack-style threads, Notion-style pages and Jira-style issues, in Korean and English.

All names, people, numbers and events in the corpus are invented.

## Requirements

| Tool | Notes |
| --- | --- |
| Docker with Compose v2 and BuildKit | Builds the server image. Runs PostgreSQL, the embedder and the server. No host ports are published. Docker Desktop and current Docker Engine releases include BuildKit (`docker buildx`); the image build uses a BuildKit cache mount and fails with the legacy builder. |
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
docker compose -f demo/compose.yaml run --rm scos import --file=/work/units.embedded.json --space=demo --embedding-generation=openai-compatible:scos-demo-hashing:768:title+aliases+content

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
freshness: {"latestCapturedAt": "2026-10-14T07:10:00Z", "staleHours": 239.4, "stale": true}
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

The server needs an embedder to start (`search_context` always embeds the query). It has no keyword-only fallback. The demo gives it a local one through the same OpenAI-compatible provider you would use for a real model, so no code or setting is demo-only:

- `embedder` (`demo/embedder/hashing_embedder.py`) is a small Python program that uses only the standard library. It answers the OpenAI-compatible `POST /v1/embeddings` request with character-bigram hashing vectors (768 dimensions). It needs no key.
- The `scos` service selects it with four settings in `compose.yaml`: `SCOS_EMBEDDER_PROVIDER=openai-compatible`, `SCOS_EMBEDDER_BASE_URL=http://embedder:8000/v1`, `SCOS_EMBEDDER_MODEL=scos-demo-hashing` and `SCOS_EMBEDDER_DIM=768`. `SCOS_RERANK_PROVIDER=none` turns reranking off, so no other model is called.
- `scos import` stores the vectors it is given and does not compute any. So step 3 (`demo/embedder/embed_units.py`) embeds every unit through the same endpoint first. Step 4 then names the generation of those vectors with `--embedding-generation=openai-compatible:scos-demo-hashing:768:title+aliases+content`; `import` refuses vectors whose generation differs from the server's embedder (see [Embedding generations](../README.md#embedding-generations)).
- The backend tests check that this Python embedder returns the same vectors as the Java stand-in the demo tests use (`HashingEmbedderParityTest`).

Hashing vectors capture spelling overlap, not meaning. Search finds documents that share words with the query, in either language, but misses paraphrases across languages. One example is "애플 실리콘" in a query versus "Apple silicon" in the document. Use a real embedding model for meaningful vectors. This demo is not a quality baseline.

If you stop the embedder, every search fails with this tool error: `OpenAI-compatible embeddings request failed: dependency_unavailable`.

The demo volumes keep the generation they were loaded with. If you loaded an older version of this demo, which used a different generation, run the clean-up step (`down -v`) before step 2; otherwise step 4 refuses the new vectors.

## Use a real model

Any server that speaks the OpenAI-compatible embeddings API works. For example, with [Ollama](https://ollama.com) running on the host and `ollama pull nomic-embed-text` done, change the four settings in `compose.yaml` (`x-scos-env`):

```yaml
  SCOS_EMBEDDER_PROVIDER: openai-compatible
  SCOS_EMBEDDER_BASE_URL: http://host.docker.internal:11434/v1
  SCOS_EMBEDDER_MODEL: nomic-embed-text
  SCOS_EMBEDDER_DIM: "768"
```

`host.docker.internal` reaches the host from Docker Desktop. On Linux, add `extra_hosts: ["host.docker.internal:host-gateway"]` to the `scos` service, and make Ollama listen beyond loopback (for example `OLLAMA_HOST=0.0.0.0`), because by default it listens only on `127.0.0.1`, which containers cannot reach. For a hosted API, also set `SCOS_EMBEDDER_API_KEY`.

The vectors loaded in step 4 come from the hashing embedder (step 3 always uses it), so after the change the server refuses to start on the `demo` space and tells you to re-embed it. Stop any MCP client that uses the demo server, then:

```bash
docker compose -f demo/compose.yaml run --rm scos reembed --space=demo --dry-run   # unit count, stored and target generation
docker compose -f demo/compose.yaml run --rm scos reembed --space=demo
```

`reembed` embeds all 153 units with the new model and records its generation (`reembed done: space=demo units=153 … generation=openai-compatible:nomic-embed-text:768:title+aliases+content`). After that, step 5 works again, and `status` shows the same `stored` and `configured` generation. On a fresh start (after `down -v`), run steps 2 to 4 with the demo settings first, because `import` accepts only vectors of the configured generation. Then change the settings and run `reembed`.

## Evaluation

`eval/ottercart-demo-pack.json` holds 18 judgment queries over this corpus. With the demo loaded (steps 2 to 4), the harness `evaluate` task measures how well the demo server finds the expected documents (it needs a JDK; see [docs/public/evaluation.md](../docs/public/evaluation.md)):

```bash
cd harness
./gradlew evaluate -Pevaluate.args="--pack ../demo/eval/ottercart-demo-pack.json --server-command ../demo/mcp-server.sh --out build/eval/demo --k 5"
```

Measured on 2026-10-24:

```text
evaluate: queries=18 k=5 recall=0.7870 mrr=0.9444 ndcg=0.8181 errors=0 verdict=no-floors
```

The hashing embedder is deterministic, so these numbers do not drift between runs. They describe the demo's spelling-overlap vectors, not a real model. The one query with no expected document in the top 5 is the cross-language paraphrase case described above.

## CI

CI runs `demo/smoke.sh` on every change to `backend/`, `harness/` or `demo/`. It runs steps 1 to 5 with the same commands as above, requires at least one hit in step 5, then runs the evaluation with floors at the measured values rounded down to 0.05: `--min-recall 0.75 --min-mrr 0.90 --min-ndcg 0.80`. It always cleans up with `down -v`, and prints the compose logs (and the evaluated server's stderr) when a step fails. You can run it locally the same way (it needs Docker, Python 3 and a JDK). Because it ends with `down -v`, running it next to a demo you loaded by hand deletes that demo's volumes; give it its own project name to keep them apart: `COMPOSE_PROJECT_NAME=scos-smoke demo/smoke.sh`.

## What is in this directory

| Path | What it is |
| --- | --- |
| `corpus/docs/{slack,notion,jira}/**.md` | Corpus source. One Markdown file per document. The document id is the path without `.md`, for example `jira/OTC-311`. Front matter holds `updated` and `tags`. The first `# heading` is the title. |
| `corpus/units.json` | Generated. The `import` input: units built by the product ingest pipeline (heading split, token counts, content hashes, links), with no embeddings. |
| `eval/queries.json` | 18 synthetic judgment queries with `expectedDocumentIds`. |
| `eval/ottercart-demo-pack.json` | Generated. The same queries plus the documents, in the judgment pack format (`name`, `version`, `docs[{id,title,body}]`, `queries[{query,expectedDocumentIds,slice,evidenceText}]`). The harness `evaluate` and `o2Compare` tasks read it (see [harness/README.md](../harness/README.md)). |
| `embedder/` | The keyless stand-in embedder and the corpus embedding step. |
| `Dockerfile`, `Dockerfile.dockerignore` | Server image: Gradle `bootJar` on a JDK 21 image, run on a JRE 21 image as a non-root user. |
| `compose.yaml` | Services `db`, `embedder`, plus `corpus` and `scos` under the `tools` profile. Because of the profile, `up` does not start them; you run them with `docker compose run`. |
| `mcp_search.py` | A minimal MCP stdio client: `initialize`, then `tools/list`, then one `search_context` call. `--min-hits N` exits 1 when there are fewer than N hits. |
| `mcp-server.sh` | Starts the demo MCP server over stdio as one executable, for tools that take a server command (harness `evaluate --server-command`). |
| `smoke.sh` | The quickstart end to end plus the evaluation gate, as CI runs it. Cleans up (`down -v`) whether it passes or fails. |

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
