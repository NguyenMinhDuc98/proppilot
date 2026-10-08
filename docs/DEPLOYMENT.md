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
3. Run the evals against the live URL and paste the table into the README:
   `node evals/run.mjs --url https://<service>.onrender.com --delay-ms 7000` (the delay respects the per-minute rate limit).

## 5. Keep it healthy

- Every push runs CI (backend tests with Testcontainers, frontend typecheck/tests/build, Docker build).
- To avoid the cold start before a demo, open the site a few minutes early.
- If a deploy fails with "Timed out ... health check" and the logs stop right after `No active profile set`, the JVM was still starting on the throttled free CPU. Click **Manual Deploy → Deploy latest commit** again; the previous version keeps serving traffic meanwhile.
- `render.yaml` has a `buildFilter`, so only changes under `backend/` redeploy the API.

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
