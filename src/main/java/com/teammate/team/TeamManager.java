package com.teammate.team;

import com.teammate.ModAttachments;
import com.teammate.network.TeamPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * All server-side team logic. Teams are backed by vanilla scoreboard teams
 * (friendly fire, see-friendly-invisibles, name color, tab list color all come
 * for free), while {@link TeamSavedData} carries the mod-specific extras.
 */
public final class TeamManager {
    public static final int RULE_FRIENDLY_FIRE = 0;
    public static final int RULE_SEE_INVISIBLES = 1;
    public static final int RULE_GLOW = 2;

    private static final String TEAM_PREFIX = "tm_";
    private static final long PING_LIFETIME_MS = 10_000L;
    /** CustomData marker flagging an item as a "Team Tool" (right-click opens the menu). */
    private static final String TOOL_MARKER = "TeammateTool";

    /** invitee uuid -> team id. In-memory on purpose: invites do not survive a restart. */
    private static final Map<UUID, String> PENDING_INVITES = new HashMap<>();
    private static final Map<UUID, Integer> LAST_PING_TICK = new HashMap<>();

    private TeamManager() {
    }

    // ------------------------------------------------------------------ queries

    public static String teamIdOf(ServerPlayer player) {
        return player.getData(ModAttachments.TEAM_ID);
    }

    @Nullable
    private static TeamSavedData.TeamInfo teamOf(ServerPlayer player) {
        String id = teamIdOf(player);
        return id.isEmpty() ? null : TeamSavedData.get(player.server).getTeam(id);
    }

    @Nullable
    private static TeamSavedData.TeamInfo requireLeader(ServerPlayer player) {
        TeamSavedData.TeamInfo info = teamOf(player);
        if (info == null || !player.getUUID().equals(info.leader)) {
            error(player, "Only the team leader can do that.");
            return null;
        }
        return info;
    }

    // ------------------------------------------------------------------ actions

    public static void createTeam(ServerPlayer player, String name, int colorId) {
        if (!teamIdOf(player).isEmpty()) {
            error(player, "You are already in a team.");
            return;
        }
        String trimmed = name.trim();
        if (trimmed.length() < 2 || trimmed.length() > 24) {
            error(player, "Team name must be 2-24 characters long.");
            return;
        }
        ChatFormatting color = colorOf(colorId);
        MinecraftServer server = player.server;
        ServerScoreboard scoreboard = server.getScoreboard();
        TeamSavedData data = TeamSavedData.get(server);

        String id = TEAM_PREFIX + sanitize(trimmed);
        if (data.getTeam(id) != null || scoreboard.getPlayerTeam(id) != null) {
            error(player, "A team with a similar name already exists.");
            return;
        }

        TeamSavedData.TeamInfo info = data.createTeam(id, trimmed, color, player.getUUID());
        info.members.add(player.getUUID());
        info.memberNames.put(player.getUUID(), player.getGameProfile().getName());
        data.setDirty();

        // mirror into a vanilla scoreboard team so Minecraft colors the name + glow outline
        PlayerTeam team = mirrorScoreboardTeam(server, info);
        ensureOnScoreboardTeam(scoreboard, player, team);

        player.setData(ModAttachments.TEAM_ID, id);
        player.setData(ModAttachments.IS_LEADER, true);
        PENDING_INVITES.remove(player.getUUID());

        player.sendSystemMessage(Component.literal("Team ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(trimmed).withStyle(color, ChatFormatting.BOLD))
                .append(Component.literal(" created. You are the leader.").withStyle(ChatFormatting.GRAY)));
        BannerManager.refresh(player);
        syncPlayer(player, false);
    }

    public static void invite(ServerPlayer leader, String targetName) {
        TeamSavedData.TeamInfo info = requireLeader(leader);
        if (info == null) {
            return;
        }
        ServerPlayer target = leader.server.getPlayerList().getPlayerByName(targetName.trim());
        if (target == null) {
            error(leader, "Player '" + targetName.trim() + "' is not online.");
            return;
        }
        if (target == leader) {
            error(leader, "You cannot invite yourself.");
            return;
        }
        if (!teamIdOf(target).isEmpty()) {
            error(leader, target.getGameProfile().getName() + " is already in a team.");
            return;
        }
        PENDING_INVITES.put(target.getUUID(), info.id);
        leader.sendSystemMessage(Component.literal("Invite sent to " + target.getGameProfile().getName() + ".")
                .withStyle(ChatFormatting.GREEN));
        // sleek action-bar notification
        target.displayClientMessage(Component.literal("[" + info.displayName + "]").withStyle(info.color)
                .append(Component.literal(" has invited you to join! Open your Team Menu to Accept.")
                        .withStyle(ChatFormatting.WHITE)), true);
        // plus a chat copy, since the action bar fades after a few seconds
        target.sendSystemMessage(Component.literal("[" + info.displayName + "]").withStyle(info.color)
                .append(Component.literal(" has invited you to join! Open your Team Menu → Requests to accept.")
                        .withStyle(ChatFormatting.GRAY)));
        syncPlayer(target, false);
    }

    public static void respondToInvite(ServerPlayer player, boolean accept) {
        String teamId = PENDING_INVITES.remove(player.getUUID());
        if (teamId == null) {
            error(player, "You have no pending team invite.");
            syncPlayer(player, false);
            return;
        }
        MinecraftServer server = player.server;
        TeamSavedData data = TeamSavedData.get(server);
        TeamSavedData.TeamInfo info = data.getTeam(teamId);
        PlayerTeam team = server.getScoreboard().getPlayerTeam(teamId);
        if (info == null || team == null) {
            error(player, "That team no longer exists.");
            syncPlayer(player, false);
            return;
        }
        if (!accept) {
            notifyLeader(server, info, Component.literal(player.getGameProfile().getName() + " declined the invite.")
                    .withStyle(ChatFormatting.RED));
            syncPlayer(player, false);
            return;
        }
        if (!teamIdOf(player).isEmpty()) {
            error(player, "You are already in a team.");
            syncPlayer(player, false);
            return;
        }
        info.members.add(player.getUUID());
        info.memberNames.put(player.getUUID(), player.getGameProfile().getName());
        data.setDirty();
        // refresh the scoreboard mirror (re-syncs the color) and add the new member to it
        ensureOnScoreboardTeam(server.getScoreboard(), player, mirrorScoreboardTeam(server, info));
        player.setData(ModAttachments.TEAM_ID, teamId);
        player.setData(ModAttachments.IS_LEADER, false);

        broadcast(server, info, Component.literal(player.getGameProfile().getName() + " joined the team!")
                .withStyle(info.color));
        BannerManager.refresh(player);
        syncTeamMembers(server, info);
    }

    public static void kick(ServerPlayer leader, UUID target) {
        TeamSavedData.TeamInfo info = requireLeader(leader);
        if (info == null) {
            return;
        }
        if (leader.getUUID().equals(target)) {
            error(leader, "You cannot kick yourself. Disband the team instead.");
            return;
        }
        if (!info.members.remove(target)) {
            error(leader, "That player is not in your team.");
            return;
        }
        MinecraftServer server = leader.server;
        TeamSavedData data = TeamSavedData.get(server);
        String targetName = info.memberNames.remove(target);
        data.setDirty();

        ServerScoreboard scoreboard = server.getScoreboard();
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (online != null) {
            removeFromScoreboardTeam(scoreboard, online.getScoreboardName(), info.id);
            online.setData(ModAttachments.TEAM_ID, "");
            online.setData(ModAttachments.IS_LEADER, false);
            BannerManager.unequip(online);
            online.sendSystemMessage(Component.literal("You were removed from team "
                    + info.displayName + ".").withStyle(ChatFormatting.RED));
            syncPlayer(online, false);
            targetName = online.getGameProfile().getName();
        } else if (targetName != null && !targetName.isEmpty()) {
            removeFromScoreboardTeam(scoreboard, targetName, info.id);
        }

        broadcast(server, info, Component.literal((targetName == null || targetName.isEmpty() ? "A player" : targetName)
                + " was removed from the team.").withStyle(ChatFormatting.GRAY));
        syncTeamMembers(server, info);
    }

    /** Members leave; the leader leaving disbands the whole team. */
    public static void leaveOrDisband(ServerPlayer player) {
        TeamSavedData.TeamInfo info = teamOf(player);
        if (info == null) {
            error(player, "You are not in a team.");
            syncPlayer(player, false);
            return;
        }
        MinecraftServer server = player.server;
        TeamSavedData data = TeamSavedData.get(server);
        ServerScoreboard scoreboard = server.getScoreboard();

        if (player.getUUID().equals(info.leader)) {
            for (UUID uuid : List.copyOf(info.members)) {
                ServerPlayer member = server.getPlayerList().getPlayer(uuid);
                String memberName = member != null ? member.getScoreboardName() : info.memberNames.getOrDefault(uuid, "");
                if (!memberName.isEmpty()) {
                    removeFromScoreboardTeam(scoreboard, memberName, info.id);
                }
                if (member != null) {
                    member.setData(ModAttachments.TEAM_ID, "");
                    member.setData(ModAttachments.IS_LEADER, false);
                    BannerManager.unequip(member);
                    member.sendSystemMessage(Component.literal("Team " + info.displayName
                            + " was disbanded.").withStyle(ChatFormatting.RED));
                    syncPlayer(member, false);
                }
            }
            PlayerTeam team = scoreboard.getPlayerTeam(info.id);
            if (team != null) {
                scoreboard.removePlayerTeam(team);
            }
            data.removeTeam(info.id);
        } else {
            info.members.remove(player.getUUID());
            info.memberNames.remove(player.getUUID());
            data.setDirty();
            removeFromScoreboardTeam(scoreboard, player.getScoreboardName(), info.id);
            player.setData(ModAttachments.TEAM_ID, "");
            player.setData(ModAttachments.IS_LEADER, false);
            BannerManager.unequip(player);
            player.sendSystemMessage(Component.literal("You left team " + info.displayName + ".")
                    .withStyle(ChatFormatting.GRAY));
            broadcast(server, info, Component.literal(player.getGameProfile().getName()
                    + " left the team.").withStyle(ChatFormatting.GRAY));
            syncPlayer(player, false);
            syncTeamMembers(server, info);
        }
    }

    public static void toggleRule(ServerPlayer leader, int rule, boolean value) {
        TeamSavedData.TeamInfo info = requireLeader(leader);
        if (info == null) {
            return;
        }
        MinecraftServer server = leader.server;
        PlayerTeam team = server.getScoreboard().getPlayerTeam(info.id);
        if (team == null) {
            return;
        }
        switch (rule) {
            case RULE_FRIENDLY_FIRE -> team.setAllowFriendlyFire(value);
            case RULE_SEE_INVISIBLES -> team.setSeeFriendlyInvisibles(value);
            case RULE_GLOW -> {
                info.glowEnabled = value;
                TeamSavedData.get(server).setDirty();
            }
            default -> {
                return;
            }
        }
        syncTeamMembers(server, info);
    }

    public static void setIcon(ServerPlayer leader, String url) {
        TeamSavedData.TeamInfo info = requireLeader(leader);
        if (info == null) {
            return;
        }
        String trimmed = url.trim();
        if (trimmed.length() > 255) {
            error(leader, "Icon URL is too long (max 255 characters).");
            return;
        }
        if (!trimmed.isEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            error(leader, "The icon must be a http(s) URL pointing to a PNG image.");
            return;
        }
        info.iconUrl = trimmed;
        TeamSavedData.get(leader.server).setDirty();
        leader.sendSystemMessage(Component.literal(trimmed.isEmpty() ? "Team icon cleared." : "Team icon updated.")
                .withStyle(ChatFormatting.GREEN));
        syncTeamMembers(leader.server, info);
    }

    /**
     * Proximity scanner: finds teamless players within the exact spherical
     * {@code radius} (5/10/20/30 blocks) of the leader and reports their names
     * back for the Search & Invite tab.
     */
    public static void scanNearby(ServerPlayer leader, int radius) {
        TeamSavedData.TeamInfo info = requireLeader(leader);
        if (info == null) {
            return;
        }
        int clamped = radius == 5 || radius == 10 || radius == 20 || radius == 30 ? radius : 10;
        double maxDistSqr = (double) clamped * clamped; // compare squared distances to skip a sqrt per player
        List<String> found = new ArrayList<>();
        for (ServerPlayer other : leader.serverLevel().players()) {
            if (other == leader || !teamIdOf(other).isEmpty()) {
                continue; // players already in a team are not listed
            }
            if (other.distanceToSqr(leader) <= maxDistSqr) {
                found.add(other.getGameProfile().getName());
            }
        }
        found.sort(String.CASE_INSENSITIVE_ORDER);
        PacketDistributor.sendToPlayer(leader, new TeamPayloads.ScanResults(found));
    }

    /**
     * Stores the player's "Team Banner" preference, then equips/unequips the native
     * banner-in-helmet accordingly (a pure state-change trigger — the hot path never
     * does this work). The banner itself is a real item, so it syncs to everyone
     * through vanilla; only a one-byte state echo goes back to the owner's GUI.
     */
    public static void setFlagPreference(ServerPlayer player, boolean enabled) {
        player.setData(ModAttachments.FLAG_ENABLED, enabled);
        BannerManager.refresh(player);
        PacketDistributor.sendToPlayer(player, bannerStatePacket(player, enabled));
    }

    private static TeamPayloads.PacketBannerState bannerStatePacket(ServerPlayer player, boolean enabled) {
        TeamSavedData.TeamInfo info = teamOf(player);
        int colorId = info != null ? info.color.getId() : ChatFormatting.WHITE.getId();
        return new TeamPayloads.PacketBannerState(enabled, colorId);
    }

    /** Broadcasts a 10 second waypoint at {@code pos} to every online team member. */
    public static void ping(ServerPlayer player, BlockPos pos) {
        TeamSavedData.TeamInfo info = teamOf(player);
        if (info == null) {
            error(player, "Join a team to use pings.");
            return;
        }
        int now = player.server.getTickCount();
        Integer last = LAST_PING_TICK.get(player.getUUID());
        if (last != null && now - last < 10) {
            return; // rate limit: max 2 pings per second
        }
        LAST_PING_TICK.put(player.getUUID(), now);

        TeamPayloads.PingMarker marker = new TeamPayloads.PingMarker(pos, info.color.getId(),
                player.getGameProfile().getName());
        forEachOnlineMember(player.server, info, member -> PacketDistributor.sendToPlayer(member, marker));
    }

    /** Backing logic for the /t command: routes chat to team members only, in the team color. */
    public static void teamChat(ServerPlayer player, String message) {
        TeamSavedData.TeamInfo info = teamOf(player);
        if (info == null) {
            error(player, "You are not in a team - /t is team-only chat.");
            return;
        }
        Component chat = Component.literal("[" + info.displayName + "] ").withStyle(info.color, ChatFormatting.BOLD)
                .append(Component.literal(player.getGameProfile().getName() + ": " + message).withStyle(info.color));
        broadcast(player.server, info, chat);
    }

    // ------------------------------------------------------------------ lifecycle

    public static void onLogin(ServerPlayer player) {
        String teamId = teamIdOf(player);
        if (!teamId.isEmpty()) {
            MinecraftServer server = player.server;
            TeamSavedData data = TeamSavedData.get(server);
            TeamSavedData.TeamInfo info = data.getTeam(teamId);
            ServerScoreboard scoreboard = server.getScoreboard();
            if (info == null || !info.members.contains(player.getUUID())) {
                // team was disbanded or the player was kicked while offline
                player.setData(ModAttachments.TEAM_ID, "");
                player.setData(ModAttachments.IS_LEADER, false);
                removeFromScoreboardTeam(scoreboard, player.getScoreboardName(), teamId);
            } else {
                info.memberNames.put(player.getUUID(), player.getGameProfile().getName());
                data.setDirty();
                // re-mirror so this client re-receives the team color used for the glow
                // outline, and make sure we are actually on the scoreboard team
                ensureOnScoreboardTeam(scoreboard, player, mirrorScoreboardTeam(server, info));
                player.setData(ModAttachments.IS_LEADER, player.getUUID().equals(info.leader));
            }
        }
        syncPlayer(player, false);
        // restore the banner-in-helmet if it applies, and echo the GUI button state (1 byte)
        BannerManager.refresh(player);
        PacketDistributor.sendToPlayer(player,
                bannerStatePacket(player, player.getData(ModAttachments.FLAG_ENABLED)));
    }

    public static void onLogout(ServerPlayer player) {
        PENDING_INVITES.remove(player.getUUID());
        LAST_PING_TICK.remove(player.getUUID());
    }

    /** Called once per second; keeps the client HUD (health/armor of allies) fresh. */
    public static void tickSync(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!teamIdOf(player).isEmpty()) {
                syncPlayer(player, false);
            }
        }
    }

    // ------------------------------------------------------------------ sync

    public static void syncPlayer(ServerPlayer player, boolean openScreen) {
        PacketDistributor.sendToPlayer(player, buildState(player, openScreen));
    }

    // ------------------------------------------------------------------ open menu / team tool

    /**
     * Honours the "Open Team Menu" key. Refused when an operator has disabled the
     * menu key via {@code /teammate off}; the Team Tool item bypasses this by
     * calling {@link #openMenu(ServerPlayer)} directly.
     */
    public static void requestOpenMenu(ServerPlayer player) {
        if (!TeamSavedData.get(player.server).isMenuKeyEnabled()) {
            error(player, "The team menu key is disabled here. Use a Team Tool item instead.");
            return;
        }
        openMenu(player);
    }

    /** Opens the team menu on the player's client (sends a state sync with openScreen=true). */
    public static void openMenu(ServerPlayer player) {
        syncPlayer(player, true);
    }

    /** {@code /teammate on|off}: server-wide toggle for the open-menu key. */
    public static void setMenuKeyEnabled(ServerPlayer operator, boolean enabled) {
        TeamSavedData.get(operator.server).setMenuKeyEnabled(enabled);
        operator.sendSystemMessage(Component.literal(enabled
                        ? "Team menu key enabled: players can open the menu with the key again."
                        : "Team menu key disabled: players must use a Team Tool item to open the menu.")
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
    }

    /** True if the stack is a Team Tool (right-clicking it opens the team menu). */
    public static boolean isTeamTool(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(TOOL_MARKER);
    }

    /**
     * {@code /itemteam}: turns the item in the player's main hand into a Team Tool
     * without consuming or replacing it — a marker component plus a gold name so it
     * reads as the team-creation tool.
     */
    public static void makeHeldItemTeamTool(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            error(player, "Hold an item in your main hand to turn it into a Team Tool.");
            return;
        }
        if (isTeamTool(stack)) {
            error(player, "That item is already a Team Tool.");
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, existing -> existing.putBoolean(TOOL_MARKER, true));
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Team Tool").withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("This item is now a Team Tool. Right-click it to open the team menu.")
                .withStyle(ChatFormatting.GREEN));
    }

    private static void syncTeamMembers(MinecraftServer server, TeamSavedData.TeamInfo info) {
        forEachOnlineMember(server, info, member -> syncPlayer(member, false));
    }

    private static TeamPayloads.TeamState buildState(ServerPlayer player, boolean openScreen) {
        MinecraftServer server = player.server;
        TeamSavedData.TeamInfo info = teamOf(player);

        String inviteTeam = "";
        String pendingId = PENDING_INVITES.get(player.getUUID());
        if (pendingId != null) {
            TeamSavedData.TeamInfo invitedTo = TeamSavedData.get(server).getTeam(pendingId);
            if (invitedTo != null) {
                inviteTeam = invitedTo.displayName;
            } else {
                PENDING_INVITES.remove(player.getUUID());
            }
        }

        if (info == null) {
            return new TeamPayloads.TeamState(false, false, "", ChatFormatting.WHITE.getId(), "",
                    false, true, false, inviteTeam, openScreen, List.of());
        }

        PlayerTeam team = server.getScoreboard().getPlayerTeam(info.id);
        boolean friendlyFire = team != null && team.isAllowFriendlyFire();
        boolean seeInvisibles = team == null || team.canSeeFriendlyInvisibles();

        List<TeamPayloads.MemberInfo> members = new ArrayList<>(info.members.size());
        for (UUID uuid : info.members) {
            ServerPlayer member = server.getPlayerList().getPlayer(uuid);
            String name = member != null
                    ? member.getGameProfile().getName()
                    : info.memberNames.getOrDefault(uuid, "Unknown");
            members.add(new TeamPayloads.MemberInfo(uuid, name, member != null, uuid.equals(info.leader),
                    member != null ? member.getHealth() : 0.0F,
                    member != null ? member.getMaxHealth() : 20.0F,
                    member != null ? member.getArmorValue() : 0));
        }

        return new TeamPayloads.TeamState(true, player.getUUID().equals(info.leader), info.displayName,
                info.color.getId(), info.iconUrl, friendlyFire, seeInvisibles, info.glowEnabled,
                inviteTeam, openScreen, members);
    }

    // ------------------------------------------------------------------ helpers

    private static void forEachOnlineMember(MinecraftServer server, TeamSavedData.TeamInfo info,
                                            java.util.function.Consumer<ServerPlayer> action) {
        for (UUID uuid : info.members) {
            ServerPlayer member = server.getPlayerList().getPlayer(uuid);
            if (member != null) {
                action.accept(member);
            }
        }
    }

    private static void broadcast(MinecraftServer server, TeamSavedData.TeamInfo info, Component message) {
        forEachOnlineMember(server, info, member -> member.sendSystemMessage(message));
    }

    private static void notifyLeader(MinecraftServer server, TeamSavedData.TeamInfo info, Component message) {
        if (info.leader != null) {
            ServerPlayer leader = server.getPlayerList().getPlayer(info.leader);
            if (leader != null) {
                leader.sendSystemMessage(message);
            }
        }
    }

    /**
     * Mirrors a mod team into a vanilla {@link PlayerTeam}. Minecraft derives BOTH
     * the name-tag color AND the glowing outline color from the scoreboard team's
     * color, so this is what actually paints the team glow — there is nothing to
     * "bypass", the scoreboard binding is the mechanism we rely on.
     * <p>
     * Creates the scoreboard team if it is missing, always re-applies the color and
     * display name, and calls {@link ServerScoreboard#onTeamChanged} to push a fresh
     * {@code ClientboundSetPlayerTeamPacket} to every client so the outline color can
     * never go stale (this is the key robustness fix for the glow).
     */
    private static PlayerTeam mirrorScoreboardTeam(MinecraftServer server, TeamSavedData.TeamInfo info) {
        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(info.id);
        if (team == null) {
            team = scoreboard.addPlayerTeam(info.id);
            // sensible combat defaults only when freshly (re)created; existing
            // teams keep whatever the leader configured via toggleRule
            team.setAllowFriendlyFire(false);
            team.setSeeFriendlyInvisibles(true);
        }
        team.setDisplayName(Component.literal(info.displayName));
        team.setColor(info.color);
        scoreboard.onTeamChanged(team);
        return team;
    }

    /** Adds the player to the mirrored scoreboard team if they are not already on it. */
    private static void ensureOnScoreboardTeam(ServerScoreboard scoreboard, ServerPlayer player, PlayerTeam team) {
        if (scoreboard.getPlayersTeam(player.getScoreboardName()) != team) {
            scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
        }
    }

    private static void removeFromScoreboardTeam(ServerScoreboard scoreboard, String playerName, String teamId) {
        PlayerTeam current = scoreboard.getPlayersTeam(playerName);
        if (current != null && current.getName().equals(teamId)) {
            scoreboard.removePlayerFromTeam(playerName, current);
        }
    }

    private static ChatFormatting colorOf(int colorId) {
        ChatFormatting color = ChatFormatting.getById(colorId);
        return color != null && color.isColor() ? color : ChatFormatting.WHITE;
    }

    private static String sanitize(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    private static void error(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
