package dev.sqeedys.lootbrush;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;

final class LuckPermsBridge {
    static Boolean decision(ServerPlayer player, String node) {
        var api = LuckPermsProvider.get();
        if (api.getUserManager().getUser(player.getUUID()) == null) return false;
        var value = api.getPlayerAdapter(ServerPlayer.class).getPermissionData(player).checkPermission(node);
        return value == Tristate.UNDEFINED ? null : value == Tristate.TRUE;
    }
}
