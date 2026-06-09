# HunterCore Notes

HunterCore is a private DivineMC-based server core with a bundled plugin layer and a small extension API.

## Build

Use the same Paperweight flow as DivineMC:

```bash
GIT_CONFIG_COUNT=1 \
GIT_CONFIG_KEY_0=url.git@github.com:.insteadOf \
GIT_CONFIG_VALUE_0=https://github.com/ \
./gradlew applyAllPatches createPaperclipJar --no-daemon
```

The final runnable jar is generated at:

```text
divinemc-server/build/libs/divinemc-paperclip-26.1.2.local-SNAPSHOT.jar
```

The `Build HunterCore` GitHub Actions workflow runs on `huntercore/**` branches and uploads the generated jars as workflow artifacts.

## Bundled Plugins

HunterCore installs bundled plugins before Paper scans the plugin directory. On first startup it writes:

```text
plugins/HunterCore/bundled-plugins.yml
```

That file can disable the entire installer, disable individual bundled plugins, or stop automatic replacement of changed bundled jars.

Current first batch:

```text
ViaVersion 5.9.1
Multiverse-Core 5.7.0
LuckPerms 5.5.55
CoreProtect 23.2
HunterTPA builtin
HunterAuth builtin
```

External plugins are prepared by:

```bash
scripts/prepare-bundled-plugins.sh
```

To add another external bundled plugin, extend that script with a download/build step and call `manifest_entry`. To add another built-in plugin, add a subproject under `huntercore-plugins/`, copy its jar into `META-INF/huntercore/bundled-plugins` from `build.gradle.kts`, and add a resource entry to `divinemc-server/src/main/resources/META-INF/huntercore/bundled-plugins.yml`.

## Commands

```text
/about
/huntercore
/huntercore system
/huntercore plugins
/huntercore reload
/hc
```

`/about` is HunterCore-specific. `/huntercore system` prints JVM, OS, CPU, memory, uptime, player count, and plugin directory information.

## API

The API entrypoint is:

```java
org.huntercore.api.HunterCoreProvider.get()
```

Plugins can register future `/huntercore` subcommands with:

```java
HunterCoreProvider.get().registerCommandExtension(extension);
```

The extension interface is `org.huntercore.api.HunterCommandExtension`.
