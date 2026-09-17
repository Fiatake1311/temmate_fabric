package com.teammate.event;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.teammate.team.TeamManager;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game-bus listeners; registered explicitly in the mod constructor. */
public final class ServerEvents {
    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TeamManager.onLogin(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TeamManager.onLogout(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // once per second: refresh ally health/armor for the client HUD
        if (event.getServer().getTickCount() % 20 == 0) {
            TeamManager.tickSync(event.getServer());
        }
    }

    /** Right-clicking a Team Tool item opens the team menu, bypassing the /teammate off key lock. */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && TeamManager.isTeamTool(event.getItemStack())) {
            TeamManager.openMenu(player);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // /t <message> - team-only chat, colored in the team's color
        event.getDispatcher().register(Commands.literal("t")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            TeamManager.teamChat(player, StringArgumentType.getString(ctx, "message"));
                            return Command.SINGLE_SUCCESS;
                        })));

        // /itemteam - turn the held item into a Team Tool (right-click opens the team menu)
        event.getDispatcher().register(Commands.literal("itemteam")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    TeamManager.makeHeldItemTeamTool(player);
                    return Command.SINGLE_SUCCESS;
                }));

        // /teammate on|off - operator-only: server-wide toggle for the open-menu key
        event.getDispatcher().register(Commands.literal("teammate")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("on").executes(ctx -> {
                    TeamManager.setMenuKeyEnabled(ctx.getSource().getPlayerOrException(), true);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    TeamManager.setMenuKeyEnabled(ctx.getSource().getPlayerOrException(), false);
                    return Command.SINGLE_SUCCESS;
                })));
    }
}
