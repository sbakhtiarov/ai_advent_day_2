#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SERVER_NAME = 'local-git-mcp';
const SERVER_VERSION = '0.1.0';
const DEFAULT_PROTOCOL_VERSION = '2024-11-05';
const MAX_OUTPUT_CHARS = 12_000;

const TOOL_DEFINITIONS = [
  {
    name: 'git_status',
    description: 'Show the current repository status in short format, including branch information.',
    inputSchema: {
      type: 'object',
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: 'git_log',
    description: 'Show recent commits in one-line decorated format.',
    inputSchema: {
      type: 'object',
      properties: {
        limit: {
          type: 'integer',
          minimum: 1,
          maximum: 50,
          description: 'Maximum number of commits to return. Defaults to 10.',
        },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'git_diff',
    description: 'Show the current diff, optionally for staged changes or a single repo-relative path.',
    inputSchema: {
      type: 'object',
      properties: {
        staged: {
          type: 'boolean',
          description: 'When true, show the staged diff.',
        },
        path: {
          type: 'string',
          description: 'Optional repo-relative path to diff.',
        },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'git_show',
    description: 'Show a revision, optionally restricted to a single repo-relative path.',
    inputSchema: {
      type: 'object',
      properties: {
        rev: {
          type: 'string',
          description: 'Git revision to show.',
        },
        path: {
          type: 'string',
          description: 'Optional repo-relative path filter.',
        },
      },
      required: ['rev'],
      additionalProperties: false,
    },
  },
];

function parseArgs(argv) {
  let repoPath = null;

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--repo') {
      if (repoPath !== null) {
        throw new Error('Duplicate --repo argument.');
      }
      repoPath = argv[index + 1] ?? null;
      if (repoPath === null) {
        throw new Error('Missing value for --repo <absolute-path> argument.');
      }
      index += 1;
      continue;
    }
    throw new Error(`Unknown argument: ${arg}`);
  }

  const usesCurrentWorkingDirectory = repoPath === null;
  const startupDirectoryHint = process.env.PWD?.trim() || null;
  const candidateRepoPath = usesCurrentWorkingDirectory ? (startupDirectoryHint ?? process.cwd()) : repoPath;
  if (!candidateRepoPath) {
    throw new Error('Unable to determine current working directory.');
  }
  if (!path.isAbsolute(candidateRepoPath)) {
    throw new Error(`Repository path must be absolute: ${candidateRepoPath}`);
  }

  const resolvedRepoPath = path.resolve(candidateRepoPath);
  const probe = spawnSync('git', ['-C', resolvedRepoPath, 'rev-parse', '--is-inside-work-tree'], {
    encoding: 'utf8',
  });
  if (probe.status !== 0 || probe.stdout.trim() !== 'true') {
    const reason = probe.stderr.trim() || probe.stdout.trim() || 'Not a Git repository.';
    if (usesCurrentWorkingDirectory) {
      throw new Error(`Startup directory '${resolvedRepoPath}' is not a Git repository: ${reason}`);
    }
    throw new Error(`Invalid Git repository '${resolvedRepoPath}': ${reason}`);
  }

  return { repoPath: resolvedRepoPath };
}

function clampLimit(value) {
  if (value === undefined) {
    return 10;
  }
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error('limit must be a number.');
  }
  return Math.min(50, Math.max(1, Math.trunc(value)));
}

function validateRepoRelativePath(value) {
  if (value === undefined) {
    return null;
  }
  if (typeof value !== 'string') {
    throw new Error('path must be a string.');
  }

  const trimmed = value.trim();
  if (trimmed.length === 0) {
    throw new Error('path must not be blank.');
  }
  if (path.isAbsolute(trimmed)) {
    throw new Error('path must be repo-relative, not absolute.');
  }

  const segments = trimmed.split(/[\\/]+/);
  if (segments.some((segment) => segment === '..')) {
    throw new Error('path must not contain parent directory traversal.');
  }

  return trimmed;
}

function validateRevision(value) {
  if (typeof value !== 'string') {
    throw new Error('rev must be a string.');
  }

  const trimmed = value.trim();
  if (trimmed.length === 0) {
    throw new Error('rev must not be blank.');
  }
  if (trimmed.startsWith('-')) {
    throw new Error('rev must not start with "-".');
  }

  return trimmed;
}

function ensurePlainObject(value) {
  if (value === undefined) {
    return {};
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('arguments must be a JSON object.');
  }
  return value;
}

function truncateOutput(text) {
  if (text.length <= MAX_OUTPUT_CHARS) {
    return text.length === 0 ? '(no output)' : text;
  }

  return `${text.slice(0, MAX_OUTPUT_CHARS)}\n\n[output truncated to ${MAX_OUTPUT_CHARS} of ${text.length} characters]`;
}

function successResult(text) {
  return {
    content: [
      {
        type: 'text',
        text: truncateOutput(text),
      },
    ],
    isError: false,
  };
}

function errorResult(message) {
  return {
    content: [
      {
        type: 'text',
        text: message,
      },
    ],
    isError: true,
  };
}

function runGit(repoPath, gitArgs) {
  return new Promise((resolve) => {
    const child = spawn('git', ['-C', repoPath, ...gitArgs], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let stdout = '';
    let stderr = '';

    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');

    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => {
      resolve({
        ok: false,
        message: error.message,
      });
    });
    child.on('close', (code) => {
      if (code === 0) {
        resolve({
          ok: true,
          text: stdout,
        });
        return;
      }

      resolve({
        ok: false,
        message: stderr.trim() || stdout.trim() || `git exited with code ${code ?? 'unknown'}.`,
      });
    });
  });
}

async function handleToolCall(repoPath, toolName, args = {}) {
  const normalizedArgs = ensurePlainObject(args);

  switch (toolName) {
    case 'git_status': {
      const result = await runGit(repoPath, ['status', '--short', '--branch']);
      return result.ok ? successResult(result.text) : errorResult(result.message);
    }

    case 'git_log': {
      const limit = clampLimit(normalizedArgs.limit);
      const result = await runGit(repoPath, ['log', '--oneline', '--decorate', '-n', String(limit)]);
      return result.ok ? successResult(result.text) : errorResult(result.message);
    }

    case 'git_diff': {
      if (normalizedArgs.staged !== undefined && typeof normalizedArgs.staged !== 'boolean') {
        throw new Error('staged must be a boolean.');
      }
      const staged = normalizedArgs.staged ?? false;
      const requestedPath = validateRepoRelativePath(normalizedArgs.path);
      const gitArgs = ['diff'];
      if (staged) {
        gitArgs.push('--cached');
      }
      if (requestedPath) {
        gitArgs.push('--', requestedPath);
      }
      const result = await runGit(repoPath, gitArgs);
      return result.ok ? successResult(result.text) : errorResult(result.message);
    }

    case 'git_show': {
      const rev = validateRevision(normalizedArgs.rev);
      const requestedPath = validateRepoRelativePath(normalizedArgs.path);
      const gitArgs = ['show', rev];
      if (requestedPath) {
        gitArgs.push('--', requestedPath);
      }
      const result = await runGit(repoPath, gitArgs);
      return result.ok ? successResult(result.text) : errorResult(result.message);
    }

    default:
      throw new Error(`Unknown tool: ${toolName}`);
  }
}

function writeFrame(stream, message) {
  stream.write(`${JSON.stringify(message)}\n`);
}

function createMessageParser(onMessage) {
  let buffer = '';

  return (chunk) => {
    buffer += chunk.toString('utf8');

    while (true) {
      const newlineIndex = buffer.indexOf('\n');
      if (newlineIndex < 0) {
        return;
      }

      const line = buffer.slice(0, newlineIndex).replace(/\r$/, '');
      buffer = buffer.slice(newlineIndex + 1);
      if (line.trim().length === 0) {
        continue;
      }

      onMessage(JSON.parse(line));
    }
  };
}

export function createServer({ repoPath, input = process.stdin, output = process.stdout, error = process.stderr }) {
  let initialized = false;

  async function handleMessage(message) {
    if (!message || typeof message !== 'object') {
      return;
    }

    const isRequest = Object.prototype.hasOwnProperty.call(message, 'id');
    const { id, method, params } = message;

    try {
      let result;

      switch (method) {
        case 'initialize': {
          initialized = true;
          result = {
            protocolVersion:
              typeof params?.protocolVersion === 'string' && params.protocolVersion.trim().length > 0
                ? params.protocolVersion
                : DEFAULT_PROTOCOL_VERSION,
            capabilities: {
              tools: {},
            },
            serverInfo: {
              name: SERVER_NAME,
              version: SERVER_VERSION,
            },
          };
          break;
        }

        case 'notifications/initialized':
          return;

        case 'ping':
          result = {};
          break;

        case 'tools/list':
          result = {
            tools: TOOL_DEFINITIONS,
          };
          break;

        case 'tools/call': {
          const toolName = params?.name;
          if (typeof toolName !== 'string' || toolName.trim().length === 0) {
            throw createJsonRpcError(-32602, 'tools/call requires a non-blank tool name.');
          }
          result = await handleToolCall(repoPath, toolName, params?.arguments ?? {}).catch((error) =>
            errorResult(error instanceof Error ? error.message : String(error)),
          );
          break;
        }

        default:
          throw createJsonRpcError(-32601, `Method not found: ${method}`);
      }

      if (!initialized && method !== 'initialize') {
        throw createJsonRpcError(-32002, 'Server not initialized.');
      }

      if (isRequest) {
        writeFrame(output, {
          jsonrpc: '2.0',
          id,
          result,
        });
      }
    } catch (cause) {
      const jsonRpcError = normalizeJsonRpcError(cause);
      error.write(`[${SERVER_NAME}] ${jsonRpcError.message}\n`);

      if (isRequest) {
        writeFrame(output, {
          jsonrpc: '2.0',
          id,
          error: jsonRpcError,
        });
      }
    }
  }

  const parser = createMessageParser((message) => {
    void handleMessage(message);
  });

  input.on('data', parser);
  input.on('end', () => {
    process.exit(0);
  });
  input.on('error', (streamError) => {
    error.write(`[${SERVER_NAME}] stdin error: ${streamError.message}\n`);
    process.exit(1);
  });

  return {
    close() {
      input.off('data', parser);
    },
  };
}

function createJsonRpcError(code, message) {
  return { code, message };
}

function normalizeJsonRpcError(cause) {
  if (cause && typeof cause === 'object' && typeof cause.code === 'number' && typeof cause.message === 'string') {
    return {
      code: cause.code,
      message: cause.message,
    };
  }

  return {
    code: -32603,
    message: cause instanceof Error ? cause.message : 'Internal server error.',
  };
}

function main() {
  try {
    const { repoPath } = parseArgs(process.argv.slice(2));
    process.stderr.write(`[${SERVER_NAME}] serving repo ${repoPath}\n`);
    createServer({ repoPath });
  } catch (error) {
    process.stderr.write(`[${SERVER_NAME}] startup failed: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exit(1);
  }
}

const isEntrypoint = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
if (isEntrypoint) {
  main();
}
