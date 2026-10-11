# Deployment (zero cost)

Three free services, no credit card required for the free tiers (check each provider's current terms):

| Part | Host | Why |
|---|---|---|
| PostgreSQL | **Neon** (free) | Free Postgres that does not expire (Render's free Postgres does). |
| Backend (Docker) | **Render** (free web service) | Builds from the `backend/Dockerfile`; `render.yaml` is included. |
| Frontend | **Vercel** (free hobby) | Static Vite build with SPA rewrites (`frontend/vercel.json`). |

Trade-offs of the free tier: the Render service sleeps after ~15 minutes idle, so the first request after a pause is slow while it wakes. I measured about 150 seconds to boot under the free tier's 0.1 CPU limit, and more when Neon is also waking. Neon also pauses idle databases and wakes them on connect. Both are fine for a portfolio demo; upgrade later by changing the plan.

The LLM defaults to the free **offline** assistant, so there is **no per-question cost** unless you opt into Claude.

## 1. Database (Neon)

1. Sign in at neon.tech, create a project (pick a region near your Render region).
2. From the connection details, note host, database, user and password.
3. Your JDBC URL is `jdbc:postgresql://<host>/<database>?sslmode=require`.

Flyway creates the schema and seed data on the first backend start; nothing to run by hand. That includes the two PostgreSQL extensions the lease search uses: migration `V5` runs `CREATE EXTENSION IF NOT EXISTS vector` and `... pg_trgm`, and Neon supports both. Only if the database role you give `DB_USER` is not allowed to create extensions, run those two statements once in Neon's SQL editor and start the backend again.

## 2. Backend (Render)

1. Push the repo to GitHub.
2. In Render: **New → Blueprint**, pick the repo (it reads `render.yaml`). Or **New → Web Service → Docker** with root `backend` if you prefer clicking.
3. Set the secret env vars when prompted:
   - `DB_URL`, `DB_USER`, `DB_PASSWORD` from Neon
   - `CORS_ALLOWED_ORIGINS` = your Vercel URL (set a placeholder first, update after step 3)
   - `ANTHROPIC_API_KEY` only if you switch `LLM_PROVIDER` to `anthropic`
   - `EMBEDDING_PROVIDER` and `VOYAGE_API_KEY`, only to turn on embeddings for the lease search (section 5); leave `EMBEDDING_PROVIDER` unset, blank or `none` otherwise
   - `ADMIN_TOKEN` (24 or more characters, `openssl rand -hex 24`): it switches on the admin endpoints and the traces page, and the nightly demo reset in section 7 needs it
   - `ANTHROPIC_MODEL_LARGE`, `OTEL_EXPORTER_OTLP_ENDPOINT`: both optional and unset by default; leave them out unless you follow section 8
4. Wait for the deploy; `https://<service>.onrender.com/actuator/health` should return `{"status":"UP"}`.
5. The first start after this version is deployed applies migrations `V5` and `V6`, and then writes the lease documents in a background thread, so the health check does not wait for them. See section 5 for what to expect. A database that is older also gets `V7` to `V10`, which add the tables of the stored traces (`run_llm_calls`, `run_tool_calls`, `run_guardrail_findings`) and a few columns of `chat_runs`; they add tables and columns and hold no seed data.

## 3. Frontend (Vercel)

1. In Vercel: **Add New → Project**, import the repo, set **Root Directory** to `frontend`.
2. Environment variable: `VITE_API_URL` = `https://<service>.onrender.com`.
3. Deploy. Copy the production URL.
4. Back in Render, set `CORS_ALLOWED_ORIGINS` to that URL (no trailing slash) and redeploy.

Open the Vercel URL, switch to Arabic with the language button, and ask a question.

## 4. Optional: use real Claude

1. In Render set `LLM_PROVIDER=anthropic`, `ANTHROPIC_API_KEY=<your key>`, optionally `ANTHROPIC_MODEL`.
2. Keep `CHAT_DAILY_LIMIT` and `CHAT_RATE_LIMIT_PER_MINUTE` low so a public demo cannot run up your bill. Also set a monthly spend limit in the Anthropic console. Prompt caching and the grounding guardrail are already on in `warn` mode; a second, larger model is optional (section 8).
3. Run the read questions against the live URL and paste the table into the README, with the date and the commit it was measured at:
   `node evals/run.mjs --only en-,ar- --url https://<service>.onrender.com --delay-ms 7000` (the delay respects the per-minute rate limit). The 20 questions use 20 of the day's `CHAT_DAILY_LIMIT`, and they expect the seeded data, which visitors can change by approving payments. Run them soon after the nightly reset, or restore the seed first: `curl -X POST https://<service>.onrender.com/api/admin/reset -H "X-Admin-Token: <ADMIN_TOKEN>"`.
4. The 16 lease cases only read, so they can run against the live URL at any time: `node evals/run.mjs --only rag- --url https://<service>.onrender.com --delay-ms 7000`. Keep the delay: the blueprint allows 10 chat requests a minute, and the runner sends the next question as soon as an answer ends, so without it the later cases get HTTP 429 and fail for a reason that has nothing to do with the assistant. They use 16 of the day's `CHAT_DAILY_LIMIT`, and they expect all 30 documents. The two combined cases (`rag-11`, `rag-12`) expect the leases that `V6` made end 49 and 56 days after it ran on that database, so they stop matching once those dates have passed; that says nothing about the assistant.
5. Keep the 6 write cases (`--only write-`) out of that run and out of that table. Every run leaves 6 proposals on the Activity page for all visitors to see and uses 6 of the day's proposals and of the daily chat limit, and the two payment cases pass only while unit A-504 has at least SAR 4,950 outstanding, so one approved payment makes them fail for a reason that has nothing to do with the model. Run them against a local stack, or on the live URL straight after a reset, and report their result on its own.

## 5. Lease search and embeddings

The lease documents are PDFs the backend writes itself from the leases in the database (the seed's dates move with the migration date, so a PDF committed to the repository would contradict the data), reads the text back out of, cuts into clauses and stores. Nothing is uploaded and no step is manual.

**The first start after deploy.** Flyway applies `V5` (the tables, the extensions and the indexes) and `V6` (seven leases get an end date 14 to 56 days after that day, so "which leases end soon" has an answer). Once the application is ready, a background thread writes the 30 documents, one transaction each. On my laptop that took about 15 seconds. The free instance has a tenth of a CPU, so expect several minutes, not seconds; I have not timed it there. Until a document is written, a question about its lease finds nothing in it. Check progress with `GET /api/info`: `leaseDocuments` counts the stored documents and `retrieval` says `keyword` or `hybrid`. Writing is safe on every start: a lease that already has a current document is left alone, so a restart costs nothing.

**Search works without embeddings.** With `EMBEDDING_PROVIDER` unset or `none`, the chunks are stored with no embedding and every question is answered by full text and trigram search. A document can be searched as soon as it is written. This is the default and costs nothing.

**Turning on real embeddings.**

1. In the Render dashboard set `EMBEDDING_PROVIDER=voyage` and `VOYAGE_API_KEY=<your key>`. `EMBEDDING_MODEL` is optional; the default is `voyage-4-lite` with 1024 dimensions, which is the size of the database column (`EMBEDDING_DIMENSIONS` must stay 1024, or the backend refuses to start). With `voyage` and no key, or a key with a quote mark (straight or typographic), a dash or a line break pasted into it, or any other character outside visible ASCII, the backend also refuses to start and says why in the log.
2. These variables are `sync: false` in `render.yaml`, like `LLM_PROVIDER`, because Render re-applies every variable that has a `value` in the file whenever the blueprint syncs and would overwrite what you set in the dashboard. Do not add a `value` for them.
3. Save, and let Render restart the service. After the documents are written, the chunks are embedded in batches of 32, in the background. A rate limit or an outage stops the run at the first failed batch, keeps what was stored, and leaves the rest for the next start or for `POST /api/admin/rag/reindex` (with `X-Admin-Token`), which answers with `{documents, chunks, embedded, failed}` when the run is over. A second call while a run is going answers 409. Until the chunks have embeddings a question is matched by words only, and when a question's own embedding fails it is answered by words too.
4. Voyage applies rate limits to its accounts, new ones included; see <https://docs.voyageai.com/docs/rate-limits>. The corpus is a few hundred chunks (409 in my local database, so 13 requests), and each distinct question costs one embedding call, which is why one reply may search the leases at most three times and `CHAT_DAILY_LIMIT` matters here too. I have not quoted prices; check Voyage's pricing page.
5. Changing `EMBEDDING_MODEL` later is safe: vectors from another model are not used, and the next run embeds the chunks again with the new one.

**Local Docker.** Compose now runs `pgvector/pgvector:pg16` instead of `postgres:16-alpine`. A data volume that the alpine image created should not be reused: the two images are built on different C libraries, which can leave text indexes wrong. For an existing local volume run `docker compose down -v` once, then `docker compose up --build`; the database holds only seed data, and Flyway recreates it.

## 6. Keep it healthy

- Every push runs CI (backend tests with Testcontainers on the pgvector image, frontend typecheck/tests/build, the eval harness tests and the offline evals against a started backend, Docker build).
- To avoid the cold start before a demo, open the site a few minutes early.
- If a deploy fails with "Timed out ... health check" and the logs stop right after `No active profile set`, the JVM was still starting on the throttled free CPU. Click **Manual Deploy → Deploy latest commit** again; the previous version keeps serving traffic meanwhile.
- `render.yaml` has a `buildFilter`, so only changes under `backend/` redeploy the API.

## 7. Nightly demo reset

Visitors can approve the changes the assistant proposes, and those changes are shared, so the demo's data is put back to its seed every night. The workflow `.github/workflows/demo-reset.yml` runs at 02:00 UTC (05:00 in Riyadh; GitHub may start a scheduled run a few minutes late) and on demand from **Actions → Demo reset → Run workflow**. It calls `POST /api/admin/reset`, which restores the seeded payments and deletes the maintenance requests, outbox messages, proposals and audit entries.

Add two repository secrets under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `DEMO_API_URL` | The base URL of the API, `https://<service>.onrender.com`, with no path after it |
| `ADMIN_TOKEN` | **The same value as `ADMIN_TOKEN` in the Render dashboard** |

The tokens must match. The backend compares them in constant time and answers 403 when they differ, and 404 when Render has no `ADMIN_TOKEN` at all (the admin endpoints are then off).

What the workflow does:

- **Skips when it is not set up.** If either secret is missing it ends successfully with a notice, so a fork or a fresh clone does not fail every night.
- **Waits for a sleeping host.** Each attempt may take up to 300 seconds, and a failed attempt is repeated up to three times with 30 seconds in between, which covers a cold start of a few minutes. A reset ends in the same state however often it runs, so repeating is safe.
- **Fails when the answer is not 2xx.** The run then shows red in the Actions tab, with a hint for the common causes (token refused, endpoint off or wrong URL, no answer).
- **Keeps the secrets out of the log.** curl runs silently, and the log shows only the status and the counts the reset returned.

GitHub turns off scheduled workflows in a public repository after 60 days without repository activity. If the nightly run stops appearing, enable the workflow again in the Actions tab.

## 8. Model routing, the guardrail, caching, tracing and the full evals

Four settings of the third phase have a place in the dashboard or in `render.yaml`. The split follows the rule in the gotcha at the end of this file: what the owner may change by hand is `sync: false` and has no value in the file; what is meant to be reviewed as code has its value in the file, and a dashboard edit of it is overwritten at the next blueprint sync.

| Variable | Where | Default | Notes |
|---|---|---|---|
| `ANTHROPIC_MODEL_LARGE` | dashboard (`sync: false`) | unset | A model id. Unset or blank = no routing and no fallback to a second model |
| `LLM_LARGE_PRICE_INPUT_PER_MTOK`, `LLM_LARGE_PRICE_OUTPUT_PER_MTOK`, `LLM_COMPLEXITY_THRESHOLD` | dashboard, only when wanted | `3.00`, `15.00`, `3` | Not in `render.yaml`. Prices are USD per million tokens |
| `PROMPT_CACHING` | `render.yaml` | `"true"` | Edit the file to change it |
| `GUARDRAIL_MODE` | `render.yaml` | `"warn"` | `off`, `warn` or `retry`; edit the file to change it |
| `OTEL_ENABLED` | `render.yaml` | `"false"` | Edit the file to turn it on |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | dashboard (`sync: false`) | unset | The collector's address with its `http://` or `https://`. Unset or blank = no export, even with `OTEL_ENABLED` true. `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (the full URL for traces) counts as an endpoint too; if you add it, give it a value |
| `OTEL_EXPORTER_OTLP_HEADERS`, `OTEL_SERVICE_NAME` | dashboard, only when wanted | unset, `proppilot` | The collector's credentials, and the service name |

A blank value (empty, or only spaces) of any of these means what an unset one means, as it does for `LLM_PRICE_INPUT_PER_MTOK` and `LLM_PRICE_OUTPUT_PER_MTOK` (`1.00` and `5.00`); none of them can stop the backend from starting. A value that is not one of the three modes in `GUARDRAIL_MODE` does stop it, with a message that names the variable, and so does a text where a number is expected, so a typo is seen at the first deploy and not in production. OpenTelemetry is the exception to that: a value the SDK refuses (an address without `http://` or `https://`, an empty `OTEL_EXPORTER_OTLP_PROTOCOL`) does not stop the backend, it logs one warning and exports nothing.

### Turning on routing to a larger model

1. Check the model id and its prices against Anthropic's documentation. The defaults of `LLM_LARGE_PRICE_*` (3.00 and 15.00) are my reading of Sonnet's list price, which I have not verified from here, and the cost shown under an answer and on the Usage page is wrong for another model unless you set its prices.
2. In the Render dashboard add `ANTHROPIC_MODEL_LARGE=<model id>` (for example `claude-sonnet-4-5`) and, for another model, the two prices. Render restarts the service. Routing needs `LLM_PROVIDER=anthropic`; with the offline assistant it does nothing.
3. A question that scores `LLM_COMPLEXITY_THRESHOLD` (3) or more goes to the larger model, so does an answer the guardrail asks for again in `retry` mode, and an overload or a timeout of one model is retried once on the other. At the default prices a routed question costs about three times what it would on Haiku. I have not measured how many real questions that is, so watch the model mix on the Usage page and the cost in the admin list for the first days, and keep `CHAT_DAILY_LIMIT` low.
4. To switch it off again, remove the variable (or leave it blank).

### The guardrail mode

`warn` (the value in `render.yaml`) checks every delivered answer, flags what nothing supports, writes one WARN line to the log (`Grounding guardrail flagged run ...`, with the kinds and values, never the answer) and changes nothing. `retry` also asks for such an answer once more, which is an extra model call per flagged answer, on the larger model when one is configured. `off` checks nothing. Start with `warn`, read the flagged answers on the admin page (`Flagged answers only`) for a while, and only then consider `retry`: the check has no measured false positive rate on real answers, and it flags a rounded or derived figure like an invented one.

### Prompt caching

`PROMPT_CACHING` is `"true"` in `render.yaml`. Anthropic caches a prefix only above a minimum length that depends on the model, so on the small model it may never trigger with this prompt (the prefix is about 3,000 tokens by estimate); the Usage page shows the cache read and write tokens the API reported, and says so when there were none. Nothing is wrong when it stays at zero. See the README for how to measure it.

### OpenTelemetry

1. Have an OTLP/HTTP collector or a hosted backend that accepts OTLP over HTTP, and its address and credentials.
2. In the dashboard add `OTEL_EXPORTER_OTLP_ENDPOINT`, the collector's address with its `http://` or `https://` (and, if it wants credentials, `OTEL_EXPORTER_OTLP_HEADERS` as `key=value` pairs separated by commas). Optionally `OTEL_SERVICE_NAME`.
3. Change `OTEL_ENABLED` to `"true"` in `render.yaml` and push. Without an endpoint the backend starts and logs `OTEL_ENABLED is true but neither OTEL_EXPORTER_OTLP_ENDPOINT nor OTEL_EXPORTER_OTLP_TRACES_ENDPOINT is set: no spans are exported`. With an address the SDK refuses, such as `collector:4318` without the scheme, it starts and logs `OpenTelemetry could not be set up (ConfigurationException): no spans are exported`, and never the address.

Each finished question is then exported as a `proppilot.run` span with a child for every model call and every tool call. Spans hold identifiers, names, counts, timings and outcomes, never a prompt, a question, an answer or a tool's arguments. Export is asynchronous and cannot fail a question. I have tested the exporter against a local stand-in for a collector only, and not on Render's free instance, which has a tenth of a CPU.

### Reading traces without OpenTelemetry

Every question's trace is stored in the database whatever the flag says. Open `https://<your-frontend>/admin/traces`, paste `ADMIN_TOKEN` and filter by status, model or flagged answers. Or call `GET /api/admin/runs` and `GET /api/admin/runs/{id}` with the `X-Admin-Token` header. The tables are never pruned (see "What I'd do next" in the README), which on Neon's free tier is a limit worth watching.

### Running the full evals with the secret

The workflow `.github/workflows/evals-full.yml` runs the harness against real Claude. It never runs on its own and it never touches the deployment: it starts its own backend and PostgreSQL inside the GitHub runner.

1. Under **Settings → Secrets and variables → Actions → New repository secret** add `ANTHROPIC_API_KEY`. Without it the workflow ends with a notice and does nothing. The key is passed only to the steps that start the backend and run the harness and is never echoed.
2. Under **Actions → Evals (full) → Run workflow** choose `runs` (start with 1), `judge`, `prompt_caching`, `guardrail_mode`, `only` (id prefixes; empty is every case) and `large_model` (empty switches routing off).
3. It spends real money, about one question per case and run, the earlier questions of the follow-up cases, and one judge call per judged case, and I have not measured what that costs for the current prompt. Set a spend limit in the Anthropic console first and read the first run's cost before raising `runs`.
4. The job summary has the results table and the run uploads `evals-full` (`latest.json` and `latest.md`), including the partial results of a run that was cancelled or timed out. Run it twice with `prompt_caching` true and false to measure the cache, and with and without `large_model` to see what routing does.

## Why the free tier cold start is slow (measured, and what did not help)

Render's free instance has 0.1 CPU, and Spring Boot + Hibernate need about 15 CPU-seconds to start, so a cold start takes roughly 2.5 to 3 minutes. I reproduced this locally with `docker run --cpus=0.1 --memory=512m` and timed start to "Started":

| Variant | Wall time |
|---|---|
| Baseline | 166 s |
| `spring.main.lazy-initialization` | 159 s |
| Lazy init + lighter JVM flags (`-XX:CICompilerCount=1 -Xss256k`) | 154 s |
| AppCDS archive built into the image | 140 s and 163 s (two runs) |
| Spring AOT | 181 s |
| Spring AOT + AppCDS | 168 s and 162 s |

Run-to-run noise is about ±20 s, so none of these is a real improvement and none was shipped. The startup is CPU-bound; the options that actually change it are more CPU (a paid instance, or a host that gives a startup CPU boost) or avoiding cold starts by keeping the service awake.

## Gotcha: blueprint syncs overwrite dashboard values

Render re-applies every value written in `render.yaml` whenever the blueprint syncs, so a variable you changed in the dashboard silently reverts if the file also gives it a `value`. Settings you manage in the dashboard (`LLM_PROVIDER`, `EMBEDDING_PROVIDER`, keys, DB credentials, CORS origins) are therefore marked `sync: false` in `render.yaml` and have no value there. This bit me once: the live demo went back to the free offline assistant after a sync.

The opposite holds for `PROMPT_CACHING`, `GUARDRAIL_MODE` and `OTEL_ENABLED`, which do have values in `render.yaml` on purpose: change them in the file, because a value set in the dashboard is overwritten by the next sync. `ANTHROPIC_MODEL_LARGE` and `OTEL_EXPORTER_OTLP_ENDPOINT` have none, so that a dashboard value stays.

## Admin endpoint and new settings

- `GET /api/admin/runs` lists the latest questions with model, cost, latency and guardrail status (filter with `status`, `model` and `guardrailFlagged`; page backwards with `before`), `GET /api/admin/runs/{id}` returns one run with its question, its trace and what its tools were asked and said, and `POST /api/admin/reset` restores the seeded demo data. All are off unless `ADMIN_TOKEN` is set in the Render dashboard (24 or more characters, for example `openssl rand -hex 24`); call them with the header `X-Admin-Token`. The page `/admin/traces` of the frontend uses them. Visitors' questions are never exposed by the public API: `GET /api/runs/{id}/trace` is public and holds no question, answer, tool arguments or tool results.
- Optional tuning, all with sensible defaults: `LLM_MAX_TOKENS` (2048), `LLM_MAX_RETRIES` (3), `AGENT_RUN_TIMEOUT_SECONDS` (90), `CHAT_MAX_HISTORY_ITEMS` (20). See the table in the README.
- `POST /api/admin/rag/reindex` writes the lease documents that are missing or outdated and embeds the chunks that need it (section 5). Same token, same 404 when `ADMIN_TOKEN` is unset.
- Lease corpus settings: `RAG_DOCUMENT_COUNT` (30, set in `render.yaml`; the evals and the seven leases that end soon assume 30) and `RAG_SEED_ON_STARTUP` (true; `false` writes no documents and runs no embeddings at start).
- Write actions have their own limits, set in `render.yaml`: `ACTION_TTL_MINUTES` (15), `ACTION_DECISIONS_PER_MINUTE` (30), `ACTION_MAX_APPROVALS_PER_DAY` (100), `ACTION_MAX_PROPOSALS_PER_RUN` (3) and `ACTION_MAX_PROPOSALS_PER_DAY` (200). They protect the shared demo data, so keep them low on a public deployment.
- The migrations so far are `V1__schema.sql`, `V2__seed.sql`, `V3__run_status.sql`, `V4__actions.sql`, `V5__lease_documents.sql`, `V6__lease_end_dates.sql`, `V7__traces.sql`, `V8__complexity_trace.sql`, `V9__guardrail_findings.sql` and `V10__tool_call_model_call.sql`; Flyway applies the new ones on the first start. `V4` adds the tables for proposed actions, maintenance requests, the outbox and the audit log, and a copy of the seeded payments (`payments_seed`) that the reset restores from. `V5` creates the `vector` and `pg_trgm` extensions and the `lease_documents` and `lease_chunks` tables with their indexes, and `V6` moves seven lease end dates. The nightly reset does not move those dates back or forward. `V7` to `V10` add the stored traces: `run_llm_calls` and `run_tool_calls`, the cache token, routing and guardrail columns of `chat_runs`, the complexity score and `run_guardrail_findings`. The nightly reset leaves the runs and their traces alone. Never edit a migration that has been applied.
