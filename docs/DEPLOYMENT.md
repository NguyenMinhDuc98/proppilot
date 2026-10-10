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

Flyway creates the schema and seed data on the first backend start; nothing to run by hand.

## 2. Backend (Render)

1. Push the repo to GitHub.
2. In Render: **New → Blueprint**, pick the repo (it reads `render.yaml`). Or **New → Web Service → Docker** with root `backend` if you prefer clicking.
3. Set the secret env vars when prompted:
   - `DB_URL`, `DB_USER`, `DB_PASSWORD` from Neon
   - `CORS_ALLOWED_ORIGINS` = your Vercel URL (set a placeholder first, update after step 3)
   - `ANTHROPIC_API_KEY` only if you switch `LLM_PROVIDER` to `anthropic`
   - `ADMIN_TOKEN` (24 or more characters, `openssl rand -hex 24`): it switches on the admin endpoints, and the nightly demo reset in step 6 needs it
4. Wait for the deploy; `https://<service>.onrender.com/actuator/health` should return `{"status":"UP"}`.

## 3. Frontend (Vercel)

1. In Vercel: **Add New → Project**, import the repo, set **Root Directory** to `frontend`.
2. Environment variable: `VITE_API_URL` = `https://<service>.onrender.com`.
3. Deploy. Copy the production URL.
4. Back in Render, set `CORS_ALLOWED_ORIGINS` to that URL (no trailing slash) and redeploy.

Open the Vercel URL, switch to Arabic with the language button, and ask a question.

## 4. Optional: use real Claude

1. In Render set `LLM_PROVIDER=anthropic`, `ANTHROPIC_API_KEY=<your key>`, optionally `ANTHROPIC_MODEL`.
2. Keep `CHAT_DAILY_LIMIT` and `CHAT_RATE_LIMIT_PER_MINUTE` low so a public demo cannot run up your bill. Also set a monthly spend limit in the Anthropic console.
3. Run the read questions against the live URL and paste the table into the README, with the date and the commit it was measured at:
   `node evals/run.mjs --only en-,ar- --url https://<service>.onrender.com --delay-ms 7000` (the delay respects the per-minute rate limit). The 20 questions use 20 of the day's `CHAT_DAILY_LIMIT`, and they expect the seeded data, which visitors can change by approving payments. Run them soon after the nightly reset, or restore the seed first: `curl -X POST https://<service>.onrender.com/api/admin/reset -H "X-Admin-Token: <ADMIN_TOKEN>"`.
4. Keep the 6 write cases (`--only write-`) out of that run and out of that table. Every run leaves 6 proposals on the Activity page for all visitors to see and uses 6 of the day's proposals and of the daily chat limit, and the two payment cases pass only while unit A-504 has at least SAR 4,950 outstanding, so one approved payment makes them fail for a reason that has nothing to do with the model. Run them against a local stack, or on the live URL straight after a reset, and report their result on its own.

## 5. Keep it healthy

- Every push runs CI (backend tests with Testcontainers, frontend typecheck/tests/build, Docker build).
- To avoid the cold start before a demo, open the site a few minutes early.
- If a deploy fails with "Timed out ... health check" and the logs stop right after `No active profile set`, the JVM was still starting on the throttled free CPU. Click **Manual Deploy → Deploy latest commit** again; the previous version keeps serving traffic meanwhile.
- `render.yaml` has a `buildFilter`, so only changes under `backend/` redeploy the API.

## 6. Nightly demo reset

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

Render re-applies every value written in `render.yaml` whenever the blueprint syncs, so a variable you changed in the dashboard silently reverts if the file also gives it a `value`. Settings you manage in the dashboard (`LLM_PROVIDER`, keys, DB credentials, CORS origins) are therefore marked `sync: false` in `render.yaml` and have no value there. This bit me once: the live demo went back to the free offline assistant after a sync.

## Admin endpoint and new settings

- `GET /api/admin/runs` lists the latest questions with cost and latency, and `POST /api/admin/reset` restores the seeded demo data. Both are off unless `ADMIN_TOKEN` is set in the Render dashboard (24 or more characters, for example `openssl rand -hex 24`); call them with the header `X-Admin-Token`. Visitors' questions are never exposed by the public API.
- Optional tuning, all with sensible defaults: `LLM_MAX_TOKENS` (2048), `LLM_MAX_RETRIES` (3), `AGENT_RUN_TIMEOUT_SECONDS` (90), `CHAT_MAX_HISTORY_ITEMS` (20). See the table in the README.
- Write actions have their own limits, set in `render.yaml`: `ACTION_TTL_MINUTES` (15), `ACTION_DECISIONS_PER_MINUTE` (30), `ACTION_MAX_APPROVALS_PER_DAY` (100), `ACTION_MAX_PROPOSALS_PER_RUN` (3) and `ACTION_MAX_PROPOSALS_PER_DAY` (200). They protect the shared demo data, so keep them low on a public deployment.
- The migrations so far are `V1__schema.sql`, `V2__seed.sql`, `V3__run_status.sql` and `V4__actions.sql`; Flyway applies the new ones on the first start. `V4` adds the tables for proposed actions, maintenance requests, the outbox and the audit log, and a copy of the seeded payments (`payments_seed`) that the reset restores from. Never edit a migration that has been applied.
