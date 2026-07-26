# qits-stt — working notes

Read `README.md` first: it says why a speech service is host-side and what the one route does. This
file is the working conventions on top of it.

## The one rule that shapes everything

This repo must build and test green from a **clone of itself alone** — no monorepo, no python, no
docker, no prior `mvn install` elsewhere, no network beyond maven central. `mvn verify` is the gate.
Anything that would break that is not a tradeoff to weigh, it is the thing this repo exists to
avoid.

That is why the poms duplicate versions instead of inheriting them, why the error types are copied
rather than imported, and — above all — why **no test may ever create a venv, run pip, or spawn the
worker**. See "Tests" below.

## Package and module conventions

`eu.wohlben.qits.stt.*`, split across two maven modules with disjoint sub-packages so there is no
split package:

- `domain/` — `control` (the venv bootstrap, the resident worker, `ProcessExecutor`) and `error`.
  Framework-free in the sense that matters: no JAX-RS.
- `service/` — `api` (the JAX-RS route and the exception mapper).

In the monorepo *both* halves lived under `eu.wohlben.qits.domain.speech` — the controller too. The
`domain.`/`.speech` segments are gone; `stt` is the context name everywhere, matching the repo and
the gateway segment.

## The host-side python is the design, not a bug

`SpeechWorker.ensureProcess()` shells a host `venv/bin/python`, and `TranscriptionService`
`pip install`s into a venv it creates. This looks like something that should be containerized or
moved into the workspace-daemon. It is not:

- the model must be loaded **once** and stay loaded, or per-utterance latency is dominated by
  startup rather than inference;
- transcription is not workspace-scoped — nothing here takes a workspace or repository id.

If that ever changes, it changes as a designed migration with a new wire protocol, not as a cleanup.

## Copied classes

Per migration-plan.md §5 (duplicate-now, library-later), these are **copies of monorepo code**, not
originals:

- `stt/error/*` — the five `DomainException` subclasses.
- `stt/control/ProcessExecutor` — from `domain.agent.control`; also copied into the daemon repo.

Treat them as vendored: fix a bug here and it needs fixing in `../qits` too, and vice versa. They
collapse into `libs/qits-commons` when that exists.

`SttExceptionMapper` is *not* one of these — the monorepo's `eu.wohlben.qits.api
.DomainExceptionMapper` is app-shell code that no target receives, so this is a fresh mapper typed
to this context's `DomainException`. It mirrors `qits-workspaces`' `WorkspacesExceptionMapper`.

## Schema changes

There are none. This context owns no tables (migration-plan.md §7), has no datasource and no Flyway
lineage. If something here ever needs to persist, that is a design decision to take deliberately —
adding a datasource is not a routine change in this repo.

## Tests

Both suites are `@QuarkusTest` and **neither touches python**:

- `TranscriptionServiceTest` installs a `FakeProcessExecutor` (records the bootstrap commands,
  returns canned results) and a `FakeSpeechWorker` (records the staged WAV, returns a canned
  transcript) via `QuarkusMock.installMockForType`, and points `qits.speech.home` at a temp dir
  through a `@TestProfile`.
- `SpeechControllerTest` is validation-level only: every request it sends fails before the
  transcription runner could spawn.

A test that actually ran the worker would need a venv, a pip install and a 700 MB model download.
Keep the fakes.

`service/src/test/resources/application.properties` sets `quarkus.rest.path=/api` because the tests
assert absolute paths. A consuming application sets the same property in its own config; nothing in
`src/main` depends on it.
