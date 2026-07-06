package com.teammate.event;

import com.teammate.team.TeamManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tactical Rule: Team-Only Healing Lock.
 * <p>
 * Healing cast by a team member only affects players on the same team.
 * {@link LivingHealEvent} is the enforcement point; because vanilla gives that
 * event no source, the caster is reconstructed from two trackers:
 * <ul>
 *   <li>splash potions: {@link ProjectileImpactEvent} records the thrower for the
 *       current tick (instant health resolves synchronously during impact),</li>
 *   <li>regeneration: {@link MobEffectEvent.Added} exposes the effect source
 *       (thrower / lingering-cloud owner), remembered until the effect expires.</li>
 * </ul>
 * Heals with no foreign caster (natural regen, food, beacons, golden apples,
 * self-healing) are never touched.
 */
public final class HealingLockEvents {
    private record RegenSource(UUID casterId, long expiresAtGameTime) {
    }

    private static final Map<UUID, RegenSource> REGEN_CASTERS = new HashMap<>();
    @Nullable
    private static UUID splashCasterId;
    private static long splashGameTime = Long.MIN_VALUE;

    private HealingLockEvents() {
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getProjectile() instanceof ThrownPotion potion
                && !potion.level().isClientSide()
                && potion.getOwner() instanceof ServerPlayer owner) {
            splashCasterId = owner.getUUID();
            splashGameTime = potion.level().getGameTime();
        }
    }

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event) {
        if (!(event.getEntity() instanceof ServerPlayer target) || target.level().isClientSide()) {
            return;
        }
        if (!event.getEffectInstance().is(MobEffects.REGENERATION)) {
            return;
        }
        ServerPlayer caster = resolveCaster(event.getEffectSource());
        if (caster == null || caster == target) {
            return;
        }
        long expiresAt = target.level().getGameTime() + event.getEffectInstance().getDuration() + 1;
        REGEN_CASTERS.put(target.getUUID(), new RegenSource(caster.getUUID(), expiresAt));
    }

    @SubscribeEvent
    public static void onLivingHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer target) || target.level().isClientSide()) {
            return;
        }
        long now = target.level().getGameTime();

        UUID casterId = null;
        if (splashCasterId != null && splashGameTime == now) {
            // instant-health splash resolving this very tick
            casterId = splashCasterId;
        } else {
            RegenSource source = REGEN_CASTERS.get(target.getUUID());
            if (source != null) {
                if (now > source.expiresAtGameTime() || !target.hasEffect(MobEffects.REGENERATION)) {
                    REGEN_CASTERS.remove(target.getUUID());
                } else {
                    casterId = source.casterId();
                }
            }
        }
        if (casterId == null || casterId.equals(target.getUUID())) {
            return;
        }
        ServerPlayer caster = target.server.getPlayerList().getPlayer(casterId);
        if (caster == null) {
            return;
        }
        String casterTeam = TeamManager.teamIdOf(caster);
        if (casterTeam.isEmpty()) {
            return; // teamless casters don't restrict anyone's healing
        }
        if (!casterTeam.equals(TeamManager.teamIdOf(target))) {
            event.setCanceled(true); // rivals receive 0 healing from this team's sources
        }
    }

    @Nullable
    private static ServerPlayer resolveCaster(@Nullable Entity source) {
        if (source instanceof ServerPlayer player) {
            return player;
        }
        if (source instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer owner) {
            return owner;
        }
        if (source instanceof AreaEffectCloud cloud && cloud.getOwner() instanceof ServerPlayer owner) {
            return owner;
        }
        return null;
    }
}
