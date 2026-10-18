# PropPilot — project index

A bilingual (English / Arabic) AI assistant for property managers: questions are answered by an agent that calls typed tools against a property database, and changes are only proposed until a person approves them. Public portfolio project with fake seed data (3 cities, 6 buildings, 150 units, 120 tenants, 12 months of payments, 30 lease PDFs).

The [README](../README.md) is the detailed, public description of the system. This folder is the team's working memory: specs, decisions, design notes and what shipped.

## Users
| Role | What they do |
| --- | --- |
| Property manager (demo visitor) | Asks questions in English or Arabic, reads answers with citations, approves or rejects proposed changes |
| Owner / admin (Duc) | Reads runs and traces behind `ADMIN_TOKEN`, resets the demo, reindexes lease search, runs paid evals |

## Modules
| Module | Purpose | Code location | Owner agent |
| --- | --- | --- | --- |
| Agent loop & LLM clients | Model calls, tool use, retries, routing, prompt caching, offline provider | `backend/.../agent/` | backend-dev |
| Tools | 9 read tools (including lease search and `find_tenant`), 3 proposal tools | `backend/.../agent/tools/` | backend-dev |
| Write actions | Propose → preview → approve/reject/expire, audit trail | `backend/.../actions/` | backend-dev |
| Chat & SSE | `/api/chat`, events, run accounting | `backend/.../chat/` | backend-dev |
| Lease search (RAG) | PDF generation and extraction, chunking, hybrid retrieval, citations | `backend/.../rag/` | backend-dev |
| Grounding guardrail | Checks figures, names and citations against tool results | `backend/.../guardrail/` | backend-dev |
| Tracing & admin | Stored traces, OpenTelemetry export, admin endpoints | `backend/.../trace/`, `admin/` | backend-dev |
| Web app | Chat, approval cards, citations, trace drawer, Activity, Usage, admin pages | `frontend/src/` | frontend-dev |
| Evals | 66 cases, grading, judge, CI offline subset | `evals/` | qa-tester |
| CI & deploy | GitHub Actions, Docker, Render, Neon, Vercel | `.github/`, `render.yaml`, `docker-compose.yml` | devops |

## Specs
| # | Title | Status |
| --- | --- | --- |
| — | No specs yet. New work starts with a spec in `docs/specs/`. | |

## Key decisions
The design decisions made before the team joined are in the README's [Design decisions](../README.md#design-decisions) section. New decisions go in `docs/decisions/` as ADRs (see `docs/decisions/README.md`).

## Backlog candidates
The README's [What I'd do next](../README.md#what-id-do-next) section lists Duc's candidate features (auth and roles for approvals, real reminder delivery, credit ledger, retention for stored runs, retrieval measurement, a Flutter client, and more). The product owner turns one into a spec when Duc picks it.

## Environments
| Env | URL | Deploys from |
| --- | --- | --- |
| Local | http://localhost:3000 (API :8080) | `docker compose up --build` |
| Production demo | https://proppilot-lake.vercel.app (frontend, Vercel); API on Render; Postgres on Neon | `main` (see [DEPLOYMENT.md](DEPLOYMENT.md)) |
