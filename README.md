# qits-stt

Speech-to-text for qits: a browser-recorded WAV goes in, a transcript comes out. Transcription runs
server-side with NVIDIA Parakeet (ONNX, CPU) via [onnx-asr](https://pypi.org/project/onnx-asr/).

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no python

## Why this is its own service

Because it is genuinely **host-side and not workspace-scoped**, which is the one thing nothing else
in the qits split is:

- `SpeechWorker` keeps a **resident host python process** alive (`<home>/venv/bin/python
  transcribe_worker.py`) with the model loaded in memory. Loading Parakeet costs seconds, so a
  python run per request would make live-ish transcription impossible; instead one worker stays up
  and requests stream over its pipes — one WAV path in, one JSON line out.
- `TranscriptionService` **bootstraps that venv on the host**, lazily and once: `python3 -m venv`
  followed by `pip install --quiet onnx-asr[cpu,hub]`, then a ~700 MB model pull from the Hugging
  Face hub on first use.

None of that belongs inside a per-workspace container, and none of it belongs in a module that has
to start fast. It wants one long-lived process with one warm model, which is exactly a service.

## Layout

| Module | What |
|---|---|
| `domain/` | `eu.wohlben.qits.stt.{control,error}` — the venv bootstrap, the resident worker, the process plumbing, and `speech/transcribe_worker.py` as a classpath resource. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.stt.api` — `POST /speech/transcriptions` and the exception mapper over it. |

Both are library jars, in the shape of the monorepo's `artifacts`/`ci` modules and of
[qits-workspaces](https://github.com/QuicklyIterateTheSoftware/qits-workspaces): a consuming Quarkus
application pulls them in and gets the route.

## What it owns

Nothing persistent. **No tables, no datasource, no Flyway lineage.** A transcription is a pure
request/response: the WAV is staged to `<home>/tmp/<uuid>.wav`, handed to the worker, and deleted in
a `finally` block. Everything durable about it — the venv, the pip install, the model cache — is
disk state under `qits.speech.home`, not database state.

The API is one route:

    POST /speech/transcriptions   { "audioBase64": "…" } -> { "text": "…" }

Base64 in JSON rather than multipart, deliberately: the clips are small and it keeps the generated
client trivial. Payloads are capped at 30 MB (≈16 minutes of 16 kHz mono 16-bit WAV).

## Configuration

| Property | Default | What |
|---|---|---|
| `qits.speech.home` | `data/speech` | Where the venv, the materialized worker script and the staging dir live. Relative to the process CWD. |
| `qits.speech.python` | `python3` | The interpreter used to *create* the venv. Must have the `venv` module. |
| `qits.speech.warmup-on-start` | `false` | Bootstrap the venv and spawn the worker (= download/load the model) on a virtual thread at startup, so the first real request doesn't pay for it. |

The worker script ships as a classpath resource at `/speech/transcribe_worker.py` and is
re-materialized to `<home>/transcribe_worker.py` on **every** bootstrap check, so script changes
deploy with the jar. The resource path kept its `speech/` prefix through the extraction — it is a
classpath location, not a package, and `WORKER_RESOURCE` in `TranscriptionService` names it
absolutely.

## Operating it

The first request (or the first warmup) is slow and network-bound: venv creation, `pip install`, and
a ~700 MB model download. `START_TIMEOUT` is 10 minutes for exactly that reason; steady-state
transcription is `TRANSCRIBE_TIMEOUT` = 2 minutes.

Requests are serialized — the worker is single-threaded. A dead or wedged worker is killed and
respawned once per request before the call gives up with a 500.

Clips up to 25 s go through `recognize()` directly; longer ones are segmented with a silero VAD
model (loaded alongside Parakeet at worker startup), because plain `recognize()` tops out around
30 s.

The host running this needs `python3` with `venv`, a C toolchain-free wheel path for `onnx-asr`, and
outbound access to PyPI and the Hugging Face hub. A deployment that cannot reach either must
pre-seed `qits.speech.home` with a built venv and a warm HF cache.

## What is deliberately *not* here

- **The recorder.** The browser side (WAV segmentation at pauses, the live transcript UI) is
  `service/src/main/webui/` in the monorepo and stays there until the frontend is redone as
  per-service Lit components.
- **Any workspace or repository awareness.** This context never sees a workspace id. It transcribes
  bytes.
- **A `main` class or an auth variant.** Like `qits-workspaces`, this is not yet a deployable — see
  migration-plan.md §9 item 7.
