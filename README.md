# AI Advent Challenge Day 2

Native macOS CLI chat agent written in Kotlin/Native with Ktor and organized with Clean Architecture layers.

## Features
- Interactive dialog mode in terminal.
- One-shot prompt mode with `--prompt`.
- In-memory conversation history per session.
- `/config` menu with tabs:
  - `Format`: Plain text, Markdown, JSON, Table.
  - `Size`: max output tokens (numeric input).
  - `Stop`: custom stop behavior instruction text.
- Dynamic system prompt generation from config.
- Footer UI with persistent prompt area and system prompt preview.

## Commands
- `/help` show help.
- `/config` open configuration menu (ESC to close).
- `/reset` reset conversation and keep current system prompt.
- `/exit` close the app.

## Project Structure
```text
.
├── README.md
└── kotlin-agent-cli/
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── src/nativeMain/kotlin/com/aichallenge/day2/agent/
        ├── core/
        ├── data/
        ├── domain/
        └── presentation/
```

## Architecture
- `domain`: models, repository contracts, use cases.
- `data`: OpenAI API DTOs, remote data source, repository implementation.
- `presentation`: CLI controller and terminal rendering/input.
- `core`: environment config and DI wiring.

## Run
1. `cd kotlin-agent-cli`
2. Export required variable:
   - `export OPENAI_API_KEY="<your_key>"`
3. Optional variables:
   - `OPENAI_MODEL` (default: `gpt-4.1-mini`)
   - `OPENAI_BASE_URL` (default: `https://api.openai.com/v1`)
   - `AGENT_SYSTEM_PROMPT`
4. Build:
   - `./gradlew linkReleaseExecutableNative`
5. Run interactive:
   - `./build/bin/native/releaseExecutable/agent-cli.kexe`

One-shot mode:
- `./build/bin/native/releaseExecutable/agent-cli.kexe --prompt "Explain clean architecture"`
