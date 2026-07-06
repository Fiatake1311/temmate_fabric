package com.teammate.event;

import com.teammate.ModAttachments;
import com.teammate.team.BannerManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Server-side lifecycle + inventory safety for the team-banner-in-helmet system.
 * <p>
 * Performance model: all equip/unequip work is <b>state-change-driven</b> and
 * lives in {@link BannerManager#refresh} (GUI toggle, join/leave team, login,
 * respawn). The tick handler below is only a throttled watchdog — it runs once
 * per second per player, staggered so players don't all validate on the same
 * tick, and its happy path is a single cache read plus one slot compare with no
 * writes, no allocation and no network traffic.
 */
public final class BannerEvents {
    /** Validate once per second — never every tick. */
    private static final int GUARD_INTERVAL_TICKS = 20;

    private BannerEvents() {
    }

    /**
     * Throttled slot watchdog. Repairs (and only then purges duplicates) solely
     * when a violation is detected: the player pulled the banner out, swapped in
     * another helmet, or their expected state went stale.
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // stagger by entity id so a full server never validates everyone on one tick
        if ((player.tickCount + (player.getId() & 15)) % GUARD_INTERVAL_TICKS != 0) {
            return;
        }
        BannerManager.guardTick(player);
    }

    /**
     * On death, swap the banner back to the real helmet <em>before</em> vanilla
     * computes drops: the player's actual gear drops/keeps and the cosmetic
     * banner simply disappears instead of becoming a world item.
     */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (BannerManager.isTeamBanner(head)) {
            ItemStack saved = player.getData(ModAttachments.SAVED_HELMET);
            player.setItemSlot(EquipmentSlot.HEAD, saved == null ? ItemStack.EMPTY : saved.copy());
            player.setData(ModAttachments.SAVED_HELMET, ItemStack.EMPTY);
        }
        BannerManager.purgeStrayBanners(player); // never drop a stray banner copy
    }

    /** Re-equip after respawning if the player still qualifies (state change, not a tick). */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BannerManager.refresh(player);
        }
    }

    /**
     * Graceful logout (also fired for every player on server shutdown): return the
     * real helmet to the head slot before the player is saved to disk, then drop
     * the session cache entry. If the process dies without this running, the
     * persisted {@code SAVED_HELMET} attachment plus the never-overwrite-stash rule
     * in {@link BannerManager} still guarantee the helmet is recovered on next login.
     */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BannerManager.unequip(player);
            BannerManager.clearSession(player.getUUID());
        }
    }

    /** Never let a team banner become a world item if one somehow gets tossed. */
    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (BannerManager.isTeamBanner(event.getEntity().getItem())) {
            event.setCanceled(true);
            event.getPlayer().getInventory().placeItemBackInInventory(event.getEntity().getItem());
        }
    }
}
