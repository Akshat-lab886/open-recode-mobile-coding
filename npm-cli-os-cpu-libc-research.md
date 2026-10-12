# npm `os` / `cpu` / `libc` / `--force` / `--include` — verified research

Sources: npm CLI **v11** docs + npm/cli & arborist & npm-install-checks **source**, plus my own empirical runs on **npm 11.6.2 (darwin arm64)**.

---

## Q1 — The `libc` field

**Doc text (entire section), verbatim:**
> "If your code only runs or builds in certain versions of libc, you can specify which ones. This field only applies if `os` is `linux`."
> `{"os":"linux","libc":"glibc"}`

— https://docs.npmjs.com/cli/v11/configuring-npm/package-json/#libc

**Default when `os:["linux"]` and no `libc`:** npm does **NOT** assume glibc. There is **no** doc sentence about a default — the doc section above is all there is. Code confirms no assumption: in `npm-install-checks/lib/index.js`, `let libcOk = target.libc ? checkList(libc, target.libc) : true` — absent field ⇒ no libc constraint ⇒ installs on glibc **and** musl. The *host* libc is separately detected (`libc()` reads `/usr/bin/ldd`, else `process.report.getReport()` → `glibcVersionRuntime` ⇒ `glibc`, musl shared objects ⇒ `musl`).

Note `if (target.libc && !libc) { libcOk = false }` — if a package declares `libc` but host detection fails, install fails.

**Versions:** the `libc` **field support** landed in npm **10.3.0** (published 2024-01-10; PR npm/cli#6914 merged 2024-01-10; `npm-install-checks` v6.3.0 has the check; npm 10.3.0 `engines: ^18.17.0 || >=20.5.0`). The `--libc` **flag** also shipped in 10.3.0. The `libc` field was **undocumented** until PR npm/cli#7822 (merged 2024-10-07) → first appears in the **v11** docs.
**Confidence: high** (code + tag bisection). **No official doc states a Node version for `libc`** — the `^14.17.0 || ^16.13.0 || >=18.0.0` figure is from npm-install-checks' own `engines`, not npm docs. ⚠️

## Q2 — `--force`

**Does `--force` force-install an os/cpu/libc-mismatched OPTIONAL dep? NO.**
**Does it bypass the platform check for a NON-optional dep? YES.**

Config doc, verbatim: "Removes various protections against unfortunate side effects, common mistakes, unnecessary performance degradation, and malicious input." Bullets include "Allow installing packages that have an `engines` declaration requiring a different version of `node`…", "Allow conflicting peerDependencies to be installed in the root project." — **os/cpu/libc are not mentioned anywhere.** — https://docs.npmjs.com/cli/v11/using-npm/config/#force
`npm install` page, verbatim: "The `-f` or `--force` argument will force npm to fetch remote resources even if a local copy exists on disk." — https://docs.npmjs.com/cli/v11/commands/npm-install
`engine-strict`: "This can be overridden by setting the `--force` flag."

Source (`arborist/lib/arborist/build-ideal-tree.js`, npm 11.6.2, lines 215–229), verbatim:
```js
checkPlatform(node.package, this.options.force)      // non-optional
…
if (node.optional && !node.inert) {
  // Mark any optional packages we can't install as inert.
  // We ignore the --force and --engine-strict flags.
  try { checkEngine(…, false); checkPlatform(node.package, false, { cpu, os, libc }) }
  catch (error) { … node.inert = true … }
}
```
and `npm-install-checks/lib/index.js`: `const checkPlatform = (target, force = false, environment = {}) => { if (force) { return } … throw … code: 'EBADPLATFORM' }`.

**Verified runs (host darwin/arm64, npm 11.6.2):**
- non-optional linux-only dep, plain → `npm error code EBADPLATFORM`
- …with `--force` → `added 1 package` ✅
- optional linux-only dep, `--force --include=optional` → **nothing installed** ❌
**Confidence: high.**

## Q3 — `--include` / `--omit` / `--no-optional`

`include`, verbatim: "Option that allows for defining which types of dependencies to install. This is the inverse of `--omit=<type>`. Dependency types specified in `--include` will not be omitted, regardless of the order in which omit/include are specified on the command-line."
`omit`, verbatim: "Dependency types to omit from the installation tree on disk. Note that these dependencies *are* still resolved and added to the `package-lock.json` or `npm-shrinkwrap.json` file. They are just not physically installed on disk."
`optionalDependencies`, verbatim: "Running `npm install --omit=optional` will prevent these dependencies from being installed."

**Key distinction:** these control **tree membership** (`dev`/`optional`/`peer`/`prod` *types*), not **platform**. A platform-mismatched optional dep is **resolved and locked but marked `inert` and never unpacked** — a completely separate mechanism (`build-ideal-tree.js` catches EBADPLATFORM for optional nodes and sets `node.inert = true`; `reify.js` then detaches them: `if (node.inert) { node.parent = null }`).

`--no-optional` is **not in the v11 docs**. It survives only as a deprecated alias in source: `deprecated: "Use \`--omit=optional\` to exclude optional dependencies, or \`--include=optional\` to include them. Default value does install optional deps unless otherwise omitted."` / `description: "Alias for --include=optional or --omit=optional"`.

**Verified:** plain → darwin-arm64 only; `--omit=optional` → nothing; `--omit=optional --include=optional` → darwin-arm64 only (include wins over omit, still no cross-platform); `--include=optional --os=linux --cpu=x64 --libc=glibc` → linux-x64 **installed**.
**Confidence: high.**

## Q4 — `--os` / `--cpu` / `--libc` CLI flags: YES, they exist

Config docs, verbatim (identical text on both pages):
- `cpu`: "Override CPU architecture of native modules to install. Acceptable values are same as `cpu` field of package.json, which comes from `process.arch`."
- `os`: "Override OS of native modules to install. Acceptable values are same as `os` field of package.json, which comes from `process.platform`."
- `libc`: "Override libc of native modules to install. Acceptable values are same as `libc` field of package.json"

— https://docs.npmjs.com/cli/v11/using-npm/config#cpu · #os · #libc, also https://docs.npmjs.com/cli/v11/commands/npm-install#cpu
Defaults: `null`. In `npm install`'s `params` list (`lib/commands/install.js`): `'cpu'`, `'os'`, `'libc'` — so plain `npm install --os=linux` works.

**PRs:**
- npm/cli#6755 "feat: Add `--cpu` and `--os` option to override platform specific install" (yukukotani, merged 2023-09-08 → **npm 10.1.0**; verified `'cpu'` absent in v10.0.0 `install.js`, present in v10.1.0): "Add `--cpu` option and `--os` option which is proposed in npm/rfcs#612… Running `npm i --cpu x64 --os freebsd` will install `@node-rs/xxhash-freebsd-x64` instead of `@node-rs/xxhash-darwin-arm64`."
- npm/cli#6914 "feat: add `--libc` option to override platform specific install" (wraithgar, merged 2024-01-10 → **npm 10.3.0**).

**Official statement about cross-install** (npm/rfcs#612, verbatim): "…given the `os` and `cpu` package.json attributes, and the rise on usage of `optionalDependencies` for native modules, and also given cross platform building of certain applications like `electron`, it would be nice to be able to install npm packages for a given platform, disregarding current platform." / "Currently `optionalDependencies` only matching current platform will be installed, and it's not configurable."

**⚠️ Caveat found in code:** the override is passed **only for optional nodes** (`checkPlatform(node.package, false, { cpu, os, libc })`). For non-optional deps npm calls `checkPlatform(node.package, this.options.force)` with **no environment**, so `--os/--cpu/--libc` do **not** apply there. Verified: non-optional linux dep + `--os=linux --cpu=x64` → still `EBADPLATFORM`.
**Confidence: high** (docs + code + runs).

## Q5 — Implementation

`npm-install-checks/lib/index.js` (`checkPlatform`), verbatim:
```js
const checkPlatform = (target, force = false, environment = {}) => {
  if (force) { return }
  const os = environment.os || currentEnv.os()
  const cpu = environment.cpu || currentEnv.cpu()
  const libc = environment.libc || currentEnv.libc(os)
  const osOk = target.os ? checkList(os, target.os) : true
  const cpuOk = target.cpu ? checkList(cpu, target.cpu) : true
  let libcOk = target.libc ? checkList(libc, target.libc) : true
  if (target.libc && !libc) { libcOk = false }
  if (!osOk || !cpuOk || !libcOk) {
    throw Object.assign(new Error('Unsupported platform'), {
      pkgid: target._id, current: { os, cpu, libc },
      required: { os: target.os, cpu: target.cpu, libc: target.libc },
      code: 'EBADPLATFORM',
    })
  }
}
```
Consumer: `arborist/lib/arborist/build-ideal-tree.js` (`_checkEngineAndPlatform`), plus `arborist/lib/arborist/reify.js` `_handleOptionalFailure` → `log.verbose('reify', 'failed optional dependency', …); node.inert = true; this[_addNodeToTrashList](node)`.

**Error code:** `EBADPLATFORM`, message `"Unsupported platform"`.
**Silent or thrown?**
- **optional** → **silent**: caught, node + its optional set marked `inert`, detached in `reify.js` (`node.parent = null`), verbose log only. No warning, exit 0.
- **non-optional** → **thrown** as a hard `EBADPLATFORM` install error (unless `--force`).
**Confidence: high** (read source in the npm 11.6.2 install; note the file has since been reorganized — `npm/arborist` `main` now calls `checkPlatform(node.package, this[_force])` only.)

## Q6 — `--os` / `--cpu` cross-install caveat / issue

- **npm/rfcs#612** (quoted above) is the design statement; **npm/cli#6755** implements it.
- **npm/cli#7906 "overwriting os and cpu within cli npm install"** — real-world cross-install attempt: reporter wanted `sharp` for a Lambda layer (`os=linux, cpu=arm64, libc=glibc`) on a darwin machine and used `npm install --os=linux --cpu=arm64 --libc=glibc --force`. npm replied: "It looks like npm is working as intended here by enforcing platform compatibility checks for the installation." (that case listed the binaries under `dependencies`, not `optionalDependencies`).
- **@img/sharp#3873**: "`npm ci --omit=dev --cpu=x64 --os=linux --libc=glibc` installs both glibc and musl libs" — the flags change platform selection but do **not** make npm resolve *both* libc variants.
- ⚠️ **There is NO official npm doc statement recommending these flags for Docker/CI cross-installs.** The docs only say "Override … of native modules to install." Rationale lives in the RFC/PR. **Confidence: medium** (RFC/PR high; "official docs recommend cross-install" — *not verified, appears absent*).