package com.teammate;

import com.mojang.serialization.Codec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Persistent player data. Attachment types survive relog and (via copyOnDeath) death,
 * so team membership and the leader role stick to the player.
 */
public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, TeammateMod.MODID);

    /** Internal scoreboard id of the player's team; "" when the player has no team. */
    public static final Supplier<AttachmentType<String>> TEAM_ID = ATTACHMENT_TYPES.register("team_id",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).copyOnDeath().build());

    /** Whether the player is the leader (creator) of their team. */
    public static final Supplier<AttachmentType<Boolean>> IS_LEADER = ATTACHMENT_TYPES.register("is_leader",
            () -> AttachmentType.builder(() -> Boolean.FALSE).serialize(Codec.BOOL).copyOnDeath().build());

    /**
     * Per-player "Show Team Flag" preference: when true, a team-colored back flag is
     * rendered on this player's model for everyone to see. Persists across relogs and
     * death. Defaults to on so team members fly their colors out of the box.
     */
    public static final Supplier<AttachmentType<Boolean>> FLAG_ENABLED = ATTACHMENT_TYPES.register("flag_enabled",
            () -> AttachmentType.builder(() -> Boolean.TRUE).serialize(Codec.BOOL).copyOnDeath().build());

    /**
     * The real helmet that was in the head slot before we swapped in the team banner.
     * Restored when the banner is removed (toggle off, leave team, death, logout), so
     * the player never loses their gear. Not copied on death — death restores it into
     * the head slot before drops are computed.
     */
    public static final Supplier<AttachmentType<ItemStack>> SAVED_HELMET = ATTACHMENT_TYPES.register("saved_helmet",
            () -> AttachmentType.builder(() -> ItemStack.EMPTY).serialize(ItemStack.OPTIONAL_CODEC).build());

    private ModAttachments() {
    }
}
