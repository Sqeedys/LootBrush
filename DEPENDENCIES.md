# Build and runtime dependencies

The release JAR contains only LootBrush classes, metadata, its logo, and its MIT license. It does not contain Minecraft, Fabric Loader, Fabric API, LuckPerms, or decompiled game source.

| Dependency | Pinned verification version | License / source |
| --- | --- | --- |
| Minecraft server | 26.3, DataVersion 5023, Java 25 | Proprietary; downloaded from Mojang's official server distribution for local compilation/testing |
| Fabric Loader | 0.20.1 | Apache-2.0; [Fabric source](https://github.com/FabricMC/fabric-loader) |
| Fabric API | 0.165.2+26.3 | Apache-2.0; [Fabric source](https://github.com/FabricMC/fabric) |
| LuckPerms Fabric | 5.5.85, optional | MIT; [LuckPerms source](https://github.com/LuckPerms/LuckPerms) |
| Sponge Mixin | 0.17.5+mixin.0.8.7 | MIT; [Mixin source](https://github.com/SpongePowered/Mixin) |
| ASM | 9.10.1 | BSD-3-Clause; [ASM license](https://asm.ow2.io/license.html) |

`build.py` pins download URLs and SHA-256 hashes (the official server bundle also has its Mojang SHA-1). The extracted unmodified game JAR must match SHA-256 `a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6`. Library contents are verified against the official bundler's manifests. Existing damaged caches fail closed and require explicit repair.

The standalone build/loopback-test scaffolding is adapted from Sqeedys' MIT-licensed LoreTools project. Logo artwork was generated specifically for LootBrush. No private server, world, or map material is included.
