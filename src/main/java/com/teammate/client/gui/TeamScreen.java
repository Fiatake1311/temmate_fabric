package com.teammate.client.gui;

import com.teammate.client.ClientTeamData;
import com.teammate.client.TeamIconManager;
import com.teammate.network.TeamPayloads;
import com.teammate.team.TeamManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Universal Minimalist Dark Mode team menu. Flat obsidian surfaces, thin
 * 1-pixel lines, platinum text, and three status colors only: soft mint for
 * active, pastel coral for destructive, muted gray for inactive. Every
 * coordinate is an integer and the vanilla background blur is skipped, so the
 * interface renders pixel-perfect at any GUI scale.
 * <p>
 * Tabs: [Overview] members + disband · [Search &amp; Invite] autocomplete invite
 * + proximity scanner · [Requests] incoming invites · [System Rules] toggles.
 */
public class TeamScreen extends Screen {
    // ---- minimalist palette -------------------------------------------------
    private static final int VEIL = 0xB2000000;         // dim the world, no blur
    private static final int BG = 0xFF121212;           // flat obsidian
    private static final int SURFACE = 0xFF1E1E1E;      // inputs, rows, cards
    private static final int SURFACE_HOVER = 0xFF2C2C2C;
    private static final int LINE = 0xFF2C2C2C;         // thin 1px separators
    private static final int LINE_LIGHT = 0xFF3D3D3D;   // component outlines
    private static final int TEXT = 0xFFFAFAFA;         // platinum
    private static final int TEXT_DIM = 0xFFB0B0B0;
    private static final int MINT = 0xFF81C784;         // active / confirm
    private static final int CORAL = 0xFFE57373;        // warning / destructive
    private static final int MUTED = 0xFF616161;        // inactive

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 228;
    private static final int TAB_ROW_Y = 26;
    private static final int MEMBER_ROW_H = 16;
    private static final int SCAN_ROW_H = 15;
    private static final int[] SCAN_RADII = {5, 10, 20, 30};

    private enum Tab {
        OVERVIEW("Overview"),
        SEARCH("Search & Invite"),
        REQUESTS("Requests"),
        RULES("System Rules");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private enum Accent { NEUTRAL, MINT, CORAL }

    private Tab currentTab;
    private static int scanRadiusIndex = 1; // remembered for the whole session

    @Nullable
    private EditBox nameBox;
    @Nullable
    private EditBox inviteBox;
    @Nullable
    private EditBox iconBox;
    @Nullable
    private MinimalButton friendlyFireButton;
    @Nullable
    private MinimalButton seeInvisiblesButton;
    @Nullable
    private MinimalButton glowButton;
    @Nullable
    private MinimalButton disbandButton;
    @Nullable
    private MinimalButton radiusButton;

    private int left;
    private int top;
    private int memberScroll;
    private long disbandArmedUntil;
    private final List<String> suggestions = new ArrayList<>();

    private int selectedColor = ChatFormatting.WHITE.getId();
    // preserved across widget rebuilds so typing survives state syncs
    private String savedName = "";
    private String savedInvite = "";
    @Nullable
    private String savedIcon;
    private String structureKey = "";

    public TeamScreen() {
        super(Component.literal("Team Menu"));
        // an invited player lands directly on their pending request
        currentTab = !ClientTeamData.inTeam && !ClientTeamData.pendingInvite.isEmpty()
                ? Tab.REQUESTS : Tab.OVERVIEW;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        structureKey = computeStructureKey();
        disbandArmedUntil = 0L;
        // drop references from the previous tab so background framing only
        // touches widgets that actually exist right now
        nameBox = inviteBox = iconBox = null;
        friendlyFireButton = seeInvisiblesButton = glowButton = null;
        disbandButton = radiusButton = null;
        suggestions.clear();

        switch (currentTab) {
            case OVERVIEW -> initOverview();
            case SEARCH -> initSearch();
            case REQUESTS -> initRequests();
            case RULES -> initRules();
        }
    }

    private int contentTop() {
        return top + 50;
    }

    private void initOverview() {
        int y = contentTop();
        if (!ClientTeamData.inTeam) {
            nameBox = new EditBox(font, left + 12, y + 27, PANEL_W - 24, 16, Component.literal("Team name"));
            nameBox.setMaxLength(24);
            nameBox.setBordered(false);
            nameBox.setTextColor(0xFAFAFA);
            nameBox.setHint(Component.literal("Team name").withStyle(ChatFormatting.DARK_GRAY));
            nameBox.setValue(savedName);
            nameBox.setResponder(value -> savedName = value);
            addRenderableWidget(nameBox);

            addRenderableWidget(new MinimalButton(left + PANEL_W / 2 - 70, y + 118, 140, 18,
                    Component.literal("Create Team"), Accent.MINT, button -> {
                String name = nameBox != null ? nameBox.getValue().trim() : "";
                if (!name.isEmpty()) {
                    PacketDistributor.sendToServer(new TeamPayloads.CreateTeam(name, selectedColor));
                }
            }));
            return;
        }

        disbandButton = addRenderableWidget(new MinimalButton(left + PANEL_W / 2 - 65, top + PANEL_H - 28, 130, 18,
                disbandLabel(false), Accent.CORAL, button -> {
            if (System.currentTimeMillis() < disbandArmedUntil) {
                PacketDistributor.sendToServer(new TeamPayloads.PacketDisbandTeam());
                disbandArmedUntil = 0L;
            } else {
                disbandArmedUntil = System.currentTimeMillis() + 2000L; // double-click within 2s
            }
        }));
        disbandButton.setTooltip(Tooltip.create(Component.literal(ClientTeamData.isLeader
                ? "Disbands the team for everyone. Requires a double-click."
                : "Leaves the team. Requires a double-click.")));
    }

    private void initSearch() {
        if (!ClientTeamData.inTeam || !ClientTeamData.isLeader) {
            return;
        }
        int y = contentTop();

        inviteBox = new EditBox(font, left + 12, y + 14, 212, 14, Component.literal("Invite player"));
        inviteBox.setMaxLength(16);
        inviteBox.setBordered(false);
        inviteBox.setTextColor(0xFAFAFA);
        inviteBox.setHint(Component.literal("Player name").withStyle(ChatFormatting.DARK_GRAY));
        inviteBox.setValue(savedInvite);
        inviteBox.setResponder(value -> {
            savedInvite = value;
            updateSuggestions(value.trim());
        });
        addRenderableWidget(inviteBox);
        addRenderableWidget(new MinimalButton(left + 234, y + 11, 74, 18, Component.literal("Invite"),
                Accent.MINT, button -> sendInvite()));
        updateSuggestions(savedInvite.trim());

        radiusButton = addRenderableWidget(new MinimalButton(left + 12, y + 66, 96, 16,
                radiusLabel(), Accent.NEUTRAL, button -> {
            scanRadiusIndex = (scanRadiusIndex + 1) % SCAN_RADII.length;
            if (radiusButton != null) {
                radiusButton.setMessage(radiusLabel());
            }
        }));
        radiusButton.setTooltip(Tooltip.create(Component.literal("Scan radius: 5 → 10 → 20 → 30 blocks")));

        addRenderableWidget(new MinimalButton(left + 114, y + 66, 140, 16,
                Component.literal("Scan Nearby Players"), Accent.NEUTRAL,
                button -> PacketDistributor.sendToServer(
                        new TeamPayloads.PacketChangeRadius(SCAN_RADII[scanRadiusIndex]))));

        // one invite button per scan result row
        List<String> results = ClientTeamData.scanResults;
        int rowY = y + 102;
        for (int i = 0; i < Math.min(results.size(), 4); i++) {
            String name = results.get(i);
            addRenderableWidget(new MinimalButton(left + PANEL_W - 62, rowY - 1, 50, 12,
                    Component.literal("Invite"), Accent.MINT,
                    button -> PacketDistributor.sendToServer(new TeamPayloads.PacketInvitePlayer(name))));
            rowY += SCAN_ROW_H;
        }
    }

    private void initRequests() {
        if (!ClientTeamData.inTeam && !ClientTeamData.pendingInvite.isEmpty()) {
            int y = contentTop();
            addRenderableWidget(new MinimalButton(left + 54, y + 82, 100, 18,
                    Component.literal("Accept"), Accent.MINT,
                    button -> PacketDistributor.sendToServer(new TeamPayloads.PacketAcceptInvite(true))));
            addRenderableWidget(new MinimalButton(left + 166, y + 82, 100, 18,
                    Component.literal("Decline"), Accent.CORAL,
                    button -> PacketDistributor.sendToServer(new TeamPayloads.PacketAcceptInvite(false))));
        }
    }

    private void initRules() {
        if (!ClientTeamData.inTeam) {
            return;
        }
        int y = contentTop();
        // Team-wide combat rules + icon: leader only
        if (ClientTeamData.isLeader) {
            friendlyFireButton = addRenderableWidget(new MinimalButton(left + PANEL_W - 62, y + 12, 50, 14,
                    stateLabel(ClientTeamData.friendlyFire), stateAccent(ClientTeamData.friendlyFire),
                    button -> PacketDistributor.sendToServer(new TeamPayloads.ToggleRule(
                            TeamManager.RULE_FRIENDLY_FIRE, !ClientTeamData.friendlyFire))));
            seeInvisiblesButton = addRenderableWidget(new MinimalButton(left + PANEL_W - 62, y + 38, 50, 14,
                    stateLabel(ClientTeamData.seeInvisibles), stateAccent(ClientTeamData.seeInvisibles),
                    button -> PacketDistributor.sendToServer(new TeamPayloads.ToggleRule(
                            TeamManager.RULE_SEE_INVISIBLES, !ClientTeamData.seeInvisibles))));

            iconBox = new EditBox(font, left + 12, y + 118, 212, 14, Component.literal("Icon URL"));
            iconBox.setMaxLength(255);
            iconBox.setBordered(false);
            iconBox.setTextColor(0xFAFAFA);
            iconBox.setHint(Component.literal("https://... (16x16 PNG)").withStyle(ChatFormatting.DARK_GRAY));
            iconBox.setValue(savedIcon != null ? savedIcon : ClientTeamData.iconUrl);
            iconBox.setResponder(value -> savedIcon = value);
            addRenderableWidget(iconBox);
            addRenderableWidget(new MinimalButton(left + 234, y + 115, 74, 18, Component.literal("Apply"),
                    Accent.NEUTRAL, button -> PacketDistributor.sendToServer(new TeamPayloads.SetTeamIcon(
                            iconBox != null ? iconBox.getValue().trim() : ""))));
        }

        // Team Banner: a per-player, persistent preference — every member gets it
        glowButton = addRenderableWidget(new MinimalButton(left + PANEL_W - 62, y + 64, 50, 14,
                stateLabel(ClientTeamData.showFlag), stateAccent(ClientTeamData.showFlag),
                button -> toggleFlag()));
    }

    private void toggleFlag() {
        boolean next = !ClientTeamData.showFlag;
        ClientTeamData.showFlag = next; // optimistic local update for instant feedback
        // one byte on the wire: toggle bit + team color id
        PacketDistributor.sendToServer(new TeamPayloads.PacketBannerState(next, ClientTeamData.color.getId()));
        if (glowButton != null) {
            glowButton.setState(stateLabel(next), stateAccent(next));
        }
    }

    private void sendInvite() {
        String name = inviteBox != null ? inviteBox.getValue().trim() : "";
        if (!name.isEmpty()) {
            PacketDistributor.sendToServer(new TeamPayloads.PacketInvitePlayer(name));
            if (inviteBox != null) {
                inviteBox.setValue("");
            }
        }
    }

    // ------------------------------------------------------------------ sync hooks

    /** Called by ClientTeamData when a TeamState sync arrives while this screen is open. */
    public void onStateSync() {
        if (!computeStructureKey().equals(structureKey)) {
            memberScroll = 0;
            rebuildWidgets();
        } else {
            if (friendlyFireButton != null) {
                friendlyFireButton.setState(stateLabel(ClientTeamData.friendlyFire),
                        stateAccent(ClientTeamData.friendlyFire));
            }
            if (seeInvisiblesButton != null) {
                seeInvisiblesButton.setState(stateLabel(ClientTeamData.seeInvisibles),
                        stateAccent(ClientTeamData.seeInvisibles));
            }
            if (glowButton != null) {
                glowButton.setState(stateLabel(ClientTeamData.showFlag), stateAccent(ClientTeamData.showFlag));
            }
        }
    }

    /** Called when scan results arrive: rebuild to add the per-row invite buttons. */
    public void onScanResults() {
        if (currentTab == Tab.SEARCH) {
            rebuildWidgets();
        }
    }

    private String computeStructureKey() {
        return ClientTeamData.inTeam + "|" + ClientTeamData.isLeader + "|" + ClientTeamData.pendingInvite.isEmpty();
    }

    private static Component stateLabel(boolean on) {
        return Component.literal(on ? "ON" : "OFF");
    }

    private static Accent stateAccent(boolean on) {
        return on ? Accent.MINT : Accent.NEUTRAL;
    }

    private static Component radiusLabel() {
        return Component.literal(SCAN_RADII[scanRadiusIndex] + " Blocks");
    }

    private Component disbandLabel(boolean armed) {
        if (armed) {
            return Component.literal("Click again to confirm");
        }
        return Component.literal(ClientTeamData.isLeader ? "Disband Team" : "Leave Team");
    }

    // ------------------------------------------------------------------ rendering

    /** Flat dim veil + solid panel. Vanilla blur is intentionally skipped. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, VEIL);
        drawPanel(guiGraphics, mouseX, mouseY);
        // input frames go under the widgets, so they render before super.render()
        frameEditBox(guiGraphics, nameBox);
        frameEditBox(guiGraphics, inviteBox);
        frameEditBox(guiGraphics, iconBox);
    }

    private void drawPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(left, top, left + PANEL_W, top + PANEL_H, BG);
        guiGraphics.renderOutline(left - 1, top - 1, PANEL_W + 2, PANEL_H + 2, LINE_LIGHT);

        // header: team identity left, role right
        int headerX = left + 12;
        if (ClientTeamData.inTeam) {
            TeamIconManager.Icon icon = TeamIconManager.get(ClientTeamData.iconUrl);
            if (icon != null) {
                guiGraphics.blit(icon.location(), headerX, top + 8, 10, 10, 0.0F, 0.0F,
                        icon.width(), icon.height(), icon.width(), icon.height());
                headerX += 14;
            }
            guiGraphics.drawString(font, Component.literal(ClientTeamData.teamName)
                    .withStyle(ClientTeamData.color, ChatFormatting.BOLD), headerX, top + 9, TEXT, false);
            String badge = ClientTeamData.isLeader ? "LEADER" : "MEMBER";
            guiGraphics.drawString(font, badge, left + PANEL_W - 12 - font.width(badge), top + 9,
                    ClientTeamData.isLeader ? MINT : MUTED, false);
        } else {
            guiGraphics.drawString(font, Component.literal("TEAM MENU").withStyle(ChatFormatting.BOLD),
                    headerX, top + 9, TEXT, false);
            guiGraphics.drawString(font, "NO TEAM", left + PANEL_W - 12 - font.width("NO TEAM"), top + 9,
                    MUTED, false);
        }

        // tab row: text + 1px mint underline for the active tab
        int tabWidth = (PANEL_W - 24) / Tab.values().length;
        for (Tab tab : Tab.values()) {
            int x = left + 12 + tab.ordinal() * tabWidth;
            int y = top + TAB_ROW_Y;
            boolean selected = tab == currentTab;
            boolean hovered = mouseX >= x && mouseX < x + tabWidth && mouseY >= y - 2 && mouseY < y + 12;
            int color = selected ? TEXT : hovered ? TEXT_DIM : MUTED;
            int textWidth = font.width(tab.label);
            int textX = x + (tabWidth - textWidth) / 2;
            guiGraphics.drawString(font, tab.label, textX, y, color, false);
            if (selected) {
                guiGraphics.fill(textX, y + 11, textX + textWidth, y + 12, MINT);
            }
        }
        guiGraphics.fill(left + 12, top + 42, left + PANEL_W - 12, top + 43, LINE);

        // requests badge: a subtle mint dot when an invite is waiting
        if (!ClientTeamData.pendingInvite.isEmpty() && !ClientTeamData.inTeam) {
            int x = left + 12 + Tab.REQUESTS.ordinal() * tabWidth;
            guiGraphics.fill(x + tabWidth - 6, top + TAB_ROW_Y - 2, x + tabWidth - 3, top + TAB_ROW_Y + 1, MINT);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (disbandButton != null) {
            disbandButton.setMessage(disbandLabel(System.currentTimeMillis() < disbandArmedUntil));
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        switch (currentTab) {
            case OVERVIEW -> renderOverview(guiGraphics, mouseX, mouseY);
            case SEARCH -> renderSearch(guiGraphics, mouseX, mouseY);
            case REQUESTS -> renderRequests(guiGraphics);
            case RULES -> renderRules(guiGraphics);
        }
    }

    private void renderOverview(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int y = contentTop();
        if (!ClientTeamData.inTeam) {
            guiGraphics.drawString(font, "CREATE A TEAM", left + 12, y + 2, TEXT_DIM, false);
            guiGraphics.drawString(font, "NAME", left + 12, y + 16, MUTED, false);
            guiGraphics.drawString(font, "COLOR", left + 12, y + 56, MUTED, false);
            for (int i = 0; i < 16; i++) {
                int x = swatchX(i);
                int sy = swatchY(i);
                ChatFormatting formatting = ChatFormatting.getById(i);
                Integer rgb = formatting != null ? formatting.getColor() : null;
                guiGraphics.fill(x, sy, x + 12, sy + 12, 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF));
                if (i == selectedColor) {
                    guiGraphics.renderOutline(x - 2, sy - 2, 16, 16, TEXT);
                } else if (mouseX >= x && mouseX < x + 12 && mouseY >= sy && mouseY < sy + 12) {
                    guiGraphics.renderOutline(x - 1, sy - 1, 14, 14, TEXT_DIM);
                } else {
                    guiGraphics.renderOutline(x, sy, 12, 12, LINE_LIGHT);
                }
            }
            guiGraphics.drawCenteredString(font, "Pick a color for the team name, then create.",
                    left + PANEL_W / 2, y + 146, MUTED);
            return;
        }

        List<TeamPayloads.MemberInfo> members = ClientTeamData.members;
        guiGraphics.drawString(font, "MEMBERS · " + members.size(), left + 12, y + 2, TEXT_DIM, false);

        int listY = memberListTop();
        int visible = visibleMemberRows();
        int maxScroll = Math.max(0, members.size() - visible);
        memberScroll = Mth.clamp(memberScroll, 0, maxScroll);

        for (int row = 0; row < visible; row++) {
            int index = memberScroll + row;
            if (index >= members.size()) {
                break;
            }
            TeamPayloads.MemberInfo member = members.get(index);
            int rowY = listY + row * MEMBER_ROW_H;
            // no row background: names float directly on the flat panel for a cleaner look
            boolean self = minecraft != null && minecraft.player != null
                    && member.uuid().equals(minecraft.player.getUUID());

            guiGraphics.fill(left + 17, rowY + 2, left + 20, rowY + 5, member.online() ? MINT : MUTED);
            String name = member.name() + (self ? " (you)" : "");
            guiGraphics.drawString(font, name, left + 26, rowY, member.online() ? TEXT : MUTED, false);
            if (member.leader()) {
                guiGraphics.drawString(font, "LEADER", left + 26 + font.width(name) + 6, rowY, MINT, false);
            }

            if (member.online()) {
                String hp = String.format("%.0f HP", member.health());
                guiGraphics.drawString(font, hp, left + PANEL_W - 52 - font.width(hp), rowY, TEXT_DIM, false);
            } else {
                guiGraphics.drawString(font, "offline", left + PANEL_W - 52 - font.width("offline"), rowY,
                        MUTED, false);
            }
            if (ClientTeamData.isLeader && !self) {
                boolean hovered = isOverKick(mouseX, mouseY, rowY);
                guiGraphics.drawString(font, "×", kickX(), rowY, hovered ? CORAL : MUTED, false);
            }
        }
        if (maxScroll > 0) {
            guiGraphics.drawString(font, memberScroll + 1 + "–" + Math.min(members.size(), memberScroll + visible)
                    + " / " + members.size(), left + PANEL_W - 60, y + 2, MUTED, false);
        }
    }

    private void renderSearch(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int y = contentTop();
        if (!ClientTeamData.inTeam) {
            guiGraphics.drawCenteredString(font, "Create a team first.", left + PANEL_W / 2, y + 70, MUTED);
            return;
        }
        if (!ClientTeamData.isLeader) {
            guiGraphics.drawCenteredString(font, "Only the team leader can invite players.",
                    left + PANEL_W / 2, y + 70, MUTED);
            return;
        }
        guiGraphics.drawString(font, "DIRECT INVITE", left + 12, y + 2, TEXT_DIM, false);

        guiGraphics.fill(left + 12, y + 38, left + PANEL_W - 12, y + 39, LINE);
        guiGraphics.drawString(font, "PROXIMITY SCAN", left + 12, y + 46, TEXT_DIM, false);

        List<String> results = ClientTeamData.scanResults;
        guiGraphics.drawString(font, "RESULTS · " + results.size(), left + 12, y + 90, TEXT_DIM, false);
        int rowY = y + 102;
        if (results.isEmpty()) {
            guiGraphics.drawString(font, "No teamless players in range.", left + 12, rowY, MUTED, false);
        } else {
            for (int i = 0; i < Math.min(results.size(), 4); i++) {
                guiGraphics.fill(left + 12, rowY - 3, left + PANEL_W - 12, rowY + SCAN_ROW_H - 4, SURFACE);
                guiGraphics.drawString(font, results.get(i), left + 18, rowY, TEXT, false);
                rowY += SCAN_ROW_H;
            }
            if (results.size() > 4) {
                guiGraphics.drawString(font, "+" + (results.size() - 4) + " more in range",
                        left + 12, rowY, MUTED, false);
            }
        }

        renderSuggestionDropdown(guiGraphics, mouseX, mouseY);
    }

    private void renderRequests(GuiGraphics guiGraphics) {
        int y = contentTop();
        if (!ClientTeamData.pendingInvite.isEmpty() && !ClientTeamData.inTeam) {
            guiGraphics.drawString(font, "PENDING REQUEST", left + 12, y + 2, TEXT_DIM, false);
            guiGraphics.fill(left + 12, y + 16, left + PANEL_W - 12, y + 72, SURFACE);
            guiGraphics.renderOutline(left + 12, y + 16, PANEL_W - 24, 56, LINE_LIGHT);
            guiGraphics.drawCenteredString(font, Component.literal(ClientTeamData.pendingInvite)
                    .withStyle(ChatFormatting.BOLD), left + PANEL_W / 2, y + 28, TEXT);
            guiGraphics.drawCenteredString(font, "has invited you to join their team",
                    left + PANEL_W / 2, y + 42, TEXT_DIM);
            guiGraphics.drawCenteredString(font, "Accepting links your player data to this team.",
                    left + PANEL_W / 2, y + 56, MUTED);
        } else if (ClientTeamData.inTeam) {
            guiGraphics.drawCenteredString(font, "You are already in a team.", left + PANEL_W / 2, y + 70, MUTED);
        } else {
            guiGraphics.drawCenteredString(font, "No pending requests.", left + PANEL_W / 2, y + 70, MUTED);
        }
    }

    private void renderRules(GuiGraphics guiGraphics) {
        int y = contentTop();
        if (!ClientTeamData.inTeam) {
            guiGraphics.drawCenteredString(font, "Create a team first.", left + PANEL_W / 2, y + 70, MUTED);
            return;
        }
        drawRule(guiGraphics, y + 12, "Friendly Fire", "Team members can damage each other",
                ClientTeamData.friendlyFire);
        drawRule(guiGraphics, y + 38, "See Friendly Invisibles", "Invisible teammates appear as ghosts",
                ClientTeamData.seeInvisibles);
        // Team Banner row: interactive for everyone, so draw the label only
        // (the toggle button renders the VISIBLE/HIDDEN state).
        drawRuleLabel(guiGraphics, y + 64, "Team Banner",
                "Wear a team-colored banner on your back for all to see");

        guiGraphics.fill(left + 12, y + 94, left + PANEL_W - 12, y + 95, LINE);
        if (ClientTeamData.isLeader) {
            guiGraphics.drawString(font, "TEAM ICON URL", left + 12, y + 104, TEXT_DIM, false);
        } else {
            guiGraphics.drawCenteredString(font, "Only the team leader can change the combat rules.",
                    left + PANEL_W / 2, y + 110, MUTED);
        }
    }

    private void drawRule(GuiGraphics guiGraphics, int y, String name, String description, boolean on) {
        drawRuleLabel(guiGraphics, y, name, description);
        if (!ClientTeamData.isLeader) {
            String state = on ? "ON" : "OFF";
            guiGraphics.drawString(font, state, left + PANEL_W - 12 - font.width(state), y + 3,
                    on ? MINT : MUTED, false);
        }
    }

    private void drawRuleLabel(GuiGraphics guiGraphics, int y, String name, String description) {
        guiGraphics.drawString(font, name, left + 12, y, TEXT, false);
        guiGraphics.drawString(font, description, left + 12, y + 10, MUTED, false);
    }

    private void renderSuggestionDropdown(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (inviteBox == null || !inviteBox.isFocused() || suggestions.isEmpty()) {
            return;
        }
        int dropX = inviteBox.getX() - 2;
        int dropY = inviteBox.getY() + 16;
        int dropW = inviteBox.getWidth() + 4;
        int dropH = suggestions.size() * 12 + 2;
        guiGraphics.fill(dropX, dropY, dropX + dropW, dropY + dropH, SURFACE);
        guiGraphics.renderOutline(dropX, dropY, dropW, dropH, LINE_LIGHT);
        for (int i = 0; i < suggestions.size(); i++) {
            int rowY = dropY + 2 + i * 12;
            boolean hovered = mouseX >= dropX && mouseX < dropX + dropW && mouseY >= rowY - 1 && mouseY < rowY + 11;
            if (hovered) {
                guiGraphics.fill(dropX + 1, rowY - 1, dropX + dropW - 1, rowY + 11, SURFACE_HOVER);
            }
            guiGraphics.drawString(font, suggestions.get(i), dropX + 6, rowY + 1,
                    hovered ? MINT : TEXT_DIM, false);
        }
    }

    /** Flat input styling: solid surface + thin line underneath, mint when focused. */
    private void frameEditBox(GuiGraphics guiGraphics, @Nullable EditBox box) {
        if (box == null) {
            return;
        }
        guiGraphics.fill(box.getX() - 2, box.getY() - 3, box.getX() + box.getWidth() + 2,
                box.getY() + box.getHeight() + 1, SURFACE);
        guiGraphics.fill(box.getX() - 2, box.getY() + box.getHeight() + 1,
                box.getX() + box.getWidth() + 2, box.getY() + box.getHeight() + 2,
                box.isFocused() ? MINT : LINE_LIGHT);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && handleSuggestionClick(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && handleTabClick(mouseX, mouseY)) {
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (currentTab == Tab.OVERVIEW && !ClientTeamData.inTeam) {
            for (int i = 0; i < 16; i++) {
                int x = swatchX(i);
                int y = swatchY(i);
                if (mouseX >= x && mouseX < x + 12 && mouseY >= y && mouseY < y + 12) {
                    selectedColor = i;
                    return true;
                }
            }
        } else if (currentTab == Tab.OVERVIEW && ClientTeamData.isLeader) {
            List<TeamPayloads.MemberInfo> members = ClientTeamData.members;
            int listY = memberListTop();
            for (int row = 0; row < visibleMemberRows(); row++) {
                int index = memberScroll + row;
                if (index >= members.size()) {
                    break;
                }
                TeamPayloads.MemberInfo member = members.get(index);
                boolean self = minecraft != null && minecraft.player != null
                        && member.uuid().equals(minecraft.player.getUUID());
                if (!self && isOverKick(mouseX, mouseY, listY + row * MEMBER_ROW_H)) {
                    PacketDistributor.sendToServer(new TeamPayloads.KickMember(member.uuid()));
                    return true;
                }
            }
        }
        return false;
    }

    private boolean handleTabClick(double mouseX, double mouseY) {
        int tabWidth = (PANEL_W - 24) / Tab.values().length;
        for (Tab tab : Tab.values()) {
            int x = left + 12 + tab.ordinal() * tabWidth;
            int y = top + TAB_ROW_Y;
            if (mouseX >= x && mouseX < x + tabWidth && mouseY >= y - 2 && mouseY < y + 12) {
                if (tab != currentTab) {
                    currentTab = tab;
                    memberScroll = 0;
                    rebuildWidgets();
                }
                return true;
            }
        }
        return false;
    }

    private boolean handleSuggestionClick(double mouseX, double mouseY) {
        if (currentTab != Tab.SEARCH || inviteBox == null || !inviteBox.isFocused() || suggestions.isEmpty()) {
            return false;
        }
        int dropX = inviteBox.getX() - 2;
        int dropY = inviteBox.getY() + 16;
        int dropW = inviteBox.getWidth() + 4;
        for (int i = 0; i < suggestions.size(); i++) {
            int rowY = dropY + 2 + i * 12;
            if (mouseX >= dropX && mouseX < dropX + dropW && mouseY >= rowY - 1 && mouseY < rowY + 11) {
                inviteBox.setValue(suggestions.get(i));
                suggestions.clear();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Tab autocompletes to the closest match
        if (keyCode == GLFW.GLFW_KEY_TAB && inviteBox != null && inviteBox.isFocused() && !suggestions.isEmpty()) {
            inviteBox.setValue(suggestions.get(0));
            suggestions.clear();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && inviteBox != null && inviteBox.isFocused()) {
            sendInvite();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (currentTab == Tab.OVERVIEW && ClientTeamData.inTeam
                && mouseX >= left && mouseX <= left + PANEL_W
                && mouseY >= memberListTop() - 4
                && mouseY <= memberListTop() + visibleMemberRows() * MEMBER_ROW_H) {
            int maxScroll = Math.max(0, ClientTeamData.members.size() - visibleMemberRows());
            memberScroll = Mth.clamp(memberScroll - (int) Math.signum(scrollY), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------ autocomplete

    /**
     * Case-insensitive prefix match against the tab list cached by
     * {@code ClientPacketListener} — no packets, no allocation-heavy work;
     * runs only when the input actually changes.
     */
    private void updateSuggestions(String typed) {
        suggestions.clear();
        if (typed.isEmpty() || minecraft == null || minecraft.getConnection() == null || minecraft.player == null) {
            return;
        }
        String lower = typed.toLowerCase(Locale.ROOT);
        for (PlayerInfo online : minecraft.getConnection().getOnlinePlayers()) {
            String name = online.getProfile().getName();
            if (name.equals(minecraft.player.getGameProfile().getName())
                    || ClientTeamData.isMember(online.getProfile().getId())
                    || name.equalsIgnoreCase(typed)) {
                continue;
            }
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                suggestions.add(name);
            }
        }
        suggestions.sort(String.CASE_INSENSITIVE_ORDER);
        while (suggestions.size() > 5) {
            suggestions.remove(suggestions.size() - 1);
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    private int swatchX(int index) {
        return left + 16 + (index % 8) * 36;
    }

    private int swatchY(int index) {
        return contentTop() + 68 + (index / 8) * 18;
    }

    private int memberListTop() {
        return contentTop() + 14;
    }

    private int visibleMemberRows() {
        return 8; // 8 rows end above the disband button
    }

    private int kickX() {
        return left + PANEL_W - 24;
    }

    private boolean isOverKick(double mouseX, double mouseY, int rowY) {
        return mouseX >= kickX() - 3 && mouseX <= kickX() + 9 && mouseY >= rowY - 2 && mouseY <= rowY + 10;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ minimal widget

    /**
     * Flat 1px-outlined button. Neutral buttons use platinum text; mint and coral
     * variants tint the label and, on hover, the outline — nothing else moves.
     */
    private class MinimalButton extends Button {
        private Accent accent;

        MinimalButton(int x, int y, int width, int height, Component message, Accent accent, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.accent = accent;
        }

        void setState(Component message, Accent accent) {
            setMessage(message);
            this.accent = accent;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            boolean lit = isHoveredOrFocused() && active;
            int accentColor = switch (accent) {
                case MINT -> MINT;
                case CORAL -> CORAL;
                case NEUTRAL -> TEXT;
            };
            int fill = lit ? SURFACE_HOVER : SURFACE;
            int outline = lit ? accentColor : LINE_LIGHT;
            guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, fill);
            guiGraphics.renderOutline(getX(), getY(), width, height, outline);
            int textColor = !active ? MUTED : accent == Accent.NEUTRAL ? (lit ? TEXT : TEXT_DIM) : accentColor;
            guiGraphics.drawCenteredString(TeamScreen.this.font, getMessage(),
                    getX() + width / 2, getY() + (height - 8) / 2, textColor);
        }
    }
}
