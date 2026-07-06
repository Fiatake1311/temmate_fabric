package com.teammate.network;

import com.teammate.client.ClientTeamData;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Thin trampoline into client-only code. Safe to reference from common networking
 * registration: the client classes are only touched when a payload actually arrives,
 * which can only happen on the client.
 */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    public static void handleTeamState(final TeamPayloads.TeamState payload, final IPayloadContext context) {
        ClientTeamData.applyState(payload);
    }

    public static void handleScanResults(final TeamPayloads.ScanResults payload, final IPayloadContext context) {
        ClientTeamData.applyScanResults(payload.names());
    }

    public static void handlePingMarker(final TeamPayloads.PingMarker payload, final IPayloadContext context) {
        ClientTeamData.addPing(payload);
    }

    public static void handleBannerState(final TeamPayloads.PacketBannerState payload, final IPayloadContext context) {
        ClientTeamData.applyFlagState(payload.enabled());
    }
}
