# PropPilot

A bilingual (English / Arabic) AI assistant for property managers. A question in either language goes to a hand-written agent loop that calls typed tools against a PostgreSQL property database and streams its work to a Vue app over SSE. Write actions are only proposed; a person approves them. Public portfolio project: all data is fake seed data. Live demo: https://proppilot-lake.vercel.app

## Team
Worked on by the dev-team agents (tech-lead, product-owner, designer, frontend-dev, backend-dev, mobile-dev, devops, qa-tester, code-reviewer). Process: `team-workflow` skill. Project memory: `docs/` (start at `docs/index.md`). The README is the public, detailed description of the system; read the section you need before changing a feature.

## Stack
- **Backend** (`backend/`): Java 21, Spring Boot 3.5, Spring Web, Spring Data JPA, Flyway, Maven wrapper. Anthropic Messages API over plain HTTP (no Spring AI / LangChain4j), with an offline rule-based provider.
- **Database:** PostgreSQL 16 with pgvector and pg_trgm (`pgvector/pgvector:pg16` locally and in tests; Neon in production).
- **Frontend** (`frontend/`): Vue 3 `<script setup>`, TypeScript, Vite, Pinia, Element Plus, vue-i18n (en, ar), vue-router.
- **Evals** (`evals/`): Node ES modules, Node's built-in test runner.
- **Infra:** Docker Compose locally; Render (API, `render.yaml`) + Neon (Postgres) + Vercel (frontend), all free tiers. GitHub Actions CI.

## Commands
| Purpose | Command |
| --- | --- |
| Whole stack | `docker compose up --build` (app :3000, API :8080) |
| DB only (for dev) | `docker compose up -d db` |
| Backend dev | `cd backend && ./mvnw spring-boot:run` |
| Backend tests (unit + `*IT` with Testcontainers, needs Docker) | `cd backend && ./mvnw verify` |
| Frontend install / dev | `cd frontend && npm ci && npm run dev` (:5173, proxies `/api`) |
| Frontend type-check | `cd frontend && npm run typecheck` |
| Frontend tests | `cd frontend && npm test` |
| Frontend build | `cd frontend && npm run build` |
| Eval harness tests | `node --test evals/*.test.mjs` |
| Offline evals (backend running, `LLM_PROVIDER=offline`) | `node evals/run.mjs --offline-only --runs 1` |

There is no lint step; `vue-tsc` and the Java compiler are the static checks.

## CI (`.github/workflows/ci.yml`)
Jobs on every push and PR: `backend` (`./mvnw verify`), `frontend` (typecheck, test, build), `evals` (offline evals against a real backend and pgvector), `docker` (`docker compose build`). These four are the checks auto-merge must wait for. `evals-full.yml` (real Claude, costs money) is started by hand only; `demo-reset.yml` resets the public demo nightly.

## Layout
- `backend/src/main/java/dev/proppilot/`: `agent/` (loop, `llm/` clients incl. `offline/`, `routing/`, `tools/`), `actions/` (propose → approve, audit), `chat/` (SSE endpoint), `rag/` (PDF, chunking, embeddings, retrieval), `guardrail/`, `trace/`, `admin/`, `config/`, `limit/`, `stats/`, `units/`, `info/`, `text/`.
- `backend/src/main/resources/db/migration/`: Flyway `V1`…`V10` (`V2` is the date-relative seed).
- `frontend/src/`: `api/` (HTTP + SSE clients, types), `components/`, `composables/`, `i18n/` (`en.ts`, `ar.ts`), `stores/`, `views/`.
- `evals/`: harness, `questions.json` cases, committed results.
- `docs/`: project memory for the team plus `DEMO.md`, `DEPLOYMENT.md`, screenshots, sample leases.

## Conventions
- **Agent tools:** each tool is a Spring bean implementing `Tool`, takes typed input (never raw SQL from the model), caps results (15 rows + total count) and returns clean error results instead of throwing. Read tools only read; write tools only store a proposal through `ActionService`.
- **Write actions:** nothing changes a business table until a person approves; approval is idempotent (browser-made `Idempotency-Key`), runs in one transaction with row locks, re-validates and writes an audit entry with before/after. Keep it that way.
- **Offline provider:** every feature must work with `LLM_PROVIDER=offline` and `EMBEDDING_PROVIDER=none` (no keys). A new tool or intent needs offline handling and eval cases.
- **Bilingual:** every user-facing string in both `en.ts` and `ar.ts` (a test checks the keys match). Arabic is a full RTL mirror: logical CSS properties only (`frontend/src/components/logicalProperties.test.ts` checks a list of components; add new components to it), bidi-safe numbers, money as SAR.
- **Privacy:** visitors' question text, answers and tool arguments never reach public endpoints, logs or OpenTelemetry spans. Raw provider errors never reach the browser.
- **Settings:** new env settings get a safe default, accept a blank value as the default, and are documented in the README's environment table.
- **Tests:** every behavior change comes with tests; integration tests are `*IT` and use Testcontainers. Neither Anthropic nor Voyage is ever called by a test.
- **Docs style:** the README states only what was measured, with dates and numbers, and says plainly what has not been measured. Keep that standard in docs, PR descriptions and commit messages.
- Conventional commits; one PR per feature; never push to `main`.

## Lessons that cost time here
- **Regex and the stack:** `java.util.regex` recurses once per round of a repeated group, and production runs with `-Xss256k` (a test thread has four times that). No unbounded repeated groups on text a visitor wrote; bound every loop, and give a new parser a hostile-input test on a 256 KB thread (see `ComplexityAssessorHostileInputTest`). Run the backend suite as `./mvnw test -DargLine=-Xss256k` when you touch a pattern.
- **MockMvc ITs:** use `@AutoConfigureMockMvc(print = MockMvcPrint.NONE)`. The result printer races the SSE thread and made `ChatRunOutcomesIT` flaky.
- **The cached prefix:** tool definitions plus the system prompt must stay over 4,096 tokens, the minimum Haiku 4.5 caches; below it nothing is cached and nothing fails. `PromptPrefixIT` guards it. The system prompt's "Conventions and defaults" section names no tool or parameter (a test checks that); the rules section may.
- **Editing `evals/questions.json`:** make surgical text edits. Reformatting it (for example `json.dump`) rewrites every line and hides the change. The first 42 cases keep their order and ids (`questions.test.mjs`).
- **Secrets:** none in git, ever. New settings go in `.env.example` and the README environment table. On Render, keys that are `sync: false` in `render.yaml` are set in the dashboard, because a value in the file would overwrite it on every blueprint sync.
- **Numbers in docs:** a README or evals README figure that a change moves (case counts, test counts, tool count, cache prefix size) is updated in the same PR. Say what was measured, how many runs, and what was not.

## Adding or changing an agent tool
A tool touches more places than its class. Checklist: the `Tool` bean and its input limits; a test against the seeded database (`*ToolIT`) and the name list in `ToolsIT`; a rule in `SystemPrompt` and its test; the label in `tools` of both `en.ts` and `ar.ts` and the tool list in `MessageBubble.test.ts`; offline routing and an offline answer, with a hostile-input test; the `TOOLS` set and a case in `evals/questions.json` (update the counts in `questions.test.mjs`); the tool count in the README diagram and `docs/index.md`. The guardrail reads a tool's result as evidence, so a result states only data, never advice or the model's own input.

## Running evals against the live demo
- A run spends the demo's shared daily question cap (100 on Render, held in memory, so a restart resets it) and real money. Use `--delay-ms 7000` for the per-minute limit and keep a sweep under the cap.
- The `write-` cases leave expired proposals on the Activity page. Reset afterwards: `POST /api/admin/reset` with `X-Admin-Token` (the `demo-reset` workflow does it; start it with `gh workflow run demo-reset.yml`).
- The Render free tier sleeps when idle; the first request after a pause can take minutes.
- A case that fails once on a real model is not yet a finding: run it again (three runs) before changing a prompt or a grader.

## Do not touch without asking Duc
- Already-applied Flyway migrations (`V1`…`V10`): add a new migration instead. The seed (`V2`) feeds eval facts.
- The approval path (`actions/ActionService`, executors, audit) and the admin token check.
- Rate limits, daily caps and their defaults (they protect the public demo and the API budget).
- `render.yaml`, Vercel config, `.github/workflows/demo-reset.yml`, `evals-full.yml`, and anything that would call paid APIs in CI.
- Committed eval results in `evals/results/`.
