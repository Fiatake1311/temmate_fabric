package com.teammate.network;

import com.teammate.TeammateMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every GUI interaction (forging pacts, summons, severances, rule toggles),
 * the astral scanner, pings and the covenant-state sync run through these
 * {@link CustomPacketPayload}s.
 */
public final class TeamPayloads {
    private TeamPayloads() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(TeammateMod.MODID, path);
    }

    // ==================================================== Client -> Server

    public record CreateTeam(String name, int colorId) implements CustomPacketPayload {
        public static final Type<CreateTeam> TYPE = new Type<>(id("create_team"));
        public static final StreamCodec<ByteBuf, CreateTeam> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CreateTeam::name,
                ByteBufCodecs.VAR_INT, CreateTeam::colorId,
                CreateTeam::new);

        @Override
        public Type<CreateTeam> type() {
            return TYPE;
        }
    }

    /** Leader invites a player by exact name. */
    public record PacketInvitePlayer(String playerName) implements CustomPacketPayload {
        public static final Type<PacketInvitePlayer> TYPE = new Type<>(id("invite_player"));
        public static final StreamCodec<ByteBuf, PacketInvitePlayer> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PacketInvitePlayer::playerName,
                PacketInvitePlayer::new);

        @Override
        public Type<PacketInvitePlayer> type() {
            return TYPE;
        }
    }

    /** Invited player accepts ({@code accept=true}) or declines the pending request. */
    public record PacketAcceptInvite(boolean accept) implements CustomPacketPayload {
        public static final Type<PacketAcceptInvite> TYPE = new Type<>(id("accept_invite"));
        public static final StreamCodec<ByteBuf, PacketAcceptInvite> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, PacketAcceptInvite::accept,
                PacketAcceptInvite::new);

        @Override
        public Type<PacketAcceptInvite> type() {
            return TYPE;
        }
    }

    /** Disband (leader) or leave (member) the team. */
    public record PacketDisbandTeam() implements CustomPacketPayload {
        public static final Type<PacketDisbandTeam> TYPE = new Type<>(id("disband_team"));
        public static final StreamCodec<ByteBuf, PacketDisbandTeam> STREAM_CODEC =
                StreamCodec.unit(new PacketDisbandTeam());

        @Override
        public Type<PacketDisbandTeam> type() {
            return TYPE;
        }
    }

    /**
     * Sets the proximity-scan radius and runs the scan: the server answers with
     * the teamless players inside that spherical radius via {@link ScanResults}.
     */
    public record PacketChangeRadius(int radius) implements CustomPacketPayload {
        public static final Type<PacketChangeRadius> TYPE = new Type<>(id("change_radius"));
        public static final StreamCodec<ByteBuf, PacketChangeRadius> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PacketChangeRadius::radius,
                PacketChangeRadius::new);

        @Override
        public Type<PacketChangeRadius> type() {
            return TYPE;
        }
    }

    public record KickMember(UUID target) implements CustomPacketPayload {
        public static final Type<KickMember> TYPE = new Type<>(id("kick_member"));
        public static final StreamCodec<ByteBuf, KickMember> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, KickMember::target,
                KickMember::new);

        @Override
        public Type<KickMember> type() {
            return TYPE;
        }
    }

    /** rule: {@code TeamManager.RULE_FRIENDLY_FIRE}, {@code RULE_SEE_INVISIBLES} or {@code RULE_GLOW}. */
    public record ToggleRule(int rule, boolean value) implements CustomPacketPayload {
        public static final Type<ToggleRule> TYPE = new Type<>(id("toggle_rule"));
        public static final StreamCodec<ByteBuf, ToggleRule> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ToggleRule::rule,
                ByteBufCodecs.BOOL, ToggleRule::value,
                ToggleRule::new);

        @Override
        public Type<ToggleRule> type() {
            return TYPE;
        }
    }

    /**
     * "Team Banner" toggle, packed into a <b>single byte</b> on the wire:
     * bit 7 = enabled, bits 0-3 = team color id (0-15). Client → server carries the
     * clicking player's own state (the server authenticates via the connection, so
     * no uuid is transmitted); server → client echoes the persisted state back to
     * the owner on login/toggle. Backed by the {@code FLAG_ENABLED} attachment.
     */
    public record PacketBannerState(boolean enabled, int colorId) implements CustomPacketPayload {
        public static final Type<PacketBannerState> TYPE = new Type<>(id("banner_state"));
        public static final StreamCodec<ByteBuf, PacketBannerState> STREAM_CODEC =
                CustomPacketPayload.codec(PacketBannerState::write, PacketBannerState::read);

        private static final int ENABLED_BIT = 0x80;
        private static final int COLOR_MASK = 0x0F;

        private void write(ByteBuf buf) {
            buf.writeByte((enabled ? ENABLED_BIT : 0) | (colorId & COLOR_MASK));
        }

        private static PacketBannerState read(ByteBuf buf) {
            int packed = buf.readByte() & 0xFF;
            return new PacketBannerState((packed & ENABLED_BIT) != 0, packed & COLOR_MASK);
        }

        @Override
        public Type<PacketBannerState> type() {
            return TYPE;
        }
    }

    public record SetTeamIcon(String url) implements CustomPacketPayload {
        public static final Type<SetTeamIcon> TYPE = new Type<>(id("set_team_icon"));
        public static final StreamCodec<ByteBuf, SetTeamIcon> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SetTeamIcon::url,
                SetTeamIcon::new);

        @Override
        public Type<SetTeamIcon> type() {
            return TYPE;
        }
    }

    /**
     * Sent when the player presses the "Open Team Menu" key. The server decides
     * whether to honour it: if an operator disabled the menu key via
     * {@code /teammate off}, it is refused (the Team Tool item still works, since
     * that path opens the menu server-side without this packet).
     */
    public record RequestOpenMenu() implements CustomPacketPayload {
        public static final Type<RequestOpenMenu> TYPE = new Type<>(id("request_open_menu"));
        public static final StreamCodec<ByteBuf, RequestOpenMenu> STREAM_CODEC =
                StreamCodec.unit(new RequestOpenMenu());

        @Override
        public Type<RequestOpenMenu> type() {
            return TYPE;
        }
    }

    public record Ping(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Ping> TYPE = new Type<>(id("ping"));
        public static final StreamCodec<ByteBuf, Ping> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Ping::pos,
                Ping::new);

        @Override
        public Type<Ping> type() {
            return TYPE;
        }
    }

    // ==================================================== Server -> Client

    /** Names of nearby teamless players, answering a {@link PacketChangeRadius}. */
    public record ScanResults(List<String> names) implements CustomPacketPayload {
        public static final Type<ScanResults> TYPE = new Type<>(id("scan_results"));
        public static final StreamCodec<ByteBuf, ScanResults> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ScanResults::names,
                ScanResults::new);

        @Override
        public Type<ScanResults> type() {
            return TYPE;
        }
    }

    public record MemberInfo(UUID uuid, String name, boolean online, boolean leader,
                             float health, float maxHealth, int armor) {
        static void write(FriendlyByteBuf buf, MemberInfo member) {
            buf.writeUUID(member.uuid);
            buf.writeUtf(member.name);
            buf.writeBoolean(member.online);
            buf.writeBoolean(member.leader);
            buf.writeFloat(member.health);
            buf.writeFloat(member.maxHealth);
            buf.writeVarInt(member.armor);
        }

        static MemberInfo read(FriendlyByteBuf buf) {
            return new MemberInfo(buf.readUUID(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(),
                    buf.readFloat(), buf.readFloat(), buf.readVarInt());
        }
    }

    /**
     * Full snapshot of the player's covenant. Sent on login, after every mutation,
     * once per second (HUD refresh) and with {@code openScreen=true} when the
     * Sacred Cloth Amulet is used.
     */
    public record TeamState(boolean inTeam, boolean isLeader, String teamName, int colorId, String iconUrl,
                            boolean friendlyFire, boolean seeInvisibles, boolean glow,
                            String pendingInvite, boolean openScreen,
                            List<MemberInfo> members) implements CustomPacketPayload {
        public static final Type<TeamState> TYPE = new Type<>(id("team_state"));
        public static final StreamCodec<FriendlyByteBuf, TeamState> STREAM_CODEC =
                CustomPacketPayload.codec(TeamState::write, TeamState::read);

        private void write(FriendlyByteBuf buf) {
            buf.writeBoolean(inTeam);
            buf.writeBoolean(isLeader);
            buf.writeUtf(teamName);
            buf.writeVarInt(colorId);
            buf.writeUtf(iconUrl);
            buf.writeBoolean(friendlyFire);
            buf.writeBoolean(seeInvisibles);
            buf.writeBoolean(glow);
            buf.writeUtf(pendingInvite);
            buf.writeBoolean(openScreen);
            buf.writeVarInt(members.size());
            for (MemberInfo member : members) {
                MemberInfo.write(buf, member);
            }
        }

        private static TeamState read(FriendlyByteBuf buf) {
            boolean inTeam = buf.readBoolean();
            boolean isLeader = buf.readBoolean();
            String teamName = buf.readUtf();
            int colorId = buf.readVarInt();
            String iconUrl = buf.readUtf();
            boolean friendlyFire = buf.readBoolean();
            boolean seeInvisibles = buf.readBoolean();
            boolean glow = buf.readBoolean();
            String pendingInvite = buf.readUtf();
            boolean openScreen = buf.readBoolean();
            int count = buf.readVarInt();
            List<MemberInfo> members = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                members.add(MemberInfo.read(buf));
            }
            return new TeamState(inTeam, isLeader, teamName, colorId, iconUrl, friendlyFire, seeInvisibles,
                    glow, pendingInvite, openScreen, members);
        }

        @Override
        public Type<TeamState> type() {
            return TYPE;
        }
    }

    /** A team ping: a 3D waypoint at {@code pos}, shown to covenant members for 10 seconds. */
    public record PingMarker(BlockPos pos, int colorId, String senderName) implements CustomPacketPayload {
        public static final Type<PingMarker> TYPE = new Type<>(id("ping_marker"));
        public static final StreamCodec<ByteBuf, PingMarker> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, PingMarker::pos,
                ByteBufCodecs.VAR_INT, PingMarker::colorId,
                ByteBufCodecs.STRING_UTF8, PingMarker::senderName,
                PingMarker::new);

        @Override
        public Type<PingMarker> type() {
            return TYPE;
        }
    }
}
