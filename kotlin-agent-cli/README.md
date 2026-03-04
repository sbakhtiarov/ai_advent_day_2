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
```

Environment variables are still supported and take precedence over `local.properties`.

Required (if not set in `local.properties`):

```bash
export OPENAI_API_KEY="<your_key>"
```

Optional:

```bash
export OPENAI_BASE_URL="https://api.openai.com/v1"
```

Model catalog is built into the app (including pricing and context-window metadata).
Use `/models` to see all available models and `/model <id|number>` to switch the active one.

Interactive mode:

```bash
./build/bin/native/releaseExecutable/agent-cli.kexe
```

One-shot mode:

```bash
./build/bin/native/releaseExecutable/agent-cli.kexe --prompt "Summarize this architecture"
```

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
- `/reset` clears in-memory session memory, clears the visible transcript, and deletes persisted session memory on disk (working/profile memory are not cleared).
- One-shot mode (`--prompt`) still does not read/write session/working/profile persisted memory, but it does load the active `user-profile-<name>.json` and inject those defaults into the system prompt.
- If persistence read/write fails, the app continues with in-memory session behavior.

## Interactive Commands

- `/help` - show commands
- `/models` - list built-in models with active marker, context window, and pricing
- `/model <id|number>` - switch active model (must be listed in `/models`)
- `/memory` - show estimated session-memory context usage
- `/compact` - choose compaction strategy (`Rolling summary`, `Sliding window`, `Fact map`, or `Branching`)
- `/profile` - choose active user profile (`user-profile-<name>.json`)
- `/reset` - clear conversation memory and transcript, then delete persisted session memory on disk (working/profile remain intact)
- `/exit` - close app
- `@<path>` - attach file path as dialog reference; file text is read only when the next prompt is submitted
- Inline refs are also supported in prompts (example: `Review @/abs/path/File.kt` or `Review @"~/path with spaces/File.kt"`).
