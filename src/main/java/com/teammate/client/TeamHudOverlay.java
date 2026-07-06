package com.teammate.client;

import com.teammate.network.TeamPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Left-side team overlay in the Universal Minimalist Dark palette: flat
 * obsidian card, thin 1px lines, mint/coral/muted status colors. Shows every
 * member with name, health bar and armor, vertically centered. Data comes from
 * the once-per-second TeamState sync.
 */
public final class TeamHudOverlay {
    private static final ResourceLocation HEART_SPRITE = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation ARMOR_SPRITE = ResourceLocation.withDefaultNamespace("hud/armor_full");

    private static final int PANEL_WIDTH = 122;
    private static final int HEADER_HEIGHT = 17;
    private static final int ROW_HEIGHT = 27;

    // minimalist palette
    private static final int BG = 0xD0121212;
    private static final int LINE = 0xFF2C2C2C;
    private static final int TEXT = 0xFFFAFAFA;
    private static final int TEXT_DIM = 0xFFB0B0B0;
    private static final int MINT = 0xFF81C784;
    private static final int AMBER = 0xFFFFB74D;
    private static final int CORAL = 0xFFE57373;
    private static final int MUTED = 0xFF616161;
    private static final int BAR_BG = 0xFF2C2C2C;

    private TeamHudOverlay() {
    }

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientTeamData.inTeam || ClientTeamData.members.isEmpty() || mc.player == null) {
            return;
        }
        if (mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }

        Font font = mc.font;
        List<TeamPayloads.MemberInfo> members = ClientTeamData.members;
        int panelHeight = HEADER_HEIGHT + members.size() * ROW_HEIGHT + 4;
        int x = 4;
        int y = (guiGraphics.guiHeight() - panelHeight) / 2;

        // flat card with a team-colored 2px accent strip
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + panelHeight, BG);
        Integer accentBoxed = ClientTeamData.color.getColor();
        int accent = 0xFF000000 | (accentBoxed != null ? accentBoxed : 0xFFFFFF);
        guiGraphics.fill(x, y, x + 2, y + panelHeight, accent);

        // header: [icon] TEAM NAME, separated by a 1px line
        int headerX = x + 7;
        TeamIconManager.Icon icon = TeamIconManager.get(ClientTeamData.iconUrl);
        if (icon != null) {
            guiGraphics.blit(icon.location(), headerX, y + 4, 8, 8, 0.0F, 0.0F,
                    icon.width(), icon.height(), icon.width(), icon.height());
            headerX += 11;
        }
        guiGraphics.drawString(font,
                Component.literal(truncate(ClientTeamData.teamName, 14))
                        .withStyle(ClientTeamData.color, ChatFormatting.BOLD),
                headerX, y + 4, TEXT, true);
        guiGraphics.fill(x + 5, y + HEADER_HEIGHT - 3, x + PANEL_WIDTH - 5, y + HEADER_HEIGHT - 2, LINE);

        int rowY = y + HEADER_HEIGHT;
        for (TeamPayloads.MemberInfo member : members) {
            boolean self = member.uuid().equals(mc.player.getUUID());
            float pct = member.maxHealth() > 0.0F
                    ? Mth.clamp(member.health() / member.maxHealth(), 0.0F, 1.0F) : 0.0F;

            // status dot: mint online, muted offline
            guiGraphics.fill(x + 6, rowY + 2, x + 9, rowY + 5, member.online() ? MINT : MUTED);
            String name = truncate(member.name(), 12) + (self ? " (you)" : "");
            guiGraphics.drawString(font, name, x + 12, rowY, member.online() ? TEXT : MUTED, true);
            if (member.leader()) {
                guiGraphics.fill(x + PANEL_WIDTH - 8, rowY + 1, x + PANEL_WIDTH - 5, rowY + 4, MINT);
            }

            if (member.online()) {
                guiGraphics.blitSprite(HEART_SPRITE, x + 11, rowY + 9, 9, 9);
                guiGraphics.drawString(font, String.format("%.0f", member.health()), x + 22, rowY + 10,
                        TEXT_DIM, false);
                guiGraphics.blitSprite(ARMOR_SPRITE, x + 62, rowY + 9, 9, 9);
                guiGraphics.drawString(font, Integer.toString(member.armor()), x + 73, rowY + 10,
                        TEXT_DIM, false);

                int barX = x + 11;
                int barY = rowY + 20;
                int barWidth = PANEL_WIDTH - 20;
                guiGraphics.fill(barX, barY, barX + barWidth, barY + 3, BAR_BG);
                int fillColor = pct > 0.5F ? MINT : pct > 0.25F ? AMBER : CORAL;
                guiGraphics.fill(barX, barY, barX + Math.round(barWidth * pct), barY + 3, fillColor);
            } else {
                guiGraphics.drawString(font, "offline", x + 11, rowY + 10, MUTED, false);
            }
            rowY += ROW_HEIGHT;
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
