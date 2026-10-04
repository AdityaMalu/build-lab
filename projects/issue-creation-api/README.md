# Retry-Safe Issue Service

**Scenario.** You're building the backend for an issue tracker. Mobile clients on flaky networks **retry** requests,
so creating an issue must be idempotent. Two people editing the same issue must not silently overwrite each other.
Lists must page through thousands of issues. Then expose it all over HTTP.

This is the hardest project: two layers, `IssueService` (domain) and `IssueHttpServer` (transport).

## Given (package `issues`)
`Role`, `User(name, role)`, `Issue(id, title, body, author, version)`, `Page(items, nextCursor)`,
`CreateResult(issue, replayed)`, `ApiException(status, message)` and a small `Json` parser/writer.
No external libraries are needed.

## Part 1: `IssueService(Map<String, User> tokens)`
Errors are thrown as `ApiException` with an HTTP-style status code.

| Method | Behaviour |
|---|---|
| `User authenticate(String token)` | Unknown/null token → **401**. |
| `CreateResult create(token, idempotencyKey, title, body)` | Must be `WRITER` (**403**). Idempotency key required (**400**). Title is trimmed, 1–200 chars (**400**). Body may be null (stored as `""`), max 5000 chars (**400**). New issue: ids 1, 2, 3... version 1, author = user name, `replayed=false`. |
| | **Idempotency:** keys are scoped per user. Same user + same key + same (trimmed) title and body → return the **original** issue with `replayed=true` and create nothing. Same key, different payload → **409**. Under concurrency, N identical retries create exactly one issue. |
| `Issue get(token, id)` | Any role. Unknown → **404**. |
| `Issue update(token, id, expectedVersion, newTitle)` | `WRITER` only. Unknown → **404**. `expectedVersion != current` → **412** (optimistic concurrency). Title validated as above. Returns the issue with `version + 1`. |
| `Page list(token, limit, cursor)` | Any role. `limit` 1–100 (**400**). Issues by ascending id. `cursor == null` starts at the beginning. `nextCursor` is an **opaque** string to pass back, or `null` when there's nothing more. Garbage cursor → **400**. Paging must stay correct while new issues are being created. |

Check order for every call: authentication (401), then authorization (403), then validation (400), then the rest.

## Part 2: `IssueHttpServer(IssueService)`
`int start(int port)` (0 = any free port, returns the actual port) and `stop()` are provided. Implement `handle`.

| Request | Response |
|---|---|
| `POST /issues` with headers `Authorization: Bearer <token>`, `Idempotency-Key: <key>` and a JSON body `{"title": "...", "body": "..."}` | **201** with the issue JSON and `Location: /issues/{id}`. A replay returns **200** with header `Idempotent-Replay: true`. |
| `GET /issues/{id}` | **200**, issue JSON, header `ETag: "<version>"` (with the quotes). |
| `PATCH /issues/{id}` with `If-Match: "<version>"` and `{"title": "..."}` | **200** with the updated issue. Missing `If-Match` → **428**. |
| `GET /issues?limit=N&cursor=C` | **200** `{"items": [...], "nextCursor": "..."}` (`null` at the end). Default limit 20. |

- Issue JSON: `{"id":1,"title":"...","body":"...","author":"...","version":1}`
- Errors: status from the `ApiException`, body `{"error":"message"}`. Malformed JSON, a non-numeric id or
  If-Match → **400**. Unknown path → **404**. Wrong method on a known path → **405**.
- All responses are `Content-Type: application/json`.

## Talking points
Where do idempotency records live in a real system, and for how long? Why 412 vs 409? Why are opaque cursors better
than `?page=7` when rows are being inserted?
