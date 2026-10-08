# Analytics: SQL reports, indexes and EXPLAIN

All reports are implemented in plain SQL in
[`AnalyticsRepository`](../src/main/java/com/esep/analytics/AnalyticsRepository.java)
via `NamedParameterJdbcTemplate`. Window functions, CTEs and `generate_series` cannot be
expressed in JPQL, and reports need numbers, not managed entities.

**Spending** = `DEBIT` ledger entries on the user's accounts **in one currency**
(money that left the user). Amounts in different currencies are never summed.

| Endpoint | SQL technique |
|---|---|
| `GET /api/analytics/spending?currency=KZT&period=DAY\|WEEK\|MONTH` | `date_trunc` + `GROUP BY` |
| `GET /api/analytics/top-transactions?currency=KZT&limit=10` | `DENSE_RANK() OVER (ORDER BY amount DESC)` |
| `GET /api/analytics/moving-average?currency=KZT&window=7` | `generate_series` (fill empty days) + `AVG() OVER (ROWS BETWEEN n PRECEDING AND CURRENT ROW)` |
| `GET /api/analytics/monthly-comparison?currency=KZT&months=6` | CTE + `LAG()` + `NULLIF` against division by zero |

Common parameters: `from`, `to` (calendar days, inclusive, default: last 30 days),
`zone` (region id like `Asia/Almaty`, default `UTC`), `userId` (ADMIN only).

## Pitfalls handled

- **Empty days in a moving average.** `ROWS BETWEEN 6 PRECEDING` counts *rows*, not days.
  Without `generate_series` a 7-day window over sparse data would silently span weeks.
- **Window warm-up.** The range is widened by `window - 1` days before computing the
  average and cut back afterwards, so the first reported day already averages a full window.
- **LAG for the first month.** One extra month is loaded so the first reported month has a
  previous value. `NULLIF(previous, 0)` returns `null` percent instead of a division error.
- **Time zones.** Day boundaries are computed in the requested zone
  (`created_at AT TIME ZONE :zone`), while the `WHERE` filter compares `created_at`
  with UTC instants, so the index is still usable. Offsets like `+05:00` are rejected:
  PostgreSQL treats them as POSIX zones with an **inverted sign**.

## EXPLAIN: spending of one user for 90 days

Dataset ([`seed_perf_data.sql`](perf/seed_perf_data.sql)): 10 000 users,
1 000 000 transfers, 2 000 000 ledger entries (319 MB), PostgreSQL 17, warm cache.

| Scenario | Plan | Execution | Buffers |
|---|---|---|---|
| A. No index on `ledger_entries.account_id` | Parallel Seq Scan over 2M rows | **125.5 ms** | 18 758 |
| B. V1 index `(account_id, created_at)` | Index Scan + Filter on `direction` | 0.24 ms | 46 |
| C. V6 partial covering index | **Index Only Scan, Heap Fetches: 0** | 0.34 ms | **11** |

**Conclusions**

- The big win (×500) comes from the composite index `(account_id, created_at)` that was
  designed in V1 together with the schema.
- V6 `ON ledger_entries (account_id, created_at) INCLUDE (amount, transaction_id) WHERE direction = 'DEBIT'`
  does not change wall time on a warm cache (both sub-millisecond, within noise), but reads
  **8× fewer pages** and never touches the table. This matters on a cold cache and for users
  with long histories.
- Cost: +54 MB and slower inserts into `ledger_entries`. Every index is a trade-off between
  read speed and write speed / disk; it is added after measuring, not "just in case".
- V6 uses `CREATE INDEX CONCURRENTLY` (no write lock on a big table). This requires
  `spring.flyway.postgresql.transactional-lock: false`, otherwise the index build waits
  forever for Flyway's own open transaction.

<details>
<summary>A. No index: Parallel Seq Scan</summary>

```
 Finalize GroupAggregate (actual time=123.199..125.387 rows=28 loops=1)
   Group Key: ((date_trunc('day'::text, (e.created_at AT TIME ZONE 'Asia/Almaty'::text)))::date)
   Buffers: shared hit=910 read=17848
   ->  Gather Merge (actual time=123.178..125.355 rows=28 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         Buffers: shared hit=910 read=17848
         ->  Partial GroupAggregate (actual time=119.870..119.878 rows=9 loops=3)
               Group Key: ((date_trunc('day'::text, (e.created_at AT TIME ZONE 'Asia/Almaty'::text)))::date)
               Buffers: shared hit=910 read=17848
               ->  Sort (actual time=119.860..119.863 rows=11 loops=3)
                     Sort Key: ((date_trunc('day'::text, (e.created_at AT TIME ZONE 'Asia/Almaty'::text)))::date)
                     Sort Method: quicksort  Memory: 25kB
                     Buffers: shared hit=910 read=17848
                     Worker 0:  Sort Method: quicksort  Memory: 25kB
                     Worker 1:  Sort Method: quicksort  Memory: 26kB
                     ->  Hash Join (actual time=82.185..119.819 rows=11 loops=3)
                           Hash Cond: (e.account_id = a.id)
                           Buffers: shared hit=894 read=17848
                           ->  Parallel Seq Scan on ledger_entries e (actual time=0.050..114.610 rows=82426 loops=3)
                                 Filter: (((direction)::text = 'DEBIT'::text) AND (created_at < now()) AND (created_at >= (now() - '90 days'::interval)))
                                 Rows Removed by Filter: 584241
                                 Buffers: shared hit=844 read=17848
                           ->  Hash (actual time=0.045..0.046 rows=1 loops=3)
                                 Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                 Buffers: shared hit=20
                                 ->  Index Scan using ix_accounts_user_id on accounts a (actual time=0.041..0.041 rows=1 loops=3)
                                       Index Cond: (user_id = 501)
                                       Filter: ((currency)::text = 'KZT'::text)
                                       Buffers: shared hit=20
 Planning:
   Buffers: shared hit=285
 Planning Time: 1.284 ms
 Execution Time: 125.520 ms
```
</details>

<details>
<summary>B. V1 index: Index Scan</summary>

```
 GroupAggregate (actual time=0.175..0.189 rows=28 loops=1)
   Group Key: ((date_trunc('day'::text, (e.created_at AT TIME ZONE 'Asia/Almaty'::text)))::date)
   Buffers: shared hit=49
   ->  Sort (actual time=0.169..0.171 rows=33 loops=1)
         Sort Key: ((date_trunc('day'::text, (e.created_at AT TIME ZONE 'Asia/Almaty'::text)))::date)
         Sort Method: quicksort  Memory: 26kB
         Buffers: shared hit=49
         ->  Nested Loop (actual time=0.123..0.151 rows=33 loops=1)
               Buffers: shared hit=46
               ->  Index Scan using ix_accounts_user_id on accounts a (actual time=0.023..0.023 rows=1 loops=1)
                     Index Cond: (user_id = 501)
                     Filter: ((currency)::text = 'KZT'::text)
                     Buffers: shared hit=6
               ->  Index Scan using ix_ledger_entries_account_id on ledger_entries e (actual time=0.011..0.026 rows=33 loops=1)
                     Index Cond: ((account_id = a.id) AND (created_at >= (now() - '90 days'::interval)) AND (created_at < now()))
                     Filter: ((direction)::text = 'DEBIT'::text)
                     Rows Removed by Filter: 26
                     Buffers: shared hit=40
 Planning:
   Buffers: shared hit=332
 Planning Time: 0.980 ms
 Execution Time: 0.244 ms
```
</details>

<details>
<summary>C. V6 index: Index Only Scan</summary>

```
 GroupAggregate (actual time=0.246..0.260 rows=28 loops=1)
   Group Key: ((date_trunc('day'::text, (e.created_at AT TIME ZONE 'Asia/Almaty'::text)))::date)
   Buffers: shared hit=14
   ->  Sort (actual time=0.239..0.242 rows=33 loops=1)
         Sort Key: ((date_trunc('day'::text, (e.created_at AT TIME ZONE 'Asia/Almaty'::text)))::date)
         Sort Method: quicksort  Memory: 26kB
         Buffers: shared hit=14
         ->  Nested Loop (actual time=0.192..0.211 rows=33 loops=1)
               Buffers: shared hit=11
               ->  Index Scan using ix_accounts_user_id on accounts a (actual time=0.056..0.057 rows=1 loops=1)
                     Index Cond: (user_id = 501)
                     Filter: ((currency)::text = 'KZT'::text)
                     Buffers: shared hit=6
               ->  Index Only Scan using ix_ledger_entries_debit_account_created on ledger_entries e (actual time=0.027..0.033 rows=33 loops=1)
                     Index Cond: ((account_id = a.id) AND (created_at >= (now() - '90 days'::interval)) AND (created_at < now()))
                     Heap Fetches: 0
                     Buffers: shared hit=5
 Planning:
   Buffers: shared hit=355
 Planning Time: 1.372 ms
 Execution Time: 0.344 ms
```
</details>

## Reproduce

```bash
docker run -d --name esep-perf -e POSTGRES_USER=esep -e POSTGRES_PASSWORD=esep -e POSTGRES_DB=esep -p 55432:5432 postgres:17-alpine
for f in src/main/resources/db/migration/V{1,2,3,4,5}__*.sql; do docker exec -i esep-perf psql -U esep -d esep < "$f"; done
docker exec -i esep-perf psql -U esep -d esep < docs/perf/seed_perf_data.sql
# run the EXPLAIN (ANALYZE, BUFFERS) query, then apply V6 and run it again
docker rm -f esep-perf
```
