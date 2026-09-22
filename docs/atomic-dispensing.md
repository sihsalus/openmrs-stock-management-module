# Coordinated dispensing and inventory

This is the backend portion of [backlog #106](https://github.com/sihsalus/sihsalus-frontend.tasktree/issues/106).
It is not ready to enable in a hospital. The frontend integration, deployment-database
validation and operational acceptance listed below remain required.

## Persistence and concurrency

`AtomicDispensingService.apply` writes the native OpenMRS MedicationDispense, the
existing stock ledger, the caller's explicit order-completion decision and an
operation receipt in one database transaction. It does not maintain another stock
balance or a duplicate clinical payload. FHIR2's translator resolves native data.

The operation UUID is the idempotency key. A replay of the same command returns
the committed receipt; a different command with that UUID conflicts. The caller
also supplies the last order receipt UUID and the medication dispense revision.
Concurrent commands based on the same order revision cannot both commit.

A database row lock coordinates the service with warehouse writes through the
existing StockManagementService transaction proxy. Locks are held to transaction
completion and work across processes. This initially serializes inventory writes;
measure contention before rollout. Direct SQL writes outside these services are
outside the application contract.

Corrections append a compensating stock movement and the replacement deduction.
Voids compensate the last deduction while retaining the clinical record and all
receipts. These actions correct recording errors; they do not implement the
physical return-of-medication workflow. A reason is required. Existing dispensations
without receipts are never automatically matched to historical ledger entries.

Quantities must fit the existing `DECIMAL(10,2)` ledger exactly. Extra decimal
places or out-of-range values are rejected before recording anything. Substitution
uses the dispensed drug's inventory and requires the existing substitution privilege,
type and reason; clinical approval of substitution remains an institutional concern.

## REST contract (version 1)

Paths are relative to `/openmrs/ws/rest/v1/stockmanagement/dispenseoperation`.
All responses have `Cache-Control: no-store`.

- `GET ?orderUuid=…&locationUuid=…` returns the contract version, whether new
  coordinated dispensations are enabled and the current order receipt UUID.
- `POST` accepts `operationUuid`, `action` (`CREATE`, `CORRECT`, `VOID`),
  `medicationDispenseUuid`, `expectedRevision`, `expectedOrderRevision`,
  `fulfillerStatus`, `reason` and, except for `VOID`, a FHIR R4
  `medicationDispense` plus `stockItemUuid`, `stockBatchUuid`, `packagingUomUuid`.
- `GET /{operationUuid}` recovers a committed receipt. A 404 means no receipt is
  visible at that moment; it is not evidence that an in-flight POST cannot commit.
- `GET /latest/{medicationDispenseUuid}` returns that record's latest receipt and
  revision. A 404 identifies an unlinked historical record.

A receipt contains identifiers, action, revision and `applied: true`. It proves
that operation committed, not that the medication dispense still has that revision.
Reload the current clinical record and latest revision before any subsequent edit.

Authentication failures are 401/403, invalid commands are 400, state conflicts are
409, and unconfirmed mutation failures are 503 with `operationOutcomeUnknown`.
Errors contain a stable code, never the submitted clinical payload or an exception
stack. There is no automatic retry in this endpoint.

The frontend must retain the operation/dispense identifiers before sending a POST,
recover by operation UUID after uncertain responses and reuse the same identifiers
on an intentional retry. Do not automatically resend after a session change or 401.
Do not store clinical payloads in browser persistence merely to support retries.

## Activation and outstanding acceptance

`stockmanagement.atomicDispensingEnabled` defaults to `false`. Enabling it requires
the coordinated frontend in every dispensing entry point, including manual
prescription completion, corrections and deletion. Once a record has receipts,
the coordination guard remains in effect even if new creation is disabled.

Before considering this issue deployable:

- Complete the frontend flow and recovery UI, preserving the existing explicit
  partial/complete order decision and role checks.
- Test the additive Liquibase changes against the deployed MariaDB version,
  including upgrade, restart, constraints and two independent sessions.
- Validate against the distribution's exact FHIR2 build (local tests currently
  compile and execute with released FHIR2 4.2.0 and OpenMRS 2.8.9).
- Add negative tests with operational minimum roles, including substitutions and
  creator-only void, rather than treating the synthetic admin tests as RBAC acceptance.
- Finish historical-unit/factor mutation protection and expired-batch correction
  rules, then test those paths without changing historical ledger values.
- Validate synthetic create/reload/partial dispense/correct/void and connection
  interruption in coordinated QLTY, and record pharmacist acceptance.
- Reconcile opening stock and unlinked historical records institutionally before
  enabling automatic deductions. Do not infer or manufacture inventory balances.

Use a new database backup before a deployment with migration. A frontend rollback
must not reopen the old independent stock-write path for linked records. Keep the
guarded backend and receipts, disable new creation if necessary, and use a reviewed
forward fix or a validated coordinated restore. Do not remove ledger entries or
receipts to make an older release appear compatible.

## Local verification

Use Java 21 and `mvn test` or `mvn verify`. Integration tests use only H2 and
synthetic Core fixtures; they create separate real transaction/session boundaries
and clean their committed fixtures. They cover committed replay, conflicts,
insufficient stock, rollback after a flushed receipt, correction/void, precision,
Core/FHIR guards, concurrent dispensations and exclusion with warehouse writes.
HTTP tests use MockMvc to verify binding, recovery, authorization responses and
safe errors. These are not production or clinical acceptance tests.
