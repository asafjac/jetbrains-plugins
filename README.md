# jetbrains-plugins

My JetBrains IDE plugins.

## Install

Add this URL under **Settings > Plugins > gear icon > Manage Plugin Repositories**, then install any plugin below from the Marketplace tab; they self-update from then on.

```
https://raw.githubusercontent.com/asafjac/jetbrains-plugins/main/updatePlugins.xml
```

Requires WebStorm or IDEA Ultimate.

## Featured plugins

### Demo Driver

Records IDE demos from a script, so a demo is re-recorded on demand instead of performed by hand.

![Shot list: record, tidy, replay](docs/demo-driver.gif)

Seven ways to frame the shot. Every control writes one line of tape, so anything you can click, a script or an agent can write.

![Framing: seven crop modes, each compiling to tape](docs/framing.gif)

### Registry Navigator

In a codebase where `<FooRegistry.qux.Baz />` is resolved by a class hierarchy, Go to Declaration lands on the base getter and stops; this jumps straight to every implementation's real component.

<table>
<tr>
<th align="left">Before: four hops</th>
<th align="left">After: one click</th>
</tr>
<tr>
<td width="50%"><img src="docs/without-plugin.gif" alt="Four separate jumps across four files"></td>
<td width="50%"><img src="docs/with-plugin.gif" alt="One click listing every implementation"></td>
</tr>
</table>

## Repo

```
plugins/<name>/    one plugin each
buildSrc/          shared build conventions
demo/              dependency-free fixture, plus tapes in demo/shots/
docs/              recordings
```

Add a plugin: create `plugins/my-plugin/build.gradle.kts` with `plugins { id("jbplugins.intellij-plugin") }` and a version, then `include("plugins:my-plugin")` in [settings.gradle.kts](settings.gradle.kts).

Build with `./gradlew buildPlugin`. Try it against the fixture in a sandbox IDE with `./gradlew runIde`.
