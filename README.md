# Day 2 - Kotlin Native CLI Agent

This workspace contains a native macOS CLI application implemented in Kotlin with Ktor.

The app behaves as a terminal chat assistant:
- sends prompts to OpenAI API
- prints assistant responses in dialog mode
- keeps in-memory conversation history for the current session

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

Clean Architecture split:
- `domain`: entities, repository contracts, use cases
- `data`: OpenAI API integration and repository implementation
- `presentation`: CLI interaction loop and commands
- `core`: app config and dependency wiring

## Quick Start

1. `cd kotlin-agent-cli`
2. export required env var:
   - `export OPENAI_API_KEY="<your_key>"`
3. optional env vars:
   - `OPENAI_MODEL` (default: `gpt-4.1-mini`)
   - `OPENAI_BASE_URL` (default: `https://api.openai.com/v1`)
   - `AGENT_SYSTEM_PROMPT`
4. build:
   - `./gradlew linkReleaseExecutableNative`
5. run interactive chat:
   - `./build/bin/native/releaseExecutable/agent-cli.kexe`

One-shot prompt mode:
- `./build/bin/native/releaseExecutable/agent-cli.kexe --prompt "Explain clean architecture"`
