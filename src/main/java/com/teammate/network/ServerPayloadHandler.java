package com.teammate.network;

import com.teammate.team.TeamManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ServerPayloadHandler {
    private ServerPayloadHandler() {
    }

    public static void handleCreateTeam(final TeamPayloads.CreateTeam payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.createTeam(player, payload.name(), payload.colorId());
        }
    }

    public static void handleInvitePlayer(final TeamPayloads.PacketInvitePlayer payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.invite(player, payload.playerName());
        }
    }

    public static void handleAcceptInvite(final TeamPayloads.PacketAcceptInvite payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.respondToInvite(player, payload.accept());
        }
    }

    public static void handleDisbandTeam(final TeamPayloads.PacketDisbandTeam payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.leaveOrDisband(player);
        }
    }

    public static void handleChangeRadius(final TeamPayloads.PacketChangeRadius payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.scanNearby(player, payload.radius());
        }
    }

    public static void handleKickMember(final TeamPayloads.KickMember payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.kick(player, payload.target());
        }
    }

    public static void handleToggleRule(final TeamPayloads.ToggleRule payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.toggleRule(player, payload.rule(), payload.value());
        }
    }

    public static void handleSetTeamIcon(final TeamPayloads.SetTeamIcon payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.setIcon(player, payload.url());
        }
    }

    public static void handlePing(final TeamPayloads.Ping payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.ping(player, payload.pos());
        }
    }

    public static void handleRequestOpenMenu(final TeamPayloads.RequestOpenMenu payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TeamManager.requestOpenMenu(player);
        }
    }

    public static void handleBannerState(final TeamPayloads.PacketBannerState payload, final IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            // authenticated by connection; only the toggle bit matters client -> server
            // (the authoritative team color always comes from the server's own data)
            TeamManager.setFlagPreference(player, payload.enabled());
        }
    }
}
