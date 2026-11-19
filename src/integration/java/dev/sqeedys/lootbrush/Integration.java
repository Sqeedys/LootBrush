package dev.sqeedys.lootbrush;

import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.util.RandomSource;

/** Native disposable-world checks; absent from the production artifact. */
public final class Integration implements ModInitializer {
    private final ArrayDeque<Runnable> checks = new ArrayDeque<>();
    private final List<String> passed = new ArrayList<>();
    private ServerPlayer player; private MinecraftServer server; private LootBrush mod;
    private final BlockPos pos = new BlockPos(0, -60, 0);
    private boolean done; private int ticks;
    private java.util.concurrent.CompletableFuture<Void> pendingReload;
    private final String phase = System.getProperty("lootbrush.integration.phase", "initial");
    static void check(boolean good, String message) { if (!good) throw new AssertionError(message); }
    private void command(String command) { try { check(server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack()) == 1,command); } catch(Exception e) {throw new RuntimeException(e);} }
    private void step(String name, Runnable test) { checks.add(() -> { test.run(); passed.add(name); }); }
    @Override public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if(done || ++ticks < 20 || ticks % 10 != 0) return;
            server=s;
            try {
                if(pendingReload!=null) {if(!pendingReload.isDone())return;pendingReload.join();pendingReload=null;}
                if(player==null) { setup(); return; }
                if(checks.isEmpty()) finish(null); else checks.remove().run();
            } catch(Throwable e) { finish(e); }
        });
    }
    private void setup() {
        mod=LootBrush.INSTANCE; var profile=new GameProfile(UUID.fromString("00000000-0000-4000-8000-000000000263"),"BrushFixture");
        player=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault()) { @Override public boolean hasDisconnected(){return false;} @Override public void sendSystemMessage(net.minecraft.network.chat.Component c,boolean overlay){System.out.println("FIXTURE MESSAGE: "+c.getString());} @Override public void sendSystemMessage(net.minecraft.network.chat.Component c){System.out.println("FIXTURE MESSAGE: "+c.getString());} };
        var connection=new Connection(PacketFlow.SERVERBOUND) { @Override public void send(Packet<?> p) {} };
        player.connection=new ServerGamePacketListenerImpl(server,connection,player,CommonListenerCookie.createInitial(profile,false)) { @Override public void send(Packet<?> p) {
            if(p instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat) System.out.println("FIXTURE CHAT: "+chat.content().getString());
            if(p instanceof net.minecraft.network.protocol.game.ClientboundCommandsPacket commands) {
                var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {net.minecraft.network.protocol.game.ClientboundCommandsPacket.STREAM_CODEC.encode(buffer,commands);var decoded=net.minecraft.network.protocol.game.ClientboundCommandsPacket.STREAM_CODEC.decode(buffer); decoded.getRoot(net.minecraft.commands.Commands.createValidationContext(server.registryAccess()), new net.minecraft.network.protocol.game.ClientboundCommandsPacket.NodeBuilder<Object>() { public com.mojang.brigadier.builder.ArgumentBuilder<Object,?> createLiteral(String name){return com.mojang.brigadier.builder.LiteralArgumentBuilder.literal(name);} public com.mojang.brigadier.builder.ArgumentBuilder<Object,?> createArgument(String name,com.mojang.brigadier.arguments.ArgumentType<?> type,Identifier suggestion){return com.mojang.brigadier.builder.RequiredArgumentBuilder.argument(name,type);} public com.mojang.brigadier.builder.ArgumentBuilder<Object,?> configure(com.mojang.brigadier.builder.ArgumentBuilder<Object,?> builder,boolean executable,boolean restricted){return executable?builder.executes(c->0):builder;} });check(!buffer.isReadable(),"command packet roundtrip");}finally{buffer.release();}
            }
        } };
        server.getPlayerList().op(player.nameAndId()); player.setPos(0.5,-59,2.5); player.setGameMode(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STICK));
        if(FabricLoader.getInstance().isModLoaded("luckperms")) PermissionChecks.load(player);
        step("native command packet roundtrip",() -> server.getCommands().sendCommands(player));
        if(phase.equals("restart")) {
            step("persisted pools and inventory survive restart",() -> {command("lootbrush show fixture"); var chest=(ChestBlockEntity)server.overworld().getBlockEntity(pos); check(chest!=null&&!chest.isEmpty(),"saved loot");});
            return;
        }
        step("native codec loaded fixture and vanilla chest tables",() -> {
            var loaded=LootBrush.tables(server); check(loaded.containsKey(Identifier.parse("lootbrush_test:normal")),"fixture codec"); check(loaded.containsKey(Identifier.parse("minecraft:chests/simple_dungeon")),"vanilla tables");
            command("lootbrush tables");command("lootbrush set fixture lootbrush_test:normal 40");command("lootbrush set fixture minecraft:chests/simple_dungeon 40");command("lootbrush set fixture minecraft:chests/abandoned_mineshaft 20");command("lootbrush show fixture");
            command("lootbrush set single lootbrush_test:normal 1"); command("lootbrush set overflow lootbrush_test:overflow 1"); command("lootbrush set multi lootbrush_test:multi 1");
            try { check(server.getCommands().getDispatcher().execute("lootbrush bind single",player.createCommandSourceStack())==1,"bind command"); } catch(Exception e){throw new RuntimeException(e);}
        });
        step("scatter conserves counts and item components",() -> {
            var bread=new ItemStack(Items.BREAD,32); bread.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Fixture"));
            for(int seed=0;seed<100;seed++) { var plan=Scatter.plan(List.of(bread),new SimpleContainer(27),RandomSource.create(seed)); check(plan.stream().mapToInt(ItemStack::getCount).sum()==32,"count conservation");check(plan.stream().filter(x->!x.isEmpty()).count()<=2,"modest splits");check(plan.stream().filter(x->!x.isEmpty()).allMatch(x->ItemStack.isSameItemSameComponents(x,bread)),"components"); }
        });
        step("binding preserves unrelated held-item components",() -> {
            var stack=player.getMainHandItem();stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Fixture wand"));
            net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,stack,t->t.putString("unrelated","kept"));
            LootBrush.bind(stack,"");check(LootBrush.binding(stack).isEmpty(),"unbind");check(stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString().equals("Fixture wand"),"custom name");check(stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().getStringOr("unrelated","").equals("kept"),"unrelated custom data");LootBrush.bind(stack,"single");
        });
        step("weighted pool validation and independent sampling",() -> {
            var p=new Pool(Map.of("minecraft:a",40,"minecraft:b",40,"minecraft:c",20)); int[] counts=new int[3];var r=RandomSource.create(12);
            for(int i=0;i<100000;i++)counts[p.choose(r).charAt(10)-'a']++;
            check(Math.abs(counts[0]-40000)<1000&&Math.abs(counts[1]-40000)<1000&&Math.abs(counts[2]-20000)<1000,"weighted frequencies");
            try{new Pool(Map.of("minecraft:a",0));throw new AssertionError("zero accepted");}catch(IllegalArgumentException expected){}
        });
        step("creative native attack inserts real loot without breaking",() -> {
            chest(); check(player.isWithinBlockInteractionRange(pos,1.0),"fixture in range");check(server.overworld().isInWorldBounds(pos),"fixture in bounds");check(!LootBrush.binding(player.getMainHandItem()).isEmpty(),"fixture bound");player.gameMode.handleBlockBreakAction(pos,ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,Direction.UP,320,1);
            var chest=(ChestBlockEntity)server.overworld().getBlockEntity(pos); check(chest!=null,"creative break cancelled"); check(total(chest)==32,"native generated bread count="+total(chest));
            var before=total(chest); hit(); check(total(chest)==before,"same tick repetition");
        });
        step("nonempty contents preserved",() -> {hit();check(total((Container)server.overworld().getBlockEntity(pos))==32,"existing loot preserved");});
        step("pending vanilla loot untouched",() -> {
            chest();var chest=(ChestBlockEntity)server.overworld().getBlockEntity(pos);var key=ResourceKey.create(Registries.LOOT_TABLE,Identifier.parse("minecraft:chests/simple_dungeon"));chest.setLootTable(key); hit();check(key.equals(chest.getLootTable()),"pending key untouched");
        });
        step("overflow rejected without truncation",() -> {chest(); LootBrush.bind(player.getMainHandItem(),"overflow");hit();check(((Container)server.overworld().getBlockEntity(pos)).isEmpty(),"overflow atomic refusal");});
        step("double chest covers both halves and 54 slots",() -> {
            var level=server.overworld(); level.setBlock(pos,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT),3);
            var other=ChestBlock.getConnectedBlockPos(pos,level.getBlockState(pos)); level.setBlock(other,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.RIGHT),3);
            check(LootBrush.target(level,pos).inventory().getContainerSize()==54,"54 slots");
            ((ChestBlockEntity)level.getBlockEntity(other)).setItem(0,new ItemStack(Items.DIAMOND));LootBrush.bind(player.getMainHandItem(),"single");hit();check(total((Container)level.getBlockEntity(pos))==0,"opposite-half occupancy refusal");
            ((ChestBlockEntity)level.getBlockEntity(other)).clearContent();hit(); // cooldown prevents second attempt this tick
            level.removeBlock(other,false);chest();
        });
        step("opposite double-half pending loot preserved",() -> {
            var other=doubleChest(); var chest=(ChestBlockEntity)server.overworld().getBlockEntity(other);var key=ResourceKey.create(Registries.LOOT_TABLE,Identifier.parse("minecraft:chests/simple_dungeon"));chest.setLootTable(key);hit();check(key.equals(chest.getLootTable()),"opposite pending key");check(((ChestBlockEntity)server.overworld().getBlockEntity(pos)).isEmpty(),"first half untouched");
        });
        step("successful double chest uses both halves",() -> {
            doubleChest();LootBrush.bind(player.getMainHandItem(),"multi");hit(); var combined=ChestBlock.getContainer((ChestBlock)server.overworld().getBlockState(pos).getBlock(),server.overworld().getBlockState(pos),server.overworld(),pos,true);check(total(combined)==28,"28 unstackables fit54");check(total((Container)server.overworld().getBlockEntity(pos))>0,"first half used");check(total((Container)server.overworld().getBlockEntity(ChestBlock.getConnectedBlockPos(pos,server.overworld().getBlockState(pos))))>0,"second half used");LootBrush.bind(player.getMainHandItem(),"single");
            server.overworld().removeBlock(ChestBlock.getConnectedBlockPos(pos,server.overworld().getBlockState(pos)),false);chest();
        });
        step("barrel insertion",() -> {server.overworld().setBlock(pos,Blocks.BARREL.defaultBlockState(),3);hit();check(total((Container)server.overworld().getBlockEntity(pos))==32,"barrel loot");});
        step("permissions rechecked and denied wand still cannot break",() -> {
            chest();server.getPlayerList().deop(player.nameAndId());player.gameMode.handleBlockBreakAction(pos,ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,Direction.UP,320,2);
            check(server.overworld().getBlockEntity(pos) instanceof ChestBlockEntity,"denied creative attack cancelled");check(((Container)server.overworld().getBlockEntity(pos)).isEmpty(),"denied insertion");
            var dispatcher=server.getCommands().getDispatcher();var suggestions=dispatcher.getCompletionSuggestions(dispatcher.parse("lootbrush bind ",player.createCommandSourceStack())).join();check(suggestions.getList().stream().noneMatch(x->x.getText().equals("single")),"revoked suggestions hidden");server.getCommands().sendCommands(player);server.getPlayerList().op(player.nameAndId());
        });
        if(FabricLoader.getInstance().isModLoaded("luckperms")) step("LuckPerms explicit deny overrides op; undefined falls back",() -> {
            PermissionChecks.run(player);
        });
        step("missing pool refuses and final loot persists",() -> {LootBrush.bind(player.getMainHandItem(),"missing_pool");hit();check(((Container)server.overworld().getBlockEntity(pos)).isEmpty(),"missing pool refusal");LootBrush.bind(player.getMainHandItem(),"single");});
        step("start native datapack reload after removing a configured table",() -> {
            command("lootbrush set transient lootbrush_test:transient 1");
            try{Files.delete(FabricLoader.getInstance().getGameDir().resolve("lootbrush-fixture-v1/datapacks/lootbrush-test/data/lootbrush_test/loot_table/transient.json"));}catch(Exception e){throw new RuntimeException(e);}
            pendingReload=server.reloadResources(server.getPackRepository().getSelectedIds());
        });
        step("removed table refuses after native reload",() -> {
            check(!LootBrush.tables(server).containsKey(Identifier.parse("lootbrush_test:transient")),"removed registry entry");LootBrush.bind(player.getMainHandItem(),"transient");hit();check(((Container)server.overworld().getBlockEntity(pos)).isEmpty(),"removed table preservation");command("lootbrush delete transient");LootBrush.bind(player.getMainHandItem(),"single");
        });
        step("invalid configuration reload keeps active pools",() -> {
            Path config=FabricLoader.getInstance().getConfigDir().resolve("lootbrush.json");
            try {String original=Files.readString(config);try{Files.writeString(config,"{\"version\":1,\"pools\":{\"bad\":{\"minecraft:empty\":0}}}");check(server.getCommands().getDispatcher().execute("lootbrush reload",server.createCommandSourceStack())==0,"bad reload refused");command("lootbrush show single");}finally{Files.writeString(config,original);}}catch(Exception e){throw new RuntimeException(e);}
        });
        step("final saved chest",() -> {hit();check(total((Container)server.overworld().getBlockEntity(pos))==32,"final loot");});
    }
    private BlockPos doubleChest(){
        chest(); var level=server.overworld();level.setBlock(pos,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT),3);var other=ChestBlock.getConnectedBlockPos(pos,level.getBlockState(pos));level.removeBlock(other,false);level.setBlock(other,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.RIGHT),3);return other;
    }
    private void chest(){server.overworld().removeBlock(pos,false);server.overworld().setBlock(pos,Blocks.CHEST.defaultBlockState(),3);}
    private void hit(){check(AttackBlockCallback.EVENT.invoker().interact(player,server.overworld(),InteractionHand.MAIN_HAND,pos,Direction.UP)==InteractionResult.FAIL,"bound attack cancellation");}
    private static int total(Container c){int total=0;for(int i=0;i<c.getContainerSize();i++)total+=c.getItem(i).getCount();return total;}
    private void finish(Throwable failure) {
        done=true;try {
            Path output=FabricLoader.getInstance().getGameDir().resolve("local/lootbrush-integration-"+phase+".txt");Files.createDirectories(output.getParent());
            if(failure!=null){failure.printStackTrace();Files.writeString(output,"FAIL "+failure+"\n"+String.join("\n",passed));}
            else Files.writeString(output,"PASS phase="+phase+" checks="+passed.size()+"\n"+String.join("\n",passed));
        }catch(Exception e){throw new RuntimeException(e);}finally{server.halt(false);}
    }
}
