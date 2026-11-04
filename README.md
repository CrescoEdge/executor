# executor

Process/shell execution plugin for the [Cresco](https://github.com/CrescoEdge) edge framework.
`executor` runs OS processes and shell commands (and containers via their CLI) on an agent,
**streaming stdout/stderr over the mesh dataplane** and accepting **stdin over the dataplane**, with
optional per-process OSHI metrics. It is wired into Cresco's central health and metrics systems.

- **Bundle:** `io.cresco.executor` (OSGi Declarative Services component; DS is the entry point, no `Bundle-Activator`)
- **Capability namespace:** `executor` (self-describes via `getcapabilities` → the fabric tool catalog)
- **Central metrics:** unified `MeasurementEngine` gauges exposed via `getmetrics` → `getmetricinventory`
- **Central health:** registers an `org.apache.felix.hc.api.HealthCheck` (name `executor`) discovered by `CrescoHealthExecutor`
- **Auto-load:** the agent auto-starts `executor` at boot (gated by `enable_executor`, default true)

## Model

A **runner** is a configured command identified by a `stream_name`. That same `stream_name` is the
dataplane stream its stdio rides on. Lifecycle: `config_process` (register) → `run_process` /
`start_process` (execute) → `status_process` (poll) → `end_process` (kill) → reusable. `reset_runners`
stops everything.

**Stdio over the dataplane:** stdout and stderr are forwarded as dataplane `TextMessage`s on the **GLOBAL** topic,
sharded by `stream_name`, with a `type` property of `output` or `error`. On process exit an `execution_log` event
(with the exit code) and a `delete_exchange` event are emitted on the same stream — **after** every line the process
wrote has been sent (bounded by `drain_ms`, so a background child that keeps the pipe open cannot hold the exit event
forever). Before 1.3 hardening the events could overtake the last lines, and a consumer that tears the stream down on
`delete_exchange` lost the tail.

**Stdin is opt-in.** A runner takes stdin over the dataplane only when `config_process` is sent with `stdin=true` (an
`-interactive-` command always does), and only from messages whose `type` is the runner's own **stdin type**: an
unguessable `input-<32 hex>` returned as `stdin_type` in the `config_process` reply, to the caller that configured it.
A client feeds stdin by opening the stream with `input_id=<stdin_type>`. Without stdin the process sees end-of-file at
once. (Before, anyone able to publish `stream_name=…, type=input` on the tenant's dataplane could type into any
runner, including an interactive shell; `stdin_legacy_type=true` restores that for development only, with a WARNING.)

**Interactive shells:** a command containing `-interactive-` starts an interactive `/bin/sh -i`
(or `CMD`) whose commands are driven entirely over the stdin stream.

## Actions

All routed to the plugin instance (`region`/`agent`/`pluginid`).

| Action | Type | Roles | Params | Returns | Purpose |
|---|---|---|---|---|---|
| `config_process` | CONFIG | **infra, admin** | `stream_name`, `command`, `metrics` (bool), `stdin` (bool) | `config_status`, `stdin_type` | Register a runner (does not start it). |
| `run_process` | EXEC | **infra, admin** | `stream_name` | `status` | Start a configured runner. |
| `start_process` | CONFIG | **infra, admin** | `stream_name` | `start_status` | Start a runner (CONFIG variant). |
| `status_process` | CONFIG | default | `stream_name` | `run_status` | Is the runner currently running? |
| `end_process` | CONFIG | **infra, admin** | `stream_name` | `end_status` | Kill the process (whole tree) and free the runner. |
| `reset_runners` | CONFIG | **infra, admin** | — | `reset_status` | Stop every runner on this executor. |
| `getmetrics` | EXEC | default | — | `metrics` | Live `MeasurementEngine` gauges JSON (for the metric inventory). |
| `getcapabilities` | EXEC | default | — | `capabilities` | Self-describing capability document. |

Roles are enforced by the agent before the executor runs (`@CrescoAction(roles=...)`; the controller's
`docs/caller-authorization.md`): every action that starts, configures or kills a process needs `infra` or `admin`
and nothing else; "default" is any caller of this agent's tenant, and `infra`. With security explicitly off
(development) the caller is `anonymous` with every role. `stream_name` must match `[A-Za-z0-9_][A-Za-z0-9_.:-]{0,127}`:
it names a dataplane stream, a selector value and a directory.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `stream_name` + `command` | *(unset)* | If both set at load, a runner is auto-created and started. |
| `metrics` | `false` | Collect per-process OSHI metrics (streamed live on the dataplane). |
| `runner_workdir` | `<plugin data dir>/runners` | Where each runner's own working directory (`<runner_workdir>/<stream_name>`) is made. |
| `runner_env` | `scrubbed` | A runner's environment: `scrubbed` (the agent's, without `CRESCO_*`, secret-named variables and JVM option variables), `minimal` (PATH, HOME, USER, LOGNAME, SHELL, LANG, LC_*, TZ, TMPDIR, TERM), or `inherit` (everything, secrets included: WARNING). Anything else refuses to run. |
| `drain_ms` | `10000` | How long, after the process exits, its output may still be forwarded before the exit events are sent. |
| `stdin_legacy_type` | `false` | Development only: stdin from any writer of `type=input` (WARNING). |
| `enable_executor` | `true` | Agent-level auto-load gate. |

## Central health & metrics

- **Metrics** — an `ExecutorMetrics` registers unified gauges on the `MeasurementEngine`
  (`executor.runners.configured`, `executor.runners.active`); `getmetrics` returns the standard
  `getAllMetrics()` shape, which the controller's `getmetricinventory` fan-out aggregates fabric-wide.
  Per-runner live process telemetry (cpu/mem/io of the tracked PID + descendants) still streams
  separately when `metrics=true`.
- **Health** — an `ExecutorHealthCheck` (`org.apache.felix.hc.api.HealthCheck`) is registered as an
  OSGi service; `CrescoHealthExecutor` discovers and schedules it with the built-in checks. It reports
  `OK` with the running/configured runner counts (self-guards to `TEMPORARILY_UNAVAILABLE` while
  starting up).

## Safeguards & robustness

- **Injection-safe, precise kill** — `end_process` kills the process **and its descendants** via the
  JVM `ProcessHandle` we launched (`destroyForcibly`), never by interpolating the command into a
  `ps | grep | kill` shell string (which was both a shell-injection vector and could kill the wrong
  process). The runner thread never hangs waiting on a missed kill.
- **No thread/handle leaks** — stdout/stderr readers exit on EOF (no busy-spin, no per-line throttle);
  the stdin dataplane listener is removed when the process ends (not only on kill); metrics/gobbler/
  runner threads are named daemons; `isStopped()` kills every running process so nothing is orphaned.
- **Correct lifecycle** — `stopRunner` removes the runner from the registry, so a `stream_name` is
  reusable after stop; `run_process` reports missing/already-running runners instead of silently
  no-oping; `running`/`complete` flags are `volatile` (cross-thread visibility).
- **PID-based metrics** — per-process metrics track the exact launched PID + descendants (via
  `ProcessHandle`), not fragile command-line string matching, and no longer enumerate every process
  on the host each cycle.

## What a runner can reach

`executor` runs arbitrary shell commands by design — it is a remote execution surface, so only `infra` and `admin`
may configure, start or kill a runner. Exactly what a runner gets:

- **The agent's OS user.** The command runs through `/bin/sh -c` (or `CMD /C`) as the user the agent runs as. It can
  read and write whatever that user can — including the agent's data directory, keystores and secret files. There is
  no sandbox, chroot, container or privilege drop; run the agent as a dedicated, unprivileged user.
- **Its own working directory**, `<runner_workdir>/<stream_name>`, not the agent's (before 1.3 hardening it started
  in the agent's working directory, where relative paths reached the agent's own files). This is a starting point,
  not a jail: the command can `cd` anywhere the user can.
- **An environment without the agent's secrets** (`runner_env=scrubbed`): no `CRESCO_*` variable (how an agent is
  given its settings and secrets, including `*_FILE` paths), no variable named like a secret, no `JAVA_TOOL_OPTIONS` /
  `_JAVA_OPTIONS` / `JDK_JAVA_OPTIONS`. Before, it inherited the agent's whole environment.
- **Stdin only when asked for** and only of the runner's own stdin type (above).
- **Its output is visible on the tenant's dataplane**: any subscriber of `stream_name` in the tenant reads stdout and
  stderr. Do not print secrets.
- **Its processes**: `end_process`, `reset_runners` and plugin stop kill the process and all its descendants; a
  child that detaches itself (`setsid`, double fork) can outlive them.

## Upgrade notes (1.3 hardening)

- `config_process`, `run_process`, `start_process`, `end_process` and `reset_runners` now need `infra` or `admin`.
- A runner starts in `<plugin data dir>/runners/<stream_name>`, not the agent's working directory: commands that used
  relative paths into the agent's directory must use absolute paths.
- A runner no longer sees the agent's `CRESCO_*` or secret-named environment variables (`runner_env=inherit` brings
  them back, with a WARNING).
- Stdin must be requested (`stdin=true`) and written with the returned `stdin_type` as the stream's `input_id`.
- `stream_name` is validated; names with spaces, quotes, slashes or a leading `.` are refused.
- The exit events now follow the last line of output.

## Testing & build

- `mvn test` — the role table, the whole output tail before the exit events (with a slow consumer), a background
  child not holding the exit event, the runner's working directory, the scrubbed and minimal environments, stdin only
  of the runner's own type, and stream names that could widen a selector or leave the runner's directory.
- `run/tests/executor_test.py` — lifecycle + **real-kill verification** + dataplane output streaming +
  central metrics (`getmetrics`/`getmetricinventory`). 11/11.

```bash
mvn clean package bundle:bundle -DskipTests   # produces a real OSGi bundle (JDK 21)
```
The bundle embeds OSHI + JNA (private-packaged); imports `io.cresco.library.*`, the OSGi framework,
and (optionally) `org.apache.felix.hc.api` for the health check.
