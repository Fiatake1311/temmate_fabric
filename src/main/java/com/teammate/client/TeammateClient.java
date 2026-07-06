package com.teammate.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.teammate.TeammateMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@Mod(value = TeammateMod.MODID, dist = Dist.CLIENT)
public class TeammateClient {
    /** Default 'P': pings the block under the crosshair for all team members. */
    public static final KeyMapping PING_KEY = new KeyMapping("key.teammate.ping",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.teammate");

    public TeammateClient(IEventBus modEventBus) {
        modEventBus.addListener(TeammateClient::onRegisterKeyMappings);
        modEventBus.addListener(TeammateClient::onRegisterGuiLayers);
        NeoForge.EVENT_BUS.register(ClientEvents.class);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PING_KEY);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(TeammateMod.MODID, "team_hud"),
                TeamHudOverlay::render);
    }
}
