# Verification

## Reproduce the native checks

Use Python 3 and JDK 25. The build verifies exact dependency hashes; all downloaded dependencies, worlds, credentials, receipts, and logs remain under ignored `local/` storage. The test harness is a separate JAR and is never included in the production mod.

```shell
python build.py --java-home <JDK-25-directory> --prepare-runtime --integration
python tests/server.py --server-dir local/native-check prepare --accept-eula --port 25676 --rcon-port 25686
python tests/datapack.py local/native-check
python tests/server.py --server-dir local/native-check run --java-home <JDK-25-directory> --runtime local/deps/runtime-26.3 --integration-phase initial
python tests/server.py --server-dir local/native-check run --java-home <JDK-25-directory> --runtime local/deps/runtime-26.3 --integration-phase restart
```

`prepare` requires a new/empty directory and explicit Minecraft EULA acceptance. It creates a marked, disposable flat world fixture bound to loopback with randomly generated RCON credentials. It refuses existing unmarked fixtures, settings changes, or occupied ports. `datapack.py` must run before the first server start. Each successful test writes a fresh receipt, records both JAR hashes, and stops the server cleanly.

For optional LuckPerms, prepare a separate fixture with different ports, install its test datapack, and use the same run commands with `--with-luckperms`. The harness loads the synthetic user's LuckPerms data explicitly; a real player's normal login performs this load. Only when LuckPerms is installed does its additional permissions check run. It checks explicit deny overriding operator status, undefined fallback, a nonoperator grant, and revocation.

The fixture uses the actual Minecraft 26.3 loot-table codec: item modifiers use `"modifier": {"type": "minecraft:set_count", "count": 32}`. Older `functions` syntax is silently ignored by this game version and is unsuitable for count assertions.

## Recorded results

Verified on 2026-11-19 with Minecraft 26.3 (DataVersion 5023), Java 25.0.5, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, and optional LuckPerms Fabric 5.5.85.

The production JAR tested has SHA-256:

```text
ca4e9472071d15d8e1973baf540ef2014aac1a2cf5a038dc7bd40773746cb86a
```

Native checks without LuckPerms passed 19 initial checks and 2 restart checks. They cover:

- Actual command execution with namespaced identifiers, loaded vanilla/custom tables, weights, pool persistence, and invalid configuration reload preserving active pools.
- Encoding/decoding the native command packet and reconstructing its command tree, including after permission revocation.
- The actual creative `START_DESTROY_BLOCK` route through Fabric's attack callback: 32 generated bread inserted, chest intact, same-tick repeat suppressed.
- Nonempty-container refusal, pending vanilla loot preserved on either double-chest half, and overflow refused before any insertion.
- Double-chest identity and 54 slots, including 28 unstackable swords successfully spread across both halves.
- Barrel insertion, missing-pool refusal, operator revocation, and permission-gated suggestions.
- A native datapack reload that removes a configured table, followed by clear refusal and an unchanged container.
- Scatter count/component preservation across 100 seeds, modest splitting, and weighted frequencies across 100,000 independent draws.
- Held-item binding/unbinding preserving unrelated custom data and names.
- Clean shutdown/restart preserving saved pool configuration and actual chest loot.

The separate LuckPerms fixture passed 20 initial checks and 2 restart checks, including its additional deny/fallback/nonoperator-grant/revocation test. LuckPerms command examples in the README were not executed by this harness; these permission semantics were exercised through its native API. Runtime logs and user records are deliberately excluded from source control.

## Implementation evidence and limits

The exact 26.3 native `LootTable.fill` implementation generates, shuffles, and splits stacks but returns early when the inventory is overfilled, discarding the remainder of its generated list. LootBrush therefore uses native `getRandomItems` once, followed by a complete off-inventory placement plan. It scatters slots and optionally splits each legal generated stack once, keeping split pieces at least two items each. If all generated stacks cannot fit, insertion is refused. Separate generated stacks are not merged merely to force a roll to fit.

Fabric's verified `ServerPlayerGameModeMixin` injects the attack event at the head of `handleBlockBreakAction` for start attacks, with native reach/world-bound checks. A non-PASS result sends a block update and cancels the action. LootBrush additionally guards the block-break event while a bound item is held.

These are native server tests with a synthetic player and dummy network connection. They are not graphical client playtesting or a claim of tested compatibility with every protection mod, datapack, newer dependency version, or multiplayer modpack. No real client login, visual scatter review, or multiple simultaneous remote players was tested. Empty rolls are supported by the implementation; the recorded deterministic insertion fixtures exercise nonempty and overflow rolls. Loot generation side effects from tables/mods are outside the inventory transaction.
