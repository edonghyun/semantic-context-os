# Evaluating search quality

`evaluate` measures how well one MCP server finds the right documents for a set of queries. You write a judgment pack (queries plus the documents that should come back), point `evaluate` at your server, and get Recall@k, MRR@k and nDCG@k. With floors it becomes a gate: it exits 1 when an average drops below a floor, so you can run it in CI.

It lives in the `harness` Gradle build and talks to the server only over stdio JSON-RPC, the same way an MCP client does. It does not need the server's code or database.

## Quick start

Against the demo server, after [Quickstart](../../demo/README.md#quickstart) steps 1 to 4 (`demo/mcp-server.sh` starts the demo MCP server):

```bash
cd harness
./gradlew evaluate -Pevaluate.args="--pack ../demo/eval/ottercart-demo-pack.json --server-command ../demo/mcp-server.sh --out build/eval/demo --k 5"
```

It prints one line and writes `summary.json` and `queries.jsonl` to `--out`:

```
evaluate: queries=18 k=5 recall=0.7870 mrr=0.9444 ndcg=0.8181 errors=0 verdict=no-floors
```

These are the demo's numbers (keyless hashing vectors, so they say nothing about a real model). The demo's CI job gates on them with `--min-recall 0.75 --min-mrr 0.90 --min-ndcg 0.80` ([`demo/smoke.sh`](../../demo/smoke.sh)).

## Judgment pack (v1)

A judgment pack is one JSON object:

```json
{
  "name": "my-team-search",
  "version": 1,
  "queries": [
    {
      "query": "How do clients send an idempotency key to the Order API?",
      "expectedDocumentIds": ["notion/order-api-v2-design", "jira/OTC-311"]
    }
  ]
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `name` | yes | Non-empty string. Names the pack in your own records. |
| `version` | yes | Number or non-empty string. Bump it when you change the queries or answers, so scores from different versions are not compared by mistake. |
| `queries[].query` | yes | Non-empty query text, sent as is. |
| `queries[].expectedDocumentIds` | yes | Non-empty list of the documents that answer the query. Each entry is a non-empty string. A repeated id counts once. |
| `docs[]`, `queries[].slice`, `queries[].evidenceText` | no | Allowed so the same file can carry the corpus and notes for people. `evaluate` does not read them. |

Unknown fields are ignored. Relevance is binary: a document is either one of the expected ids or it is not. There are no grades in v1.

Document ids must be the same strings the server returns as `documentId` in `search_context` hits. For SCOS that is the id the connector or `import` gave the document, such as `jira/OTC-311`. An expected id that the server never returns (for example, a typo, or a document that is not in the index) cannot be found, so it lowers recall. `evaluate` does not check ids against the corpus.

A query log (`SCOS_QUERY_LOG`) is not a judgment pack. It has no answers, so `evaluate` rejects it.

## Metrics

For each query, `evaluate` calls `search_context` with `{"query": …, "limit": <limit>}`. The server returns hits per unit (a chunk of a document), so the same document can appear more than once. `evaluate` keeps the first occurrence of each document id. That gives the ranked list of documents. Let R be the set of expected ids and the top k be the first k documents of that list.

- **Recall@k** = |top k ∩ R| / |R|
- **Reciprocal rank@k** = 1 / (rank of the first expected document in the top k), or 0 if there is none. **MRR@k** is its mean over queries.
- **nDCG@k** = DCG / IDCG, where DCG = Σ_{i ≤ k} rel_i / log2(i + 1) with rel_i = 1 if the document at rank i is in R, else 0, and IDCG = Σ_{i ≤ min(|R|, k)} 1 / log2(i + 1).

A ranked list shorter than k is scored as is. Missing ranks count as not relevant.

`--k` (default 5) is the depth the metrics look at. `--limit` (default 10) is the number of hits requested from the server. Since several hits can belong to one document, keep `--limit` at least as large as `--k`, or larger if your documents are split into many units.

The summary reports the mean of each metric over the queries that ran without errors.

## Command line

```
--pack <file>               judgment pack (required)
--server-command <exe>      one executable that starts your MCP server on stdio (required)
--out <dir>                 where to write summary.json, queries.jsonl and server-stderr.log (required)
--k <n>                     metric depth, default 5
--limit <n>                 search_context limit, default 10
--effort <low|medium|high>  search_context effort; omitted = the argument is not sent
--min-recall <x>            floor for mean Recall@k
--min-mrr <x>               floor for MRR@k
--min-ndcg <x>              floor for mean nDCG@k
--timeout-seconds <n>       per-call and startup timeout, default 120
--include-query-text        also write query text to queries.jsonl
--no-server-stderr          discard the server's stderr instead of writing server-stderr.log
```

Floors are numbers in [0, 1]. A mean below its floor is a violation. A mean equal to the floor passes. A metric without a floor is not judged. Unknown options are rejected, so a mistyped floor cannot be silently skipped.

### Exit codes

These are the exit codes of the evaluator itself, as you get them when you run it directly:

```bash
java -cp "<harness classpath>" io.scos.harness.evaluation.EvaluationMain --pack … --server-command … --out …
```

| Code | Meaning |
| --- | --- |
| 0 | All floors pass, or no floors were given |
| 1 | At least one mean is below its floor |
| 2 | Usage error: missing or unknown option, a floor outside [0, 1], a timeout of 0 or less, pack not found, a query log instead of a pack, a pack without `name` or `version`, or a query whose `expectedDocumentIds` is missing, empty or has an entry that is not a non-empty string (the message names the query index, counted from 0). No result files are written. |
| 3 | At least one query failed to run (server exited, timed out, or returned an error), or the evaluator itself failed. Result files are still written when the queries ran; failed queries carry an `error` field and are left out of the means. |

Through Gradle (`./gradlew evaluate`), every non-zero code makes the task fail, and Gradle itself exits with 1. The evaluator's code appears in Gradle's error message as `finished with non-zero exit value N`. A CI step that only needs pass or fail can use the Gradle exit code. To tell a floor violation (1) from a broken run (3), run the class directly or read `verdict` in `summary.json`.

### Connecting your own server

`--server-command` takes one executable and no arguments. (The Gradle task splits `-Pevaluate.args` on spaces, so paths must not contain spaces.) Wrap whatever starts your server in a small script:

```bash
#!/bin/sh
exec docker compose -f /path/to/compose.yaml run --rm -T scos mcp
```

The server is started once and reused for all queries. If a call fails, `evaluate` restarts it and moves on; after three failures in a row, or if the restart itself fails, it stops and marks the remaining queries as errors. The working directory is the script's directory. The server inherits `evaluate`'s environment, except that `SCOS_QUERY_LOG` is set to an empty value so that evaluation queries do not end up in a query log.

Load your index before running `evaluate`. It only searches.

## Output

`summary.json`:

```json
{
  "queries" : 18,
  "k" : 5,
  "recall" : 0.9,
  "mrr" : 0.8,
  "ndcg" : 0.82,
  "errors" : 0,
  "effort" : "low",
  "latencyMs" : { "median" : 41.2, "p95" : 88.5 },
  "escalated" : 3,
  "floors" : { "recall" : 0.85 },
  "verdict" : "pass"
}
```

`effort` is present only when `--effort` was given. `latencyMs` is the median and p95 of the `search_context` call time over the queries that ran without errors. `escalated` is the number of queries the server re-ran at a higher effort; it appears only when the server reports `applied` in its responses (then each `queries.jsonl` line also carries `appliedEffort` and `escalated`). `verdict` is `pass`, `violations`, `errors` or `no-floors`. `recall`, `mrr` and `ndcg` are absent when no query ran successfully.

`queries.jsonl` has one line per query:

```json
{"index":0,"expected":["notion/order-api-v2-design"],"ranked":["notion/order-api-v2-design","jira/OTC-311"],"recall":1.0,"rr":1.0,"ndcg":1.0}
```

`index` is the position in the pack's `queries` array, counted from 0. A failed query has `error` instead of the metrics.

Query text is not written by default, because queries can contain internal information. Pass `--include-query-text` to add a `query` field. Document contents are never written, only ids. The server's stderr can contain query text, so `server-stderr.log` is created with mode 600.

## Baseline: plain `rg` and `git log`

`baseline` measures how well plain text search ranks the documents that answer your queries, so that you can compare a smarter search against it. It runs `rg -uu` over transcripts, notes and memory files, and `git log` over your repositories. It needs no server.

Add fields to the same judgment pack. `evaluate` ignores them.

| Field | Required | Meaning |
| --- | --- | --- |
| `queries[].kind` | no | `"why"` or `"resume"`. Copied to each `queries.jsonl` line. |
| `queries[].baseline.terms` | yes | 1 to 3 non-empty strings. The search words a person would type into `rg`. |
| `queries[].baseline.gitTerms` | no | 1 to 3 strings for `git log`. Defaults to `terms`. |

```bash
cd harness
SCOS_RG=/path/to/rg ./gradlew baseline -Pbaseline.args="--pack ../eval/my-pack.json \
  --projects $HOME/.claude/projects --docs notes=../notes --git app=../app --out build/baseline/run --k 5"
```

`--projects` is required. `--docs <name>=<dir>` and `--git <name>=<dir>` can be repeated. `--include-query-text` adds the query text to `queries.jsonl`. The `rg` executable is read from the environment variable `SCOS_RG` (default `rg`). Exit codes: 0 = done, 2 = usage error, 3 = `rg` or `git` could not run.

Document ids follow the path:

| Source | Document id |
| --- | --- |
| `<projects>/<dir>/<session>.jsonl` | `transcript/<dir>/<session>` |
| `<projects>/<dir>/<session>/subagents/*.jsonl` | the parent session, `transcript/<dir>/<session>` |
| `<projects>/<dir>/memory/<name>.md` | `memory/<dir>/<name>.md` |
| `<root>/<rel>` for `--docs <name>=<root>` | `doc/<name>/<rel>` |
| a commit in `--git <name>=<repo>` | `git/<name>/<first 8 characters of the sha>` |

**Ranking.** `rg -uu -c -i -F -e <term> …` counts matching lines per file. The counts are summed per document id and ranked from most lines to fewest; ties go to the smaller id. For `git log --all`, the commits whose message contains any term (`--grep`, case-insensitive, fixed strings) are joined with the commits whose changes add or remove a term (`-S<term>`, one run per term). They are ranked by commit time, newest first.

**Scoring favors the baseline.** For each query, the top k of the `rg` list and the top k of the `git` list are merged into one list (`rg` first, each document once). Recall and nDCG are computed over that whole merged list. The reciprocal rank is the better of the two lists' reciprocal ranks at k. `summary.json` also reports each source alone under `bySource`:

```json
{"queries":2,"k":5,"recall":1.0,"mrr":1.0,"ndcg":1.0,"bySource":{"rg":{"recall":1.0,"mrr":1.0,"ndcg":1.0},"git":{"recall":0.0,"mrr":0.0,"ndcg":0.0}}}
```

Never commit a real pack, transcripts or results. They contain private text.

## In GitHub Actions

```yaml
jobs:
  search-quality:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      # Start and load your index here, so that ./ci/mcp-server.sh can serve it.
      - name: Evaluate
        working-directory: harness
        run: >
          ./gradlew evaluate -Pevaluate.args="--pack ../eval/my-pack.json
          --server-command ../ci/mcp-server.sh --out build/eval --k 5
          --min-recall 0.80 --min-mrr 0.70 --min-ndcg 0.70"
      - if: always()
        uses: actions/upload-artifact@v4
        with:
          name: search-quality
          path: |
            harness/build/eval/summary.json
            harness/build/eval/queries.jsonl
```

Set floors a little below a measured run, so that small, expected changes in ranking do not fail the build.

## The `harness` profile

The server's `harness` profile and the golden fixtures in `harness/fixtures` replay recorded embeddings for this project's own regression tests. You do not need them to evaluate your server. Run `evaluate` against your server as you normally start it.
