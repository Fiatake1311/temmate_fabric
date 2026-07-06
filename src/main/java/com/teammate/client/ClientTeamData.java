package com.teammate.client;

import com.teammate.client.gui.TeamScreen;
import com.teammate.network.TeamPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Client-side cache of the last {@code TeamState} sync plus the active ping markers. */
public final class ClientTeamData {
    public static boolean inTeam;
    public static boolean isLeader;
    public static boolean friendlyFire;
    public static boolean seeInvisibles = true;
    public static boolean glow;
    /** Local player's own "Show Team Flag" preference, mirrored for the GUI button. */
    public static boolean showFlag = true;
    public static String teamName = "";
    public static String iconUrl = "";
    public static String pendingInvite = "";
    public static ChatFormatting color = ChatFormatting.WHITE;
    public static List<TeamPayloads.MemberInfo> members = List.of();
    /** Last Astral Scanner answer: names of nearby teamless souls. */
    public static List<String> scanResults = List.of();

    private static final Set<UUID> MEMBER_IDS = new HashSet<>();

    public static final List<ActivePing> PINGS = new ArrayList<>();

    public record ActivePing(BlockPos pos, int colorId, String sender, long expiresAtMillis) {
    }

    private ClientTeamData() {
    }

    public static void applyState(TeamPayloads.TeamState state) {
        inTeam = state.inTeam();
        isLeader = state.isLeader();
        teamName = state.teamName();
        color = colorOf(state.colorId());
        iconUrl = state.iconUrl();
        friendlyFire = state.friendlyFire();
        seeInvisibles = state.seeInvisibles();
        glow = state.glow();
        pendingInvite = state.pendingInvite();
        members = state.members();
        MEMBER_IDS.clear();
        for (TeamPayloads.MemberInfo member : members) {
            MEMBER_IDS.add(member.uuid());
        }
        if (!iconUrl.isEmpty()) {
            TeamIconManager.request(iconUrl);
        }

        Minecraft mc = Minecraft.getInstance();
        if (state.openScreen()) {
            mc.setScreen(new TeamScreen());
        } else if (mc.screen instanceof TeamScreen screen) {
            screen.onStateSync();
        }
    }

    public static void applyScanResults(List<String> names) {
        scanResults = names;
        if (Minecraft.getInstance().screen instanceof TeamScreen screen) {
            screen.onScanResults();
        }
    }

    /** Applies the local player's "Team Banner" preference echoed from the server (GUI button state). */
    public static void applyFlagState(boolean enabled) {
        showFlag = enabled;
        if (Minecraft.getInstance().screen instanceof TeamScreen screen) {
            screen.onStateSync();
        }
    }

    public static void addPing(TeamPayloads.PingMarker marker) {
        PINGS.removeIf(ping -> ping.pos().equals(marker.pos()));
        PINGS.add(new ActivePing(marker.pos(), marker.colorId(), marker.senderName(),
                System.currentTimeMillis() + 10_000L));

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.7F, 1.5F);
            mc.player.displayClientMessage(Component.literal(marker.senderName() + " pinged a location")
                    .withStyle(colorOf(marker.colorId())), true);
        }
    }

    public static boolean isMember(UUID uuid) {
        return inTeam && MEMBER_IDS.contains(uuid);
    }

    public static ChatFormatting colorOf(int colorId) {
        ChatFormatting formatting = ChatFormatting.getById(colorId);
        return formatting != null && formatting.isColor() ? formatting : ChatFormatting.WHITE;
    }

    public static void reset() {
        inTeam = isLeader = friendlyFire = glow = false;
        seeInvisibles = true;
        teamName = iconUrl = pendingInvite = "";
        color = ChatFormatting.WHITE;
        members = List.of();
        scanResults = List.of();
        showFlag = true;
        MEMBER_IDS.clear();
        PINGS.clear();
    }
}
