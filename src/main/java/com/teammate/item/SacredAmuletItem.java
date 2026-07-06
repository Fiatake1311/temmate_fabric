package com.teammate.item;

import com.teammate.team.TeamManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * ผ้ายันต์ — the Sacred Cloth Amulet. Right-clicking asks the server for a fresh
 * snapshot of the player's covenant; the resulting {@code TeamState} payload
 * opens the occult Covenant GUI on the client.
 */
public class SacredAmuletItem extends Item {
    public SacredAmuletItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            TeamManager.syncPlayer(serverPlayer, true);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click to commune with your covenant").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("ผ้ายันต์ — the yant cloth binds souls as one")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
