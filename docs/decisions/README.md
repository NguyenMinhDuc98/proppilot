# Decisions

Architecture decision records, one per file: `NNNN-<slug>.md` (template in the dev-team `project-memory` skill). Number them in order starting at `0001`.

Decisions made before this folder existed are listed in the README's [Design decisions](../../README.md#design-decisions): stateless chat, propose-then-approve, the offline provider, a deterministic date-relative seed, capped tool results, generated PDFs through a real pipeline, keyword retrieval as a first-class mode, a guardrail that starts by warning, routing and cache markers that cost nothing until used, recording what the API reports, and an exact vector scan filtered first. Write an ADR when one of those changes, and mark which one it replaces.
