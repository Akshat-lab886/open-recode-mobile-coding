# Research Report: Hosting Concurrent AI Agent CLI Processes on Android

**Research date:** 2026-10-12
**Scope:** Node.js agent CLI children (DeepSeek Harness, Claude Code) running inside one Android app process, each on its own localhost port.

> **Bottom line up front:** The architecture is feasible, but Android 17 (API 37) introduces a hard per-app cgroup memory ceiling that a foreground service gets **half** of, and Claude Code's observed memory behaviour is a known unbounded leak. A 2-agent design does not fit on 4 GB devices at all, and is marginal on 6 GB. Gate concurrency on device RAM and cap every child with `--max-old-space-size`.

---

## 0. BLOCKER FOUND (not in the original brief, but the design cannot work without solving it)

### 0.1 W^X blocks `execve()` from your app's data directory

Exact doc text from [Behavior changes: apps targeting API 29+](https://developer.android.com/about/versions/10/behavior-changes-10):

> **Removed execute permission for app home directory**
> Execution of files from the writable app home directory is a W^X violation. Apps should load only the binary code that's embedded within an app's APK file. Untrusted apps that target Android 10 cannot invoke execve() directly on files within the app's home directory.

Consequence: you cannot unzip a Node binary into `getFilesDir()` and `Runtime.exec()` it. The binary must live in the **native library directory** (`nativeLibraryDir`), which requires packaging it under `jniLibs/` and setting `android:extractNativeLibs="true"` (see the community-confirmed workaround at [r/androiddev](https://www.reddit.com/r/androiddev/comments/b2inbu/psa_android_q_blocks_executing_binaries_in_your/) and [busybox#137](https://github.com/meefik/busybox/issues/137)). Native lib files are extracted read-only into a non-writable path, which satisfies W^X.

Note: `nodejs-mobile-react-native` runs Node **in-process** (linked as a `.so`), not as a spawned child. See §5.4.

### 0.2 The Phantom Process Killer will target your children

Since Android 12, the system kills forked "phantom" child processes of an app under cgroup limits and heuristics, and also kills the app if children use excessive CPU. The application-side effect reported by multiple sources is `Process completed (Signal 9)` within seconds. Sources: [issuetracker.google.com/issues/205156866](https://issuetracker.google.com/issues/205156866), [cosyra guide](https://cosyra.com/guides/termux-signal-9-fix.html), [droiddeck.app](https://droiddeck.app/blog/fix-android-12-child-process-restrictions-phantom-killer), [XDA "Spare the Phantoms"](https://xdaforums.com/t/module-spare-the-phantoms.4571437/).

⚠️ **FLAGGED — partially verified.** I could not find the primary Google statement in `developer.android.com` (it is not in the current [Processes and app lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle) page). Community consensus is that the killer triggers on: >~32 child processes, or CPU-hungry children, or cgroup memory pressure. **Two agent children is well under the process-count threshold, but "CPU-hungry children" is exactly what an LLM agent CLI loop is.** Mitigate by keeping child count low and checking for a Developer Options "Disable child process restrictions" toggle on test hardware.

---

## 1. Node.js Memory Footprint on Android (arm64)

### 1.1 First-hand measurements

Measured locally on **Node v24.11.1, `process.arch === "arm64"`**. ⚠️ This is arm64 macOS, **not Android arm64** — Android uses bionic + a different V8 build, so treat these as an order-of-magnitude anchor, not a device measurement.

| Scenario | RSS | heapTotal | heapUsed |
|---|---|---|---|
| Bare `node -e ""` startup | **41.3 MB** | 5.3 MB | 3.9 MB |
| After `require("http")` + `listen(0,"127.0.0.1")` | **44.4 MB** | 5.6 MB | 4.5 MB |
| After requiring ~28 core modules | **48.4 MB** | — | — |
| 200 HTTP servers bound to 200 unique ports | **48.9 MB** | — | — |

**Critical default:** `v8.getHeapStatistics().heap_size_limit` = **4288 MB** on this 64-bit build. Node defaults its old-generation heap to ~4 GB on any 64-bit host. On a 4 GB Android phone a Node child will grow its V8 heap to consume the entire device before the cgroup limiter intervenes.

**⇒ You must pass `--max-old-space-size` explicitly to every agent child.** This is the single highest-leverage control you have.

### 1.2 Realistic per-agent RSS

Node bare baseline is ~40–50 MB. An *agent CLI* is nothing like bare Node — it loads a CLI framework, a REPL/renderer, an HTTP client, file watchers, an MCP client, and accumulates conversation context.

| Source | Figure |
|---|---|
| [Claude Code issue #4953](https://github.com/anthropics/claude-code/issues/4953) (v1.0.53, Aug 2025) | Expected behaviour quoted by reporter: *"not exceeding a reasonable amount (e.g., 1-2GB for typical usage)"*. Actual: `anon-rss` 119–125 GB, `total-vm` 234 GB, repeated Linux OOM kills every 30–60 min. |
| [Claude Code issue #21665](https://github.com/anthropics/claude-code/issues/21665) (v2.1.19, Jan 2026, **closed**) | Idle leak loop: 2,358 MB → 2,709 MB in 10 s ≈ **2 GB/min**, CPU 90.2% *while idle*, VM 75 GB, with Playwright MCP attached. Reporter used `MemoryMax=5G` via systemd to survive. |
| [r/ClaudeCode](https://www.reddit.com/r/ClaudeCode/comments/1n9wth1/why_does_running_claude_code_often_use_2040gb_of/) (Sept 2025) | Users reporting routine 20–40 GB usage. |
| [marceloretana.com](https://marceloretana.com/blog/why-claude-code-eats-all-your-ram) | *"Claude Code itself typically uses 400MB-2GB of resident memory under normal conditions. The extreme numbers (12GB+, 129GB virtual) are the result of memory leaks and accumulated session data, not normal operation."* |
| [Platformatic](https://blog.platformatic.dev/we-cut-nodejs-memory-in-half) | Production Node service workers commonly *"512 MB"* each; pointer compression halves that to ~256 MB. |
| [dev.to](https://dev.to/alanwest/how-to-cut-nodejs-memory-usage-by-40-in-self-hosted-apps-10kn) | A small dashboard service at 380 MB RSS, tuned to 210 MB. |
| [claude-trace.com](https://claude-trace.com/guides/claude-code-memory-leak) | Recommends alerting at 2 GB per process as a practical threshold. |

⚠️ **FLAGGED — I found NO published benchmark of Claude Code (or any agent CLI) running on Android arm64.** Everything above is x86_64/aarch64 Linux or macOS. Android arm64 will be *worse* (no zRAM you control, tighter cgroup, slower CPU → longer sessions). Treat 400 MB as the optimistic idle floor and 1–2 GB as the realistic steady state per agent.

### 1.3 Practical recommendation

Budget **600–900 MB RSS per agent** as the steady-state target, and enforce it with:

```
--max-old-space-size=512        # V8 old-gen ceiling, per child
--max-semi-space-size=16        # don't let young-gen balloon
```

Node will GC harder near the cap at a small CPU cost (~4% reported at a 256 MB cap). Enforce the ceiling from the parent too: poll `process.memoryUsage()` over the child's IPC channel and kill/restart on breach. Because of the confirmed leak behaviour, **treat agent restart-on-OOM as normal operation, not an error path.**

---

## 2. Android Process & Memory Limits

### 2.1 `android:largeHeap`

Exact text from [`<application>` manifest attribute](https://developer.android.com/guide/topics/manifest/application-element#largeHeap):

> **android:largeHeap** — Whether the application's processes are created with a large **Dalvik heap**. This applies to all processes created for the application. It only applies to the first application loaded into a process. […] Most apps don't need this and instead focus on reducing their overall memory usage for improved performance. Enabling this also doesn't guarantee a fixed increase in available memory, because some devices are constrained by their total available memory.

**⇒ `largeHeap` raises only the ART Java heap. It does nothing for Node child processes, and on Android 17 it does nothing about the cgroup v2 `memory.high` ceiling in §2.4.** Do not treat it as your memory strategy.

### 2.2 `ActivityManager.getMemoryClass()` / `getLargeMemoryClass()`

Exact text from [`ActivityManager`](https://developer.android.com/reference/android/app/ActivityManager):

> **getMemoryClass()** — Return the approximate per-application memory class of the current device. […] The returned value is in **megabytes**; the baseline Android memory class is 16 (which happens to be the Java heap limit of those devices); some devices with more memory may return 24 or even higher numbers.

> **getLargeMemoryClass()** — Return the approximate per-application memory class of the current device when an application is running with a large heap. […] **This is the size of the application's Dalvik heap** if it has specified `android:largeHeap="true"` in its manifest. […] This may be the same size as getMemoryClass() on memory constrained devices, or it may be significantly larger on devices with a large amount of available RAM.

Both are in **MB of ART heap only**. Widely-reported modern values are 128–256 MB (and 512 MB with `largeHeap` on mid/high-end devices), but **the values are OEM-tunable**, so read them at runtime, never hardcode. From [Memory allocation in Android apps](https://medium.com/@amsanjeev/memory-allocation-in-android-apps-ea4f859fa8c0): *"Typically 128MB to 256MB on modern devices. If you set `android:largeHeap="true"` […] the system can increase this—often to 512MB."*

### 2.3 `onTrimMemory` — largely DEAD on modern Android (important)

From [`ComponentCallbacks2`](https://developer.android.com/reference/android/content/ComponentCallbacks2), verbatim:

> **TRIM_MEMORY_COMPLETE** — This constant was **deprecated in API level 35. Apps are not notified of this level since API level 34**
> **TRIM_MEMORY_MODERATE** — This constant was **deprecated in API level 35. Apps are not notified of this level since API level 34**
> **TRIM_MEMORY_RUNNING_CRITICAL** — This constant was **deprecated in API level 35. Apps are not notified of this level since API level 34**
> **TRIM_MEMORY_RUNNING_LOW** — This constant was **deprecated in API level 35. Apps are not notified of this level since API level 34**
> **TRIM_MEMORY_RUNNING_MODERATE** — This constant was **deprecated in API level 35. Apps are not notified of this level since API level 34**

> **onLowMemory()** — This method was **deprecated in API level 35** […] **Since API level 34 this is never called.**

Confirmed in AOSP source, `core/java/android/ComponentCallbacks.java`.

**Only `TRIM_MEMORY_UI_HIDDEN` (20) and `TRIM_MEMORY_BACKGROUND` (40) are still delivered.** Both describe app lifecycle, not memory pressure.

**⇒ You cannot use `onTrimMemory` as a memory-pressure signal. Poll `ActivityManager.getMemoryInfo()` instead (§3).**

### 2.4 ⚠️ Android 17 (API 37) Memory Limiter — the hard per-app ceiling

This is the single most important constraint on your design.

From the official [AOSP Memory Limiter doc](https://source.android.com/docs/core/perf/memory-limiter):

> Android 17 and higher includes the Memory Limiter, a system service that monitors and limits the memory usage of application processes using **Linux cgroup v2**. The Memory Limiter prevents individual apps from consuming disproportionate system memory […]

> The Memory Limiter monitors app processes (**UID >= 10000**) by default. Core system processes are exempt to preserve essential system stability.

Enforcement attributes:

> `memory.high` — A soft limit. When exceeded, the process is throttled and the kernel attempts proactive page reclaim from that process.
> `memory.swap.max` — Limits the maximum amount of swap space (such as ZRAM) the process can use.

**The limit is scoped to the UID's cgroup, not to a single PID** (`am memory-limiter ignore <uid>` ignores "enforcement on all processes associated with that UID"). **⇒ Your spawned Node children, which inherit your app UID and cgroup, consume the same budget as your Java heap, native code and WebViews.** Putting the service in a separate `android:process` does **not** buy a separate budget.

#### Process-state → limit-class mapping (verbatim table)

| Process state | Memory limit class |
|---|---|
| `PERSISTENT`, `PERSISTENT_UI` | Unrestricted |
| `TOP`, `BOUND_TOP` | **Visible** |
| **`FOREGROUND_SERVICE`** | **Not visible** |
| `BOUND_FOREGROUND_SERVICE` | **Not visible** |
| `IMPORTANT_FOREGROUND` | Visible |
| `IMPORTANT_BACKGROUND`, `TRANSIENT_BACKGROUND`, `BACKUP`, `SERVICE`, `RECEIVER`, `HEAVY_WEIGHT`, `HOME`, `LAST_ACTIVITY` | Not visible |
| `CACHED_ACTIVITY`, `CACHED_ACTIVITY_CLIENT`, `CACHED_RECENT`, `CACHED_EMPTY` | Cached (frozen, then maximally reclaimed) |

**`FOREGROUND_SERVICE` is classified "Not visible" and therefore receives the more restrictive `memNotVisible` cap — half the visible cap on every tier.**

#### Standard platform limits (`/system/etc/memory-limiter-config.xml`)

| Target physical RAM | MemTotal range | `memVisible` | **`memNotVisible`** | `swapVisible` | `swapNotVisible` |
|---|---|---|---|---|---|
| 4 GB | [3200, 4800) MiB | 2,048 MiB | **1,024 MiB** | 1,024 MiB | 1,024 MiB |
| 6 GB | [4800, 6800) MiB | 4,096 MiB | **2,048 MiB** | 2,048 MiB | 2,048 MiB |
| 8 GB | [6800, 9216) MiB | 5,120 MiB | **3,072 MiB** | 3,072 MiB | 3,072 MiB |
| 12 GB | [9216, 14336) MiB | 8,192 MiB | **4,096 MiB** | 4,096 MiB | 4,096 MiB |
| 16 GB | [14336, 18432) MiB | 10,240 MiB | **5,120 MiB** | 5,120 MiB | 5,120 MiB |

> For devices with 18,432 MiB or more or for devices with less than 3,200 MiB, the Memory Limiter doesn't apply limits, leaving processes unconstrained.

Platform design principles, verbatim:

> Visible processes: Allocated approximately 1/2 to 2/3 of total physical RAM […]
> Not visible processes: Allocated approximately 1/4 to 1/3 of total physical RAM for background tasks.
> **Absence of runtime query APIs**: In Android 17 and higher, **apps can't programmatically query their assigned memory limits at run time.** The standard platform limits are defined with generous margins so that well-behaved apps operate without constraints during normal usage.

**⇒ You must derive your own budget from `totalMem` and hardcode the tier table.** There is no API to ask.

#### Enforcement & detection

From [Preparing your app for broader memory limits](https://android-developers.googleblog.com/2026/08/app-broader-memory-limits.html) (Google, 19 Aug 2026):

> **zRAM Swapping**: If your app reaches its allocated limit, the system forces your app's pages into zRAM (compressed RAM). While zRAM prevents immediate eviction, compressing and decompressing pages adds CPU overhead, which can result in noticeable UI jank and experience slowdowns.
> **Process Termination**: If your app continues to increase its memory usage beyond the zRAM threshold, it will be terminated by the system. […] the exit reason is reported as `REASON_OTHER` and the description string will contain **"MemoryLimiter:AnonSwap"**.

Detection code:

```kotlin
val am = getSystemService(ActivityManager::class.java)
am.getHistoricalProcessExitReasons(packageName, 0, 10)
  .filter { it.reason == ApplicationExitInfo.REASON_OTHER }
  .filter { it.description?.contains("MemoryLimiter:AnonSwap") == true }
```

**⇒ For an agent app this is brutal.** Your children are CPU-bound V8 work. zRAM compress/decompress on a hot JS heap destroys p99 latency long before you get killed — you will experience "the agent got mysteriously slow" long before "the agent died."

### 2.5 LMK / lmkd behaviour

From [Low memory killer daemon (AOSP)](https://source.android.com/docs/core/perf/lmkd):

> Android 10 and higher support a new lmkd mode that uses kernel **pressure stall information (PSI)** monitors for memory pressure detection. […] The default is `true` […]. Because the PSI patchset […] signals (generated by the kernel for memory pressure detection and used by lmkd) often include numerous false positives, lmkd must perform filtering.

> The Android LMK daemon process monitors the memory state of a running Android system and reacts to high memory pressure by killing the least essential processes […] (from [Low memory killers](https://developer.android.com/topic/performance/issues/lmk))

Killing order, verbatim from the developer doc: *"Background apps are killed first; system processes, last."*

#### `oom_score_adj` for your case

From [Service bindings and process states](https://developer.android.com/topic/performance/memory/guide/service-bindings):

> The Android framework uses Process States to track the importance of each running process. These states are then used by the OomAdjuster to assign an OOM Score Adjustment (`oom_score_adj`) value, ranging from -1000 to 1000. A lower `oom_score_adj` means the process is more important and less likely to be killed by the Low Memory Killer (LMK).

| Process state | Typical `oom_score_adj` |
|---|---|
| PER (Persistent) | -800 |
| TOP | 0 |
| VIS (Visible) | 200 |
| **FGS** | **0 to 200 (varies)** |
| BTOP | 100 |
| BFGS | 0 |
| PREV | 700 |
| CACHED | 900 to 999 |

**A foreground service is meaningfully protected from LMK** (0–200 vs 700+ for cached), which is good news — LMK will not be your first failure mode. **The Memory Limiter (§2.4) will be, because it does not consult `oom_score_adj` at all.**

⚠️ **FLAGGED — high-confidence inference, not documented:** `oom_score_adj` is inherited across `fork()` by the Linux kernel, so an `exec`'d child of your FGS should start with your FGS's adjustment. I found no Android documentation that explicitly states the value for `Runtime.exec()` children. Verify on-device with `adb shell cat /proc/<child_pid>/oom_score_adj`.

### 2.6 Recommended concurrency gate

Reserve **500 MB** for the host app (ART heap up to `getMemoryClass()`, one or two WebViews, native code, your bridge). Budget **800 MB per agent** steady state (§1.3).

```
budgetMiB   = (isAppVisible ? memVisible : memNotVisible) - 500      // agent children in an FGS => use memNotVisible
maxAgents   = floor(budgetMiB / 800)
```

| Device RAM | FGS ceiling (`memNotVisible`) | after 500 MB host reserve | **Max concurrent agents** |
|---|---|---|---|
| 4 GB | 1,024 MiB | 524 MiB | **0 background / 1 foreground-only** |
| 6 GB | 2,048 MiB | 1,548 MiB | **1** |
| 8 GB | 3,072 MiB | 2,572 MiB | **2** (tight) |
| 12 GB | 4,096 MiB | 3,596 MiB | **2** (comfortable) |
| 16 GB+ | 5,120 MiB | 4,620 MiB | **2–3** |

Notes:
- **4 GB:** do not offer a background/multi-agent mode at all. If the Activity is visible you get `memVisible` = 2,048 MiB → 1 agent is viable. Gate on `isAppVisible`, not just RAM.
- **6 GB:** 2 agents at 800 MB = 1,600 MB fits in 2,048 MB but leaves <20% headroom for a WebView and transient V8 growth. Ship 1, or ship 2 behind an explicit "reduced" heap cap (384 MB each).
- **8 GB / 12 GB / 16 GB:** 2 agents is the safe default. This is your primary target.
- **Never gate on `getMemoryClass()`.** It measures ART heap and is unrelated to either the LMK or the Memory Limiter.
- Consider an explicit user-visible "device memory tier" in settings rather than a silent downgrade, and always expose a manual agent-count override.

### 2.7 Testing hooks (build these into your CI)

```
adb shell am memory-limiter status                  # shows enforced memVisible/memNotVisible + event count
adb shell am memory-limiter ignore <uid>           # disable limits for your UID
adb shell am memory-limiter manual <pid> 512MB     # clamp one child process — ideal for leak testing
adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS <pkg>
adb shell device_config put activity_manager data_sync_fgs_timeout_duration <ms>
adb shell cat /proc/<pid>/oom_score_adj
```

---

## 3. Detecting Available RAM on Android

From [`ActivityManager.MemoryInfo`](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo), verbatim field docs:

> **availMem** *(API 1)* — The available memory on the system. **This number should not be considered absolute: due to the nature of the kernel, a significant portion of this memory is actually in use and needed for the overall system to run well.**

> **totalMem** *(API 16)* — The total memory accessible by the kernel. This is basically the RAM size of the device, **not including below-kernel fixed allocations** like DMA buffers, RAM for the baseband CPU, etc.

> **threshold** *(API 1)* — The threshold of `availMem` at which we consider memory to be low and start killing background services and other non-extraneous processes.

> **lowMemory** *(API 1)* — Set to true if the system considers itself to currently be in a low memory situation.

> **advertisedMem** *(API 34)* — The advertised memory of the system, as the end user would encounter in a retail display environment. This value might be different from `totalMem`. This could be due to many reasons. For example, the ODM could reserve part of the memory for the Trusted Execution Environment (TEE) which the kernel doesn't have access or knowledge about it.

> **freeMem** *(API 37)* — The free memory on the system. This is the unused RAM size of the device. Unlike `availMem`, it's a basic snapshot of free RAM, not accounting for reclaimable memory.

### Best practice

1. **Branch the concurrency gate on `totalMem`**, mapped to the §2.6 tier table — because the Android 17 limit is a function of total RAM and is not queryable.
2. Use `advertisedMem` (API 34+) if you want the number that matches what the marketing says; note the Memory Limiter's own tiers key off **kernel `MemTotal`**, which is closer to `totalMem`. **Prefer `totalMem`** for tier selection.
3. **Poll `availMem` as a runtime safety valve**, not as the gate. Since `availMem` "should not be considered absolute", treat it as advisory: refuse to *start* a new agent when `availMem < 15% of totalMem`, and shed an agent when `availMem < 8% of totalMem`. Use `MemoryInfo.threshold` as the system-provided "LMK will start killing" line.
4. **`lowMemory` is a blunt system-wide flag** — good for a UI warning, too coarse for automation.
5. **Poll every 5–15 s in a `Handler` on the main looper** (or a coroutine on `Dispatchers.Main.immediate`). `getMemoryInfo()` is a binder call into system_server — cheap, but not free.
6. **Combine with your own accounting**: sum the RSS your children report over IPC, and use that as the primary input, with `availMem` as a cross-check. Your children's RSS is what actually consumes the cgroup budget.

---

## 4. Foreground Service Constraints (API 34 / 35 / 36 / 37)

### 4.1 Does `dataSync` still allow long-running background work in 2026? **No.**

Verbatim from [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout):

> If an app targets Android 15 or higher, the system places restrictions on how long certain foreground services are allowed to run while your app is in the background. Currently, this restriction only applies to `dataSync` and `mediaProcessing` foreground service type foreground services.

> The system permits `dataSync` and `mediaProcessing` foreground services to run for a **total of 6 hours in a 24-hour period**, after which the system calls the running service's `Service.onTimeout(int, int)` method (introduced in Android 15). […] The six-hour time limit is tracked separately for `dataSync` and for `mediaProcessing` services.

> **The 6-hour time limit is shared by all of an app's foreground services of the specified type.** For example, if an app runs a `dataSync` service for four hours, then starts a different `dataSync` service, that second service will only be allowed to run for two hours. **However, if the user brings the app to the foreground, the timer resets and the app has 6 hours available.**

> When a foreground service reaches the six-hour limit, the service has a few seconds to call `Service.stopSelf()`. When the system calls `Service.onTimeout()`, the service is no longer considered a foreground service. **If the service does not call `Service.stopSelf()`, the system throws an internal exception.** The exception is logged in Logcat with the following message:
> `Fatal Exception: android.app.RemoteServiceException: "A foreground service of type [service type] did not stop within its timeout: [component name]"`

> If your app's `dataSync` foreground services have run for 6 hours in the last 24, you cannot start another `dataSync` foreground service *unless* the user has brought your app to the foreground […] the system throws `ForegroundServiceStartNotAllowedException` with an error message like "Time limit already exhausted for foreground service type dataSync".

Additionally, from [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): *"Apps that target Android 15 or higher are not allowed to launch a data sync foreground service from a BOOT_COMPLETED broadcast receiver."*

An agent that must stay alive for a 30-minute coding session is fine; one that must survive an overnight run is not. **`dataSync` is the wrong type.**

### 4.2 The correct long-lived type: `specialUse`

Verbatim from [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types):

> **Special use**
> Manifest: `android:foregroundServiceType="specialUse"` · Permission: `FOREGROUND_SERVICE_SPECIAL_USE` · Constant: `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` · Runtime prerequisites: None
> **Description:** Covers any valid foreground service use cases that aren't covered by the other foreground service types.
> In addition to declaring the `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` foreground service type, developers should declare use cases in the manifest. To do so, they specify the `<property>` element within the `<service>` element. **These values and corresponding use cases are reviewed when you submit your app in the Google Play Console.** The use cases you provide are free-form, and you should make sure to provide enough information to let the reviewer see why you need to use the specialUse type.

```xml
<service android:name=".AgentService"
         android:foregroundServiceType="specialUse"
         android:exported="false">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
              android:value="Hosts long-running AI coding agent CLI child processes that execute user-requested code edits and must survive backgrounding." />
</service>
```

**`specialUse` has NO documented wall-clock timeout** — it is the only unbounded type with no runtime prerequisites. ⚠️ Trade-off: **Play Console review.** This is the correct technical answer but it is the type most likely to be challenged at review, so the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value must be specific and honest.

### 4.3 Full FGS type list and their limits

Per [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), the complete set is: `camera`, `connectedDevice`, `dataSync`, `health`, `location`, `mediaPlayback`, `mediaProcessing`, `mediaProjection`, `microphone`, `phoneCall`, `remoteMessaging`, `shortService`, `specialUse`, `systemExempted`.

| Type | Timeout | Applicable to you? |
|---|---|---|
| `dataSync` | **6 h / 24 h** (API 35+) | ❌ wrong type |
| `mediaProcessing` | **6 h / 24 h** (API 35+) | ❌ wrong type |
| `shortService` | **~3 minutes** | ❌ explicitly excluded |
| `camera`, `connectedDevice`, `health`, `location`, `mediaPlayback`, `mediaProjection`, `microphone`, `phoneCall`, `remoteMessaging` | none documented | ❌ semantically false |
| `systemExempted` | unrestricted | ❌ system apps only |
| **`specialUse`** | **none documented** | ✅ **use this** |

On `shortService`, verbatim — worth knowing because it is a tempting fallback:

> Can only run for a short period of time (about 3 minutes). No support for sticky foreground services. **Cannot start other foreground services.** […] If you start a foreground service that includes the shortService type and another foreground service type, the system ignores the shortService type declaration.

### 4.4 Other relevant changes by API level

From [Changes to foreground services](https://developer.android.com/develop/background-work/services/fgs/changes), verbatim:

> **Android 16 (API level 36)** — The following changes apply to apps that run on Android 16 or higher, regardless of what API level they target: **Background jobs started from a foreground service now must adhere to their respective runtime quotas.** This includes jobs scheduled directly with `JobScheduler`, as well as jobs created by other libraries like `WorkManager` or `DownloadManager`.

> **Android 15 (API level 35)** — There are new restrictions on how long a dataSync foreground service can run. […] `BOOT_COMPLETED` foreground services are no longer allowed to launch certain foreground services. Apps that hold the `SYSTEM_ALERT_WINDOW` permission are only allowed to launch foreground services from the background if they currently have a visible overlay window.

> **Android 14 (API level 34)** — You must declare all foreground services with their service types. Apps must request the appropriate permission type for the kind of work the foreground service will be doing. […] If an app targets API level 34 or higher and doesn't request the appropriate specific permission, the system throws a `SecurityException`.

> **Android 12 (API level 31)** — Apps are not allowed to launch foreground services while the app is in the background, with a few specific exceptions.

**⇒ Practical FGS plan:**
1. Manifest: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` permissions.
2. `AgentService` with `foregroundServiceType="specialUse"` and a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property.
3. Start it from a **visible Activity** (button press / user action) — required by the API 31+ background-start restriction.
4. Do **not** restart it from `BOOT_COMPLETED` on API 35+.
5. Never schedule `JobScheduler`/`WorkManager` work from inside it and expect it to bypass quotas.
6. No `onTimeout()` handling needed for `specialUse` — but implement it defensively anyway.

---

## 5. Multiple Localhost Ports in One Android Process

### 5.1 Is there any constraint on binding many ports? **No meaningful one.**

**First-hand measurement** (Node v24.11.1, arm64): bound **200 HTTP servers on 200 unique ports in a single process**:

```
servers bound OK: 200 unique ports: 200
first 5: 52632, 52633, 52634, 52635, 52636 | max: 52831
RSS after 200 http servers (MB): 48.9
open fds: 213
```

- Process RSS went from **44.4 MB → 48.9 MB** for 200 listeners ⇒ **~22 KB of RSS per bound port.** Effectively free.
- **1 file descriptor per listening socket** (213 fds for 200 servers + ~13 baseline).
- Ports are allocated from the ephemeral range (`/proc/sys/net/ipv4/ip_local_port_range`, typically 32768–60999 on Android, so ~28k available). Binding **2–4** ports is nowhere near any limit.

Port binding is an ordinary Linux socket operation. Android adds no per-app restriction on the number of listening sockets beyond the generic file-descriptor limit (`RLIMIT_NOFILE`), which is in the tens of thousands on Android.

### 5.2 Can two WebView instances each use their own port? **Yes.**

WebView instances are independent HTTP clients; each does `loadUrl("http://127.0.0.1:PORT")` against whatever server owns that port. There is no constraint tying a WebView to a port. Bind `serverA → 0` (kernel-assigned ephemeral), read `server.address().port`, and give each WebView its own.

**Port hygiene — do this anyway:**
- Bind to `127.0.0.1`, never `0.0.0.0`. Android does not block you from `0.0.0.0`, but the port is then reachable from other apps on the device — a real local-privilege-escalation surface, since your Node server would be running shell/tooling as your app's UID.
- Pass port `0` and let the kernel allocate, then hand the resolved port to the WebView via your bridge. Hardcoded ports collide across concurrent app instances and with other apps.
- Handle `EADDRINUSE` by retrying with port `0`.

### 5.3 ⚠️ Cleartext HTTP to localhost is blocked by default — you need a Network Security Config

From [`<application>` → android:usesCleartextTraffic`](https://developer.android.com/guide/topics/manifest/application-element): *"Indicates whether the app intends to allow cleartext network traffic. **Apps that target API level 28 or higher default to `false`.**"*

`http://127.0.0.1:PORT` is cleartext. You must ship a [network security config](https://developer.android.com/privacy-and-security/security-config) scoped to loopback:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

Keep `base-config` at the default (`cleartextTrafficPermitted="false"`) so you don't weaken cleartext policy app-wide. ⚠️ **FLAGGED — the platform docs do not document a default loopback exemption**; I am inferring one is required from the `usesCleartextTraffic` default plus community reports of WebViews failing on cleartext localhost ([issuetracker #397619883](https://issuetracker.google.com/issues/397619883), [just_audio#984](https://github.com/ryanheise/just_audio/issues/984) which uses exactly this trick with a local proxy). **Verify on-device.**

### 5.4 Architectural alternative: don't spawn, embed

[nodejs-mobile-react-native](https://github.com/nodejs-mobile/nodejs-mobile-react-native) runs Node **in-process** as a linked `.so` — it sidesteps W^X, SELinux, and the Phantom Process Killer entirely. But it gives you **one** runtime, so "two concurrent agent CLIs" would mean two V8 isolates or worker threads in one process — a single RSS pool you cannot cap independently with `--max-old-space-size`, and one crash kills both agents.

For your requirement — *two independent CLIs, independently capped, independently restartable* — **separate `exec()`'d processes remain the right design.** Just accept the W^X packaging requirement (§0.1) and the Phantom Process Killer risk (§0.2).

---

## Summary of Flags

| # | Item | Status |
|---|---|---|
| 1 | No published benchmark of Claude Code / agent CLI on **Android arm64** | 🔴 Unverified — all data is Linux x86_64 / macOS arm64 |
| 2 | Node RSS figures in §1.1 measured on arm64 **macOS**, not Android | 🟡 Approximate anchor only |
| 3 | `exec()` children inherit the app's **cgroup** and count against the Memory Limiter budget | 🟡 Strong inference from "UID >= 10000" + `ignore <uid>` semantics; not stated explicitly for children |
| 4 | `exec()` children inherit `oom_score_adj` from the FGS (0–200) | 🟡 Linux kernel fork semantics; no Android doc statement found — verify with `/proc/<pid>/oom_score_adj` |
| 5 | Phantom Process Killer thresholds (>~32 procs, CPU heuristics) | 🟡 Community sources only; no primary `developer.android.com` statement located |
| 6 | Loopback cleartext requires an explicit Network Security Config | 🟡 Inferred; verify on-device |
| 7 | `specialUse` is Play-reviewable | 🟡 Known policy risk, not a technical blocker |
| 8 | Memory Limiter tier thresholds for 18 GB+ / <3.2 GB devices | ✅ Documented as "unconstrained" |