# jetbrains-plugins

My monorepo of JetBrains IDE plugins.

| Plugin | Does |
|---|---|
| [`registry-navigator`](plugins/registry-navigator) | Navigate TypeScript component registries with per-implementation overrides |

## Install

Add this repo as a plugin repository once, and every plugin here installs and updates
from inside the IDE - no zips, no rebuilding.

**1.** `Ctrl+Alt+S` → **Plugins**

**2.** **⚙ gear** (next to the search box) → **Manage Plugin Repositories…** → **`+`**

**3.** Paste this and hit **OK**:

```
https://raw.githubusercontent.com/asafjac/jetbrains-plugins/main/updatePlugins.xml
```

**4.** **Marketplace** tab → search **Registry Navigator** → **Install** → **Restart IDE**

New releases then show up as ordinary plugin updates. To pull one immediately, reopen
Manage Plugin Repositories and hit the refresh icon.

Requires **WebStorm** or **IDEA Ultimate** - the JS/TS PSI these plugins resolve against
isn't in the free IDEs.

<details>
<summary>Other ways to install</summary>

Grab the zip from [Releases](https://github.com/asafjac/jetbrains-plugins/releases/latest),
then **⚙ → Install Plugin from Disk…**

Or run a throwaway sandbox IDE with the plugin loaded and nothing installed:
`./gradlew runIde`
</details>

---

## Registry Navigator

**Problem.** You write `<FooRegistry.Bar />`. A class hierarchy picks the implementation.

- `Ctrl+B` → lands on the base *getter*. Not the component. Dead end.
- Find Usages on `AcmeBar` → one hit, inside its own registry. Dead end.

**Before the plugin**, reaching Acme's component from a call site takes four hops:

| # | Action | Lands on |
|---|---|---|
| 1 | `Ctrl+B` on `Baz` in `<FooRegistry.qux.Baz />` | `Baz` in the `QuxSlot` **type**. A type, not a component. |
| 2 | `Ctrl+B` again / go to `BaseFooRegistry.qux` | the base getter, returning the neutral `Baz` |
| 3 | `Ctrl+Alt+B` (Go to Implementations) on `qux` | a list of implementation getters; pick `AcmeFooRegistry` |
| 4 | `Ctrl+B` on `AcmeBaz` in the returned object | finally, the component |

Four hops, and step 3 is the one nobody remembers exists. The flat slot `FooRegistry.Bar`
is the same count: getter, implementations list, implementation getter, component.

**With the plugin: one `Ctrl+Click`.** A popup of every implementation's actual component, each row
labelled with the registry it came from:

```
AcmeBar    AcmeFooRegistry - AcmeBar.tsx
ZedBar     ZedFooRegistry - ZedBar.tsx
Bar        BaseFooRegistry - Bar.tsx
```

Implementations that *don't* override the slot don't appear - they inherit the base.

Stuck? **Tools → Registry Navigator: Diagnose at Caret** prints what the resolver saw.

Works on JSX tags (`<FooRegistry.qux.Baz />`) and plain expressions alike.

### What it keys off

- Registry class = name contains `Registry`
- Slot = a getter on one: `get Bar() { ... }`
- Component = a direct `return Identifier;` (followed through its import)

### Nested slots work too

Slots that return an object of components:

```ts
get qux(): QuxSlot {   // base
    return { Baz, Corge };
}
get qux() {            // override
    return { ...super.qux, Baz: AcmeBaz };
}
```

Ctrl+Click `Baz` in `FooRegistry.qux.Baz` resolves the whole path.
An override that only spreads `...super.qux` without redefining the key doesn't appear -
it has no answer of its own.

### Try it

[`demo/`](demo) is a dependency-free fixture using the pattern. Open it in a sandbox IDE
with the plugin loaded, nothing installed:

```bash
./gradlew runIde
```

Then Ctrl+Click the segments in [`demo/src/App.tsx`](demo/src/App.tsx). Expected counts:

| Click | Targets | Why |
|---|---|---|
| `FooRegistry` | 3 | all three registry classes |
| `Bar` | 3 | Acme and Zed both override the flat slot |
| `qux` | 2 | Zed never overrides the nested slot |
| `Baz` | 2 | base + Acme |
| `Corge` | 1 | Acme spreads `...super.qux` without redefining it |

The counts differing is the point. A tool that just listed every implementation would say 3 every
time.

### Doesn't work on

- Slots whose getter does anything other than return a component or an object literal -
  a conditional, an inline arrow, a `useMemo`.
- Registries not reached through a class hierarchy.

---

## Adding a plugin

1. `plugins/my-plugin/build.gradle.kts`:
   ```kotlin
   plugins { id("jbplugins.intellij-plugin") }
   version = "0.1.0"
   ```
2. Add `include("plugins:my-plugin")` to [settings.gradle.kts](settings.gradle.kts).
3. Write `src/main/resources/META-INF/plugin.xml` + your Kotlin.

That's it. Platform, JDK, compat range, and verification come from the shared
`jbplugins.intellij-plugin` convention in [buildSrc](buildSrc).

Need an IDE plugin dependency? Add it yourself:
```kotlin
dependencies { intellijPlatform { bundledPlugin("JavaScript") } }
```

## Layout

```
gradle/libs.versions.toml   versions, single source of truth
gradle.properties           platform, JDK, sinceBuild
buildSrc/                   the shared convention
plugins/<name>/             one plugin each
local.properties            your IDE path (gitignored)
```

## Build against your installed IDE

Faster than downloading a platform, and matches the IDE you actually run.
`local.properties`:
```
localIdePath=C:/Users/you/AppData/Local/Programs/WebStorm
```

Keep `pluginSinceBuild` at the major version you build against. Claiming older support is
an assertion nothing checks.

Gradle needs a JDK matching `javaToolchain`. It'll download one, or point at an existing
JBR in `~/.gradle/gradle.properties`:
```
org.gradle.java.installations.paths=C:/Users/you/AppData/Local/Programs/WebStorm/jbr
```
