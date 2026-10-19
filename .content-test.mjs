import { apply, Config } from '/Users/akshatpratap/.dsh/profiles/web/mcp/serper-provider.mjs';
let p = null;
const ctx = { web: { registerSearchProvider: (x) => { p = x; return () => {}; } }, on: () => {}, logger: null };
apply(ctx, Config({}));
for (const q of ['capital of france', 'barack obama', 'who invented the telephone']) {
  const r = await p.search({ query: q, maxResults: 3 });
  console.log('Q:', q);
  console.log('   content :', r.content ? r.content.slice(0, 90) : '(none)');
  console.log('   sources :', r.sources.length);
}