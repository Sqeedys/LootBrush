package dev.sqeedys.lootbrush;

import java.util.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.RandomSource;

/** Plans entirely off-inventory; never truncates a loot roll. */
final class Scatter {
    static List<ItemStack> plan(List<ItemStack> rolled, Container target, RandomSource random) {
        int slots = target.getContainerSize();
        var pieces = new ArrayList<ItemStack>();
        for (var stack : rolled) {
            if (stack.isEmpty()) continue;
            int maximum = Math.min(stack.getMaxStackSize(), target.getMaxStackSize(stack));
            if (maximum < 1) throw new IllegalArgumentException("Unsupported stack limit");
            int remaining = stack.getCount();
            while (remaining > 0) {
                if (pieces.size() >= slots) throw new IllegalArgumentException("Rolled loot does not fit; container unchanged.");
                int count = Math.min(remaining, maximum);
                pieces.add(stack.copyWithCount(count)); remaining -= count;
            }
        }
        // At most one extra split per generated legal stack, and only stacks >= 4.
        // This keeps small stacks intact and avoids turning everything into singles.
        int originals = pieces.size();
        for (int i = 0; i < originals && pieces.size() < slots; i++) {
            var stack = pieces.get(i);
            if (stack.getCount() >= 4 && random.nextBoolean()) {
                int part = 2 + random.nextInt(stack.getCount() - 3);
                pieces.add(stack.copyWithCount(part)); stack.shrink(part);
            }
        }
        var result = new ArrayList<ItemStack>(Collections.nCopies(slots, ItemStack.EMPTY));
        var order = new ArrayList<Integer>(); for (int i = 0; i < slots; i++) order.add(i);
        for (int i = slots - 1; i > 0; i--) Collections.swap(order, i, random.nextInt(i + 1));
        for (int i = 0; i < pieces.size(); i++) result.set(order.get(i), pieces.get(i));
        return result;
    }
}
