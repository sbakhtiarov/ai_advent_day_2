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
- On interactive startup, the app restores persisted memory exactly as previously saved.
- Each successful prompt turn is persisted immediately.
- `/config` resets session memory after applying output configuration and persists the reset state.
- `/reset` clears in-memory session memory, clears the visible transcript, and deletes persisted memory.
- One-shot mode (`--prompt`) does not read or write persistent memory.
- If persistence read/write fails, the app continues with in-memory session behavior.

## Interactive Commands

- `/help` - show commands
- `/models` - list built-in models with active marker, context window, and pricing
- `/model <id|number>` - switch active model (must be listed in `/models`)
- `/config` - open config menu (ESC to close)
- `/temp <temperature>` - set OpenAI temperature (`0..2`)
- `/reset` - clear conversation memory, transcript, and persisted session memory
- `/exit` - close app
