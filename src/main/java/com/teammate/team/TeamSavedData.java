package com.teammate.team;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * World-persistent extension of the vanilla scoreboard teams: icon URL, leader,
 * ally-glow toggle and the authoritative member list (vanilla teams only store
 * member names, we need stable UUIDs for offline handling).
 */
public class TeamSavedData extends SavedData {
    private static final SavedData.Factory<TeamSavedData> FACTORY =
            new SavedData.Factory<>(TeamSavedData::new, TeamSavedData::load);

    private final Map<String, TeamInfo> teams = new LinkedHashMap<>();

    public static TeamSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "teammate_teams");
    }

    @Nullable
    public TeamInfo getTeam(String id) {
        return teams.get(id);
    }

    public Collection<TeamInfo> allTeams() {
        return teams.values();
    }

    public TeamInfo createTeam(String id, String displayName, ChatFormatting color, UUID leader) {
        TeamInfo info = new TeamInfo(id);
        info.displayName = displayName;
        info.color = color;
        info.leader = leader;
        teams.put(id, info);
        setDirty();
        return info;
    }

    public void removeTeam(String id) {
        teams.remove(id);
        setDirty();
    }

    public static TeamSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TeamSavedData data = new TeamSavedData();
        ListTag list = tag.getList("Teams", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag teamTag = list.getCompound(i);
            TeamInfo info = new TeamInfo(teamTag.getString("Id"));
            info.displayName = teamTag.getString("Name");
            ChatFormatting color = ChatFormatting.getById(teamTag.getInt("Color"));
            info.color = color != null && color.isColor() ? color : ChatFormatting.WHITE;
            if (teamTag.hasUUID("Leader")) {
                info.leader = teamTag.getUUID("Leader");
            }
            info.iconUrl = teamTag.getString("IconUrl");
            info.glowEnabled = teamTag.getBoolean("Glow");
            ListTag members = teamTag.getList("Members", Tag.TAG_COMPOUND);
            for (int j = 0; j < members.size(); j++) {
                CompoundTag memberTag = members.getCompound(j);
                UUID uuid = memberTag.getUUID("Id");
                info.members.add(uuid);
                info.memberNames.put(uuid, memberTag.getString("Name"));
            }
            data.teams.put(info.id, info);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (TeamInfo info : teams.values()) {
            CompoundTag teamTag = new CompoundTag();
            teamTag.putString("Id", info.id);
            teamTag.putString("Name", info.displayName);
            teamTag.putInt("Color", info.color.getId());
            if (info.leader != null) {
                teamTag.putUUID("Leader", info.leader);
            }
            teamTag.putString("IconUrl", info.iconUrl);
            teamTag.putBoolean("Glow", info.glowEnabled);
            ListTag members = new ListTag();
            for (UUID uuid : info.members) {
                CompoundTag memberTag = new CompoundTag();
                memberTag.putUUID("Id", uuid);
                memberTag.putString("Name", info.memberNames.getOrDefault(uuid, ""));
                members.add(memberTag);
            }
            teamTag.put("Members", members);
            list.add(teamTag);
        }
        tag.put("Teams", list);
        return tag;
    }

    public static class TeamInfo {
        public final String id;
        public String displayName = "";
        public ChatFormatting color = ChatFormatting.WHITE;
        @Nullable
        public UUID leader;
        public String iconUrl = "";
        public boolean glowEnabled = false;
        public final Set<UUID> members = new LinkedHashSet<>();
        public final Map<UUID, String> memberNames = new HashMap<>();

        public TeamInfo(String id) {
            this.id = id;
        }
    }
}
