package com.teammate.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetworking {
    private ModNetworking() {
    }

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");

        // Client -> Server: GUI interactions, proximity scanner + ping key
        registrar.playToServer(TeamPayloads.CreateTeam.TYPE, TeamPayloads.CreateTeam.STREAM_CODEC,
                ServerPayloadHandler::handleCreateTeam);
        registrar.playToServer(TeamPayloads.PacketInvitePlayer.TYPE, TeamPayloads.PacketInvitePlayer.STREAM_CODEC,
                ServerPayloadHandler::handleInvitePlayer);
        registrar.playToServer(TeamPayloads.PacketAcceptInvite.TYPE, TeamPayloads.PacketAcceptInvite.STREAM_CODEC,
                ServerPayloadHandler::handleAcceptInvite);
        registrar.playToServer(TeamPayloads.PacketDisbandTeam.TYPE, TeamPayloads.PacketDisbandTeam.STREAM_CODEC,
                ServerPayloadHandler::handleDisbandTeam);
        registrar.playToServer(TeamPayloads.PacketChangeRadius.TYPE, TeamPayloads.PacketChangeRadius.STREAM_CODEC,
                ServerPayloadHandler::handleChangeRadius);
        registrar.playToServer(TeamPayloads.KickMember.TYPE, TeamPayloads.KickMember.STREAM_CODEC,
                ServerPayloadHandler::handleKickMember);
        registrar.playToServer(TeamPayloads.ToggleRule.TYPE, TeamPayloads.ToggleRule.STREAM_CODEC,
                ServerPayloadHandler::handleToggleRule);
        registrar.playToServer(TeamPayloads.SetTeamIcon.TYPE, TeamPayloads.SetTeamIcon.STREAM_CODEC,
                ServerPayloadHandler::handleSetTeamIcon);
        registrar.playToServer(TeamPayloads.Ping.TYPE, TeamPayloads.Ping.STREAM_CODEC,
                ServerPayloadHandler::handlePing);

        // Team Banner toggle: one byte on the wire; client -> server stores it,
        // server -> client echoes the persisted state to the owner
        registrar.playBidirectional(TeamPayloads.PacketBannerState.TYPE, TeamPayloads.PacketBannerState.STREAM_CODEC,
                new net.neoforged.neoforge.network.handling.DirectionalPayloadHandler<>(
                        ClientPayloadHandler::handleBannerState,
                        ServerPayloadHandler::handleBannerState));

        // Server -> Client: state sync, scan results + ping markers
        registrar.playToClient(TeamPayloads.TeamState.TYPE, TeamPayloads.TeamState.STREAM_CODEC,
                ClientPayloadHandler::handleTeamState);
        registrar.playToClient(TeamPayloads.ScanResults.TYPE, TeamPayloads.ScanResults.STREAM_CODEC,
                ClientPayloadHandler::handleScanResults);
        registrar.playToClient(TeamPayloads.PingMarker.TYPE, TeamPayloads.PingMarker.STREAM_CODEC,
                ClientPayloadHandler::handlePingMarker);
    }
}
