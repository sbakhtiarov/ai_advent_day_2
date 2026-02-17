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

Required:

```bash
export OPENAI_API_KEY="<your_key>"
```

Optional:

```bash
export OPENAI_MODEL="gpt-4.1-mini"
export OPENAI_BASE_URL="https://api.openai.com/v1"
export AGENT_SYSTEM_PROMPT="You are a concise and pragmatic assistant."
```

Interactive mode:

```bash
./build/bin/native/releaseExecutable/agent-cli.kexe
```

One-shot mode:

```bash
./build/bin/native/releaseExecutable/agent-cli.kexe --prompt "Summarize this architecture"
```

## Interactive Commands

- `/help` - show commands
- `/reset` - clear history
- `/system <prompt>` - set system prompt and clear history
- `/exit` - close app

