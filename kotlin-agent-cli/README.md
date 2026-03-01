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
AGENT_SYSTEM_PROMPT=You are a concise and pragmatic assistant.
```

Environment variables are still supported and take precedence over `local.properties`.

Required (if not set in `local.properties`):

```bash
export OPENAI_API_KEY="<your_key>"
```

Optional:

```bash
export OPENAI_BASE_URL="https://api.openai.com/v1"
export AGENT_SYSTEM_PROMPT="You are a concise and pragmatic assistant."
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
- Session snapshot persistence includes both conversation messages and a context-usage estimate.
- On interactive startup, the app restores persisted memory exactly as previously saved.
- Each successful prompt turn is persisted immediately.
- Rolling compactization triggers when 12 non-system messages are accumulated, compacts first 10, keeps last 2, and carries previous summary forward.
- Sliding-window compactization keeps only the last 10 non-system messages and does not inject summary context.
- Fact-map compactization keeps only the last 10 non-system messages and injects a JSON key-value summary for durable facts (`goal`, `constraints`, `decisions`, `preferences`, `agreements`).
- Branching compactization groups memory by `topic/subtopic`, classifies each completed turn after assistant reply, stores turns only in the resolved subtopic, keeps a topic-level rolling summary, and injects that topic summary for the active branch context.
- Branching mode truncates oldest active-subtopic turns at request-build time when estimated context exceeds model window; stored branch history is not mutated by this truncation.
- Branching mode prints system messages when a new topic/subtopic is found or when switching to an existing branch.
- Switching to or from Branching mode via `/compact` resets active memory immediately.
- Prompt context order is: system prompt, compacted summary (as system context when present), remaining conversation, current user prompt.
- If you attach files with `@<path>`, their text content is injected into the next submitted prompt and persisted in session memory.
- `/config` resets session memory after applying output configuration and persists the reset state.
- `/reset` clears in-memory session memory, clears the visible transcript, and deletes persisted session memory on disk.
- One-shot mode (`--prompt`) does not read or write persistent memory.
- If persistence read/write fails, the app continues with in-memory session behavior.

## Interactive Commands

- `/help` - show commands
- `/models` - list built-in models with active marker, context window, and pricing
- `/model <id|number>` - switch active model (must be listed in `/models`)
- `/memory` - show estimated session-memory context usage
- `/compact` - choose compaction strategy (`Rolling summary`, `Sliding window`, `Fact map`, or `Branching`)
- `/config` - open config menu (ESC to close)
- `/temp <temperature>` - set OpenAI temperature (`0..2`)
- `/reset` - clear conversation memory and transcript, then delete persisted session memory on disk
- `/exit` - close app
- `@<path>` - attach file path as dialog reference; file text is read only when the next prompt is submitted
- Inline refs are also supported in prompts (example: `Review @/abs/path/File.kt` or `Review @"~/path with spaces/File.kt"`).
