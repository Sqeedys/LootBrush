package dev.sqeedys.lootbrush;
import net.minecraft.server.level.ServerPlayer;
final class PermissionChecks {
    static void load(ServerPlayer player) {net.luckperms.api.LuckPermsProvider.get().getUserManager().loadUser(player.getUUID()).join();}
    static void run(ServerPlayer player) {
        var api=net.luckperms.api.LuckPermsProvider.get();var user=api.getUserManager().loadUser(player.getUUID()).join();
        var deny=net.luckperms.api.node.Node.builder("lootbrush.use").value(false).build();user.data().add(deny);
        Integration.check(!LootBrush.allowed(player.createCommandSourceStack(),"use"),"LP explicit deny");user.data().remove(deny);Integration.check(LootBrush.allowed(player.createCommandSourceStack(),"use"),"LP undefined fallback");
        player.level().getServer().getPlayerList().deop(player.nameAndId());
        var grant=net.luckperms.api.node.Node.builder("lootbrush.use").value(true).build();user.data().add(grant);
        Integration.check(LootBrush.allowed(player.createCommandSourceStack(),"use"),"LP nonoperator grant");user.data().remove(grant);Integration.check(!LootBrush.allowed(player.createCommandSourceStack(),"use"),"LP revocation");player.level().getServer().getPlayerList().op(player.nameAndId());
    }
}
