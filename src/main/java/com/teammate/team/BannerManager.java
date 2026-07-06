package com.teammate.team;

import com.teammate.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side team-banner-in-helmet system, optimized to be fully
 * <b>state-change-driven</b>: the head slot is only written when something
 * actually changes (GUI toggle, team join/leave, login/respawn) — never from a
 * hot tick loop. A throttled guard ({@link #guardTick}) validates the slot once
 * per second using an O(1) session cache: one map read and one slot compare,
 * zero allocations on the happy path.
 * <p>
 * Item safety is explicit: the real helmet is stashed in the persistent
 * {@code SAVED_HELMET} attachment, an existing stash is never overwritten
 * (crash-recovery leftovers are preserved), and restores that cannot go back
 * into the slot fall into the inventory instead of being voided.
 */
public final class BannerManager {
    private static final String MARKER = "TeammateBanner";

    /**
     * Dye for each ChatFormatting color id (0-15), precomputed once at class load,
     * so runtime lookups are a single array read — no distance loops, no garbage.
     */
    private static final DyeColor[] DYE_FOR_CHAT_COLOR = new DyeColor[16];

    static {
        for (int id = 0; id < 16; id++) {
            ChatFormatting formatting = ChatFormatting.getById(id);
            Integer rgb = formatting != null ? formatting.getColor() : null;
            DYE_FOR_CHAT_COLOR[id] = nearestDye(rgb == null ? 0xFFFFFF : rgb);
        }
    }

    /**
     * Session cache: uuid → dye of the banner that player is expected to wear;
     * absent = no banner expected. Written only on state changes, read by the
     * guard tick. Server-thread only.
     */
    private static final Map<UUID, DyeColor> EXPECTED = new HashMap<>();

    private BannerManager() {
    }

    // ------------------------------------------------------------------ state changes

    /** Should this player currently be wearing the team banner? */
    public static boolean shouldWear(ServerPlayer player) {
        return !TeamManager.teamIdOf(player).isEmpty() && player.getData(ModAttachments.FLAG_ENABLED);
    }

    /**
     * The ONLY entry point for state changes (GUI toggle, join/leave team,
     * login, respawn). Recomputes the expected state, updates the cache and
     * touches the head slot only if it actually needs to change.
     */
    public static void refresh(ServerPlayer player) {
        DyeColor dye = shouldWear(player) ? teamDye(player) : null;
        if (dye == null) {
            unequip(player);
        } else {
            EXPECTED.put(player.getUUID(), dye);
            equip(player, dye);
        }
    }

    /**
     * Throttled validation, called about once per second per player. Happy path
     * is one map read + one marker check — no writes, no allocation, no network
     * traffic. Only on a detected violation does it repair the slot and sweep
     * the inventory for duplicates.
     */
    public static void guardTick(ServerPlayer player) {
        DyeColor expected = EXPECTED.get(player.getUUID());
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        boolean wearingBanner = isTeamBanner(head);
        if (expected == null) {
            if (wearingBanner) {
                unequip(player); // stale banner (e.g. state changed while offline)
            }
            return;
        }
        if (!wearingBanner || head.getItem() != bannerItem(expected)) {
            // tampered or outdated: return the foreign item, restore the banner, sweep copies
            if (!wearingBanner && !head.isEmpty()) {
                player.getInventory().placeItemBackInInventory(head.copy());
            }
            player.setItemSlot(EquipmentSlot.HEAD, createBanner(expected));
            purgeStrayBanners(player);
        }
    }

    /** Drops the player from the session cache; call when they disconnect. */
    public static void clearSession(UUID player) {
        EXPECTED.remove(player);
    }

    // ------------------------------------------------------------------ equip / unequip

    private static void equip(ServerPlayer player, DyeColor dye) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (isTeamBanner(head)) {
            if (head.getItem() != bannerItem(dye)) {
                player.setItemSlot(EquipmentSlot.HEAD, createBanner(dye)); // team color changed
            }
            return; // already correct: no redundant slot write, no sync spam
        }
        // bulletproof stash: NEVER overwrite an existing backup (e.g. left over after
        // a crash) — that would void the previously saved helmet
        ItemStack stashed = player.getData(ModAttachments.SAVED_HELMET);
        if (stashed == null || stashed.isEmpty()) {
            player.setData(ModAttachments.SAVED_HELMET, head.copy());
        } else if (!head.isEmpty()) {
            player.getInventory().placeItemBackInInventory(head.copy()); // keep both items alive
        }
        player.setItemSlot(EquipmentSlot.HEAD, createBanner(dye));
    }

    /**
     * Removes the banner and securely returns the stashed helmet. If the head
     * slot is unexpectedly occupied by something else, the stash goes into the
     * inventory instead — the backup is never voided.
     */
    public static void unequip(ServerPlayer player) {
        EXPECTED.remove(player.getUUID());
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack saved = player.getData(ModAttachments.SAVED_HELMET);
        ItemStack backup = saved == null ? ItemStack.EMPTY : saved.copy();
        if (isTeamBanner(head)) {
            player.setItemSlot(EquipmentSlot.HEAD, backup);
        } else if (!backup.isEmpty()) {
            player.getInventory().placeItemBackInInventory(backup);
        }
        player.setData(ModAttachments.SAVED_HELMET, ItemStack.EMPTY);
    }

    /** Removes any stray team-banner stacks so none can be kept, dropped or duplicated. */
    public static void purgeStrayBanners(ServerPlayer player) {
        Inventory inv = player.getInventory();
        purgeFrom(inv.items);
        purgeFrom(inv.offhand);
    }

    private static void purgeFrom(List<ItemStack> list) {
        for (int i = 0; i < list.size(); i++) {
            if (isTeamBanner(list.get(i))) {
                list.set(i, ItemStack.EMPTY);
            }
        }
    }

    // ------------------------------------------------------------------ banner item

    public static boolean isTeamBanner(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARKER);
    }

    private static ItemStack createBanner(DyeColor dye) {
        ItemStack stack = new ItemStack(bannerItem(dye));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(MARKER, true);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Team Banner").withStyle(ChatFormatting.GOLD));
        return stack;
    }

    /** Direct constant mapping — no registry lookups, no ResourceLocation allocation. */
    private static Item bannerItem(DyeColor dye) {
        return switch (dye) {
            case WHITE -> Items.WHITE_BANNER;
            case ORANGE -> Items.ORANGE_BANNER;
            case MAGENTA -> Items.MAGENTA_BANNER;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_BANNER;
            case YELLOW -> Items.YELLOW_BANNER;
            case LIME -> Items.LIME_BANNER;
            case PINK -> Items.PINK_BANNER;
            case GRAY -> Items.GRAY_BANNER;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_BANNER;
            case CYAN -> Items.CYAN_BANNER;
            case PURPLE -> Items.PURPLE_BANNER;
            case BLUE -> Items.BLUE_BANNER;
            case BROWN -> Items.BROWN_BANNER;
            case GREEN -> Items.GREEN_BANNER;
            case RED -> Items.RED_BANNER;
            case BLACK -> Items.BLACK_BANNER;
        };
    }

    @Nullable
    private static DyeColor teamDye(ServerPlayer player) {
        String id = TeamManager.teamIdOf(player);
        if (id.isEmpty()) {
            return null;
        }
        TeamSavedData.TeamInfo info = TeamSavedData.get(player.server).getTeam(id);
        return info == null ? null : DYE_FOR_CHAT_COLOR[info.color.getId() & 15];
    }

    /** Used once, at class load, to precompute {@link #DYE_FOR_CHAT_COLOR}. */
    private static DyeColor nearestDye(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        DyeColor best = DyeColor.WHITE;
        long bestDistance = Long.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            int c = dye.getTextureDiffuseColor();
            int dr = ((c >> 16) & 0xFF) - r;
            int dg = ((c >> 8) & 0xFF) - g;
            int db = (c & 0xFF) - b;
            long distance = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = dye;
            }
        }
        return best;
    }
}
