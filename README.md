# Storage service — SCRUM-47–54

Java 21, Spring Boot **4.1.2** (same parent as Auth), Maven wrapper copied unchanged from API Gateway. Package `ir.vigoapp.storage`, port **8082**. Spring MVC, OAuth2 resource server, Security, JPA/Hibernate, Flyway, PostgreSQL, Kafka, Actuator and Prometheus. Only private coach-to-athlete program files are implemented. No image/media endpoints, cloud storage, public filesystem routes, JWT issuance or auth endpoints.

## Run locally

1. Start the separately managed `development` infrastructure: PostgreSQL database `vigo_storage_service`, Kafka topics `subscription.events`, `subscription.events.storage.dlq`, `storage.events`, external network `${APP_NETWORK_NAME:-vigo-app}`. This service's Compose does **not** create dependencies or change their repositories. Shared local database credentials are for development only; production needs a storage-only DB role and broker ACLs/TLS/SASL.
2. Copy `.env.example` to `.env`, fill real local credentials and the **actual** Auth issuer/audience/JWKS URI. Compose automatically reads `.env`; Maven/Java do not automatically load it. Export the variables into your shell before a direct JVM launch. For direct JVM development use `jdbc:postgresql://localhost:5432/vigo_storage_service`, Kafka `localhost:19092`, and a local writable `STORAGE_FS_ROOT` (for example an absolute path to `.storage`).
3. With JDK21: `./mvnw test`, then `./mvnw spring-boot:run`. On Windows use `mvnw.cmd`. Build image and start only this service with `docker compose -f compose.yml up --build -d storage`. These are operator instructions; implementation verification does not launch daemons.
4. Management runs on a **separate port 9082**, bound to `${MANAGEMENT_ADDRESS:127.0.0.1}` by default. `GET /actuator/health/liveness` is anonymous/minimal process liveness. `/actuator/health/readiness` is anonymous and status-only but aggregates readinessState, PostgreSQL (`db`), disk space, Kafka broker reachability and storage root. Readiness cannot be UP while DB access/migrations (Flyway runs before readiness state is accepted) or required storage are unavailable. A not-ready dependency is a reason to stop routing traffic, not to delete the container. Docker `HEALTHCHECK` uses `localhost:9082/actuator/health/readiness`.

The management-port filter chain permits only GET health/probes/Prometheus and denies everything else; the app-port filter chain explicitly denies `/actuator/prometheus` even with ADMIN JWT. Compose sets `MANAGEMENT_ADDRESS=0.0.0.0` so internal Prometheus can scrape `storage:9082`, but **does not publish 9082 to the host**. Treat the external `vigo-app` network as a trusted monitoring plane; use firewall/network policy and TLS/auth at your monitoring boundary in production. Do not route 9082 through the public gateway or publish it. Direct JVM development keeps management loopback-only. The Bruno `management_url` is `http://localhost:9082` for direct JVM use; it will not be reachable from the host against default Compose without an operator-approved private tunnel. No real scrape integration was verified without Docker.

`docker compose` mounts named volume `${STORAGE_VOLUME_NAME:-vigo-storage-data}` at `/var/lib/vigo/storage`. The image initializes its directory as UID/GID **10001:10001**, mode 0700; a new named volume inherits that ownership. Existing/bind-mounted volumes must be provisioned for that UID by an operator. Never run the service as root to bypass permissions. Ordinary container recreation/down preserves the volume; explicitly deleting the volume loses bytes and is not part of the soft-delete API. Back up PostgreSQL **and** this volume together. Runtime is read-only apart from that volume and bounded `/tmp`; curl is installed for the Docker healthcheck.

### Required configuration

| Variable | Purpose |
|---|---|
| SPRING_DATASOURCE_URL / USERNAME / PASSWORD | Dedicated storage PostgreSQL database |
| KAFKA_BOOTSTRAP_SERVERS | Broker addresses (Compose `kafka:9092`) |
| AUTH_JWKS_URI | Auth's RS256 public-key JWKS endpoint, required |
| JWT_ISSUER | Exact required token issuer |
| JWT_AUDIENCE | Required member of token audience |
| STORAGE_FS_ROOT | Filesystem root, default `/var/lib/vigo/storage` |
| STORAGE_OUTBOX_DELAY_MS | Relay poll delay, default 1000ms |
| APP_NETWORK_NAME / STORAGE_VOLUME_NAME | Compose external network / persistent volume names |

`AUTH_JWKS_URI` and `JWT_ISSUER` are independent: JWKS is the network address this container/host fetches keys from, while issuer must exactly equal the URL Auth put in tokens. Compose's `http://auth:8080` assumes an Auth container named `auth` on `vigo-app`; Auth is not orchestrated by this repository. If Auth runs on the host, use `http://host.docker.internal:8080/.well-known/jwks.json` (Linux needs a `host-gateway` mapping in an operator override; Compose's `extra_hosts` was not modified here) while keeping the real token issuer. No real end-to-end request test is possible until Auth issues storage-audience tokens with UUID subjects and scalar roles against an agreed audience/issuer.

Blank or absent auth configuration fails startup. An unavailable/unknown JWKS key fails authentication, not open access. Production should use HTTPS JWKS and trusted network/DNS. Multipart max file is 20 MiB = 20,971,520 bytes (contract's 20 MB interpreted as binary MiB), request 21 MiB to allow form overhead; the independent streaming counter enforces the actual content limit regardless of client claims.

## API and authorization

Native Bruno collection: `api-collection/service/bruno.json`; every endpoint has executable assertions, request body examples and response documentation. Base URL defaults to direct service `http://localhost:8082`. The shared `api-contracts/rest/storage.yaml` defines the file schemas and role matrix. Approved scope overrides the old header trust/media descriptions: this service **validates JWTs itself** and ignores all `X-User-*` client headers; media is intentionally absent.

| Route | Policy |
|---|---|
| POST /api/v1/files | Owner COACH only, no self-target, active subscription, creates version 1 |
| GET /api/v1/files | ATHLETE addressed-to-self; COACH uploaded-by-self; ADMIN all |
| GET /api/v1/files/{file_id} | Addressed ATHLETE, owner COACH, ADMIN; versions newest first |
| PATCH /api/v1/files/{file_id} | Owner COACH or ADMIN, fields title/description/category only |
| DELETE /api/v1/files/{file_id} | Owner COACH or ADMIN; soft delete, bytes/history retained |
| POST /api/v1/files/{file_id}/versions | Owner COACH only, active relation; server assigns version |
| GET /api/v1/files/{file_id}/download?version=1 | Same read policy; omitted version means latest |

ADMIN cannot upload, including new versions. Expired subscriptions never remove existing read permissions. List queries are scoped in the repository, not filtered after pagination. An ATHLETE's forged `athlete_id` or COACH's forged `coach_id` returns 403; cross-party filters remain permitted by the matrix. Detail/locking queries are similarly scoped; a separate **existence-only** active-file probe distinguishes forbidden existing files (403) from nonexistent/deleted ones (404). No metadata is returned by that probe.

All JSON uses snake_case and `{success,code,message,message_fa,data,timestamp}` with English and Persian messages. Binary downloads are the exception: streaming bytes, actual stored MIME/length, `Content-Disposition: attachment` with sanitized UTF-8 encoded filename, `X-File-Version`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`. No static storage resource handler exists. Missing committed blob/incorrect length is an internal integrity failure (not silent empty success).

JWT checks: **RS256 only**, signature from Auth JWKS, exact issuer, required audience and expiration, expiration/not-before with zero clock tolerance, canonical UUID `sub`, scalar enum `role` exactly ATHLETE/COACH/ADMIN. Role arrays, unknown roles, unsigned/wrong-algorithm/wrong-key tokens fail. This relies on Auth assigning COACH only after approval. The current Auth backlog does **not** provide immediate suspension/session revocation across services: a previously issued valid token can remain usable until expiry. Immediate cross-service revocation requires separately approved Auth token/session invalidation or introspection/event integration; this implementation does not pretend to solve it with a new Redis cache or consumer.

Title identity is **exact, case-sensitive**, enforced by PostgreSQL `COLLATE "C"` and the unique partial index `(athlete_id,category,title) WHERE deleted_at IS NULL`. Titles are not silently lowercased/trimmed. Concurrent creates and metadata collisions produce 409 FILE_ALREADY_EXISTS. Different title casing remains distinct because the contract does not prescribe case folding. New versions serialize under a pessimistic file row lock; `(file_id,version)` is also unique. Soft deletion frees that active title identity without deleting historical rows.

## File validation and filesystem safety

Allowed extensions: pdf, png, jpg, jpeg, docx, xlsx, txt, case-insensitive. Client `Content-Type` and advertised length are **untrusted**. Stage streaming bytes, compute SHA-256 and actual length, then validate before publication:

- PNG signature, bounded chunks, CRCs and IEND plus successful decode; JPEG SOI/EOI plus successful decode with no decoder warnings. Both are capped at 25 million pixels.
- PDF header/EOF plus **strict PDFBox 3.0.6 structural parsing**, page presence/count (1–1000), encrypted PDFs rejected, decoded page content bounded to 80 MiB. PDFBox is justified because a `%PDF` prefix alone accepts corrupt/truncated documents. No Apache Tika, external converter, shell helper or cloud service is used.
- DOCX/XLSX ZIP **never extracted**: central-directory integrity, entry CRC/size, max 2000 entries, total expanded bytes 80 MiB, XML part max 10 MiB, required Office parts/content types/root namespaces, no traversal/duplicate names, embedded `.bin`/VBA rejected. XML disallows DTDs/entities/external access. Arbitrary ZIPs renamed `.docx` do not pass.
- TXT is streaming strict UTF-8; malformed encoding and NUL rejected.

These checks are format validation, **not antivirus, content disarm, or a guarantee that a valid document has no malicious active content**. Serve only as attachment/nosniff; any future AV policy is a separate requirement. Archive and decoded content limits intentionally reject unusually complex but otherwise valid documents.

The blob abstraction exposes put/open/exists/size; lifecycle cleanup is package-private, not an HTTP delete. Keys are server-generated 32 lowercase hex characters, never original names. Root/ancestor/final symlinks and path traversal are rejected, final opens use NOFOLLOW_LINKS; directories are private to a dedicated service UID. Non-service users must not be able to mutate this root or its ancestors (symlink checks are defense-in-depth, not isolation against a hostile process with the same UID). Windows requires equivalent owner-only ACLs; POSIX permissions are applied where supported.

## Crash consistency and recovery

Filesystem and PostgreSQL **cannot share an atomic transaction**. Each request creates its own immutable random staged blob, validates and fsyncs it, then publishes using atomic **no-clobber hard-link creation** within the same volume, removes the stage name and fsyncs the objects directory before opening the metadata transaction. This avoids `ATOMIC_MOVE`'s platform-dependent replacement behavior. The filesystem must support local hard links and directory fsync (Linux container volume is the deployment target; Windows directory fsync may be unavailable). Metadata, file version and outbox insert commit together only after publication. A failed DB transaction checks references in a fresh transaction and compensates **only that operation's own unreferenced blob**. If commit outcome or DB reachability is uncertain, bytes are retained rather than deleting a possibly committed blob.

Crash windows can leave stage files or published **orphans**, but never intentionally commit visible metadata before bytes exist. Disk loss/manual deletion can still break this invariant; alert and restore from matched backups. There is no auto-delete janitor or public reconciliation/replay endpoint. Operator reconciliation is deliberately conservative:

1. Pause uploads and relay maintenance as appropriate; take consistent DB/volume backups. Never infer unreferenced state while Postgres is unavailable.
2. Compare object keys to **all** `file_versions.blob_key` rows, including soft-deleted files and every historical version. Check referenced size/checksum, quarantine missing/corrupt evidence and restore rather than rewriting history.
3. Inspect staged and unreferenced object names older than a safety window exceeding maximum request/transaction lifetimes. Only operator-approved, provably unreferenced staged/orphan bytes may be quarantined/removed after review. Never delete referenced soft-deleted bytes.
4. Resume traffic and monitor health/outbox. Retain audit evidence; backups and orphans may contain private athlete information.

Directory publication durability depends on the local filesystem/storage guarantees and does not replace backups. Validation exceptions clean their own stages. Request failure after an ambiguous commit may still have committed metadata; consult the unique identity/version before retrying.

## Kafka dependency contract

Canonical agreed proposal: sibling `api-contracts/events.md`. Order ownership is not yet confirmed by that team's implementation; coordinate producer rollout before real uploads. Subscription input is UTF-8 JSON with required `event_id`, `event_type`, integer `event_version=1`, `occurred_at`, `producer=order-service`, UUID `aggregate_id`, positive integer `aggregate_version`, `data`. Kafka key must equal subscription/aggregate UUID. Data is a complete snapshot: `subscription_id`, `coach_id`, `athlete_id`, `plan_id`, `status`, `starts_at`, `ends_at`; status must match SubscriptionActivated/Expired/Cancelled. Identity changes, unknown types, incompatible schemas, malformed JSON (including duplicate keys) are rejected to DLQ; additive fields are tolerated. No occurred_at last-write-wins fallback exists.

Projection and `(consumer,event_id)` dedup receipt are committed in **one DB transaction**. An atomic upsert changes a subscription only for a strictly newer aggregate_version. Distinct subscriptions for the same coach/athlete remain separate; a relation is active if **any** matching row has ACTIVE status and future ends_at, even if another subscription expired. Missing projection/event/snapshot is a fail-closed 403 NO_ACTIVE_RELATION. Offset acknowledgment occurs only **after** commit. A crash before offset commit safely redelivers. Dedup records must not be purged independently of a planned projection rebuild; bootstrap requires retained complete history or an approved Order snapshot, not a guessed active relation.

Listener uses 3 retries at 1s before recovery (4 processing attempts for retryable errors). Spring may classify certain conversion/argument failures as immediately terminal. Recovery publishes original binary payload/key/headers plus `storage-source-topic`, `storage-source-partition`, `storage-source-offset`, and sanitized `storage-error-code` to `subscription.events.storage.dlq`; it waits for broker ack before source offset commit. **DLQ publication failure throws, preventing offset advancement and retrying recovery.** Protect DLQ access: preserved original headers/payload may contain sensitive producer data, though our added error contains no exception text/credentials.

Replay is operator-controlled: fix schema/publisher/DB/broker issue; inspect the original evidence; replay corrected records to `subscription.events` with canonical key and the same event_id/aggregate_version where semantically the same event. Already processed IDs are no-ops; lower versions cannot resurrect expired/cancelled subscriptions. Unknown-type policy is DLQ, not silent ignore. There is no added consumer beyond subscriptions and no new infrastructure service.

Outbox records use stable UUID IDs, producer `storage-service`, topic `storage.events`, key file UUID, exact event types **Uploaded** (every version) and **Deleted** (soft delete). Uploaded data combines file metadata with scalar `version`, actual `size_bytes`, `content_type`, `checksum_sha256`, original name/upload info. Deleted includes file metadata and `deleted_by`. Envelope aggregate_version is version number for uploads, latest_version+1 for deletion. Metadata-only edits emit no extra events.

Relay holds a row lock until broker ack, selects only the oldest unpublished event **per aggregate**, uses SKIP LOCKED for independent aggregates, and backs off failed sends. An older pending/retrying row blocks successors across concurrent service instances. Producer uses idempotence, acks=all, bounded delivery timeout; mark published only after broker ack. Process crash between ack and DB marking can duplicate delivery: **at-least-once**, not exactly-once. Consumers must deduplicate event_id and respect aggregate versions. No unpublished outbox rows are automatically purged. Metrics: `storage.outbox.pending`, `storage.outbox.oldest.seconds`, `storage.outbox.retries`, publish/relay failure counters (Prometheus converts dots to underscores). A stuck aggregate, DLQ growth, missing disk bytes and volume space need operator alerts.

## Approved backlog traceability

Paths below are relative to this `storage/` repository; the common Java source directory is `src/main/java/ir/vigoapp/storage/` and test directory is `src/test/java/ir/vigoapp/storage/`.

| Issue / approved title | Implementation | Verification |
|---|---|---|
| SCRUM-47 — Project skeleton setup, DB migrations, Docker volume mounting, and health metrics | `pom.xml`, `StorageApplication.java`, `DependencyHealth.java`, `src/main/resources/application.yaml`, `src/main/resources/db/migration/V1__storage.sql`, `Dockerfile`, `compose.yml` | Maven compile/package; `StorageIT.java` (Docker required); container persistence not executed here |
| SCRUM-48 — Storage engine implementation (Disk/FS abstraction) and upload payload validation | `BlobStore.java`, `FileValidator.java` | `FileSystemBlobStoreTest.java`, `FileValidatorTest.java` |
| SCRUM-49 — File upload API with auto-versioning and soft-delete capability | `FileController.java`, `FileService.java`, `StoredFile.java`, SQL active unique index/row locks | `PolicyTest.java`, `StorageIT.java` |
| SCRUM-50 — Document retrieval APIs: list files, file details, and version history | `FileRepository.java`, `FileService.java`, `FileController.java` | `PolicyTest.java`, `StorageIT.java`, `api-collection/service/02 List files.bru`, `03 File metadata.bru` |
| SCRUM-51 — Secure file download endpoint with JWT validation and role-based permissions | `SecurityConfiguration.java`, `FileController.java`, `FileService.java` | `JwtCryptoTest.java`, `PolicyTest.java`, `StorageIT.java`, Bruno downloads |
| SCRUM-52 — Kafka consumer for subscription events with idempotency check, DLQ, and upload gating | `SubscriptionConsumer.java`, `InfrastructureConfiguration.java`, SQL subscriptions/processed_events | `SubscriptionConsumerTest.java`, `MessagingTest.java`, `StorageIT.java` |
| SCRUM-53 — Transactional Outbox pattern and Kafka publisher for storage.events (Uploaded / Deleted) | `StorageOutbox.java`, `FileService.java`, SQL outbox | `MessagingTest.java`, integration metadata/outbox writes; broker concurrency execution remains Docker-dependent |
| SCRUM-54 — Unit and integration testing suite and code review | `src/test/java/ir/vigoapp/storage/*Test.java`, `StorageIT.java`, Maven integration profile | Results and limitations below; integration skips are not passes |

## Tests and fixture rules

Final local verification command: `./mvnw -B -ntp -Pintegration verify` — **BUILD SUCCESS**, unit **44 run / 0 failures / 0 errors / 1 skipped** (Windows symlink privilege), integration **6 run / 0 failures / 0 errors / 6 skipped** (Docker daemon unavailable); executable JAR packaged. Skipped integration tests are not successful execution. Static `docker compose -f compose.yml config --quiet` succeeds with placeholder variables (no services started). The Bruno CLI is not installed here, so collection assertions were not executed. The Windows-privileged symlink unit case skips explicitly if symbolic links are unavailable.

`./mvnw test` runs unit tests without Docker: filesystem containment/stream limits, image/PDF/Office/TXT validation, real RSA cryptographic JWT positive/negative tests (no mocked `jwt()` shortcut), role policies, strict event parser, DLQ evidence/failure propagation and outbox acknowledgment/retry. Symlink tests explicitly skip when Windows lacks privilege.

`./mvnw -Pintegration verify` additionally runs `StorageIT` using **real PostgreSQL and Kafka Testcontainers**, a local in-test JWKS server and signed RSA fixture tokens. Coverage includes concurrent version allocation, unique title races, duplicate/out-of-order subscription projection, multiple subscriptions, real Kafka projection/DLQ, HTTP scope/header spoofing, byte-level downloads and soft-delete history. This requires an already-running authorized Docker daemon; it does not silently substitute H2 or embedded Kafka. No Docker daemon was available during implementation, so integration execution and image/container persistence were **not verified**, only source compilation. The implementation machine has JDK23; Maven compiles with `--release 21`; the production Dockerfile uses JDK/JRE21. Boot4.1.2 and wrapper3.9.16 were resolved without changing the agreed baseline.

Bruno environments contain **no tokens or private keys**. Obtain short-lived actual Auth tokens with UUID subjects and scalar roles; `environments/Local.bru` binds `coach_token`, `athlete_token`, `admin_token` to `process.env.STORAGE_COACH_TOKEN`, `STORAGE_ATHLETE_TOKEN`, `STORAGE_ADMIN_TOKEN`; export those only in your local shell or Bruno's untracked collection `.env`. No token values are committed. Fixture subjects must match recipient/coach IDs and a canonical subscription event/snapshot in storage's DB; without it coach upload correctly fails closed. JWT fixtures in automated tests are generated ephemerally and signed by the test-only JWKS key; never disable production issuer/audience/signature checks to use fixtures. Immediate suspension tests cannot be asserted until Auth implements revocation dependency.
