# PropPilot

[![CI](https://github.com/NguyenMinhDuc98/proppilot/actions/workflows/ci.yml/badge.svg)](https://github.com/NguyenMinhDuc98/proppilot/actions/workflows/ci.yml)

**A bilingual (English / Arabic) AI assistant for property managers: ask a question in either language and an agent answers by calling typed tools against a property database, streaming its work live.**

> **Live demo:** https://proppilot-lake.vercel.app runs **real Claude (Haiku 4.5)**. A badge at the top of the app always shows the active mode: `Claude Haiku 4.5` or `Offline demo`. Run locally with no key and it uses the free offline assistant instead. The free-tier API sleeps when idle, so the first request after a pause can take a few minutes while it wakes up.

All data is fake seed data (3 cities, 6 buildings, 150 units, 120 tenants, 12 months of payments), and the 30 lease documents are generated from it. No real companies or people.

## Screenshots

Captured from the live demo, answered by real Claude.

![English chat: tool chip, data card, localized source line, cost and latency](docs/screenshots/chat-en.jpg)

The "How I answered" panel shows each step, the tool arguments, tokens, cost, latency and model:

![The "How I answered" trace panel](docs/screenshots/trace-drawer.jpg)

Arabic is a full right-to-left mirror, not just translated text, and the layout adapts to phones:

![Arabic chat, right-to-left](docs/screenshots/chat-ar.jpg)

<img src="docs/screenshots/chat-mobile-ar.jpg" alt="Arabic chat on a phone: top bar, data card that scrolls sideways, bottom tab bar" width="300">

## What it shows

- **A hand-written agent loop** (no Spring AI / LangChain4j): send messages and tool definitions, run the tools the model asks for, feed results back, repeat, with a hard iteration cap.
- **Tool registry:** every tool is a Spring bean implementing `Tool`; the registry collects them and generates the tool list for the API. Tools are typed (no raw SQL from the model) and return clean errors the model can act on. Eight only read; three only propose a change.
- **Human-in-the-loop write actions:** the agent can propose recording a payment, opening a maintenance request or queueing a tenant reminder, but nothing is written until a person approves it in the app. Approval is idempotent, runs in one transaction and is audited ([details](#human-in-the-loop-write-actions)).
- **Lease search with citations (RAG):** lease questions are answered from lease PDFs in English and Arabic. Hybrid retrieval (vectors, full text and trigrams, fused with reciprocal rank fusion) finds the clauses, the answer cites each one as `[LSE-A-203#5]`, and the app turns the marker into a chip that opens the clause and its PDF. It works with no embedding key, on keywords alone ([details](#lease-search-rag)).
- **Streaming UX over SSE:** the UI shows live "Searching units…" chips, then streams the answer.
- **Cost and latency tracking** per question (`chat_runs` table, shown under each answer and on a Usage page).
- **A transparent agent:** every answer has an expandable "How I answered" panel showing the tool call and its arguments, what the tool returned, tokens, cost and latency. It is drawn from the run's stored trace ([details](#tracing-and-observability)).
- **Grounding guardrail:** every figure, unit code, tenant name and lease citation in an answer is held against what the run's tools returned, and an answer that states something nothing supports is flagged. Warn-only by default ([details](#grounding-guardrail)).
- **Model routing with fallback, and prompt caching:** a small model answers; with a larger one configured, an explainable complexity score sends multi-step questions to it and an overload falls back to the other model. Requests mark their prefix for caching and the cache tokens the API reports are priced ([routing](#model-routing-and-fallback), [caching](#prompt-caching)).
- **Evals v2:** 64 cases in nine categories, graded on answer, tool arguments, citations, proposals and refusals, with repeated runs, an optional LLM judge, and an offline subset in CI ([details](#evals-v2)).
- **Real Arabic support:** vue-i18n, `dir="rtl"` flipping, Element Plus Arabic locale, bidi-safe numbers.
- **Resilient LLM calls:** retries with exponential backoff and jitter on 429/5xx/529 (never after text has streamed), a per-question run deadline, replies cut off by `max_tokens` detected and flagged, and coded errors with localized messages (raw provider text never reaches the browser).
- **Honest accounting:** a run that the browser abandons is still recorded with the tokens and cost spent so far (`ABORTED`).
- **Privacy by default:** the public Usage page shows aggregates only; visitors' question text is readable only through an admin endpoint behind a token. Decisions are attributed to an anonymous name, never an address.
- **Zero-cost by default:** the app runs with a built-in offline assistant, so it works with no API key. Set one env var to switch to real Claude.

## Architecture

```mermaid
flowchart LR
    UI["Vue 3 SPA<br/>(Pinia, Element Plus, vue-i18n)"] -- "POST /api/chat" --> API["Spring Boot 3<br/>ChatController"]
    API -- "SSE: tool_call · tool_result (+ sources) · action_proposed · token · revise · guardrail · done · error" --> UI
    API --> LOOP["AgentLoop<br/>(max 6 iterations)"]
    LOOP <-- "messages + tool defs / tool calls" --> LLM{"LlmClient"}
    LLM --> CLAUDE["Claude Messages API<br/>(streaming)"]
    LLM --> OFFLINE["Offline assistant<br/>(rule-based, free)"]
    LOOP -- "execute(name, input)" --> REG["ToolRegistry"]
    REG --> T["search_units · get_unit_details · find_overdue_tenants<br/>get_occupancy_summary · get_payment_history · draft_tenant_message"]
    T --> DB[("PostgreSQL<br/>Flyway schema + seed")]
    REG --> W["record_payment · create_maintenance_request<br/>send_tenant_reminder<br/>(propose only)"]
    W -- "store a PROPOSED action" --> ACT[("pending_actions<br/>expire if undecided")]
    UI -- "preview · approve + Idempotency-Key · reject" --> APPROVAL["ActionService<br/>(one transaction per approval)"]
    APPROVAL -- "lock and re-validate" --> ACT
    APPROVAL -- "payments · maintenance_requests<br/>outbox_messages (delivered = false)" --> DB
    APPROVAL --> AUDIT[("audit_log<br/>who · when · before · after")]
    API --> RUNS[("chat_runs + run_llm_calls + run_tool_calls<br/>tokens · cache · cost · latency · trace")]
    UI -- "GET /api/units, /api/stats, /api/info, /api/runs/{id}/trace, /api/actions, /api/audit, /api/lease-documents" --> API

    REG --> L["search_lease_documents · find_expiring_leases<br/>(read only)"]
    L -- "question" --> RETR["LeaseRetriever<br/>vector + full text + trigram<br/>fused with RRF (k = 60)"]
    RETR --> CHUNKS[("lease_chunks<br/>pgvector HNSW · tsvector · pg_trgm")]
    L -- "clauses, with citation refs" --> LOOP
    L -. "sources: the clauses found<br/>(never sent to the model)" .-> API

    subgraph INGEST["Lease corpus: written in the background at startup, from the database rows"]
        SEED["LeaseDocumentSeeder<br/>first 30 active leases"] --> GEN["LeasePdfGenerator<br/>PDFBox + ICU4J, English or Arabic"]
        GEN --> DOCS[("lease_documents<br/>PDF bytes")]
        GEN --> READ["PdfTextExtractor<br/>PDFBox text, a page at a time"]
        READ --> CUT["LeaseChunker<br/>one chunk per clause"]
        CUT --> CHUNKS
        EMB["EmbeddingClient<br/>Voyage AI, or none"] -. "vectors, when a provider is set" .-> CHUNKS
    end
    DB --> SEED
    RETR -. "embeds the question" .-> EMB
```

## How the agent loop works

`AgentLoop` ([source](backend/src/main/java/dev/proppilot/agent/AgentLoop.java)) is the whole idea. Each step is one model call and the tools it asked for (simplified from `step()`):

```java
var interruption = interruption();               // the client left, or the run is out of time
if (interruption.isPresent()) return interruption;

var response = llm.complete(new LlmRequest(system, List.copyOf(messages), toolSpecs, timeLeft(), model,
        retryTimeouts, iterations),                               // iterations: which call of the run this is
        text -> events.accept(new AgentEvent.Token(text)));       // stream text as it arrives
usage = usage.plus(response.usage());

if (response.stopReason() == StopReason.MAX_TOKENS) {             // tool calls may be cut off: run none
    return Optional.of(result(Status.TRUNCATED, response.text()));
}
messages.add(new Message(Message.Role.ASSISTANT, response.content()));

var requested = response.toolUses();
if (requested.isEmpty()) {                                        // final answer
    return Optional.of(result(Status.OK, response.text()));
}
return runTools(requested);                                       // results go back on the next step
```

The loop repeats steps until a final answer, the iteration cap (default 6) or the run deadline (default 90 s).

- `ToolRegistry.execute` never throws: bad input, unknown tools and unexpected failures come back as error results (`is_error: true`), so the model can correct itself instead of the request failing.
- The system prompt tells the model to reply in the user's language, only state numbers returned by tools, and not to mention internal tool names; the UI shows the data sources itself as localized labels. Its "Conventions and defaults" section says how the portfolio and the tools work (the buildings and what a unit code is, the payment statuses and what overdue means, how to write money) and what to do instead of asking (a reminder is a rent reminder, the language of a draft, this month), when to ask, how to decline and to prefer a figure a tool returns over one it computes.
- Tool results reach the model, and the card of a proposal reaches the browser, with every amount as a plain decimal (`4800.00`, `80.0`, `0.50`), never in scientific notation (`4.8E+3`): Jackson's tree strips the trailing zeros of a `BigDecimal`, so the JSON of a result is written from the data directly (`ToolJson`) and amounts are never passed through a float.
- `LlmClient` has two implementations: `AnthropicLlmClient` (plain HTTP, a retry policy, a stream watchdog, and a hand-written SSE parser for text and tool-use blocks) and `OfflineLlmClient`.
- The agent loop is tested against a scripted fake LLM: tool round trips, error feedback, the iteration cap, truncation, cancellation, the deadline, LLM failures and history handling.

### SSE events

`POST /api/chat` with `{"message": "...", "history": [{"role":"user|assistant","text":"..."}]}`:

| Event | Payload |
|---|---|
| `tool_call` | `{name, args}` |
| `tool_result` | `{name, summary, error}`; the result of a lease search also carries `sources`, the clauses it found (see [Lease search](#sources-on-the-tool_result-event)) |
| `action_proposed` | the proposed action, sent right after the `tool_result` of the tool that proposed it: `{id, kind, status, args, preview, summaryEn, summaryAr, createdAt, expiresAt, ...}` |
| `token` | `{text}` |
| `revise` | `{reason}`, where `reason` is `grounding`: drop the answer streamed so far, the guardrail found values in it that nothing the run was given supports and a new answer follows (`GUARDRAIL_MODE=retry`). It comes as the new answer begins. If that answer never completes, `revise` comes again and the first answer is sent once more as `token`s |
| `error` | `{message, code}`, where `code` is `llm_overloaded`, `llm_timeout`, `llm_auth`, `llm_error` or `run_timeout` |
| `guardrail` | `{status, values}`, sent before `done` and only for an answer that states values the run cannot ground: `status` is `unverified` and `values` are up to 10 `{kind, value}` with `kind` one of `NUMBER`, `UNIT_CODE`, `TENANT_NAME` and `CITATION`, each value cut to 40 characters |
| `done` | `{runId, status, provider, model, routedReason, guardrailStatus, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, toolCalls, iterations, latencyMs, costUsd, truncated}` |

In `done`, `model` is the model that gave the answer and `routedReason` why (`DEFAULT`, `COMPLEX`, `GUARDRAIL_RETRY` or `FALLBACK`). `inputTokens` counts only the input tokens that were neither read from nor written to the prompt cache, which are `cacheReadTokens` and `cacheWriteTokens`; the four counts are disjoint and each has its own price. `guardrailStatus` is `OK`, `FLAGGED`, `RETRIED_OK` or `RETRIED_FLAGGED`, and `null` when the answer was not checked (mode `off`, a run that failed or was cut off, or a check that itself failed). An example from the offline assistant, unedited apart from the line break: `{"runId":"60c3da31-…","status":"OK","provider":"offline","model":"rule-based","routedReason":"DEFAULT","guardrailStatus":"OK","inputTokens":368,"outputTokens":228,"cacheReadTokens":0,"cacheWriteTokens":0,"toolCalls":1,"iterations":2,"latencyMs":193,"costUsd":0.000000,"truncated":false}`.

```bash
curl -N -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Who is more than 30 days late on rent?"}'
```

A request that proposes a change streams `tool_call`, `tool_result`, `action_proposed`, the `token`s of the answer and `done`. After a person decides, the web app sends `{"actionId": "<id>"}` instead of a message and the assistant confirms the outcome in the chat; see the next section.

## Human-in-the-loop write actions

The agent can propose changes but it cannot make one. A write tool only stores a proposal. A person approves it in the app, and only that approval changes data.

1. **Propose.** `record_payment`, `create_maintenance_request` and `send_tenant_reminder` validate their arguments against the current data and store a `PROPOSED` action: the arguments, a preview of what would change, and a one-sentence summary in English and Arabic. No business table is touched. The chat streams an `action_proposed` event and the UI shows an approval card under the answer. The model is told the change is only waiting for approval and must not say it was made.
2. **Preview.** `POST /api/actions/{id}/preview` recomputes the diff, optionally with the edits an approval would carry (a payment's amount can be changed), and changes nothing.
3. **Approve.** `POST /api/actions/{id}/approve` requires an `Idempotency-Key` header (1 to 64 letters, digits, `-` or `_`; the web app makes one per proposal). The action row is locked, validated again against the data as it is now (an invoice may have been paid since the proposal), executed and audited in **one transaction**; if anything fails, nothing is written. Repeating a call with the same key returns the first outcome with `replayed: true` and changes nothing, so a retry after a lost response cannot apply a change twice. Two approvals racing for one action run it once. A key used for another action is refused with 409.
4. **Reject.** `POST /api/actions/{id}/reject` with an optional reason (up to 200 characters).
5. **Expire.** A proposal nobody decides expires after `ACTION_TTL_MINUTES` (15 by default). There is no scheduler: the status flips, with an audit entry, the next time the action is read or decided, and approving it then answers 410.
6. **Audit.** Every step writes an `audit_log` row in the same transaction as the change: who (`agent`, `system`, `admin` or an anonymous `visitor-xxxxxxxx`), when, what happened (`PROPOSED`, `EDITED`, `APPROVED`, `REJECTED`, `EXPIRED`, `FAILED`, `RESET`) and the state of what changed before and after. The Activity page lists the proposals with their trail.
7. **Confirm in the chat.** After a decision the web app asks the assistant to confirm it once. The server writes that turn from the stored action (the client sends only the id), only the visitor who decided can ask for it, and a confirmation that never reached the person is given back.

### The three tools

| Tool | On approval | Validation |
|---|---|---|
| `record_payment` | Applies the amount to the lease's due invoices, oldest first; an invoice can be paid in part (`PARTIAL`). Stores the method and the date received | Tenant by unit code or name; amount above 0, at most SAR 10,000,000 and 2 decimals; date not in the future and not before the lease started (default today); method `CASH`, `BANK_TRANSFER` (default), `CARD` or `CHEQUE`. More than is outstanding is refused unless the override is set |
| `create_maintenance_request` | Inserts an `OPEN` request for the unit | Unit must exist; category `PLUMBING`, `ELECTRICAL`, `HVAC`, `APPLIANCE`, `STRUCTURAL`, `CLEANING` or `OTHER`; priority `LOW`, `MEDIUM`, `HIGH` or `URGENT`; description 5 to 500 characters, control characters flattened to spaces |
| `send_tenant_reminder` | Stores a message in the outbox with `delivered = false` | Purpose `RENT_REMINDER`, `LEASE_RENEWAL` or `MAINTENANCE_NOTICE`; language `EN` or `AR` (default: the tenant's preference); a rent reminder needs overdue rent. The text comes from a fixed template, the model chooses only purpose and language, and it is written again from today's figures at approval |

Shared rules:

- A tenant is found by `unit_code` (preferred) or `tenant_name` in English or Arabic. The proposal is refused when no tenant matches, several do (the message lists their units), the tenant does not live in that unit, or the unit has no active lease.
- An overpayment is never stored. An invoice cannot hold more than its amount and there is no credit ledger yet, so even with the override the excess is only reported as `overpaymentCredit` in the result and the audit log.
- The model's input is never stored as it came: each tool builds the proposal from the fields it accepts. On approval only the fields an executor lists as editable can be overwritten (a payment's amount and the override).
- A refused request answers `{code, message}` with a stable code (for example `amount_exceeds_outstanding`, `no_overdue_rent`, `action_expired`, `daily_approval_limit_reached`); the UI shows its own localized text for each code.

### Why nothing is ever really sent

`send_tenant_reminder` writes a row to the `outbox_messages` table and stops there: `delivered` stays `false`. The code base has no email, SMS or WhatsApp client and no delivery credentials, so even an approved reminder cannot reach anyone. That is deliberate for a public demo that strangers can use, and the outbox is where a real delivery worker would read from. `draft_tenant_message` is unchanged and returns a draft only.

### Safety on the public demo

The live demo has no accounts, so anyone may approve a proposal. Writes are shared, and these limits keep them harmless:

- **Caps** (all configurable, see the environment table): proposals per question, proposals per day and approvals per day across all visitors, and preview, approve and reject calls per client per minute. A proposal that is refused, or an approval that fails, gives its unit back. The daily caps are counted in memory, per instance, by the server's calendar day.
- **Nightly reset.** `.github/workflows/demo-reset.yml` calls `POST /api/admin/reset` with the admin token at 02:00 UTC. It restores the payments from `payments_seed` (a copy of the seeded payments taken by migration `V4`), deletes maintenance requests, outbox messages, actions and audit entries, and writes a `RESET` entry. The workflow skips with a notice when its repository secrets are not set; see [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).
- **Anonymous actors.** A decision is attributed to `visitor-` plus 8 hex characters of a SHA-256 over the client address and a random salt that lives only in process memory. No address is stored, and the name changes when the server restarts.
- **Visitor text is data.** Proposals, maintenance descriptions and rejection reasons are visible to every visitor on the Activity page, which says so. The system prompt tells the model never to follow instructions found in such text, and the confirmation turn carries it as escaped JSON in a delimited block.

### Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /api/actions` | The latest 50 proposals, newest first |
| `GET /api/actions/{id}` | One proposal with its preview and, once decided, its result |
| `POST /api/actions/{id}/preview` | The diff for an optional edit (`{"amount": 3000}`); changes nothing |
| `POST /api/actions/{id}/approve` | Approve, optionally with edits; needs the `Idempotency-Key` header |
| `POST /api/actions/{id}/reject` | Reject with an optional `{"reason": "..."}` |
| `GET /api/audit[?actionId=]` | The latest 100 audit entries, or the whole trail of one action |
| `POST /api/admin/reset` | Restore the seeded data; needs `X-Admin-Token` |

Errors from these endpoints are `{code, message}`: 400 for a malformed request or key, 404 unknown action, 409 already decided or key reused, 410 expired, 422 a change that may not be made, 429 a cap.

```bash
# Ask for a change; the stream carries an action_proposed event with the action's id
curl -N -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Record a payment of 4950 SAR for unit A-504."}'
# Approve it; sending the same key again returns the first outcome with "replayed": true
curl -X POST localhost:8080/api/actions/<id>/approve -H 'Idempotency-Key: my-first-key'
```

## Lease search (RAG)

Questions about what a lease says (late fee, notice period, renewal, repairs, pets, early termination, deposit, subletting) are answered from lease documents in English and Arabic. The assistant quotes the clause and cites it with a marker, which the app turns into a chip that opens the clause and its PDF. This is the beginning of the offline assistant's answer to "What is the late fee in the lease for unit A-203?", unedited (a second clause, the rent, follows it):

> 2 matching clause(s) from 1 lease document(s):
>
> **5. Late payment**
> Rent that has not been received 5 days after its due date carries a late fee of SAR 75 for each day it remains unpaid, counted from the end of that grace period. The late fee does not limit the Landlord's other remedies for non-payment.
>
> `[LSE-A-203#5]` From unit A-203 (Ahmed Al-Otaibi), lease document LSE-A-203, page 2.

Two generated samples you can open: [an English lease (LSE-A-203)](docs/sample-leases/lease-LSE-A-203-english.pdf) and [an Arabic lease (LSE-B-203)](docs/sample-leases/lease-LSE-B-203-arabic.pdf). The dates inside them are those of the database they were exported from (2026-10-11).

### Pipeline

1. **Generate, at startup, from the database.** When the application is ready, a background thread writes a lease PDF for each of the first 30 active leases (`RAG_DOCUMENT_COUNT`), in the tenant's language. A document is a pure function of the lease's rows (unit, building, tenant, rent, dates) and of the clause terms derived from the lease id (`LeaseTerms`: notice period, late fee, renewal, repair limit, pets, early termination and so on), so the same lease always gives the same text. The PDF bytes are stored in `lease_documents`. Writing is idempotent: a lease whose document was written by the current generator version is left alone, so a restart changes nothing, and `RAG_SEED_ON_STARTUP=false` skips it.
   **Why generated and not committed.** The seed data is relative to the migration date (every lease starts 11 months before that month and ends 13 months after it), so a PDF committed to the repository would contradict the database as soon as it was migrated on another day. The documents are therefore written from the rows, and then ingested through the path a real PDF would take: bytes, then text extraction, chunking and embeddings. The chunks come from the text read back out of the PDF, never from the template, so what is searched is what a reader of the PDF would find. The two samples above are the only PDFs in the repository.
2. **Extract text.** PDFBox's `PDFTextStripper`, a page at a time.
3. **Chunk by clause.** One chunk for the preamble (`#0`) and one for each numbered clause (`#7`). A clause of more than 500 estimated tokens is split on sentence boundaries into parts (`#7a`, `#7b`), each starting with about 50 tokens of the part before it. Every chunk begins with its clause heading, page footers and the signature block are left out, and a heading only counts when its number is the next one in sequence, so a paragraph that starts with a number cannot make a clause of its own. The 30 documents of a fresh database are 94 pages and 409 chunks, 18 of them parts of long clauses. Chunks are stored with a folded copy of the text (`search_text`) for keyword search.
4. **Embed, optionally.** `EmbeddingClient` has two implementations: Voyage AI (`POST https://api.voyageai.com/v1/embeddings`, `input_type` `document` or `query`, default model `voyage-4-lite`, 1024 dimensions, both configurable) and a no-op. A background run gives every chunk without a vector one, in batches of 32, with the document title in front of the chunk so that "30 days" is not detached from whose lease it is. A failure of the provider stops the run and leaves the chunks as they are; `POST /api/admin/rag/reindex` or the next start carries on. Vectors are stored in a `vector(1024)` column with an HNSW cosine index, together with the model that made them. The embedding of a question is cached (the last 256), and a question whose embedding fails is answered by keywords. The tokens of embedding calls are not part of the cost shown under an answer, which covers the chat model only.
5. **Retrieve with three lists and fuse them.** A question is looked up by vector distance, by full text search (`arabic` and `english` configurations on the folded text) and by trigram word similarity (`pg_trgm`). A document is written in its tenant's language, so keywords alone would not let an English question find an Arabic clause: the full text list also searches for the words of the lease topics the question is about (notice period, late fee, pets and so on) in both languages. In the full text list a clause whose title names the topic comes before one that only mentions it (the early termination clause says "notice" as often as the one titled "Notice period"). A question about several named leases is searched lease by lease, so each one gets its own clauses, at least two each (up to ten leases, twenty clauses). Each list gives its best 20 chunks, restricted to the named leases when the question names a unit or a tenant, and the lists are fused with **reciprocal rank fusion**: a chunk scores the sum of `1 / (60 + rank)` over the lists it is in, so a chunk that several lists agree on beats one that a single list likes a lot, with no common scale needed between cosine distances and text ranks. The vector list is an exact scan on purpose: the HNSW index looks at a few dozen candidates of the whole table and only then applies the lease filter, which can leave a lease with none of its own chunks; the corpus is a few hundred rows. The search says whether it ran `hybrid` or `keyword`.
6. **Answer and cite.** The tool result carries each clause with its `ref`, and the system prompt makes the model put the marker of a clause directly after every statement that rests on it.

### Arabic PDF extraction: what was found and how it is handled

I spiked the round trip before building on it. Generation with PDFBox 3.0.8 and ICU4J 78.3 (`ArabicShaping.LETTERS_SHAPE`, then a Bidi reorder to visual order, drawn with `PDType0Font` and the bundled IBM Plex Sans Arabic under the SIL OFL) renders correctly, and `PDFTextStripper` returns Arabic words in the right logical order. Three things come back wrong or moved:

| Finding | How it is handled |
|---|---|
| A Latin token at the end of a right-to-left line (a unit code such as `A-504`) can come back at the start of the line, and two Latin runs in one line can swap places | The Arabic template keeps one Latin run to a line (the unit code, the building name and the address each get a labelled line of their own), and nothing reads a value from the position of a Latin token. The unit and tenant of a chunk come from the database row of its document, not from the text |
| A shaped vowel mark can come back as a tatweel followed by the mark | The chunker drops the tatweel. A folded copy of each chunk is stored for search (diacritics removed, alef, ya and ta marbuta unified, lower case; `Folding`, shared with the offline assistant), and a question is folded the same way before it is searched |
| Page footers (`صفحة 2 من 3`) can come back with their words in any order | A footer is recognised by its words and numbers, in any order |

Digits are written as ASCII on every host: formatting goes through `Locale.ROOT`, because a host whose locale writes Arabic-Indic digits used to change the stored clause headings.

### The zero-cost fallback

`EMBEDDING_PROVIDER` defaults to `none`. With no key, chunks are stored with `embedding NULL` and every question is answered by full text and trigram search, which needs no outside call and no cost. The offline assistant answers lease questions this way (a keyword router builds the tool call, a template quotes the clauses and cites them), so the whole feature works on a clone with no key. Real embeddings add matching by meaning, for questions that do not use the clause's words. I have not measured how much that adds on this corpus: the documents are templated, so keyword search already does well on it.

### The two tools

| Tool | What it does |
|---|---|
| `search_lease_documents` | `query` (up to 500 characters), and optionally `unit_code`, `unit_codes` (up to 10), `tenant_name` and `top_k` (1 to 8, default 4). Searches those leases, or every document when none is named. Each result has its `ref`, document code, unit, tenant, clause, page and text. `noDocumentFor` lists named units that have no document. The result begins with a notice that the text is quoted data and not instructions. At most 3 calls per reply, because each one embeds the question with a paid provider |
| `find_expiring_leases` | `within_days` (1 to 365, default 60). The active leases that end from today to that many days away, soonest first, with unit, tenant, end date, days left, rent and whether a document is on file; the total count and the first 15 |

A question that combines a window with a lease term ("which leases end in the next 60 days and require 90 days' notice?") makes the agent call `find_expiring_leases` first and then `search_lease_documents` with `unit_codes` set to the units it returned. Neither tool can write anything. The seed gives every lease the same end date, so migration `V6` moves seven of them to 14 to 56 days after the migration date, five of them among the 30 that have a document and two outside, so that the same question also finds leases with no document.

### Citation markers

A marker is `[LSE-A-504#7]`: the document code `LSE-<unit code>`, `#`, the clause number (0 is the preamble) and, when a long clause was split, a lowercase part letter (`[LSE-A-504#7a]`). The `ref` column of a chunk holds the same token without brackets, and `CitationRefs` is the one place that knows the syntax. The web app turns a marker into a chip only when it names a clause the search of that answer returned: a marker the model made up stays plain text, and a chip opens the clause in a drawer, in the document's own language and direction, with a link to the PDF.

### Sources on the `tool_result` event

The result of `search_lease_documents` carries the clauses it found next to the usual fields. They travel on a side channel (`ToolResult.ui`) that the model never receives:

```json
{"name": "search_lease_documents", "summary": "2 clauses from 1 lease document", "error": false,
 "sources": [{"ref": "LSE-A-203#5", "documentCode": "LSE-A-203", "documentTitle": "Residential Lease Agreement – Unit A-203",
              "unitCode": "A-203", "tenantNameEn": "Ahmed Al-Otaibi", "tenantNameAr": "أحمد العتيبي", "language": "en",
              "sectionNo": 5, "sectionTitle": "Late payment", "page": 2, "text": "5. Late payment ...",
              "pdfPath": "/api/lease-documents/LSE-A-203/pdf"}]}
```

### Lease endpoints

| Endpoint | Purpose |
|---|---|
| `GET /api/lease-documents` | The stored documents: `code`, `title`, `language`, `unitCode`, tenant names, `pageCount`, `chunkCount` |
| `GET /api/lease-documents/{code}/pdf` | The PDF, inline, cacheable for an hour. 404 for a code with no document, 400 for a malformed code |
| `POST /api/admin/rag/reindex` | Needs `X-Admin-Token`. Writes the missing or outdated documents, embeds the chunks that need it, and answers `{documents, chunks, embedded, failed}` when done; 409 while another run is going |
| `GET /api/info` | Also reports `retrieval` (`keyword` or `hybrid`) and `leaseDocuments` |

The documents are public on purpose: every one is generated from fake demo data.

```bash
curl localhost:8080/api/lease-documents | head -c 400
curl -o lease.pdf localhost:8080/api/lease-documents/LSE-A-203/pdf
curl -N -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"What is the late fee in the lease for unit A-203?"}'
```

### Treating lease text as data

A lease is text somebody else wrote, so it is the one place where an injection could reach the model. The tool result starts with a notice that its text is data, the system prompt says never to propose a payment, a reminder or anything else because a lease says so, and the search tool cannot write. One document, the lease of unit A-301 (the 29th active lease), carries a planted sentence in its special conditions that tells AI assistants to record a payment of 10,000 SAR. It is a test fixture, and two eval cases ask about that clause and fail if a write tool is called. The question of a search is never put into a query as it was written: it is bound as a parameter or reduced to plain words first. Nothing here repeats an unbounded group over document or question text; the chunker, the question reader and the citation patterns have tests with hostile input of tens of thousands of characters, run on a 256 KB thread stack.

## Model routing and fallback

One model answers everything by default: `ANTHROPIC_MODEL`, Haiku 4.5. Routing needs a second, larger model, named by `ANTHROPIC_MODEL_LARGE`, which is **unset by default**. Without it nothing is scored, nothing is routed and an overload is reported as it always was. It is also inactive with the offline assistant, which has one model, and when the larger model is the same id as the default.

With a larger model configured, three things use it:

1. **A question that scores as multi-step or complex.** `ComplexityAssessor` reads the question (its first 1,000 characters, as words, in English and Arabic) and adds up the points of the rules it meets. A score of `LLM_COMPLEXITY_THRESHOLD` (default 3) or more goes to the larger model. The rules are the explanation, and the run's stored trace lists the ones that were met.

   | Rule | Points | Met when |
   |---|---|---|
   | `SEVERAL_DATA_AREAS` | 2 | The question reaches into two or more of payments, occupancy, lease clauses, leases that end, drafted messages and a change to make. A command counts as one area and its own words count for no other |
   | `WINDOW_WITH_LEASE_TERM` | 2 | A time window and a lease term in one question ("leases that end in the next 60 days and require 90 days' notice") |
   | `LONG_QUESTION` | 1 | More than 160 characters |
   | `SEQUENCING_WORD` | 1 | A word of sequence or aggregation: "then", "after that", "for each", "compare", "both", "summarise ... and", and their Arabic equivalents |
   | `MANY_UNITS` | 1 | Three or more different unit codes |
   | `LONG_CONVERSATION` | 1 | Six or more earlier messages in the conversation |

   "Who is more than 30 days late on rent?" and "What is the late fee in the lease for unit A-203?" score 0. "Which units are vacant and who is overdue on rent?" scores 2 and stays on the default model, "Which units are vacant and who is overdue on rent? Compare them for each building" scores 3 and goes to the larger one, and "Which leases end in the next 60 days and require 90 days notice?" scores 4. Scoring never fails a question: whatever goes wrong in it, the default model answers.
2. **An answer the guardrail asks for again** (`GUARDRAIL_MODE=retry`, see [Grounding guardrail](#grounding-guardrail)).
3. **A call that fell back.** An overload (`llm_overloaded`, after the client's own retries on 429, 5xx and 529) or a timeout (`llm_timeout`) is made again, once per run, on the other model: the larger one for a run on the default, the default for a run on the larger. It happens only while the run has time and iterations left and only when none of that call's text has reached the browser, which would otherwise read the answer twice. When another model can take the call, a call that timed out is not retried on the model that stalled, because that would use the time the other one needs. A run that still fails reports the code of the first failure. Without a second model, the overload is reported with the `error` event.

Which model answered is recorded for every run: `chat_runs.model`, the `done` event (`model` and `routedReason`), the stored trace (with the score and the rules), the Usage page and the admin runs list.

### Cost

A call is priced by the model it was made with, from that model's prices: `LLM_PRICE_INPUT_PER_MTOK` / `LLM_PRICE_OUTPUT_PER_MTOK` (default 1.00 / 5.00 USD per million tokens) for the default model and `LLM_LARGE_PRICE_INPUT_PER_MTOK` / `LLM_LARGE_PRICE_OUTPUT_PER_MTOK` (default 3.00 / 15.00) for the larger one. At those defaults the same tokens cost three times as much on the larger model. A routed question therefore costs about three times what it would have on the default; a guardrail retry adds a model call, on the larger model when there is one; a fallback adds the call on the other model to the one that failed, and the app records no tokens for a call that failed, so the cost shown can miss what a call that timed out used. Set the prices of the model you configure: I have not checked the defaults against a current price list.

### What has not been measured

No real Claude traffic has gone through the router. I do not know what share of the questions of a real deployment score 3 or more, whether the larger model answers those better, or what routing adds to the cost per question. The rules are a heuristic written from the shapes of the eval questions, and each is covered by a unit test; the end-to-end routing, the guardrail retry and the fallback are tested against stub Anthropic servers and scripted clients only. With the offline assistant none of it is active.

## Prompt caching

`PROMPT_CACHING` is `true` by default. A request to Claude then carries `cache_control` markers of type `ephemeral` (the 5-minute cache) at the end of the tool definitions and at the end of the system prompt, which are the same from one call and one question to the next, and, from the **third model call of a run**, at the end of the conversation so far. That is at most three of the four breakpoints a request may have. The conversation is marked only from the third call because a prefix that is written is paid for at a premium and earns it back only when a later call of the run reads it, and a call after the second is expected to be rare (most questions take one tool call and then answer). That a fourth call is common once there is a third is an assumption that is not measured: the only data is the first measurement below, which had one cache read in 20 runs and says nothing about a fourth call. So the conversation is first written on the third call and first read on the fourth; the third call can read only the tools and the system prompt. `false` sends the requests exactly as they were before caching existed, at every iteration.

**What is recorded and priced.** The API reports three input counts, which are disjoint: `input_tokens` (not cached), `cache_read_input_tokens` and `cache_creation_input_tokens`. They are stored apart for every model call and for the run, and `chat_runs.input_tokens` keeps meaning the uncached ones. Each has its own price, as a multiple of that model's input price:

| Tokens | Price | Setting |
|---|---|---|
| Input, not cached | 1.00 | the model's input price |
| Written to the cache | 1.25 | `LLM_CACHE_WRITE_MULTIPLIER` |
| Read from the cache | 0.10 | `LLM_CACHE_READ_MULTIPLIER` |

The read and write tokens are on the `done` event, in the stored trace (the "How I answered" panel shows them), in the admin runs list and in the cache section of the Usage page (`GET /api/stats`, `cache`).

**The cost model.** The multipliers are Anthropic's published ones for the 5-minute cache. For a marked prefix of P tokens, a write costs 0.25 P more than sending it unmarked and each read costs 0.90 P less, so a prefix that is written once and read once costs 1.25 + 0.10 = 1.35 P where two uncached calls cost 2.00 P. The break-even is 0.25 / 0.90, a little under 0.3 reads: one read of a write (within the five minutes, which each read renews) pays for it, and a write that nothing reads is a loss of 0.25 P. Two things follow, and both happened on 2026-10-12. A prefix under the model's minimum is never written, so it costs nothing and saves nothing. A marker whose write is not followed by a call that reads it costs 0.25 of the tokens it marks.

**What the API will not cache.** Anthropic caches a prefix only when it is longer than a minimum that depends on the model: 4,096 tokens for Haiku 4.5, the default model here (other models have other minimums, so look up the one you use). Below it the request is accepted, billed in full and reported with zero cache tokens, with no error. Nothing in this backend assumes a hit; the tokens the API reports are what is stored and priced.

**Measured: the design as shipped (the baseline), 2026-10-12.** The first measurement of caching on real Claude: Haiku 4.5 (`claude-haiku-4-5-20251001`) on the deployed demo, the 20 read questions of `evals/questions.json`, one run each, the same run as the one under [the guardrail](#grounding-guardrail). 18 of the 20 passed, the average cost was $0.0099 per question, and over the 20 runs **43,427 tokens were written to the cache and 4,671 were read**. In that design all three markers were on every call. The stable prefix, the 11 tool definitions and the system prompt (11,774 characters), was about 3,660 tokens, below the 4,096 that Haiku 4.5 needs, so the first model call of a run never wrote anything. The marker on the conversation of the second call, which was usually the last, wrote about 4,300 to 4,700 tokens that nothing read; the 4,671 tokens that were read are about one such prefix, from a call that followed a call that had written it. At 1.25 and 0.10 that is 0.25 x 43,427 more and 0.90 x 4,671 less than the same tokens unmarked, a net cost increase of about 3 percent of the average cost (computed from these figures at the model's input price; I did not run the same questions with `PROMPT_CACHING=false`, so the unmarked cost is arithmetic, not a measurement).

**Changed since (3.1), and what it did.** Two changes, aimed at the two losses above.

1. *The conversation is marked from the third call only.* The write of the second call that nothing read is gone. It costs a little where a run has exactly three calls (it writes on its last call and reads nothing of it); whether a fourth call is common once there is a third is still not measured, because no question of the runs below needed a third call.
2. *The prefix is over the minimum.* The system prompt gained a "Conventions and defaults" section, and two tool descriptions (the overdue list and the occupancy summary) and the `purpose` field of the two message tools a sentence each (about 3,500 characters). They are there for the model, not for the cache: how to write money (never `4.8E+3`), the six buildings and what a unit code means, what the payment statuses mean, which defaults to apply instead of asking (a reminder is a rent reminder; the language of a draft; this month), when to ask and when not, how to decline, answer style, and to prefer a figure a tool returns over one it computes. `PromptPrefixIT` fails if the prefix falls under 4,200 tokens at the measured ratio below, so a later trim cannot turn caching off silently, and it checks the buildings, the unit codes and the payment statuses the section states against the seeded database.

**Measured after (2026-10-12, build `eeb9515` on the deployed demo, same Haiku 4.5, the same 20 read questions, 1 run each, sent 7 seconds apart).**

- **The prefix is 4,252 tokens**, which the first call of a run reported as its cache write: 15,311 characters, 3.60 characters per token. The estimate that I made before the change (4,760 tokens, from the ratio of the 3,660-token prefix of the first run) was 12 percent too high, because prose is less dense in tokens than the JSON that ratio came from. The real margin over the 4,096 minimum is 156 tokens, about 4 percent, which is thin: a trim of about 560 characters would turn caching off again, and the test now uses the measured ratio.
- **Every question passed (20 of 20)** and the guardrail flagged none (before: 18 of 20 and 7 flagged).
- **Cost per question fell from $0.0099 to $0.0043** (-57 percent, the same 20 questions before and after). Uncached input fell from 104,758 to 23,012 tokens; 165,828 tokens were read from the cache and 4,252 written. In a warm run each question reads the 4,252-token prefix on both of its calls (8,504 read tokens).
- **Latency did not change**: 4.19 s on average and 7.07 s at the 95th percentile before, 4.45 s and 7.09 s after, which is the noise of one run each. A cache read saves the cost of the tokens, not the time of the answer.
- **The warm figure flatters.** The harness sent its questions back to back, so almost every call found the cache warm. The one question that found it cold (`en-01`, the first of the run) wrote the prefix on its first call and read it on its second, and cost $0.0084 against $0.0102 before: **-18 percent for a question that arrives after five idle minutes**, which is the figure to expect for the occasional visitor. A question that follows another within five minutes costs about as in the table.
- **Not measured:** the same questions with `PROMPT_CACHING=false` (the unmarked cost in the comparison is the baseline run, with its own writes, not a run with caching off); a cost with the larger model; a run of three or more model calls.

| Run | Questions | Cache read tokens | Cache write tokens | Average cost per question | Average latency (p95) |
|---|---|---|---|---|---|
| `true`, before 3.1 (2026-10-12, deployed demo, 1 run each) | 20 | 4,671 | 43,427 | $0.0099 | 4.19 s (7.07 s) |
| `true`, after 3.1 (2026-10-12, build `eeb9515`, 1 run each) | 20 | 165,828 | 4,252 | $0.0043 | 4.45 s (7.09 s) |
| `false` | not measured | 0 (nothing is marked) | 0 (nothing is marked) | not measured | not measured |

To settle the `false` row, run the manual workflow `Evals (full)` ([evals-full.yml](.github/workflows/evals-full.yml), needs the `ANTHROPIC_API_KEY` repository secret) twice with the same `runs`, `only`, `guardrail_mode` and `large_model`, once with `prompt_caching` true and once with it false, and copy the figures from the two job summaries. Every cache figure of the committed offline results is zero, because the offline assistant has no prompt cache.

Read any such comparison with these in mind: the harness sends its questions one after another, much closer together than visitors do, so it finds the cache warm more often than a demo with a few questions an hour, and a cache that is older than five minutes is gone. A run that sees no cache read tokens at all means the prefix is below the minimum, not that the code is wrong.

## Grounding guardrail

An answer from a model can state a figure that no tool returned. The guardrail holds every delivered answer against the evidence of its run and flags what is not in it. It costs no model call: the scan is written by hand, with no regular expression over the text, so the work is proportional to the length of the answer, and a check that fails for any reason leaves the answer as it was.

**What is checked.** The *evidence* is what every tool result of the run stated (an error text included, less what the model wrote into the call and the app's own guidance), the question, the earlier turns and the text the app wrote for the turn (today's date, a decision notice). Four kinds of claim in the answer are held against it:

| Kind | Grounded when |
|---|---|
| `NUMBER` | The same figure is in the evidence, or follows from the rows of one tool result by a rule under *Derived figures*. `4,950`, `4950.00` and `٤٩٥٠` are one figure; so is a figure with decimals rounded as an answer writes it (49.8 as 50), and a rounded one that no result states is another. A whole number up to 10, a list number, and the digits of a unit code or a citation are never reported. A date such as `31.12.2025` is read as the figures 31, 12 and 2025 |
| `UNIT_CODE` | A code such as `A-203` is in the evidence |
| `TENANT_NAME` | A tenant that the answer names in full, in English or in Arabic, is named in the evidence |
| `CITATION` | A marker such as `[LSE-A-203#5]` is a clause that a lease search of this run returned. A marker inside the text of a clause is quoted text and makes nothing real |

**Derived figures.** A model counts and adds up what a result lists, and a correct answer then states a figure that no result states. These are accepted as evidence for a `NUMBER` claim, each computed with exact arithmetic from one tool result of the run:

| Rule | Figure | Example |
|---|---|---|
| Count | For an array of objects, the number of rows that share a value of a scalar field, for each group of two or more | 11 tenants who are 103 days late (`daysOverdue` is 103 in 11 rows) |
| Sum | For an array of objects and a field whose values are all numbers: the sum over all rows, and the sum over each group of rows that share a value of another field | The four unpaid months of 4,800 are 19,200 (`amount` of the rows with `status` `UNPAID`) |
| Rate | A number above 0 and up to 1 that has a fractional part, times 100, rounded to 0, 1 and 2 decimals as an answer prints it | `0.857` is also 86 and 85.7 |
| Rounding | A number that a result states with decimals, rounded to a whole number and to one decimal | `49.8` is also 50 ("just under 50%"), `45.25` is also 45.3 |

A derived figure grounds a figure of the answer and nothing else: not a unit code, a tenant name or a citation. Nothing more is derived, so a difference, a product, a quotient, an average, a sum of rows that nothing groups, a count that is off by one, a percentage that is not the fraction times 100 and a figure that is merely near a stated one (51 when the result states 49.8) stay reported, and so does a figure that follows only from another result or another run (the rows of two results are never added or counted together). The work is bounded: 500 rows of an array (one with more derives nothing, because a sum of some of its rows is no sum of it), 20 scalar fields of a row, 30 distinct values of a field, 6 levels of nesting and 20,000 derived figures for a run, and a result of a million characters or of 100,000 rows is checked in well under a second on a 256 KB stack (`GroundingCheckerHostileInputTest`). The tools of the app return their rates as percentages already (`occupancyRatePercent`: `80.0`; before tool results were written as plain decimals it read `8E+1`), so the rate rule applies to a tool that returns a fraction and to none of the seven cases below; the rounding rule is the one that covers the two Building A answers.

**Measured before these rules.** The first measurement of the guardrail on real Claude answers (Haiku 4.5, the 20 read questions of `evals/questions.json`, one run each, 2026-10-12; the guardrail did not exist yet on the run of 2026-10-08 that [the evals section](#against-real-claude-what-exists-and-what-does-not) describes) flagged 7 of the 20 answers under the rules as they were before derived figures: a figure was grounded only when a result, the conversation or an array length stated it. All seven answers were correct. I read what the same tools return for those questions from the seeded database on that date (the stored run keeps the tool's arguments and a summary, not its result, so this is what the model was given if the demo's data was the seed's, which I could not check). Four of the seven are explained by the rules above and are grounded by them in `DerivedFigureGroundingTest`: `en-04` and `ar-03` flagged 11, the rows of the overdue list that share `daysOverdue` 103, and `en-09` and `ar-08` flagged 19,200, the sum of `remainingAmount` over the rows of the payment history of B-203. Three were not explained by those rules: `en-07` and `ar-06` (Building A) flagged 50 and `en-08` (Building D) flagged 57,000, and no count, sum or fraction of the occupancy result gives either. I then asked the deployed demo the same two questions again (a second sample: the wording of a model varies). The Building A answer printed the collection rate as 49.8% and described it as "just under 50%", so the flagged 50 was a rounding of a figure the tool states, and the rounding rule above now grounds it (`justUnderFiftyPercentIsTheRoundingOfTheRateTheResultStates`). The Building D answer said that 57,000 SAR of 123,000 had been collected, a rate of 45.3%, and 45.3% of 123,000 is about 55,700: the figure contradicts the rate of the same answer, so that flag was right and stays (`aCollectedAmountThatTheRateOfTheSameAnswerContradictsIsStillReported`). The tool result of the demo is not stored, so the collected amount in that test is inferred from the answer's own rate. **Measured after (2026-10-12, build `eeb9515`, then `0903929`).** The same 20 read questions again: none of the 20 answers was flagged, and all 20 passed, including the seven that carry `noGuardrailFlag`. Of the 16 lease questions, the first run flagged one answer and two further runs of all 16 flagged none, so 1 of 68 answers in all. The flagged one (`rag-11`, the English combined question) wrote that notice for B-501 should have been given on August 31 and "48 days ago", and for A-204 "35 days ago": figures of date arithmetic that no result states, and one of them is wrong (31 August is 42 days before 12 October, not 48). It is the sort of figure the guardrail is for, and it is a different class from the counts and sums above: a computation on dates is not derived and stays reported. The samples are small (one run of 20, one of 16 and two of 16), so this is evidence that the flag rate fell from 7 of 20 to about 1 of 68, not a measured rate.

**Modes.** `GUARDRAIL_MODE`, default `warn`:

| Mode | What it does |
|---|---|
| `off` | Checks nothing. The `done` event has `guardrailStatus: null` |
| `warn` | Checks the answer and flags it. It never changes or blocks the answer |
| `retry` | As `warn`, and asks for an answer that states ungrounded values once more, on the larger model when one is configured, naming the values. The second answer replaces the first (a `revise` event tells the browser to drop it) and is flagged if it still states ungrounded values. If the second call fails, is empty, or runs out of iterations or time, the first answer stands, flagged |

A blank value is `warn`; any other word than these three stops the backend from starting, with `GUARDRAIL_MODE must be off, warn or retry`. The guardrail never turns an answer into an error. An answer that was cut off by `max_tokens` and a run that failed are not checked.

**What a flag looks like.** The browser gets a `guardrail` event with the values, and shows a notice under the answer ("Some figures could not be checked against the data") that lists them; an answer that was asked for again and is clean shows "Checked again". The run's `guardrailStatus` is `OK`, `FLAGGED`, `RETRIED_OK` or `RETRIED_FLAGGED`. The status and the number of values are in the public trace; the values themselves are stored (`run_guardrail_findings`) and shown only on the admin page. The log gets one line per flagged answer, and one more when the answer is asked for again, with the kinds and the values and never the answer or the question:

```
WARN ... dev.proppilot.agent.AgentLoop : Grounding guardrail flagged run <run id>: 2 value(s) not found in the data (NUMBER "5,000", UNIT_CODE "A-204")
INFO ... dev.proppilot.agent.AgentLoop : Grounding guardrail asks run <run id> to answer again: 2 value(s) not found in the data (NUMBER "5,000", UNIT_CODE "A-204")
```

**What it cannot catch, and what it gets wrong.**

- It tests presence, not truth. A wrong figure that happens to be in the evidence is grounded: the number of a different tenant's invoice, or "3 bedrooms" when some other number 3 is in a result, passes.
- A correct figure that is rounded, or derived in a way the rules above do not cover, is flagged like an invented one. "About 5,000" for 4,950, a difference the model computed, an average, or a total over rows that nothing groups is not in the evidence, so it is reported, and the notice then asks a reader to check something that was right. That is a false positive of design: of the 7 flagged answers of the 2026-10-12 run, 4 were of this kind (a count and a sum, grounded by the rules above) and 3 were flagged for a reason I have not established, all under the earlier rules, and I have no rate for the current ones.
- It does not read meaning. A wrong status ("paid" for "overdue"), a wrong direction, a misquoted clause that cites a real ref, a tenant named by first name alone, an amount written in words, or a month named without a day are not checked.
- A date or a figure that the model took from the question counts as grounded, because the question is evidence.

The offline assistant quotes tool results, so the offline eval run flagged no answer. The only measurement of the guardrail on real Claude answers is the one above (7 of 20 flagged, all correct, under the earlier rules); its miss rate is unknown, and so is the false positive rate of the current rules. The eval harness counts flagged answers, and a case can require that none is flagged: the seven read cases whose correct answers that run flagged do, so that the next full run measures it and reports a false positive as a failure.

## Tracing and observability

**What is stored.** Every run keeps its model calls and its tool calls (migrations `V7` to `V10`):

| Table | One row per | Holds |
|---|---|---|
| `chat_runs` | question | The question (cut to 1,000 characters), status, the model that answered and why (`routed_reason`), how the question scored, the guardrail status and the number of ungrounded values, tokens (input, output, cache read, cache write), cost, latency |
| `run_llm_calls` | model call | Model, input, output, cache read and cache write tokens, latency, stop reason, outcome (`OK` or `ERROR`) and error code, start time. A call that failed is a row too |
| `run_tool_calls` | tool execution | Tool, the `seq` of the model call that asked for it, arguments (cut to 2,000 characters), result summary (300), error, latency |
| `run_guardrail_findings` | ungrounded value | Kind and value (cut to 40 characters) |

Nothing deletes these rows. The nightly demo reset restores the business data and leaves the runs, so the tables grow with every question; see [What I'd do next](#what-id-do-next).

**Endpoints.**

| Endpoint | Who | Returns |
|---|---|---|
| `GET /api/runs/{id}/trace` | Anyone with the run id (its `done` event) | The stored trace: status, model and why, complexity score and rules, guardrail status and count, totals (tokens, cache tokens, cost, latency), every model call, every tool call with the call that asked for it. No question, no answer, no arguments and no results, because those carry what the visitor typed. 404 for an unknown run or an id that is not a UUID |
| `GET /api/admin/runs` | `X-Admin-Token` | The latest runs with their question, newest first: `limit` (1 to 100, default 50), `status`, `model`, `guardrailFlagged`, and `before` (the `createdAt` of the last run of the previous page) |
| `GET /api/admin/runs/{id}` | `X-Admin-Token` | One run: the question and the same trace, plus what each tool was asked and said (`toolCallDetails`) and the values the guardrail could not ground (`guardrailFindings`) |
| `GET /api/stats` | Anyone | Aggregates only: totals, tool usage, `byModel` (runs and cost per answering model), `cache` (read and write tokens) and `guardrail` (flagged runs) |

The admin endpoints answer 404 when `ADMIN_TOKEN` is unset and 403 for a wrong token.

**In the web app.** The "How I answered" panel of an answer shows what the stream carried until the run's stored trace arrives, and then draws from the trace: each model call followed by the tools it asked for, with tokens, cache tokens, cost, latency, the model and why it was chosen, and the guardrail verdict. The stream supplies the tool arguments and results, which the public trace does not hold. The Usage page adds the model mix, the cache and the flagged answers.

**The admin page.** `/admin/traces` is not in the navigation. Open it by its address on the frontend (`http://localhost:3000/admin/traces` under docker compose, your Vercel URL plus `/admin/traces` on the demo) and paste `ADMIN_TOKEN`. It lists the latest runs with filters (status, model, flagged answers only) and "Load older runs", and a row opens the run's detail with its question, every call and the ungrounded values. The token stays in the browser tab (`sessionStorage`, gone when the tab closes), goes only into the `X-Admin-Token` header, and "Forget token" drops it. A server with no `ADMIN_TOKEN` shows "Admin endpoints are disabled on this server".

**OpenTelemetry.** Off by default: with `OTEL_ENABLED` unset, `false` or blank no OpenTelemetry object is created and nothing leaves the process. When it is `true` and an endpoint is set (`OTEL_EXPORTER_OTLP_ENDPOINT`, or `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` for traces only), each finished run is exported over OTLP/HTTP as one root span `proppilot.run`, a child `chat <model>` for each model call (with `gen_ai.*` attributes: model, input, output, cache read and cache write tokens, finish reason) and a child `tool <name>` for each tool execution, placed at the times that were measured. Only traces are exported.

| Variable | Default | Meaning |
|---|---|---|
| `OTEL_ENABLED` | `false` | `true` exports the runs. Blank is `false` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset | The collector's OTLP/HTTP address with its `http://` or `https://`, for example `http://localhost:4318`. An address is required to export: with `OTEL_ENABLED=true` and none the backend starts, logs one warning and exports nothing. Blank is unset |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | unset | The address for traces only, as the full URL (`.../v1/traces`). It counts as an endpoint and wins over the one above. Leave it out rather than empty: the SDK refuses an empty one |
| `OTEL_EXPORTER_OTLP_HEADERS` | unset | The collector's headers as `key=value` pairs separated by commas (a hosted collector's token goes here) |
| `OTEL_SERVICE_NAME` | `proppilot` | The service name. Blank is `proppilot` |

The SDK also reads the other standard `OTEL_*` variables when they are set to something (under docker compose only the ones that `docker-compose.yml` lists reach the backend; add the others under `backend.environment`). A value it refuses (an address without `http://` or `https://`, an empty `OTEL_EXPORTER_OTLP_PROTOCOL` or `OTEL_TRACES_SAMPLER`) does not stop the backend: it logs one warning that names the kind of error and never the value, and exports nothing. **What is never put in a span:** a prompt, a question, an answer, a tool's arguments or result, a lease clause, a key or a header. A span holds identifiers (the run id), names (model, tool), counts, timings and outcomes. The exporter is tested against a local stand-in for a collector; I have not sent spans to a real collector or a hosted backend, and the app does not sample.

## Evals v2

`evals/` is a harness with no dependencies (Node 22, ES modules; every module has a test that runs without a backend). It asks a running backend the questions of [`evals/questions.json`](evals/questions.json), grades what comes back, and writes `evals/results/latest.json` and `latest.md`. [`evals/README.md`](evals/README.md) is the reference: options, the fields of a case and every check.

### Methodology

A case is a question (or a conversation, for the follow-up cases) with what a good answer must hold and what it must never do. The grading is code first: facts the answer must state, facts it must not (an invented unit, tenant or amount), tools that must be called and must not, the arguments the model gave a tool, the citation markers, the actions it proposed, and whether it declined or asked which tenant was meant. A case passes when it has no reason to fail, and each failure says which. Expected facts come from the seeded data and from the lease terms, never from a date or an amount that depends on the day.

### Cases

64 cases in nine categories. A category is the prefix of a case's id.

| Category | Cases | What it asks |
|---|---|---|
| read (`en-`, `ar-`) | 20 | One lookup: vacant units, overdue tenants, a unit, occupancy, payment history, a draft |
| write (`write-`) | 8 | A change that must be proposed and never executed: a payment, a maintenance request, a reminder |
| lease (`rag-`) | 16 | What a lease says, with a citation; a unit with no document; the lease with the planted instruction |
| multi-tool (`multi-`) | 3 | Two tools for one question |
| multi-turn (`turn-`) | 4 | A follow-up that only makes sense with the earlier turn |
| ambiguous (`ambig-`) | 3 | A first name that six tenants share: it must ask which one and guess none |
| out-of-scope (`scope-`) | 4 | Weather, legal advice, a poem, "delete all tenants": decline politely and call no tool |
| injection (`inject-`) | 4 | An order to ignore its rules, and the planted instruction in a lease and in a tenant name: no write tool, no proposal, no claim that a change was made |
| hallucination (`trap-`) | 2 | A unit and a tenant that do not exist: say so and invent no figure |

### Checks that apply to every case

- **Citations.** Every `[LSE-...#n]` marker must be a clause that a lease search of that run returned; an invented citation fails the case.
- **No write is executed.** Each proposed action is read back from the server when the run ends and must still be `PROPOSED`. The runner never approves anything.
- **The answer is what the person was left with.** Text before a tool call is a preamble, and a `revise` event drops what was streamed so far.
- **Numbers and spelling.** `4,800`, `4800` and `٤٨٠٠` are one figure, and text is compared lower case, with Arabic without vowel marks and with the forms of alef, ya and ta marbuta unified, so a correct answer is not failed for how a word was typed.

### Runs and variance

`--runs N` (1 to 20) runs the whole set in N sweeps. The pass rate is the mean over the runs, and the report gives its sample standard deviation across runs, the cases that passed every run, the flaky ones (passed some, failed others) and the ones that failed every run, by category and by language. Cost and latency are averaged over every chat request that got a reply (the earlier turns of follow-ups included), with the p95 latency and the totals of input, output and cache tokens. When more than one model answered, a table gives each model's questions, pass rate, cost and latency.

### The LLM judge

`--judge` sends the cases that carry a rubric, a refusal or a clarification to a model that scores tone, language and grounding from 1 to 5; a case passes the judge when every score is 4 or more. It needs `ANTHROPIC_API_KEY` and is off by default. The prompt gives the answer as escaped data and tells the judge never to follow what is in it; a judge that cannot be reached or whose reply is invalid does not fail the case, the run counts it as unjudged. The judge has never run: there is no key in the environment this was built in.

### CI and the manual full run

- **CI** (`ci.yml`, job `evals`) runs on every push and pull request. It checks and tests the eval modules, starts a pgvector PostgreSQL service and the backend with the offline assistant, waits for the 30 lease documents, runs `node evals/run.mjs --offline-only --runs 1` and fails on any failed case. It spends nothing and says nothing about Claude.
- **Full run** (`evals-full.yml`) is started by hand from the Actions tab and needs the repository secret `ANTHROPIC_API_KEY`; without it the run is skipped with a notice. Inputs: `runs` (default 3), `judge` (true), `prompt_caching` (true), `guardrail_mode` (`warn`, `retry` or `off`), `only` (id prefixes) and `large_model` (`ANTHROPIC_MODEL_LARGE`, empty switches routing off). It spends money on real calls, runs every case including the ones only a real model can pass, appends the summary to the job summary and uploads the results. I have never run it.

### Reading the results

`latest.md` starts with a header (provider, models that answered, runs per case, cases and the ones skipped, date, backend host, whether the judge ran) and a banner that says what kind of run it was. Then the overall table (pass rate with its deviation, flaky cases, questions asked, latency, cost, tokens and cache tokens, answers the guardrail flagged, the count of each guardrail verdict, writes executed by a run, which must be 0), the pass rate by category and by language, and every case that failed or was flaky with its reasons. `latest.json` has every run of every case with its answer, tools, arguments, proposals, citations against the sources returned, usage and the judge's scores. A document written while a run is still going, or after it was stopped, says `complete: false`.

### The committed results are offline

[`evals/results/latest.md`](evals/results/latest.md) is the run of the offline assistant: `node evals/run.mjs --offline-only --runs 3` against a local stack on a freshly migrated database, with no key.

| | |
|---|---|
| Date | 2026-10-11, 17:25 UTC (committed in `901f8bf`) |
| Provider | `offline` (rule-based, zero cost), lease search by keyword |
| Cases | 54 of the 64; 10 skipped because only a real model can pass them (`offline: false`: `multi-01`, `multi-02`, `turn-01` to `turn-04`, `ambig-02`, `ambig-03`, `inject-01`, `inject-04`) |
| Runs | 3 per case, 162 questions |
| Pass rate | 100.0% (162/162), standard deviation 0.00 pt, 0 flaky cases |
| Latency | average 9 ms, p95 31 ms |
| Cost | $0.00 (the token counts, 48,030 in and 25,104 out, are estimates of about four characters each) |
| Cache read / write tokens | 0 / 0 (the offline assistant has no prompt cache) |
| Answers the guardrail flagged | 0 |
| Guardrail verdicts | OK 162 |
| Writes executed by a run | 0 |
| LLM judge | not run |

**What this proves:** the data, the tools, the streaming, the citation and proposal checks and the grading agree with each other on the cases the offline assistant is built for, and nothing a run did changed data. **What it does not prove:** anything about Claude. The offline assistant is a keyword router written against these question shapes, so 100% is the expected result and says nothing about tool choice, language, tone, grounding, cost, latency, prompt caching, routing or how often the guardrail flags a free-form answer. It also skips the ten cases above, which only a real model can pass.

### Against real Claude: what exists and what does not

Two runs of the 20 read questions against real Claude (Haiku 4.5, seeded data) exist. The table below is from the first, on 2026-10-08 at commit `1a9eaf7` with `claude-haiku-4-5-20251001`, from a laptop talking to the Anthropic API; it is older than the write actions, the lease tools, the guardrail and this harness. The second, on 2026-10-12 against the deployed demo, is the [first live run](#first-live-run-2026-10-12) below, and the third is the [second live run](#second-live-run-after-31-and-the-lease-fix-2026-10-12).

| Set | Passed | Pass rate | Avg cost / question | Avg latency |
|---|---|---|---|---|
| All | 20/20 | 100% | $0.0059 | 4.2 s |
| English | 10/10 | 100% | $0.0055 | 3.6 s |
| Arabic | 10/10 | 100% | $0.0063 | 4.8 s |

The agent has since gained three write tool definitions, two lease tool definitions and more rules in the system prompt, so every call now sends more input tokens and the cost and latency above are out of date. The first Claude run scored 16/20; I read the four failures, all were correct answers that my grader was too literal about (`4,800` for `4800`, an Arabic word for the status `UNPAID`), and I fixed the grader without changing what is checked. Since then the 20 read questions and the 16 lease cases have run against the deployed demo ([below](#second-live-run-after-31-and-the-lease-fix-2026-10-12)), with a before and after comparison for prompt caching. Nothing else has: not the write, multi-tool, multi-turn, ambiguous, out-of-scope, injection and hallucination cases, no judge score and no routing number. Those are the first things the manual full run is for, and its first failures should be read before they are read as the assistant's, because the expectations of the cases that need a model have only been exercised against stubs and the offline assistant.

```bash
docker compose up -d db
(cd backend && CHAT_RATE_LIMIT_PER_MINUTE=1000 CHAT_DAILY_LIMIT=100000 ./mvnw spring-boot:run)   # or the whole stack: docker compose up
node evals/run.mjs --offline-only --runs 3                       # what the offline assistant is expected to pass
node evals/run.mjs --only rag-                                   # the 16 lease cases
node evals/run.mjs --compare evals/results/latest.json           # how a run differs from an earlier one
node --test evals/*.test.mjs                                     # the harness's own tests, no backend
```

Against a public deployment add `--delay-ms 7000` (it answers 10 chat requests a minute by default), expect every request to count against its daily question cap, and leave the write cases out or reset first: they leave `PROPOSED` actions on the Activity page. [`evals/README.md`](evals/README.md#against-a-deployment) has the details, including why `multi-03`, `rag-11` and `rag-12` stop matching on a database that is older than a few weeks.

#### First live run (2026-10-12)

**One run of one model against the deployed demo, without the judge:** 20 questions, each asked once, so it says nothing about variance, and a case that passed once may be flaky. Tone, language and grounding were not scored, and visitors can change the demo's data. It is the first run since the write actions, the lease tools and the guardrail existed; the answers are not in the repository, so these are the figures recorded at the time. [`evals/README.md`](evals/README.md#first-live-run-2026-10-12) has the detail.

| | |
|---|---|
| Date and model | 2026-10-12, Haiku 4.5 (`claude-haiku-4-5-20251001`) |
| Cases | the 20 read cases of `evals/questions.json`, 1 run each |
| Passed | 18 of 20 |
| Average cost per question | $0.0099 |
| Latency (average, p95) | 4.19 s, 7.07 s |
| Answers the guardrail flagged | 7 of 20, all of them correct ([the guardrail section](#grounding-guardrail)) |
| Cache write / read tokens | 43,427 / 4,671 ([prompt caching](#prompt-caching)) |

- **`ar-08` failed on the grader.** The answer was right; the model wrote "لم يتم الدفع" for an unpaid month and the case did not list that wording.
- **`ar-10` failed on the model.** Asked for an English reminder for unit B-203, it asked which kind of reminder was meant instead of drafting a rent reminder.

What 3.1 changed because of the run: the grader lists the Arabic wordings of an unpaid month; seven read cases (`en-04`, `en-07`, `en-08`, `en-09`, `ar-03`, `ar-06`, `ar-08`) carry `noGuardrailFlag`, so a correct answer that the guardrail flags is reported as their failure; the guardrail grounds a count, a sum and a rate that follow from a result; tool results write amounts as plain decimals instead of `4.8E+3`; the system prompt gained conventions and defaults (a reminder is a rent reminder, which also takes the prefix over the cache minimum); and the conversation is marked for caching from the third call of a run. #### Second live run: after 3.1 and the lease fix (2026-10-12)

The same 20 read questions on build `eeb9515`, and the 16 lease cases, one run each, then the lease cases twice more on build `0903929`. Again the deployed demo, Haiku 4.5, no judge, questions 7 seconds apart.

| | Before (first live run) | After |
|---|---|---|
| The 20 read cases | 18 of 20 | **20 of 20** |
| Average cost per read question | $0.0099 | **$0.0043** |
| Latency (average, p95) | 4.19 s, 7.07 s | 4.45 s, 7.09 s |
| Read answers the guardrail flagged | 7 of 20 | **0 of 20** |
| Cache write / read tokens (20 questions) | 43,427 / 4,671 | 4,252 / 165,828 |
| The 16 lease cases | not run | 14 of 16, then **32 of 32** (two runs, after the fix) |

- **`ar-08` and `ar-10` both pass now** (the grader's wording, and the rent-reminder default in the system prompt).
- **The two lease failures were real and are fixed (`0903929`).** `rag-12` (Arabic: which leases end in 60 days and require 90 days' notice) missed B-501: the model's query was padded with extra words and the one clause that each English lease returned was the early termination clause, which says "notice" as often as the clause titled "Notice period". Full text search now ranks a clause whose title matches first, and a search over several named leases returns at least two clauses of each. `rag-16` (Arabic: the special conditions of A-301) failed because the model replaced the words of the question with "الشروط والأحكام والحقوق والالتزامات" and keyword search found nothing in an English document; the tool's `query` field and the system prompt now tell the model to keep the user's words and add the topic's name in English and Arabic. Both passed on both runs afterwards.
- **Retrieval is still keyword-only on the demo.** These runs are without embeddings, and the cases that failed are exactly the ones where meaning would help; whether `EMBEDDING_PROVIDER=voyage` closes the remaining gap has not been measured.
- **What this is not:** two or three runs of one model against a deployment whose data visitors can change, with the deterministic checks only. The judge, a larger model, routing, and cases that need a clarification or a refusal have not run on real Claude.

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Web, Spring Data JPA, PostgreSQL 16, Flyway, Maven |
| LLM | Anthropic Messages API over plain HTTP with streaming, prompt caching and optional routing to a second model; offline assistant for zero cost |
| Tracing | Stored traces in PostgreSQL (`run_llm_calls`, `run_tool_calls`); OpenTelemetry Java SDK 1.49.0 with an OTLP/HTTP exporter, off unless `OTEL_ENABLED=true` |
| Lease search | PostgreSQL with pgvector (`vector(1024)`, HNSW cosine), full text search (`arabic` and `english`) and `pg_trgm`; Apache PDFBox 3.0.8 to write and read the PDFs; ICU4J 78.3 for Arabic shaping and bidi; IBM Plex Sans Arabic; Voyage AI embeddings, optional |
| Frontend | Vue 3 (`<script setup>`), TypeScript, Vite, Pinia, Element Plus, vue-i18n, vue-router |
| Quality | JUnit 5, Mockito, Testcontainers (`pgvector/pgvector:pg16`), Vitest, Node's test runner for the eval harness, GitHub Actions |
| Infra | Docker Compose; Render (API) + Neon (Postgres) + Vercel (frontend), all free tiers |

## Run locally

```bash
cp .env.example .env        # optional: only needed to use real Claude
docker compose up --build   # Postgres + backend + frontend
```

- App: http://localhost:3000 · API: http://localhost:8080 · Health: http://localhost:8080/actuator/health
- The database image is `pgvector/pgvector:pg16`. A local volume made by an earlier version (`postgres:16-alpine`) should not be reused: run `docker compose down -v` once, which deletes the local seed data (Flyway recreates it).
- The lease documents are written in the background after start (about 15 seconds on my laptop); `GET /api/info` shows `leaseDocuments` as they appear.

For development with hot reload:

```bash
docker compose up -d db
cd backend && ./mvnw spring-boot:run          # http://localhost:8080
cd frontend && npm ci && npm run dev          # http://localhost:5173 (proxies /api to :8080)
```

### Using real Claude

```bash
LLM_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-ant-... docker compose up --build
```

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `LLM_PROVIDER` | `offline` | `offline` (free, no key) or `anthropic` |
| `ANTHROPIC_API_KEY` | – | Required for `anthropic`. Read only by the backend, never sent to the browser |
| `ANTHROPIC_MODEL` | `claude-haiku-4-5-20251001` | Model id |
| `LLM_PRICE_INPUT_PER_MTOK` / `LLM_PRICE_OUTPUT_PER_MTOK` | `1.00` / `5.00` | USD per million tokens, used for the cost estimate |
| `ANTHROPIC_MODEL_LARGE` | – | A larger model for questions that score as complex, for guardrail retries and as the fallback when `ANTHROPIC_MODEL` is overloaded. Unset or blank = no routing and no fallback to a second model. Used only with `anthropic` ([details](#model-routing-and-fallback)) |
| `LLM_LARGE_PRICE_INPUT_PER_MTOK` / `LLM_LARGE_PRICE_OUTPUT_PER_MTOK` | `3.00` / `15.00` | USD per million tokens of the larger model |
| `LLM_COMPLEXITY_THRESHOLD` | `3` | The score at which a question goes to the larger model |
| `PROMPT_CACHING` | `true` | Mark the tools, the system prompt and the conversation so far for Claude to cache; `false` sends requests unmarked ([details](#prompt-caching)) |
| `LLM_CACHE_WRITE_MULTIPLIER` / `LLM_CACHE_READ_MULTIPLIER` | `1.25` / `0.10` | What a token written to and one read from the prompt cache cost, as multiples of the input price |
| `GUARDRAIL_MODE` | `warn` | `off`, `warn` or `retry` ([details](#grounding-guardrail)). A blank value counts as `warn`; any other value stops the backend from starting |
| `OTEL_ENABLED` | `false` | `true` exports each run as OpenTelemetry spans ([details](#tracing-and-observability)) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | – | The collector's OTLP/HTTP address with its `http://` or `https://`; the second is for traces only and is left out rather than empty. One is needed to export: `OTEL_ENABLED=true` without either starts the backend, logs a warning and exports nothing |
| `OTEL_EXPORTER_OTLP_HEADERS` / `OTEL_SERVICE_NAME` | – / `proppilot` | The collector's headers (`key=value`, comma separated) and the service name |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Postgres | JDBC connection |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated frontend origins |
| `LLM_MAX_TOKENS` | `2048` | Longest single model reply; a reply that hits it is cut off and flagged |
| `LLM_MAX_RETRIES` | `3` | Extra attempts after a transient failure (429, 5xx, 529, network) |
| `AGENT_RUN_TIMEOUT_SECONDS` | `90` | Longest a single question may run, retries included |
| `CHAT_MAX_MESSAGE_LENGTH` | `500` | Max characters per question |
| `CHAT_MAX_HISTORY_ITEMS` | `20` | Most earlier messages a request may carry (the web app reads this from `/api/info`) |
| `ADMIN_TOKEN` | – | Enables `GET /api/admin/runs`, `GET /api/admin/runs/{id}`, the page `/admin/traces` and `POST /api/admin/reset` (header `X-Admin-Token`); 24+ characters or the backend refuses to start; unset = endpoints answer 404 |
| `CHAT_RATE_LIMIT_PER_MINUTE` | `10` | Per-client limit on `/api/chat` |
| `CHAT_DAILY_LIMIT` | `500` | Global daily cap on questions (protects your API budget) |
| `ACTION_TTL_MINUTES` | `15` | Minutes a proposed change waits for a decision before it expires |
| `ACTION_DECISIONS_PER_MINUTE` | `30` | Per-client limit on preview, approve and reject calls |
| `ACTION_MAX_APPROVALS_PER_DAY` | `100` | Global daily cap on approved changes (protects the shared demo data) |
| `ACTION_MAX_PROPOSALS_PER_RUN` | `3` | Most changes one question may propose |
| `ACTION_MAX_PROPOSALS_PER_DAY` | `200` | Global daily cap on proposed changes |
| `EMBEDDING_PROVIDER` | `none` | `none` (lease search by keywords, free) or `voyage` (also by meaning). A blank value counts as `none`; any other value stops the backend from starting |
| `VOYAGE_API_KEY` | – | Required for `voyage`. Read only by the backend. New accounts have rate limits: https://docs.voyageai.com/docs/rate-limits |
| `EMBEDDING_MODEL` | `voyage-4-lite` | Voyage model id. Chunks embedded by another model are embedded again |
| `EMBEDDING_DIMENSIONS` | `1024` | Must be 1024, the size of the `lease_chunks.embedding` column, or the backend refuses to start |
| `RAG_SEED_ON_STARTUP` | `true` | Write the missing lease documents and embed the chunks in the background at start; `false` skips both |
| `RAG_DOCUMENT_COUNT` | `30` | How many leases get a document (the first active leases by id). The evals, and the seven leases that end soon, assume 30 |
| `VITE_API_URL` (frontend build) | empty | Backend base URL when the frontend is hosted separately |

A host may pass a variable on empty, or as only spaces. For `LLM_PRICE_INPUT_PER_MTOK`, `LLM_PRICE_OUTPUT_PER_MTOK`, `ANTHROPIC_MODEL_LARGE`, `GUARDRAIL_MODE`, `PROMPT_CACHING`, the larger model's prices, the cache multipliers, `LLM_COMPLEXITY_THRESHOLD`, `OTEL_ENABLED`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_HEADERS` and `OTEL_SERVICE_NAME`, a blank value means what an absent one means and never stops the backend from starting (`BlankHostValuesTest` reads the real `application.yml` with each of them empty and as spaces, `HostWhitespaceIT` starts the whole application with spaces in every number, and `TracingConfigTest` covers the endpoint). A text where a number is expected, such as `LLM_COMPLEXITY_THRESHOLD=three`, still stops it, so that a typo is seen at the first deploy. `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` is the one to leave out rather than empty, because the SDK refuses an empty one; the backend still starts and exports nothing. The settings of the earlier phases were left as they were: a blank `LLM_MAX_TOKENS`, `LLM_MAX_RETRIES`, `AGENT_RUN_TIMEOUT_SECONDS`, `CHAT_*`, `ACTION_*`, `RAG_*` or `EMBEDDING_DIMENSIONS` still stops the backend from starting.

## Tests

```bash
cd backend && ./mvnw verify      # 2128 tests in 137 classes; 56 of them are integration tests (`*IT`) that start PostgreSQL (pgvector) via Testcontainers, so Docker is needed
cd frontend && npm test          # 488 Vitest tests in 29 files: SSE parser, chat store, approval card, Activity page, Markdown sanitising, citation chips and the clause drawer, trace builder and drawer, guardrail badge, admin traces page, Usage page, error codes, info badge, matching English and Arabic message keys
node --test evals/*.test.mjs     # 172 tests in 15 files: the eval harness (runner, grading, claims, judge, aggregation, report) and the shape of every case in questions.json
```

Backend: the agent loop against a scripted LLM, retry policy and the Anthropic client against an in-process HTTP server (429/529 then success, 401, mid-stream errors, `max_tokens` truncation), run outcomes (OK, ERROR, TRUNCATED, ABORTED, deadline), admin authentication, every tool against the real seeded database, the SSE endpoint end to end, rate limiter, cost calculator, units API.

For the write actions: the proposal lifecycle against PostgreSQL (propose, preview with edits, approve, reject, expire, fail), the change matching its preview, idempotent replay, two approvals or two calls with one key racing, partial and over-payments and the oldest-invoice-first allocation, the daily and per-run caps, the audit trail, the demo reset, the offline router for write commands in both languages, and the proposal and confirmation flow through the chat endpoint. CI (`.github/workflows/ci.yml`) builds and tests both on every push.

For the lease search: PDF generation and the text read back from it for both languages (including under a host locale that writes Arabic-Indic digits), the chunker with its hostile inputs on a 256 KB stack, the Voyage client against a local stub HTTP server (rate limits, a bad key, a malformed reply, a key that must never reach a log), reciprocal rank fusion, retrieval against the real corpus with keywords only and with a deterministic fake embedding client, the two tools against the seeded database, the offline router and answers for lease questions in both languages (including hostile questions and every eval question), the agent loop on lease questions, the streamed `sources`, the reindex endpoint, and the web app's citation chips and drawer. Voyage is never called by a test.

For routing, caching, the guardrail and tracing: the complexity rules in both languages and hostile questions on a 256 KB stack, the router and the one-time fallback (overload, timeout, a call that had streamed text, a stalled model) against scripted clients, the request body with and without cache markers at the first, second and third call of a run and the cache tokens read from a stub Anthropic server, the size of the prefix against the cache minimum and the facts of the system prompt against the seeded database, amounts written as plain decimals, the cost of calls on two models with cache reads and writes, the guardrail's claims, the figures it derives from the rows of a result (counts, sums, fractions and their bounds) and the seven answers that the run of 2026-10-12 flagged, hostile input and its three modes through the chat endpoint (warn, retry and off, including a retry that fails), the stored trace and its schema, the public and the admin trace endpoints (no question or arguments in the public one), the OpenTelemetry tracer against an in-memory exporter and against a local stand-in for a collector, and blank values of every new setting. Neither Anthropic nor Voyage is called by a test.

## Safety

- Chat endpoint: per-client rate limit, global daily cap, max message length, bounded history (item count and size), per-question run deadline.
- Visitors' question text is never public: `/api/stats` returns aggregates only, and `/api/admin/runs` needs an `ADMIN_TOKEN` header (compared in constant time, never accepted in a URL). The public trace of a run (`GET /api/runs/{id}/trace`) holds no question, answer, tool arguments or tool results, and OpenTelemetry spans never carry them either.
- The API key lives only in the backend environment; CORS is restricted to configured frontend origins.
- Tools take typed inputs. Eight only read, and `draft_tenant_message` returns a draft and never sends anything. The three write tools only store a proposal; a person's approval, with an idempotency key, is the only thing that changes data, and it is audited. Nothing is ever delivered to a tenant.
- Write actions are capped per question, per day and per client per minute, and the public demo's data is restored every night (see [Human-in-the-loop write actions](#human-in-the-loop-write-actions)).
- Tool failures never leak internals to the model or the user, and raw LLM provider errors are logged server-side only.
- The grounding guardrail never rewrites or blocks an answer by itself, and its log line names the kinds and the values it could not ground, never the answer or the question.
- Lease text is data: the search tool cannot write, its result says so before anything else, and a reply may search the leases at most three times (each search embeds the question with a paid provider). The Voyage key is read only by the backend and redacted from logged replies, and `POST /api/admin/rag/reindex` needs the admin token. The lease PDFs are public because they hold only fake data.

## Deployment

Zero-cost path, step by step: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Design decisions

- **Stateless chat:** the client sends recent turns with each question, so the server stores no conversations. A question's text is stored only in its `chat_runs` row, readable through the admin endpoint. Proposals are stored with their audit trail and are public by design on the demo.
- **Propose, then approve:** a write tool cannot write. Keeping the change in a stored proposal (instead of a confirm dialog in the browser) means the preview, the approver, the outcome and the idempotency key are server facts that can be audited and replayed.
- **Offline provider:** keeps the demo and the evals free, and proves the loop is independent of any one model.
- **Seed is deterministic and date-relative:** payments always cover the 12 months before the migration date; 11 "chronic" tenants are always the most overdue, which keeps eval facts stable.
- **Tool results are capped** (15 rows) with the total count included, so the model never needs to see a whole table.
- **Generated PDFs through a real pipeline:** the seed is date-relative, so documents are written from the database at start and then read back like any uploaded PDF. That keeps the documents true to the data and keeps the ingestion code honest, at the cost of PDFs that are all templated and clean.
- **Keyword retrieval is a first-class mode, not an error path:** the feature must work on a clone with no key, so keywords and meaning are separate lists that fuse, and a missing or failing embedding provider only removes one of them.
- **A guardrail that starts by warning:** a check that reads figures and names accepts only the derivations it knows (a count, a sum, a fraction as a percentage) and cannot tell a correct figure derived another way from an invented one, so it flags and logs first (`warn`) and only asks again (`retry`) when the owner chooses, until a real run shows how often it is wrong. It never decides an answer alone.
- **Routing costs nothing until configured, and a cache marker is placed where a read is expected:** with no larger model nothing is scored, and a prefix that the API does not cache (one under its minimum) costs no more than no marker, because the tokens it reports are what is priced. A marker whose write nothing reads is a loss (the first measurement showed it), so the conversation is marked only from the third call of a run, on the assumption, not yet measured, that a run with a third call often has a fourth. Nothing in the code assumes a hit.
- **Record what the API reports:** the cache read and write tokens, the model that answered and why, and every model call and tool call of a run are stored as they happened, so a claim about cost or caching can be checked against rows rather than against the code.
- **Exact vector scan, filtered first:** a lease filter applied after an approximate index can return nothing for a lease that has chunks. With a few hundred chunks an exact scan costs nothing and cannot do that.

## What I'd do next

- Auth and roles for approvals. The public demo lets any visitor approve, protected only by caps, expiry and the nightly reset; a real deployment needs signed-in approvers, who may approve what, and a named actor in the audit log. Multi-tenant portfolios come with that.
- Real delivery for reminders: a worker that reads the outbox and sends through an email or messaging provider, with consent and opt-out. Today nothing is sent on purpose.
- A credit ledger, so an overpayment can be kept and applied to the next invoice instead of only being reported.
- Run the evals against real Claude. The harness exists and is tested, and two of the eight write cases already grade the arguments the model chose (the amount, the method, the priority), but only the 20 read cases have run against Claude (twice, and never with the judge) and none of the write cases has. That run is the first thing that would make any claim about the assistant's quality, and the cases that need a model may turn out too strict.
- Measure what routing, caching and the guardrail do on real traffic. Two full runs with `prompt_caching` true and false fill in the [caching table](#prompt-caching) and show whether the larger prefix is now over the cache minimum, which the run of 2026-10-12 showed it was not; the run's routing reasons show what share of the cases the complexity rules send to the larger model, and whether they send the right ones; the flagged answers show how often the guardrail is wrong in both directions. Until then `warn` is the right mode and `retry` is unproven.
- Make the guardrail smarter where it is wrong: counts, group sums and fractions are recognised now, a rounding of a figure with decimals is recognised now, and the check does not read the quantity a figure is attached to (this tenant's balance, not any figure that happens to be present). Of the seven answers that the run of 2026-10-12 flagged, four are derived counts and sums, two (50 twice) a rounding of 49.8, and one (57,000) an unsupported figure that the flag rightly caught; but neither the answer nor the tool results of a run are stored, so that was read back by asking again: keep both for the flagged runs, on the admin page only like the question, before deciding on another rule. The judge could grade grounding on a sample of real answers to calibrate it.
- Retention for stored runs. `chat_runs` and the trace tables grow with every question and nothing deletes them, which is fine for a demo and not for a service. A scheduled prune, and a decision about how long a visitor's question text may be kept, come before real traffic.
- Sample and ship traces to a real backend. Spans are exported for every run and the exporter is tested against a stand-in for a collector only; sampling, a real collector and dashboards (latency by model, cache hit share, flagged answers) are not done.
- Measure retrieval, not only answers. The 16 lease cases check final answers against the offline assistant. I have no run against real Claude and none with embeddings on, no recall@k over a labelled question set, and so no evidence yet about how much the vector list adds over keywords on this corpus.
- Real documents. The corpus is templated and generated, so extraction is easy. Scanned PDFs need OCR, tables and two-column layouts need layout-aware extraction, and Arabic PDFs from other producers will not all behave as the ones I wrote do.
- Keep "ends soon" alive. Migration `V6` fixes seven end dates at migration time, so the combined question has fewer answers every day and none after 56 days; the nightly reset could move them again. The offline assistant also quotes the notice clause of every lease that ends instead of filtering to the one asked for.
- Make a blank value of the older numeric settings (`LLM_MAX_TOKENS`, `CHAT_DAILY_LIMIT` and the rest) mean its default, as the settings of this phase do, so that an empty field in a host's dashboard cannot stop the backend.
- Count embedding tokens in the cost shown under an answer, and add a reranker or a query rewrite step if the measurement above says it would help.
- Calibrate the routing rules against labelled questions instead of the shapes of the eval cases, and consider a model-based classifier only if the rules prove too coarse. They are cheap and explainable, which a classifier would not be.
- A Flutter client against the same SSE API.

## License

MIT, see [LICENSE](LICENSE).
