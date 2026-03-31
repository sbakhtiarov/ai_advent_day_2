import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const serverPath = path.join(__dirname, 'server.mjs');
const repoPath = execFileSync('git', ['-C', __dirname, 'rev-parse', '--show-toplevel'], {
  encoding: 'utf8',
}).trim();

class McpTestClient {
  constructor(child) {
    this.child = child;
    this.nextId = 1;
    this.pending = new Map();
    this.buffer = Buffer.alloc(0);
    this.stderr = '';

    child.stdout.on('data', (chunk) => {
      this.#onStdout(chunk);
    });
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => {
      this.stderr += chunk;
    });
    child.on('exit', (code, signal) => {
      const error = new Error(`Server exited before responding (code=${code}, signal=${signal}).\n${this.stderr}`);
      for (const { reject } of this.pending.values()) {
        reject(error);
      }
      this.pending.clear();
    });
  }

  async initialize() {
    const response = await this.request('initialize', {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: {
        name: 'node-test',
        version: '1.0.0',
      },
    });
    this.notify('notifications/initialized');
    return response;
  }

  request(method, params = {}) {
    const id = this.nextId;
    this.nextId += 1;

    const payload = {
      jsonrpc: '2.0',
      id,
      method,
      params,
    };
    const frame = `${JSON.stringify(payload)}\n`;

    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.child.stdin.write(frame);
    });
  }

  notify(method, params = {}) {
    const payload = {
      jsonrpc: '2.0',
      method,
      params,
    };
    const frame = `${JSON.stringify(payload)}\n`;
    this.child.stdin.write(frame);
  }

  async close() {
    if (this.child.exitCode !== null || this.child.signalCode !== null) {
      return;
    }
    this.child.stdin.end();
    await new Promise((resolve) => {
      this.child.once('exit', resolve);
    });
  }

  #onStdout(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);

    while (true) {
      const newlineIndex = this.buffer.indexOf('\n');
      if (newlineIndex < 0) {
        return;
      }

      const line = this.buffer.subarray(0, newlineIndex).toString('utf8').replace(/\r$/, '');
      this.buffer = this.buffer.subarray(newlineIndex + 1);
      if (line.trim().length === 0) {
        continue;
      }

      const payload = JSON.parse(line);

      const pending = this.pending.get(payload.id);
      if (!pending) {
        continue;
      }
      this.pending.delete(payload.id);

      if (payload.error) {
        pending.reject(new Error(`${payload.error.code}: ${payload.error.message}`));
      } else {
        pending.resolve(payload.result);
      }
    }
  }
}

async function withClient(fn) {
  const child = spawn('node', [serverPath, '--repo', repoPath], {
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const client = new McpTestClient(child);

  try {
    await client.initialize();
    await fn(client);
  } finally {
    await client.close();
  }
}

function spawnServer({
  args = [],
  cwd,
  env,
} = {}) {
  return spawn('node', [serverPath, ...args], {
    stdio: ['pipe', 'pipe', 'pipe'],
    cwd,
    env: env ?? process.env,
  });
}

test('initialize advertises tool capability with explicit --repo', async () => {
  const child = spawnServer({
    args: ['--repo', repoPath],
  });
  const client = new McpTestClient(child);

  try {
    const result = await client.initialize();
    assert.equal(result.protocolVersion, '2024-11-05');
    assert.deepEqual(result.capabilities, { tools: {} });
    assert.equal(result.serverInfo.name, 'local-git-mcp');
    assert.equal(result.serverInfo.version, '0.1.0');
  } finally {
    await client.close();
  }
});

test('tools/list exposes the expected git tool names', async () => {
  await withClient(async (client) => {
    const result = await client.request('tools/list', {});
    assert.deepEqual(
      result.tools.map((tool) => tool.name),
      ['git_status', 'git_log', 'git_diff', 'git_show'],
    );
  });
});

test('git_status returns a non-error text result', async () => {
  await withClient(async (client) => {
    const result = await client.request('tools/call', {
      name: 'git_status',
      arguments: {},
    });

    assert.equal(result.isError, false);
    assert.equal(result.content[0].type, 'text');
    assert.match(result.content[0].text, /##| M |^\?\?/m);
  });
});

test('server defaults to process cwd when --repo is omitted', async () => {
  const child = spawnServer({
    cwd: repoPath,
  });
  const client = new McpTestClient(child);

  try {
    const initializeResult = await client.initialize();
    assert.equal(initializeResult.serverInfo.name, 'local-git-mcp');

    const listResult = await client.request('tools/list', {});
    assert.deepEqual(
      listResult.tools.map((tool) => tool.name),
      ['git_status', 'git_log', 'git_diff', 'git_show'],
    );

    const statusResult = await client.request('tools/call', {
      name: 'git_status',
      arguments: {},
    });
    assert.equal(statusResult.isError, false);
    assert.equal(statusResult.content[0].type, 'text');
    assert.match(statusResult.content[0].text, /##| M |^\?\?/m);
  } finally {
    await client.close();
  }
});

test('server prefers PWD environment hint when --repo is omitted', async () => {
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'git-mcp-pwd-env-'));
  const child = spawnServer({
    cwd: temporaryDirectory,
    env: {
      ...process.env,
      PWD: repoPath,
    },
  });
  const client = new McpTestClient(child);

  try {
    await client.initialize();
    const statusResult = await client.request('tools/call', {
      name: 'git_status',
      arguments: {},
    });
    assert.equal(statusResult.isError, false);
    assert.equal(statusResult.content[0].type, 'text');
    assert.match(statusResult.content[0].text, /##| M |^\?\?/m);
  } finally {
    await client.close();
    fs.rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test('invalid repo-relative path returns a tool error', async () => {
  await withClient(async (client) => {
    const result = await client.request('tools/call', {
      name: 'git_diff',
      arguments: {
        path: '../secret.txt',
      },
    });

    assert.equal(result.isError, true);
    assert.match(result.content[0].text, /repo-relative|parent directory traversal/i);
  });
});

test('invalid revision returns a tool error', async () => {
  await withClient(async (client) => {
    const result = await client.request('tools/call', {
      name: 'git_show',
      arguments: {
        rev: '-HEAD',
      },
    });

    assert.equal(result.isError, true);
    assert.match(result.content[0].text, /must not start with "-"/i);
  });
});

test('startup fails without --repo when cwd is not a git repository', async () => {
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'git-mcp-no-repo-'));
  const child = spawnServer({
    cwd: temporaryDirectory,
  });

  let stderr = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => {
    stderr += chunk;
  });

  const exitCode = await new Promise((resolve) => {
    child.once('exit', (code) => resolve(code));
  });

  fs.rmSync(temporaryDirectory, { recursive: true, force: true });

  assert.equal(exitCode, 1);
  assert.match(stderr, /startup directory .* is not a git repository/i);
});
