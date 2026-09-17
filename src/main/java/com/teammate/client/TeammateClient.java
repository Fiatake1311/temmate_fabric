package com.teammate.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.teammate.TeammateMod;
import com.teammate.network.TeamPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@Mod(value = TeammateMod.MODID, dist = Dist.CLIENT)
public class TeammateClient {
    /** Default 'P': pings the block under the crosshair for all team members. */
    public static final KeyMapping PING_KEY = new KeyMapping("key.teammate.ping",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.teammate");

    /** Default 'G': asks the server to open the team menu (an operator can lock this with /teammate off). */
    public static final KeyMapping OPEN_TEAM_MENU_KEY = new KeyMapping("key.teammate.open_menu",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.teammate");

    /** Default 'H': shows/hides the team HUD overlay so it never clutters the screen. */
    public static final KeyMapping TOGGLE_HUD_KEY = new KeyMapping("key.teammate.toggle_hud",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.teammate");

    public TeammateClient(IEventBus modEventBus) {
        modEventBus.addListener(TeammateClient::onRegisterKeyMappings);
        modEventBus.addListener(TeammateClient::onRegisterGuiLayers);
        NeoForge.EVENT_BUS.register(ClientEvents.class);
        NeoForge.EVENT_BUS.addListener(TeammateClient::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_TEAM_MENU_KEY.consumeClick()) {
            // server-authoritative: it decides whether the menu key is allowed and,
            // if so, replies with a state sync that opens the screen
            if (mc.player != null && mc.screen == null) {
                PacketDistributor.sendToServer(new TeamPayloads.RequestOpenMenu());
            }
        }
        while (TOGGLE_HUD_KEY.consumeClick()) {
            if (mc.player != null) {
                TeamHudOverlay.visible = !TeamHudOverlay.visible;
                mc.player.displayClientMessage(Component.literal(
                        TeamHudOverlay.visible ? "Team HUD shown" : "Team HUD hidden")
                        .withStyle(ChatFormatting.GRAY), true);
            }
        }
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PING_KEY);
        event.register(OPEN_TEAM_MENU_KEY);
        event.register(TOGGLE_HUD_KEY);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(TeammateMod.MODID, "team_hud"),
                TeamHudOverlay::render);
    }
}
