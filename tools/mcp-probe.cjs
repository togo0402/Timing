const { spawn } = require('child_process');
const path = require('path');

const g = 'C:\\Users\\seven\\AppData\\Roaming\\npm\\node_modules';

async function test(name, entry) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [entry], { stdio: ['pipe', 'pipe', 'pipe'] });
    let buf = '';
    let done = false;
    const finish = (result) => {
      if (done) return;
      done = true;
      try { child.kill(); } catch {}
      resolve({ name, ...result });
    };
    child.stdout.on('data', (d) => {
      buf += d.toString();
      const lines = buf.split('\n');
      buf = lines.pop();
      for (const line of lines) {
        if (!line.trim()) continue;
        try {
          const msg = JSON.parse(line);
          if (msg.id === 1) {
            child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }) + '\n');
          }
          if (msg.id === 2) {
            const tools = (msg.result?.tools || []).map(t => t.name);
            finish({ ok: true, tools });
          }
        } catch (e) {}
      }
    });
    child.stderr.on('data', () => {});
    child.on('error', (e) => finish({ ok: false, error: e.message }));
    setTimeout(() => finish({ ok: false, error: 'timeout' }), 20000);
    child.stdin.write(JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'probe', version: '1.0.0' } }
    }) + '\n');
  });
}

(async () => {
  const results = [];
  results.push(await test('ui-ux-pro', path.join(g, 'ui-ux-pro-mcp', 'dist', 'index.js')));
  results.push(await test('wash-compose', path.join(g, '@menzies-mariesta-com', 'wash-compose-mcp', 'dist', 'index.js')));
  console.log(JSON.stringify(results, null, 2));
})();
