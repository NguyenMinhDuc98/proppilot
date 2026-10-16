# Glossary

Domain terms as the code and the app use them. Arabic labels are the ones in `frontend/src/i18n/ar.ts`; keep them consistent in new UI.

| Term | Arabic (app label) | Meaning in PropPilot |
| --- | --- | --- |
| Unit | الوحدة | A rentable apartment in a building, code like `A-504`; status vacant / occupied / maintenance |
| Building | المبنى | One of 6 buildings across 3 cities |
| Tenant | المستأجر | A person renting a unit under a lease |
| Lease | العقد | The contract between tenant and unit; has clauses and a generated PDF |
| Clause | البند | A numbered section of a lease; the unit of lease search and citation |
| Citation | — | A marker like `[LSE-A-203#5]` in an answer that the app renders as a chip opening the clause |
| Invoice | فاتورة | A monthly rent charge; payments settle the oldest unpaid invoices first |
| Payment | الدفعة | Money received against invoices, in SAR (ريال) |
| Overdue | متأخر | A tenant with unpaid invoices past their due date |
| Occupancy | الإشغال | Share of units occupied, with rent collected |
| Maintenance request | طلب صيانة | A request opened for a unit, with category and priority |
| Tenant reminder | تذكير للمستأجر | A message queued in an outbox; never actually sent |
| Proposal / action | اقتراح | A change a write tool stored for approval: record payment, open maintenance request, queue reminder |
| Approve / reject | موافقة / رفض | A person's decision on a proposal; approval executes it in one transaction |
| Expired | منتهي الصلاحية | A proposal nobody decided within `ACTION_TTL_MINUTES` (15) |
| Audit trail | — | Entries with the state before and after each proposal and decision (Activity page) |
| Run | — | One question's processing: model calls, tool calls, tokens, cost, latency (`chat_runs`) |
| Trace | — | The stored steps of a run, shown in the "How I answered" panel |
| Grounding guardrail | — | Check that every figure, unit, tenant and citation in an answer is supported by the run's tool results (`off` / `warn` / `retry`) |
| Offline assistant | المساعد المحلي | The free rule-based provider used when `LLM_PROVIDER=offline` |
| Routing | — | Sending complex questions to a larger model when one is configured, with fallback on overload |
