# executor

Process/shell execution plugin for the [Cresco](https://github.com/CrescoEdge) edge framework.
`executor` runs OS processes and shell commands (and containers via their CLI) on an agent,
**streaming stdout/stderr over the mesh dataplane** and accepting **stdin through `write_stdin`**, with
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

**Stdin is opt-in, and never on the dataplane.** A runner keeps stdin open only when `config_process` is sent with
`stdin=true` (an `-interactive-` command always does); otherwise the process sees end-of-file at once. Stdin is written
with the `write_stdin` action (a MsgEvent), which the executor accepts only from the verified caller that started the
runner (`run_process` / `start_process`), or from an `admin` or `infra` caller. Before 1.3 hardening stdin came from
dataplane frames, where any member of the tenant who could see or guess a frame could type into the process.

**Interactive shells:** a command containing `-interactive-` starts an interactive `/bin/sh -i`
(or `CMD`) whose commands are driven entirely through `write_stdin`.

## Actions

All routed to the plugin instance (`region`/`agent`/`pluginid`).

| Action | Type | Roles | Params | Returns | Purpose |
|---|---|---|---|---|---|
| `config_process` | CONFIG | **infra, admin** | `stream_name`, `command`, `metrics` (bool), `stdin` (bool) | `config_status`, `stdin` | Register a runner (does not start it). |
| `run_process` | EXEC | **infra, admin** | `stream_name` | `status` | Start a configured runner. |
| `start_process` | CONFIG | **infra, admin** | `stream_name` | `start_status` | Start a runner (CONFIG variant). |
| `status_process` | CONFIG | default | `stream_name` | `run_status` | Is the runner currently running? |
| `end_process` | CONFIG | **infra, admin** | `stream_name` | `end_status` | Kill the process (whole tree) and free the runner. |
| `reset_runners` | CONFIG | **infra, admin** | — | `reset_status` | Stop every runner on this executor. |
| `write_stdin` | EXEC | default + **starter, admin or infra** | `stream_name`, `data` (text; a newline follows unless `newline=false`) or `data_b64`, `close_stdin` (bool) | `status` (`10` written, `9` refused: `not-owner`, `no-stdin`, `not-running`) | Write to a running process's stdin. |
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
| `runner_env` | `scrubbed` | A runner's environment, an **allowlist**: `scrubbed` (PATH, HOME, USER, LOGNAME, SHELL, LANG, LC_*, TZ, TMPDIR, TERM, plus `runner_env_allow`), `minimal` (PATH, HOME, LANG, LC_*, TZ), or `inherit` (everything, secrets included: WARNING). Anything else refuses to run. |
| `runner_env_allow` | *(unset)* | Extra variable names `scrubbed` passes; a `CRESCO_*`, JVM option (`*_OPTS`, `JAVA_TOOL_OPTIONS`...), `*PWD`/`*PASSWD` or secret-named variable is never passed, even if listed. |
| `runner_user` | *(unset)* | Run every runner as this OS user (`sudo -n -u <user>`; the agent's user needs a sudoers rule allowing it, without a password). The only real boundary between a runner and the agent: see "What a runner can reach". |
| `drain_ms` | `10000` | How long, after the process exits, its output may still be forwarded before the exit events are sent. |
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
  stdin is closed when the process ends (not only on kill); metrics/gobbler/
  runner threads are named daemons; `isStopped()` kills every running process so nothing is orphaned.
- **Correct lifecycle** — `stopRunner` removes the runner from the registry, so a `stream_name` is
  reusable after stop; `run_process` reports missing/already-running runners instead of silently
  no-oping; `running`/`complete` flags are `volatile` (cross-thread visibility).
- **PID-based metrics** — per-process metrics track the exact launched PID + descendants (via
  `ProcessHandle`), not fragile command-line string matching, and no longer enumerate every process
  on the host each cycle.

## What a runner can reach

`executor` runs arbitrary shell commands by design — it is a remote execution surface, so only `infra` and `admin`
may configure, start or kill a runner. Exactly what a runner gets, and exactly where the boundary is:

- **The agent's OS user, unless `runner_user` is set.** The command runs through a launcher shell
  (`/bin/sh -c '/bin/sh -c "$1"; exit $?'`, or `CMD /C`) as the user the agent runs as. **It can read and write
  whatever that user can** — the agent's data directory, keystores and secret files — and it can read the agent JVM's
  own environment by inspecting that process (`ps -E -p <agent pid>` on macOS, `/proc/<agent pid>/environ` on Linux),
  because a process may read another process of the same user. There is no sandbox, chroot, container or privilege
  drop. **With `runner_user` set, the launcher and the command run as that user** (`sudo -n -u`), which can read none
  of the agent's files or process environment unless file permissions say otherwise: the only real boundary. Run the
  agent as a dedicated user, give secrets to it through files only it can read, and set `runner_user`.
- **A launcher, not the agent JVM, as its parent.** The launcher shell has the runner's own environment, so the
  command's parent process holds no more than the command does. This removes the obvious route (`$PPID`); it is not a
  boundary against the agent's user (above).
- **Its own working directory**, `<runner_workdir>/<stream_name>` (a `:` in the name becomes `%3A`, so two runners
  never share one). A starting point, not a jail: the command can `cd` anywhere its user can.
- **An allowlisted environment** (`runner_env=scrubbed`): only PATH, HOME, USER, LOGNAME, SHELL, LANG, LC_*, TZ, TMPDIR
  and TERM, plus names an admin adds with `runner_env_allow` (never a `CRESCO_*`, JVM option, password or
  secret-named one). Before 1.3 hardening it inherited the agent's whole environment.
- **Stdin only when asked for**, only through `write_stdin`, only from the verified caller that started the runner, an
  admin or infra.
- **Its output is visible on the tenant's dataplane**: any subscriber of `stream_name` in the tenant reads stdout and
  stderr. Do not print secrets.
- **Its processes**: `end_process`, `reset_runners` and plugin stop kill the launcher, the command and all their
  descendants; a child that detaches itself (`setsid`, double fork) can outlive them.

## Upgrade notes (1.3 hardening)

- `config_process`, `run_process`, `start_process`, `end_process` and `reset_runners` now need `infra` or `admin`.
- A runner starts in `<plugin data dir>/runners/<stream_name>`, not the agent's working directory: commands that used
  relative paths into the agent's directory must use absolute paths.
- A runner's environment is an allowlist (PATH, HOME, USER, LOGNAME, SHELL, LANG, LC_*, TZ, TMPDIR, TERM); a command
  that needs another variable must have it added with `runner_env_allow` (`runner_env=inherit` passes everything, with
  a WARNING).
- Stdin must be requested (`stdin=true`) and is written with `write_stdin` by the caller that started the runner (or an
  admin or infra). Dataplane frames no longer reach a process's stdin.
- A runner's command runs under a launcher shell: it is the command's parent, so `$PPID` is the launcher.
- Set `runner_user` (and a sudoers rule) to run runners as another OS user: the only boundary between a runner and the
  agent's own files and environment.
- `stream_name` is validated; names with spaces, quotes, slashes or a leading `.` are refused.
- The exit events now follow the last line of output.

## Testing & build

- `mvn test` — the role table, the whole output tail before the exit events (with a slow consumer), a background
  child not holding the exit event, the runner's working directory (distinct for every name), the allowlisted
  environments, the launcher as the command's parent (and `runner_user`'s command line), stdin written only by the
  starter, an admin or infra and never from the dataplane, and stream names that could widen a selector or leave the
  runner's directory. The adversarial review's tests are part of the suite; one reads the parent's environment and
  needs a `CRESCO_*` variable set (e.g. `CRESCO_REVIEW_SECRET=x mvn test`).
- `run/tests/executor_test.py` — lifecycle + **real-kill verification** + dataplane output streaming +
  central metrics (`getmetrics`/`getmetricinventory`). 11/11.

```bash
mvn clean package bundle:bundle -DskipTests   # produces a real OSGi bundle (JDK 21)
```
The bundle embeds OSHI + JNA (private-packaged); imports `io.cresco.library.*`, the OSGi framework,
and (optionally) `org.apache.felix.hc.api` for the health check.
