# V1.6 clean stock-first baseline — 2026-08-09

This local comparison anchor was executed before V2 planning artifacts changed the worktree. It is attributable to clean revision `3fd476d789e3e652ed3b93ae67d23cda0315e7c4` and is not a production capacity or availability claim.

## Correctness gates

- `mvn -o -Dmaven.repo.local=/private/tmp/flashflow-m2 test`: **PASS**, 37 tests, 0 failures, 0 errors, 0 skipped.
- `openspec validate --all --strict`: **PASS**, four main specifications.

## Canonical stock-first run

- Run: `20260809T073514Z-baseline-7f29666e-7981-45e4-a636-3d6940ff3c39`.
- Inputs: local profile, conditional atomic strategy, stock-first transaction, one hot SKU, stock 100, 10 VUs, 5 seconds, pool 10, transaction retry budget 3.
- Outcomes: 3,745 requests; 100 created; 3,645 sold out; zero retryable contention and unexpected responses.
- Latency: mean 13.27 ms; p95 22.80 ms.
- Database: `100 = 0 + 100 + 0`; 100 effective orders, claims, reservations, and movements; zero invariant violations.

The V2 Redis-admission comparison must retain these controlled inputs except for its declared admission mode and must report Redis decisions separately from committed MySQL outcomes.
