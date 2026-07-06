package com.teammate.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.teammate.network.TeamPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Client render/tick handlers: team name + icon above heads ({@link RenderNameTagEvent}),
 * ally-only glow, the ping keybind and the 3D ping waypoint rendering.
 */
public final class ClientEvents {
    private static final double PING_RAY_DISTANCE = 64.0D;
    /** Teammates whose invisibility we cleared for the current render pass (restored in Post). */
    private static final Set<UUID> INVIS_OVERRIDDEN = new HashSet<>();

    private ClientEvents() {
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        while (TeammateClient.PING_KEY.consumeClick()) {
            sendPing(mc);
        }
        tickPings(mc);
    }

    private static void sendPing(Minecraft mc) {
        if (!ClientTeamData.inTeam) {
            mc.player.displayClientMessage(
                    Component.literal("Join a team to use pings").withStyle(ChatFormatting.RED), true);
            return;
        }
        HitResult hit = mc.player.pick(PING_RAY_DISTANCE, 1.0F, false);
        BlockPos pos = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                ? blockHit.getBlockPos()
                : BlockPos.containing(hit.getLocation());
        PacketDistributor.sendToServer(new TeamPayloads.Ping(pos));
    }

    // ------------------------------------------------------------------ invisibility bypass

    /**
     * Team invisibility bypass. Before a teammate's model is drawn, if they are
     * invisible and vanilla would hide them from us, clear the invisible flag for
     * this one render pass so their body is drawn (restored in {@link #onRenderPlayerPost}).
     * Only our client and only our teammates are affected, so enemies still see them
     * as fully invisible. When "See Friendly Invisibles" is on, vanilla already draws
     * them as a translucent ghost, so we leave that path alone.
     */
    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!ClientTeamData.inTeam) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = event.getEntity();
        if (mc.player == null || player == mc.player || !ClientTeamData.isMember(player.getUUID())) {
            return;
        }
        if (player.isInvisible() && player.isInvisibleTo(mc.player)) {
            player.setInvisible(false);
            INVIS_OVERRIDDEN.add(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        Player player = event.getEntity();
        if (INVIS_OVERRIDDEN.remove(player.getUUID())) {
            player.setInvisible(true);
        }
    }

    private static void tickPings(Minecraft mc) {
        long now = System.currentTimeMillis();
        ClientTeamData.PINGS.removeIf(ping -> now >= ping.expiresAtMillis());
        if (ClientTeamData.PINGS.isEmpty() || mc.level.getGameTime() % 4 != 0) {
            return;
        }
        RandomSource random = mc.level.random;
        for (ClientTeamData.ActivePing ping : ClientTeamData.PINGS) {
            BlockPos pos = ping.pos();
            mc.level.addParticle(ParticleTypes.END_ROD,
                    pos.getX() + 0.5D + (random.nextDouble() - 0.5D) * 0.6D,
                    pos.getY() + 1.1D + random.nextDouble() * 0.5D,
                    pos.getZ() + 0.5D + (random.nextDouble() - 0.5D) * 0.6D,
                    0.0D, 0.03D, 0.0D);
        }
    }

    // ------------------------------------------------------------------ name tag

    /** Renders "[icon] Team Name" one line above the vanilla username of allies. */
    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!ClientTeamData.inTeam || ClientTeamData.teamName.isEmpty()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.player || !ClientTeamData.isMember(player.getUUID())) {
            return;
        }
        if (player.isDiscrete()) {
            return; // sneaking: vanilla hides the tag, so do we
        }
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        if (dispatcher.distanceToSqr(player) > 4096.0D) {
            return;
        }
        Vec3 attach = player.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0,
                player.getViewYRot(event.getPartialTick()));
        if (attach == null) {
            return;
        }

        Font font = mc.font;
        Component label = Component.literal(ClientTeamData.teamName).withStyle(ClientTeamData.color);
        TeamIconManager.Icon icon = TeamIconManager.get(ClientTeamData.iconUrl);
        float iconSpace = icon != null ? 10.0F : 0.0F;
        float xStart = -(font.width(label) + iconSpace) / 2.0F;
        float y = -12.0F; // one text line above the username

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        // same transform vanilla uses for the name tag
        poseStack.translate(attach.x, attach.y + 0.5D, attach.z);
        poseStack.mulPose(dispatcher.cameraOrientation());
        poseStack.scale(0.025F, -0.025F, 0.025F);
        Matrix4f matrix = poseStack.last().pose();

        MultiBufferSource buffer = event.getMultiBufferSource();
        int light = event.getPackedLight();
        int background = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        float textX = xStart + iconSpace;
        font.drawInBatch(label, textX, y, 0x20FFFFFF, false, matrix, buffer,
                Font.DisplayMode.SEE_THROUGH, background, light);
        font.drawInBatch(label, textX, y, -1, false, matrix, buffer,
                Font.DisplayMode.NORMAL, 0, light);

        if (icon != null) {
            // 8x8 icon quad in name-tag space, to the left of the team name
            VertexConsumer consumer = buffer.getBuffer(RenderType.text(icon.location()));
            float x0 = xStart;
            float x1 = xStart + 8.0F;
            float y0 = y - 0.5F;
            float y1 = y + 7.5F;
            consumer.addVertex(matrix, x0, y0, 0.0F).setColor(255, 255, 255, 255).setUv(0.0F, 0.0F).setLight(light);
            consumer.addVertex(matrix, x0, y1, 0.0F).setColor(255, 255, 255, 255).setUv(0.0F, 1.0F).setLight(light);
            consumer.addVertex(matrix, x1, y1, 0.0F).setColor(255, 255, 255, 255).setUv(1.0F, 1.0F).setLight(light);
            consumer.addVertex(matrix, x1, y0, 0.0F).setColor(255, 255, 255, 255).setUv(1.0F, 0.0F).setLight(light);
        }
        poseStack.popPose();
    }

    // ------------------------------------------------------------------ ping waypoints

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ClientTeamData.PINGS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        long now = System.currentTimeMillis();

        for (ClientTeamData.ActivePing ping : ClientTeamData.PINGS) {
            BlockPos pos = ping.pos();
            Integer rgbBoxed = ClientTeamData.colorOf(ping.colorId()).getColor();
            int rgb = rgbBoxed != null ? rgbBoxed : 0xFFFFFF;
            float r = (rgb >> 16 & 0xFF) / 255.0F;
            float g = (rgb >> 8 & 0xFF) / 255.0F;
            float b = (rgb & 0xFF) / 255.0F;
            float pulse = 0.55F + 0.45F * Mth.sin((now % 1000L) / 1000.0F * Mth.TWO_PI);

            poseStack.pushPose();
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            LevelRenderer.renderLineBox(poseStack, lines, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D, r, g, b, pulse);
            // vertical beacon-style beam fading out with height
            PoseStack.Pose pose = poseStack.last();
            lines.addVertex(pose, 0.5F, 1.0F, 0.5F).setColor(r, g, b, pulse).setNormal(pose, 0.0F, 1.0F, 0.0F);
            lines.addVertex(pose, 0.5F, 33.0F, 0.5F).setColor(r, g, b, 0.05F).setNormal(pose, 0.0F, 1.0F, 0.0F);
            poseStack.popPose();
        }
        buffer.endBatch(RenderType.lines());
    }

    // ------------------------------------------------------------------ disconnect

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientTeamData.reset();
        INVIS_OVERRIDDEN.clear();
    }
}
