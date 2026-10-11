# PropPilot evals

|  |  |
|---|---|
| Provider | offline |
| Model(s) that answered | rule-based (162) |
| Runs per case | 3 |
| Cases | 54, 10 skipped (`offline: false`): multi-01, multi-02, turn-01, turn-02, turn-03, turn-04, ambig-02, ambig-03, inject-01, inject-04 |
| Date | 2026-10-11T17:25:51.454Z |
| Backend | localhost:8080, lease search by keyword |
| LLM judge | not run (`--judge` was not given) |

> **Offline run.** The assistant was the built-in rule-based one (zero cost). This checks the data, the tools, the streaming and the grading. It says nothing about how well Claude answers. The cases that only a real model can pass (`offline: false`) were left out, as listed above. The LLM judge did not run, unless the table above says so. Its token counts are estimates of about four characters each, and no prompt cache was involved.

## Overall

| Measure | Value |
|---|---|
| Pass rate (mean over runs) | 100.0% (162/162 runs of cases) |
| Standard deviation of the pass rate across runs | 0.00 pt |
| Cases that passed every run | 54/54 |
| Flaky cases (passed some runs) | 0 |
| Cases that failed every run | 0 |
| Questions asked (earlier turns of follow-ups included) | 162 |
| Average latency | 9 ms |
| p95 latency | 31 ms |
| Average cost per question | $0.00000 |
| Total cost | $0.00000 |
| Cache read / write tokens | 0 / 0 |
| Input / output tokens | 48030 / 25104 |
| Answers the guardrail flagged | 0 |
| Writes executed by a run | 0 |

## By category

|  | Cases | Passed | Pass rate |
|---|---|---|---|
| read | 20 | 60/60 | 100.0% |
| write | 8 | 24/24 | 100.0% |
| lease | 16 | 48/48 | 100.0% |
| multi-tool | 1 | 3/3 | 100.0% |
| ambiguous | 1 | 3/3 | 100.0% |
| out-of-scope | 4 | 12/12 | 100.0% |
| injection | 2 | 6/6 | 100.0% |
| hallucination | 2 | 6/6 | 100.0% |

## By language

|  | Cases | Passed | Pass rate |
|---|---|---|---|
| English | 28 | 84/84 | 100.0% |
| Arabic | 26 | 78/78 | 100.0% |

## Cases that failed or were flaky

Every case passed every run.
