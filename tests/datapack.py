"""Install deterministic loot tables into a NEW, marked, stopped test fixture."""
import argparse
import json
from pathlib import Path
import server

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
args = parser.parse_args()
directory = args.directory.resolve()
marker = server.read_marker(directory)
server.require_stopped(marker)
pack = directory / "lootbrush-fixture-v1/datapacks/lootbrush-test"
if (directory / "lootbrush-fixture-v1/level.dat").exists():
    raise ValueError("Install test datapack before the first start")
pack.mkdir(parents=True, exist_ok=True)
(pack / "pack.mcmeta").write_text(json.dumps({"pack": {"min_format": [121, 0], "max_format": [121, 0], "description": "Disposable LootBrush codec fixture"}}), encoding="utf-8")
for name, count in (("normal", 32), ("overflow", 2048)):
    path = pack / f"data/lootbrush_test/loot_table/{name}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minecraft:bread", "modifier": {"type": "minecraft:set_count", "count": count}}]}]}), encoding="utf-8")
print("Installed native loot-table codec fixture")

path = pack / "data/lootbrush_test/loot_table/multi.json"
path.write_text(json.dumps({"type": "minecraft:chest", "pools": [{"rolls": 28, "entries": [{"type": "minecraft:item", "name": "minecraft:diamond_sword"}]}]}), encoding="utf-8")

(pack / "data/lootbrush_test/loot_table/transient.json").write_text((pack / "data/lootbrush_test/loot_table/normal.json").read_text(encoding="utf-8"), encoding="utf-8")
