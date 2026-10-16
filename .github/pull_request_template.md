## Summary
<!-- What changed and why. Link the spec: docs/specs/NNN-... -->

## Acceptance criteria
- [ ] AC-…

## Test evidence
<!-- Commands run and their results: ./mvnw verify, npm run typecheck, npm test, node --test evals/*.test.mjs, offline evals if behavior changed. State what was measured and what was not. -->

## Checklist
- [ ] Works with `LLM_PROVIDER=offline` and `EMBEDDING_PROVIDER=none`
- [ ] New user-facing strings in both `en.ts` and `ar.ts`; RTL checked
- [ ] New settings documented in the README with a safe default
- [ ] No question text, answers or tool arguments in public endpoints, logs or spans

## Review
- QA verdict: <!-- PASS / FAIL -->
- Code review verdict: <!-- APPROVE / CHANGES REQUESTED -->

## Risk
- [ ] Needs human approval (migration, approval path, auth/admin, rate limits, deploy config, paid API use, secrets) → label `needs-human`
<!-- Rollback plan if relevant. -->
