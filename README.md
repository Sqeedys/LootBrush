<p align="center"><img src="assets/logo.png" width="160" alt="LootBrush chest and brush logo"></p>

# LootBrush

Paint real loot into empty chests and barrels with a weighted loot-table wand.

LootBrush is a server-only Fabric mod for map makers and server staff. Bind the item in your hand to a named pool, punch an empty container, and LootBrush rolls one table and inserts its generated items immediately. Players can open the container normally afterward. Clients do not need the mod.

**Minecraft 26.3 · Java 25 · Fabric Loader 0.20.1 · Fabric API 0.165.2+26.3**

## Quick start

Install the LootBrush JAR and Fabric API in the server's `mods` folder. Start the server, then run these commands as an operator or permitted staff member:

```mcfunction
/lootbrush set ruins minecraft:chests/simple_dungeon 40
/lootbrush set ruins minecraft:chests/abandoned_mineshaft 40
/lootbrush set ruins minecraft:chests/shipwreck_treasure 20
/lootbrush show ruins
/lootbrush bind ruins
```

Hold any nonempty item while binding it, then **left-click an empty chest or barrel**. A double chest is one 54-slot inventory. Loot scatters among its slots, with occasional modest stack splits. A bound wand cancels block breaking, including creative attacks; run `/lootbrush unbind` or switch items before mining.

The example weights mean 40%, 40%, and 20% probability **on each roll**. They do not promise exact quotas across a sequence of containers. Empty loot rolls remain empty and are reported as such.

## Commands

`/lootbrush` shows help. Table identifiers support namespaces and paths without quotation marks.

| Command | Purpose | Permission |
| --- | --- | --- |
| `tables [page]` | Browse all currently loaded tables, 12 per page; `*` marks supported context | `lootbrush.list` |
| `pools` | List saved pool names | `lootbrush.list` |
| `show <pool>` | Show weights and calculated probabilities | `lootbrush.list` |
| `set <pool> <table> <weight>` | Create a pool or add/update a table | `lootbrush.configure` |
| `remove <pool> <table>` | Remove a table; the final table cannot be removed | `lootbrush.configure` |
| `delete <pool>` | Delete a pool; its existing wands refuse further painting | `lootbrush.configure` |
| `bind <pool>` | Bind the held item, preserving its name, lore, and other custom data | `lootbrush.configure` |
| `unbind` | Remove only LootBrush's held-item binding | `lootbrush.configure` |
| `reload` | Validate/reload `config/lootbrush.json` | `lootbrush.configure` |
| Punch with a wand | Generate and insert one loot roll | `lootbrush.use` |

Pool names allow 1–32 lowercase letters, digits, underscores, and hyphens. Each pool supports 1–64 tables with integer weights from 1 to 1,000,000. Up to 128 pools and a 256 KiB configuration are supported. Binding affects the held stack; splitting/copying that stack also copies its binding. Permissions are always checked again when used.

## Permissions

Without LuckPerms, permission level 2 and above can use all commands and wands. Console has command access at the same level, although binding requires a player.

LuckPerms is optional. An explicit `true` grants a node, an explicit `false` denies it even to an operator, and an undefined node falls back to the level-2 operator default. If LuckPerms is loaded but its API or user data is unavailable, player access is denied. For example:

```mcfunction
/lp group builders permission set lootbrush.use true
/lp group builders permission set lootbrush.list true
/lp group builders permission set lootbrush.configure true
```

## Preservation and supported tables

LootBrush fills **empty containers only**. It refuses occupied containers and containers with a pending vanilla loot-table key, checking both halves of a double chest before reading inventory contents. Open pending-loot containers normally, then empty them before painting. There is no overwrite or append mode.

One table is chosen and generated once per accepted hit. The entire result must fit before any inventory slots change. Overflow is refused without partial insertion or dropped items. Bound attacks are canceled even when permissions, context, capacity, or configuration checks fail. A per-player eight-tick cooldown suppresses rapid repeats, including failed and empty rolls. Ordinary block protection and reach checks apply; third-party region protection integration is not included.

All loaded vanilla and datapack tables appear in the list. Painting supports tables whose required context can be supplied from the container position and staff player. Tables requiring block drops, victims, damage sources, fishing tools, or other unavailable context are rejected. Optional context-specific conditions may still yield no items. Native loot generation preserves item components, enchantments, and other table functions. Loot generation can have table-defined side effects; the inventory fit check does not undo those effects.

Server datapack `/reload` changes which tables are available. LootBrush reads current registries when listing, binding, editing pools, and painting; stale/missing tables cause clear refusal. `/lootbrush reload` reloads only this mod's configuration. Configuration replacement is atomic; an invalid reload keeps the active configuration.

## Build and verification

Python 3 and a JDK 25 installation are required:

```shell
python build.py --java-home <JDK-25-directory> --prepare-runtime
```

This verifies pinned dependency hashes, downloads the official 26.3 server into ignored local storage, compiles the source, and writes `build/libs/lootbrush-0.1.0.jar`. It does not start a server or accept Minecraft's EULA. Dependencies and game classes are not bundled. See [native test instructions and results](docs/TESTING.md) and [dependency notes](DEPENDENCIES.md).

## Credits and license

Created by **Sqeedys** · [YouTube](https://www.youtube.com/@Sqeedys).

Code: [MIT](LICENSE). The chest-and-brush logo was generated for this project. Minecraft is a trademark of Mojang/Microsoft; this project is independent and unofficial.
