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
OPENAI_MODEL=gpt-4.1-mini
OPENAI_MODELS=gpt-4.1-mini,gpt-4.1
OPENAI_MODEL_PRICING=gpt-4.1-mini=0.80:3.20,gpt-4.1=2.00:8.00
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
export OPENAI_MODEL="gpt-4.1-mini"
export OPENAI_MODELS="gpt-4.1-mini,gpt-4.1"
export OPENAI_MODEL_PRICING="gpt-4.1-mini=0.80:3.20,gpt-4.1=2.00:8.00"
export OPENAI_BASE_URL="https://api.openai.com/v1"
export AGENT_SYSTEM_PROMPT="You are a concise and pragmatic assistant."
```

`OPENAI_MODELS` is optional. If omitted, the app allows only `OPENAI_MODEL`. If provided, `OPENAI_MODEL` must be present in `OPENAI_MODELS`.
`OPENAI_MODEL_PRICING` is optional. Format: `model=input_usd_per_1m:output_usd_per_1m` (comma-separated for multiple models).

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
price> Total: $<amount>   # when pricing is configured for active model
time> <seconds> s
```

## Interactive Commands

- `/help` - show commands
- `/models` - list configured models and mark active one
- `/model <id|number>` - switch active model (must be listed in `/models`)
- `/config` - open config menu (ESC to close)
- `/temp <temperature>` - set OpenAI temperature (`0..2`)
- `/reset` - clear history
- `/exit` - close app
