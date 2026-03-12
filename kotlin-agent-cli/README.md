# kotlin-agent-cli

Native macOS CLI chat application (Kotlin/Native + Ktor) that calls OpenAI Responses API.

## Build

```bash
./gradlew linkReleaseExecutableNative
```

Binary output:

```text
build/bin/native/releaseExecutable/agent-cli.kexe
```

## Run

Create `local.properties` in this folder (`kotlin-agent-cli/local.properties`):

```properties
OPENAI_API_KEY=sk-...
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_API_LOG_FILE=/Users/you/.kotlin-agent-cli/openai-api-traffic.log
```

Environment variables are still supported and take precedence over `local.properties`.

Required (if not set in `local.properties`):

```bash
export OPENAI_API_KEY="<your_key>"
```

Optional:

```bash
export OPENAI_BASE_URL="https://api.openai.com/v1"
export OPENAI_API_LOG_FILE="$HOME/.kotlin-agent-cli/openai-api-traffic.log"
```

Raw OpenAI API traffic is logged to `~/.kotlin-agent-cli/openai-api-traffic.log` by default.
Set `OPENAI_API_LOG_FILE` to a different absolute path to override it, or set it to an empty value to disable file logging.
The logger writes raw JSON request/response bodies plus HTTP status and headers; the `Authorization` header is redacted in the log file.

Model catalog is built into the app (including pricing and context-window metadata).
Use `/models` to see all available models and `/model <id|number>` to switch the active one.

MCP server configuration is read from `~/.kotlin-agent-cli/mcp-servers.json`.
Example:

```json
{
  "servers": [
    { "name": "Linear", "type": "http", "url": "http://localhost:3000", "enabled": true, "public": false },
    {
      "name": "Local MCP",
      "type": "stdio",
      "command": "node",
      "args": ["/Users/you/path/to/server/dist/cli.js"],
      "enabled": false,
      "public": false
    }
  ]
}
```

`public` is loaded and persisted as part of MCP server configuration. It is currently schema-only and does not change runtime behavior yet.

In interactive mode, `/mcp` still opens the MCP server menu. You can also invoke a tool directly for connectivity checks:

```text
/mcp <server-index> <tool-name> [json-object-args]
```

`server-index` is 1-based and follows the configured server order from `mcp-servers.json`. `json-object-args` must be a raw JSON object; if omitted, the CLI sends `{}`.

The agent also exposes a built-in private tool named `notify_user` to the model on macOS turns.
It sends a local macOS notification through `/usr/bin/osascript` with schema `{ "message": string, "title"?: string }`.
This tool is always available to the LLM in interactive mode, workflow turns, and one-shot `--prompt`, but it is not shown in `/mcp` and is not manually invokable from the CLI in v1.

The agent also exposes a built-in private tool named `scheduler` to the model on macOS turns.
It supports `action: "create" | "delay" | "list" | "cancel" | "current_time"`, can schedule one-shot prompts with `run_at`, one-shot prompts relative to now with `delay_amount` + `delay_unit`, or repeating prompts with `starts_at` + `interval_minutes`, and can report the current local user time with timezone-aware structured output.
The `delay` action is one-shot only and accepts `delay_unit` values `minute|minutes|hour|hours`.
All scheduler timestamps use RFC3339 with an explicit UTC offset; repeating jobs schedule the first run at `starts_at` and then reschedule one future `launchd` trigger at a time without backfilling missed intervals.
Scheduled runs are backed by macOS `launchd`, survive app exit/reboot, write to per-job log files, and send completion/failure notifications through the same local notification backend as `notify_user`.
Like `notify_user`, this tool is model-only: it is not shown in `/mcp` and is not manually invokable from the interactive CLI in v1.

The agent also exposes a built-in private tool named `save_to_file` to the model on macOS turns.
It writes text content to disk with schema `{ "file_name": string, "content": string, "overwrite"?: boolean }`.
`file_name` is required and must resolve inside the current workspace root; relative paths are resolved from the current working directory and paths outside the workspace are rejected.
The tool auto-creates missing parent directories, fails by default when the target file already exists, and overwrites only when `overwrite: true`.
Like `notify_user` and `scheduler`, this tool is model-only: it is not shown in `/mcp` and is not manually invokable from the interactive CLI in v1.

Interactive mode:

```bash
./build/bin/native/releaseExecutable/agent-cli.kexe
```

One-shot mode:

```bash
./build/bin/native/releaseExecutable/agent-cli.kexe --prompt "Summarize this architecture"
```

Internal scheduled runs reuse the same one-shot pipeline through a hidden `--run-scheduled-job <schedule-id>` mode that is intended for `launchd`, not for interactive use.

Each assistant reply includes token usage in this format:

```text
tokens> Total: <n> | Input: <n> | Output: <n>
price> Total: $<amount>
time> <seconds> s
```

## Session Memory

- Interactive mode keeps session memory in process and persists it to `~/.kotlin-agent-cli/session-memory.json`.
- Rolling-summary compactization also persists current summary to `~/.kotlin-agent-cli/session-summary.json`.
- Interactive mode also persists independent working memory to `~/.kotlin-agent-cli/working-memory.json`.
- Interactive mode also persists independent profile/preference memory to `~/.kotlin-agent-cli/profile-memory.json`.
- Optional user-defined profile overrides are discovered from `~/.kotlin-agent-cli/user-profile-<name>.json`.
- Profile files must match `user-profile-<name>.json`; each file can include optional `display_name` (used only in `/profile` menu labels).
- Active user profile file selection is persisted in `~/.kotlin-agent-cli/active-user-profile.json` as `{ "active_file_name": "<file-name>" }`.
- Optional user-defined workflows are discovered from `~/.kotlin-agent-cli/workflow-<name>.json`.
- Workflow files must match `workflow-<name>.json` and include non-blank top-level keys: `name`, `planning`, `execution`, `validation`.
- Workflow files can optionally include `basePrompt` as an additional static system prompt for that workflow.
- Active workflow file selection is persisted in `~/.kotlin-agent-cli/active-workflow.json` as `{ "active_file_name": "<file-name>" }`.
- MCP server configuration is loaded from `~/.kotlin-agent-cli/mcp-servers.json`.
- MCP server entries use explicit transport types: `http` (`url`) or `stdio` (`command` + `args`).
- Legacy URL-only MCP entries are still accepted on load and are rewritten to the explicit `type` format on the next save.
- Scheduled jobs are persisted in `~/.kotlin-agent-cli/scheduled-jobs.json`.
- Scheduler run logs are written to `~/.kotlin-agent-cli/scheduler-logs/<schedule-id>.log`.
- Scheduler `launchd` definitions are written to `~/Library/LaunchAgents/com.aichallenge.day2.agent.scheduler.<schedule-id>.plist`.
- Invariant constraints are persisted independently in `~/.kotlin-agent-cli/invariant-constraints.json`.
- All user-visible assistant responses (interactive, workflow planning/execution, and one-shot `--prompt`) are validated against invariant constraints when configured.
- Invariant constraints are also injected into each model prompt as strict system-level requirements (normalized to `[Strict] ...` entries).
- Invariant validation runs with a strict JSON contract: `{"status":"PASS|FAIL","failed_constraints":[{"constraint":"...","source":"user|llm","user_message":"..."}]}`.
- If invariant validation returns `FAIL`, the response is blocked (not shown/persisted), regeneration runs automatically with validator feedback, and retries are capped at 2.
- If any failed constraint is sourced from `user`, generation is not retried for that turn.
- If all retries fail, the turn stops with an explicit invariant-validation error and the failed response is not stored in memory.
- Working memory is a distilled structured task state updated incrementally after each successful turn from previous working state + latest user/assistant messages.
- Working memory lifecycle is independent from session memory compaction strategy and session-memory snapshot files.
- Working memory is injected into interactive prompt context as a dedicated system-context block with normalized JSON task state.
- Profile memory stores persistent user defaults (writing style, tooling preferences, workflow defaults, stable constraints), explicit general user facts (name, work, profession, other facts), plus deterministic environment facts (timezone, OS, repo path).
- Profile memory is injected into interactive prompt context as a dedicated system-context block with normalized JSON state and is distilled incrementally after each successful turn.
- User-defined profile overrides have highest priority over distilled profile memory; non-empty user-defined fields always win during prompt building.
- Distilled profile memory remains stored only in `profile-memory.json`; merged effective state is not written back to the user-defined file.
- Profile memory distillation captures only explicit user-provided facts (no inferred assumptions), and the assistant asks 1–2 concise relevant preference questions when needed to fill missing preferences.
- Session snapshot persistence includes both conversation messages and a context-usage estimate.
- On interactive startup, the app restores persisted memory exactly as previously saved.
- Workflow mode state and workflow runtime step state are persisted in the session snapshot and restored on startup.
- Workflow mode runs a strict state machine: `User Input -> Planning -> Execution -> Validation`.
- Workflow mode clears session conversation memory at the start of every planning/execution/validation attempt (including retries).
- Workflow step continuity is carried by step prompt data (original request, approved plan, and accumulated feedback), not by prior step transcript carry-over.
- Allowed extra transitions are `Execution -> Planning` (execution comment) and `Validation -> Execution` (validation fail/invalid JSON).
- Planning and execution approvals use footer input: `1` approve, `2` cancel, any other non-blank input is treated as comment.
- Planning and execution step responses use a JSON contract: `{"needs_user_input": boolean, "questions": string[], "answer": string}`.
- If `needs_user_input=true`, the CLI asks each item from `questions` one by one and reruns the same step with collected Q/A feedback.
- Validation output is parsed as JSON contract: `{"status":"PASS|FAIL","summary":"...","details":"..."}`.
- Validation `PASS` prints completion summary + execution result and disables workflow mode.
- Validation `FAIL` or invalid JSON appends validation feedback and reruns execution.
- `/workflow` enables workflow mode by opening workflow selection when disabled, and disables workflow mode immediately when already enabled.
- Changing active workflow via `/workflow` resets current in-memory conversation context.
- When workflow mode is enabled, the footer shows a red `Workflow` label below the bottom divider.
- Each successful prompt turn is persisted immediately.
- Rolling compactization triggers when 12 non-system messages are accumulated, compacts first 10, keeps last 2, and carries previous summary forward.
- Sliding-window compactization keeps only the last 10 non-system messages and does not inject summary context.
- Fact-map compactization keeps only the last 10 non-system messages and injects a JSON key-value summary for durable facts (`goal`, `constraints`, `decisions`, `preferences`, `agreements`).
- Branching compactization groups memory by `topic/subtopic`, classifies each completed turn after assistant reply, stores turns only in the resolved subtopic, keeps a topic-level rolling summary, and injects that topic summary for the active branch context.
- Branching classification is strict-reuse first: existing topic/subtopic is reused whenever there is a reasonable semantic match, especially for details that stay within the same design scope.
- Branching starts with no default topic/subtopic branch and always routes each completed turn to a concrete specific topic/subtopic.
- When classifier proposes a new topic/subtopic, Branching runs an additional novelty-validation step and only creates a new branch if validation confirms no existing branch matches.
- If novelty validation fails or returns invalid JSON, Branching falls back to existing branch reuse (preferring active topic/subtopic when available).
- If classification fails twice and no active branch exists, Branching derives specific topic/subtopic names from the current turn text and creates that branch.
- Legacy persisted `General` topic/subtopic branches are dropped on restore.
- Branching mode truncates oldest active-subtopic turns at request-build time when estimated context exceeds model window; stored branch history is not mutated by this truncation.
- Branching mode prints system messages when a new topic/subtopic is found or when switching to an existing branch.
- Switching to or from Branching mode via `/compact` resets active memory immediately.
- Prompt context order is: system prompt (includes optional user-defined profile defaults), compacted summary (as system context when present), working-memory block (as system context when present), profile-memory block (as system context when present), remaining conversation, current user prompt.
- If you attach files with `@<path>`, their text content is injected into the next submitted prompt and persisted in session memory.
- `/profile` opens profile selection menu, switches active user-defined profile, resets in-memory session context, and persists reset snapshot.
- `/reset` clears in-memory session memory, clears the visible transcript, resets persisted conversation to an empty snapshot, and also clears working memory (profile memory remains intact).
- One-shot mode (`--prompt`) still does not read/write session/working/profile persisted memory, but it does load the active `user-profile-<name>.json` and inject those defaults into the system prompt.
- If persistence read/write fails, the app continues with in-memory session behavior.

## Interactive Commands

- `/help` - show commands
- `/models` - list built-in models with active marker, context window, and pricing
- `/model <id|number>` - switch active model (must be listed in `/models`)
- `/memory` - show estimated session-memory context usage
- `/compact` - choose compaction strategy (`Rolling summary`, `Sliding window`, `Fact map`, or `Branching`)
- `/profile` - choose active user profile (`user-profile-<name>.json`)
- `/workflow` - enable strict workflow mode with workflow selection (toggle off when already enabled)
- `/mcp` - configure MCP servers from `mcp-servers.json` (`Enter` toggles, `I` inspects tools, `ESC` closes)
- `/mcp <server-index> <tool-name> [json-object-args]` - call a tool on an enabled, already initialized MCP server (`args` must be a JSON object, omitted means `{}`)
- `/invariant` - manage persisted invariant constraints (`Del` removes selected item, `Add new constraint` creates one)
- `/reset` - clear conversation memory and transcript, clear working memory, then persist an empty session snapshot (profile memory remains intact)
- `/exit` - close app
- `@<path>` - attach file path as dialog reference; file text is read only when the next prompt is submitted
- Inline refs are also supported in prompts (example: `Review @/abs/path/File.kt` or `Review @"~/path with spaces/File.kt"`).
