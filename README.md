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

**Stdio over the dataplane (any client on the mesh):** stdout and stderr are forwarded as dataplane
`TextMessage`s on the **GLOBAL** topic, sharded by `stream_name`, with a `type` property of `output`
or `error`. Stdin is accepted by subscribing to the same stream with `type=input`. An external client
reads/writes via `get_dataplane(stream_name)`. On process exit an `execution_log` event (with the exit
code) and a `delete_exchange` event are emitted on the same stream.

**Interactive shells:** a command containing `-interactive-` starts an interactive `/bin/sh -i`
(or `CMD`) whose commands are driven entirely over the stdin stream.

## Actions

All routed to the plugin instance (`region`/`agent`/`pluginid`).

| Action | Type | Params | Returns | Purpose |
|---|---|---|---|---|
| `config_process` | CONFIG | `stream_name`, `command`, `metrics` (bool) | `config_status` | Register a runner (does not start it). |
| `run_process` | EXEC | `stream_name` | `status` | Start a configured runner. |
| `start_process` | CONFIG | `stream_name` | `start_status` | Start a runner (CONFIG variant). |
| `status_process` | CONFIG | `stream_name` | `run_status` | Is the runner currently running? |
| `end_process` | CONFIG | `stream_name` | `end_status` | Kill the process (whole tree) and free the runner. |
| `reset_runners` | CONFIG | — | `reset_status` | Stop every runner on this executor. |
| `getmetrics` | EXEC | — | `metrics` | Live `MeasurementEngine` gauges JSON (for the metric inventory). |
| `getcapabilities` | EXEC | — | `capabilities` | Self-describing capability document. |

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `stream_name` + `command` | *(unset)* | If both set at load, a runner is auto-created and started. |
| `metrics` | `false` | Collect per-process OSHI metrics (streamed live on the dataplane). |
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

> **Security note:** `executor` runs arbitrary shell commands by design — it is a remote execution
> surface. Authorization is enforced at the Cresco broker/tenant layer; only principals allowed to
> route messages to this plugin can invoke it.

## Testing & build

- `run/tests/executor_test.py` — lifecycle + **real-kill verification** + dataplane output streaming +
  central metrics (`getmetrics`/`getmetricinventory`). 11/11.

```bash
mvn clean package bundle:bundle -DskipTests   # produces a real OSGi bundle (JDK 21)
```
The bundle embeds OSHI + JNA (private-packaged); imports `io.cresco.library.*`, the OSGi framework,
and (optionally) `org.apache.felix.hc.api` for the health check.
