package dev.sqeedys.lootbrush;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import static net.minecraft.commands.Commands.*;
import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static com.mojang.brigadier.arguments.IntegerArgumentType.*;

public final class LootBrush implements ModInitializer {
    public static LootBrush INSTANCE;
    static final String BINDING = "lootbrush_pool";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private Map<String, Pool> pools = Map.of();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private Path config;
    @Override public void onInitialize() {
        INSTANCE = this;
        config = FabricLoader.getInstance().getConfigDir().resolve("lootbrush.json");
        try { pools = readConfig(); } catch (Exception e) { throw new IllegalStateException("Invalid LootBrush config; original preserved", e); }
        CommandRegistrationCallback.EVENT.register((d, a, e) -> register(d));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!(player instanceof ServerPlayer staff) || hand != InteractionHand.MAIN_HAND || binding(staff.getMainHandItem()).isEmpty()) return InteractionResult.PASS;
            // Cancel every attack with a bound wand, including creative and rejected hits.
            if (level instanceof ServerLevel serverLevel) {
                try { paint(staff, serverLevel, pos); }
                catch (IllegalArgumentException ex) { staff.sendSystemMessage(Component.literal(ex.getMessage()), true); }
                catch (Exception ex) {
                    org.slf4j.LoggerFactory.getLogger("lootbrush").error("Loot generation failed; no insertion confirmed", ex);
                    staff.sendSystemMessage(Component.literal("Loot generation failed. See server log."), true);
                }
            }
            return InteractionResult.FAIL;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> binding(player.getMainHandItem()).isEmpty());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cooldown.remove(handler.player.getUUID()));
    }
    static String binding(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr(BINDING, ""); }
    static void bind(ItemStack stack, String pool) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> { if (pool.isEmpty()) tag.remove(BINDING); else tag.putString(BINDING, pool); });
    }
    static boolean allowed(CommandSourceStack source, String node) {
        var player = source.getPlayer();
        if (player != null && FabricLoader.getInstance().isModLoaded("luckperms")) {
            try { Boolean decision = LuckPermsBridge.decision(player, "lootbrush." + node); if (decision != null) return decision; }
            catch (LinkageError | RuntimeException ex) { return false; }
        }
        return (player == null ? source : player.createCommandSourceStack()).permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
    private static void require(CommandSourceStack source, String node) { if (!allowed(source, node)) throw new IllegalArgumentException("Permission denied: lootbrush." + node); }
    static Map<Identifier, LootTable> tables(MinecraftServer server) {
        var result = new TreeMap<Identifier, LootTable>();
        server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).listElements().forEach(h -> result.put(h.key().identifier(), h.value()));
        return result;
    }
    static boolean supported(LootTable table) {
        return Set.of(LootContextParams.ORIGIN, LootContextParams.THIS_ENTITY).containsAll(table.getParamSet().required());
    }
    private Pool pool(String id) { Pool p = pools.get(id); if (p == null) throw new IllegalArgumentException("Unknown pool: " + id); return p; }
    private static void validateTables(Pool pool, Map<Identifier, LootTable> loaded) {
        pool.tables().keySet().forEach(id -> {
            LootTable table = loaded.get(Identifier.parse(id));
            if (table == null) throw new IllegalArgumentException("Table is not loaded: " + id);
            if (!supported(table)) throw new IllegalArgumentException("Table needs unsupported context (use a chest-style table): " + id);
        });
    }
    record Target(Container inventory, List<RandomizableContainerBlockEntity> parts) {}
    static Target target(ServerLevel level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        if (!(entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity)) throw new IllegalArgumentException("Punch a chest or barrel.");
        var parts = new ArrayList<RandomizableContainerBlockEntity>(); parts.add((RandomizableContainerBlockEntity) entity);
        var state = level.getBlockState(pos);
        Container inventory = (Container) entity;
        if (state.getBlock() instanceof ChestBlock chest) {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                var neighborPos = ChestBlock.getConnectedBlockPos(pos, state);
                if (!level.hasChunkAt(neighborPos)) throw new IllegalArgumentException("Other chest half is not loaded.");
                var neighbor = level.getBlockEntity(neighborPos);
                if (!(neighbor instanceof ChestBlockEntity other)) throw new IllegalArgumentException("Incomplete double chest.");
                parts.add(other);
            }
            inventory = ChestBlock.getContainer(chest, state, level, pos, true);
            if (inventory == null || inventory.getContainerSize() != parts.size() * 27) throw new IllegalArgumentException("Incomplete chest.");
        }
        // getItem/isEmpty unpack pending loot, so inspect the keys FIRST on both halves.
        for (var part : parts) if (part.getLootTable() != null) throw new IllegalArgumentException("Container has pending vanilla loot; open it normally first.");
        if (!inventory.isEmpty()) throw new IllegalArgumentException("Container must be empty; contents preserved.");
        return new Target(inventory, List.copyOf(parts));
    }
    void paint(ServerPlayer player, ServerLevel level, BlockPos pos) {
        require(player.createCommandSourceStack(), "use");
        if (player.isSpectator() || !player.mayInteract(level, pos) || !player.isWithinBlockInteractionRange(pos, 1.0)) throw new IllegalArgumentException("Cannot interact here.");
        long tick = level.getServer().getTickCount();
        if (tick - cooldown.getOrDefault(player.getUUID(), -100L) < 8) return;
        // Rate-limit failed/empty rolls too; never reroll automatically until loot fits.
        cooldown.put(player.getUUID(), tick);
        var target = target(level, pos);
        Pool pool = pool(binding(player.getMainHandItem()));
        var loaded = tables(level.getServer()); validateTables(pool, loaded);
        String selected = pool.choose(level.getRandom()); LootTable table = loaded.get(Identifier.parse(selected));
        var builder = new LootParams.Builder(level);
        if (table.getParamSet().allowed().contains(LootContextParams.ORIGIN)) builder.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos));
        if (table.getParamSet().allowed().contains(LootContextParams.THIS_ENTITY)) builder.withParameter(LootContextParams.THIS_ENTITY, player);
        var rolled = table.getRandomItems(builder.create(table.getParamSet()));
        var plan = Scatter.plan(rolled, target.inventory(), level.getRandom());
        require(player.createCommandSourceStack(), "use");
        // Generation is synchronous on the server thread. Recheck preservation before commit.
        var checked = target(level, pos);
        if (!checked.parts().equals(target.parts())) throw new IllegalArgumentException("Container changed; no insertion.");
        for (int i = 0; i < plan.size(); i++) checked.inventory().setItem(i, plan.get(i));
        checked.inventory().setChanged(); checked.parts().forEach(BlockEntity::setChanged);
        player.sendSystemMessage(Component.literal("LootBrush: " + selected + (rolled.isEmpty() ? " (empty roll)" : " inserted")), true);
    }
    private Map<String, Pool> readConfig() throws Exception {
        if (!Files.exists(config)) { save(Map.of()); return Map.of(); }
        if (Files.size(config) > 262144) throw new IllegalArgumentException("Config exceeds 256 KiB.");
        var object = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!object.keySet().equals(Set.of("version", "pools")) || !object.get("version").isJsonPrimitive() || !object.getAsJsonPrimitive("version").isNumber() || !object.get("version").getAsString().equals("1")) throw new IllegalArgumentException("Expected version 1 and pools.");
        var result = new TreeMap<String, Pool>();
        for (var entry : object.getAsJsonObject("pools").entrySet()) {
            validName(entry.getKey()); var weights = new LinkedHashMap<String, Integer>();
            for (var weight : entry.getValue().getAsJsonObject().entrySet()) {
                if (!weight.getValue().isJsonPrimitive() || !weight.getValue().getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Weights must be JSON integers.");
                int value = new java.math.BigDecimal(weight.getValue().getAsString()).intValueExact(); weights.put(weight.getKey(), value);
            }
            result.put(entry.getKey(), new Pool(weights));
        }
        if (result.size() > 128) throw new IllegalArgumentException("Maximum 128 pools.");
        return Collections.unmodifiableMap(result);
    }
    private static void validName(String name) { if (!name.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Pool names: 1–32 lowercase letters, digits, _ or -."); }
    private void save(Map<String, Pool> replacement) throws Exception {
        var root = new JsonObject(); root.addProperty("version", 1); var object = new JsonObject(); root.add("pools", object);
        replacement.forEach((id, pool) -> object.add(id, JSON.toJsonTree(pool.tables())));
        String data = JSON.toJson(root) + "\n";
        if (data.getBytes(StandardCharsets.UTF_8).length > 262144) throw new IllegalArgumentException("Config exceeds 256 KiB.");
        Files.createDirectories(config.getParent()); Path temporary = Files.createTempFile(config.getParent(), "lootbrush-", ".tmp");
        try { Files.writeString(temporary, data, StandardCharsets.UTF_8); Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    private void replace(String name, Pool pool) throws Exception {
        validName(name); var next = new TreeMap<>(pools); if (pool == null) next.remove(name); else next.put(name, pool);
        if (next.size() > 128) throw new IllegalArgumentException("Maximum 128 pools."); save(next); pools = Collections.unmodifiableMap(next);
    }
    private static void tell(CommandSourceStack s, String text) { s.sendSuccess(() -> Component.literal(text), false); }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static int safe(CommandSourceStack s, String permission, Action action) {
        try { require(s, permission); action.run(); return 1; }
        catch (Exception e) { s.sendFailure(Component.literal(e.getMessage() == null ? "Operation failed." : e.getMessage())); return 0; }
    }
    private static LiteralArgumentBuilder<CommandSourceStack> command(String name, String permission) { return literal(name).requires(s -> allowed(s, permission)); }
    private void register(CommandDispatcher<CommandSourceStack> d) {
        var root = literal("lootbrush").requires(s -> allowed(s, "use") || allowed(s, "configure") || allowed(s, "list"));
        root.executes(c -> { tell(c.getSource(), "/lootbrush tables [page] | pools | show <pool> | set <pool> <table> <weight> | remove <pool> <table> | delete <pool> | bind <pool> | unbind | reload\nCreate a pool with set; weights are independent probabilities. Bind your held item, then punch an EMPTY chest/barrel. Bound wands never break blocks. Use /lootbrush unbind before mining."); return 1; });
        root.then(command("tables", "list").executes(c -> list(c.getSource(), 1)).then(argument("page", integer(1)).executes(c -> list(c.getSource(), getInteger(c, "page")))));
        root.then(command("pools", "list").executes(c -> safe(c.getSource(), "list", () -> tell(c.getSource(), pools.isEmpty() ? "No pools. /lootbrush set <pool> <table> <weight>" : String.join(", ", pools.keySet())))));
        root.then(command("show", "list").then(argument("pool", word()).suggests((c,b) -> allowed(c.getSource(), "configure") || allowed(c.getSource(), "list") ? SharedSuggestionProvider.suggest(pools.keySet(), b) : b.buildFuture()).executes(c -> safe(c.getSource(), "list", () -> {
            Pool p = pool(getString(c, "pool")); double total = p.tables().values().stream().mapToInt(Integer::intValue).sum();
            p.tables().forEach((id,w) -> tell(c.getSource(), id + " weight=" + w + " (" + String.format(Locale.ROOT, "%.2f", w*100/total) + "%)"));
        }))));
        root.then(command("set", "configure").then(argument("pool", word()).then(argument("table", net.minecraft.commands.arguments.IdentifierArgument.id()).suggests((c,b) -> allowed(c.getSource(), "configure") ? SharedSuggestionProvider.suggest(tables(c.getSource().getServer()).entrySet().stream().filter(e -> supported(e.getValue())).map(e -> e.getKey().toString()), b) : b.buildFuture()).then(argument("weight", integer(1, 1_000_000)).executes(c -> safe(c.getSource(), "configure", () -> {
            String name = getString(c, "pool"); var weights = new LinkedHashMap<>(pools.containsKey(name) ? pool(name).tables() : Map.<String,Integer>of());
            weights.put(net.minecraft.commands.arguments.IdentifierArgument.getId(c,"table").toString(), getInteger(c,"weight")); Pool next = new Pool(weights); validateTables(next,tables(c.getSource().getServer())); replace(name,next); tell(c.getSource(), "Saved pool " + name);
        }))))));
        root.then(command("remove", "configure").then(argument("pool", word()).then(argument("table", net.minecraft.commands.arguments.IdentifierArgument.id()).executes(c -> safe(c.getSource(), "configure", () -> {
            String name=getString(c,"pool"); var weights=new LinkedHashMap<>(pool(name).tables()); if(weights.remove(net.minecraft.commands.arguments.IdentifierArgument.getId(c,"table").toString())==null) throw new IllegalArgumentException("Table is not in pool."); replace(name,new Pool(weights)); tell(c.getSource(),"Saved pool " + name);
        })))));
        root.then(command("delete", "configure").then(argument("pool", word()).executes(c -> safe(c.getSource(), "configure", () -> { String name=getString(c,"pool"); pool(name); replace(name,null); tell(c.getSource(),"Deleted pool " + name + "; existing wands now refuse to paint."); }))));
        root.then(command("bind", "configure").then(argument("pool", word()).suggests((c,b) -> allowed(c.getSource(), "configure") || allowed(c.getSource(), "list") ? SharedSuggestionProvider.suggest(pools.keySet(), b) : b.buildFuture()).executes(c -> safe(c.getSource(), "configure", () -> {
            String name=getString(c,"pool"); validateTables(pool(name),tables(c.getSource().getServer())); var stack=c.getSource().getPlayerOrException().getMainHandItem(); if(stack.isEmpty())throw new IllegalArgumentException("Hold an item first."); bind(stack,name); tell(c.getSource(),"Held item bound to " + name);
        }))));
        root.then(command("unbind", "configure").executes(c -> safe(c.getSource(), "configure", () -> { bind(c.getSource().getPlayerOrException().getMainHandItem(),""); tell(c.getSource(),"Held item unbound."); })));
        root.then(command("reload", "configure").executes(c -> safe(c.getSource(), "configure", () -> { var next=readConfig(); var loaded=tables(c.getSource().getServer()); next.values().forEach(p -> validateTables(p,loaded)); pools=next; tell(c.getSource(),"Reloaded " + pools.size() + " pools."); })));
        d.register(root);
    }
    private static int list(CommandSourceStack s, int page) { return safe(s,"list", () -> {
        var entries=tables(s.getServer()).entrySet().stream().toList(); int pages=Math.max(1,(entries.size()+11)/12);
        if(page>pages)throw new IllegalArgumentException("Last page: " + pages); tell(s,"Loaded loot tables " + page + "/" + pages + " (* = supported context)");
        entries.stream().skip((long)(page-1)*12).limit(12).forEach(e -> tell(s,(supported(e.getValue())?"* ":"  ")+e.getKey()));
    }); }
}
