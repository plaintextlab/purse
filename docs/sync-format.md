# Sync format (v1)

Purse syncs through a folder in a git repository that you may share with other
apps. Everything Purse owns lives in `<folder>/` (default: the app name). Nothing
outside that folder is read or written. The format is plain JSON so that the
planned desktop client (Rust) can read and write the same data.

```
<folder>/
  meta.json               {"format": 1, "app": "purse"}
  accounts.json           [AccountRec]
  categories.json         [CategoryRec]
  sms-templates.json      [TemplateRec]
  transactions/
    2026-09.json          [TxnRec]   one file per month (UTC) of `timestamp`
    2026-10.json
```

Lists are sorted (by `uuid`, transactions by `timestamp` then `uuid`), so the
same data always produces the same bytes and git diffs stay small.

## Records

Every record has:

| field       | type    | meaning                                        |
|-------------|---------|------------------------------------------------|
| `uuid`      | string  | stable id, never reused                        |
| `updatedAt` | int64   | epoch millis of the last edit                  |
| `deleted`   | bool    | tombstone; deleted records stay in the files   |

Amounts are **int64 minor units** (paise / cents). No floating point.

- **AccountRec**: `name`, `type` (`SAVINGS` `CURRENT` `CREDIT_CARD` `CASH`
  `WALLET` `FD` `RD` `MUTUAL_FUND` `STOCKS` `LOAN` `OTHER`), `numberHint`
  (comma-separated account/card digits for SMS routing), `excludeFromTotal`,
  `archived`, `sortOrder`.
- **CategoryRec**: `name`, `kind` (`EXPENSE` | `INCOME`), `icon` (emoji), `sortOrder`.
- **TxnRec**: `type` (`EXPENSE` `INCOME` `TRANSFER` `ADJUSTMENT`), `amount`,
  `accountId`, `toAccountId` (transfers), `categoryId`, `note`, `timestamp`
  (epoch millis), `source` (`MANUAL` | `SMS`), `smsHash`.
- **TemplateRec**: `name`, `senderPattern`, `pattern`, `sample`, `txnType`,
  `accountId`, `categoryId`, `enabled`.

Balance of an account = Σ income + Σ adjustments + Σ transfers in − Σ expenses
− Σ transfers out (non-deleted only). Adjustment amounts are signed; all other
amounts are positive.

Seeded defaults use fixed ids (`seed-cat-food`, `seed-acc-cash`, …) with
`updatedAt = 0`, so devices that seed independently converge.

## Merge rule

Per record, the copy with the larger `updatedAt` wins; on a tie the local copy
is kept. A client syncs like this:

1. fetch, hard-reset its checkout to the remote branch;
2. merge the folder's records into its local store;
3. rewrite the folder from the local store, commit, push;
4. if the push is rejected as non-fast-forward, repeat from 1.

Because the local store is the source of truth, a client never needs to resolve
git merge conflicts. Readers must ignore unknown fields and unknown files, and
refuse to write when `meta.json` has a higher `format` than they understand.
