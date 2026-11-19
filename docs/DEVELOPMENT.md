# For developers: building from source

These instructions are for people who want to modify or compile LootBrush. To install the released mod, follow the [normal installation instructions](../README.md#installation). Server owners installing the released JAR do not need Python or a development kit.

## Requirements

- Python 3.
- A JDK 25 installation, including the Java compiler.
- A downloaded or cloned copy of this repository.

## Build

Open a terminal in the repository's root directory. Replace `<JDK-25-directory>` with the path to your JDK 25 installation, keeping the quotation marks if the path contains spaces:

```shell
python build.py --java-home "<JDK-25-directory>" --prepare-runtime
```

The build verifies pinned dependency hashes, downloads the official Minecraft 26.3 server into ignored local storage, compiles the source, and writes **`build/libs/lootbrush-0.1.0.jar`**. Minecraft and dependency classes are not bundled into the mod.

This command does not start a server or accept Minecraft's EULA.

## Testing and dependencies

See [native test instructions and recorded results](TESTING.md) and [dependency notes](../DEPENDENCIES.md). The integration-test JAR is only for disposable test servers; it is not part of normal installation.
