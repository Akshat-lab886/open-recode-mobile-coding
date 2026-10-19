import { apply, Config, name, inject } from '/Users/akshatpratap/.dsh/profiles/web/mcp/serper-provider.mjs';

console.log('plugin name :', name);
console.log('inject      :', JSON.stringify(inject));

let registered = null;
const ctx = {
  web: { registerSearchProvider: (p) => { registered = p; return () => { registered = null; }; } },
  on: () => {},
  logger: { info: (...a) => console.log('  [log]', ...a) },
};

apply(ctx, Config({}));
console.log('registered  :', registered ? registered.id : 'NONE');
console.log('available() :', registered.available());

const r = await registered.search({ query: 'deepseek harness dsh', maxResults: 5 });
console.log('');
console.log('--- search result ---');
console.log('content   :', r.content ? r.content.slice(0, 80) : '(none)');
console.log('sources   :', r.sources.length);
console.log('truncated :', r.truncated);
for (const s of r.sources.slice(0, 4)) {
  console.log('  -', (s.title || '(untitled)').slice(0, 55));
  console.log('    ', s.url.slice(0, 70));
  if (s.snippet) console.log('     snippet:', s.snippet.slice(0, 55));
}

const r2 = await registered.search({ query: 'what is react', maxResults: 3 });
console.log('');
console.log('--- knowledge-graph query ---');
console.log('content   :', r2.content ? r2.content.slice(0, 110) : '(none)');
console.log('sources   :', r2.sources.length);

process.env.SERPER_API_KEY = 'invalid-key-for-test';
let err = null;
try {
  await registered.search({ query: 'test' });
} catch (e) { err = e; }
console.log('');
console.log('--- bad key handling ---');
console.log('threw     :', !!err);
console.log('code      :', err && err.code);
console.log('message   :', err && err.message ? err.message.slice(0, 85) : '(none)');

delete process.env.SERPER_API_KEY;
console.log('');
console.log('--- missing key ---');
console.log('available() with no key:', registered.available(), '(must be false)');