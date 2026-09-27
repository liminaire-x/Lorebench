/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.client;

import kr.guinnessgroup.lorebench.quest.QuestDoc;
import kr.guinnessgroup.lorebench.quest.QuestState;
import kr.guinnessgroup.lorebench.quest.QuestSyncPayload;
import kr.guinnessgroup.lorebench.quest.Quests;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The quest screen: revealed quests on the left, the chosen one's story, needs
 * (with progress from this player's inventory) and rewards on the right.
 */
public final class QuestScreen extends Screen {

    private static final int LIST_W = 120;
    private static final int ROW_H = 20;
    private static final int PAD = 6;

    private static final int PANEL = 0xE0101010;
    private static final int BORDER = 0xFF505050;
    private static final int SELECTED = 0x40FFFFFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFF909090;
    private static final int LIGHT = 0xFFD0D0D0;
    private static final int GOLD = 0xFFFFD84A;
    private static final int GREEN = 0xFF55FF55;

    private final QuestCard card = new QuestCard();
    private String selectedId;
    private int left;
    private int top;
    private int panelW;
    private int panelH;

    public QuestScreen() {
        super(Component.translatable("lorebench.quests.title"));
    }

    @Override
    protected void init() {
        panelW = Math.min(380, width - 20);
        panelH = Math.min(230, height - 20);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (LorebenchClient.OPEN_QUESTS.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<QuestSyncPayload.Entry> quests = ClientQuests.all();
        int x0 = left + PAD;
        int y0 = listTop();
        if (mouseX >= x0 && mouseX < x0 + LIST_W && mouseY >= y0) {
            int row = (int) ((mouseY - y0) / ROW_H);
            if (row < quests.size()) {
                selectedId = quests.get(row).quest().id();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int listTop() {
        return top + PAD + font.lineHeight + 8;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + panelW + 1, top + panelH + 1, BORDER);
        g.fill(left, top, left + panelW, top + panelH, PANEL);
        g.drawCenteredString(font, title, left + panelW / 2, top + PAD, WHITE);

        List<QuestSyncPayload.Entry> quests = ClientQuests.all();
        if (quests.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("lorebench.quests.none"),
                    left + panelW / 2, top + panelH / 2 - font.lineHeight / 2, GRAY);
            return;
        }
        QuestSyncPayload.Entry selected = quests.get(0);
        for (QuestSyncPayload.Entry e : quests) {
            if (e.quest().id().equals(selectedId)) {
                selected = e;
            }
        }

        LocalPlayer player = minecraft.player;

        // Left: the list.
        int x0 = left + PAD;
        int y = listTop();
        g.enableScissor(x0, y, x0 + LIST_W, top + panelH - PAD);
        for (QuestSyncPayload.Entry e : quests) {
            if (e == selected) {
                g.fill(x0, y, x0 + LIST_W, y + ROW_H, SELECTED);
            }
            g.renderItem(card.icon(e.quest()), x0 + 2, y + 2);
            String name = font.plainSubstrByWidth(e.quest().title(), LIST_W - 24);
            g.drawString(font, name, x0 + 22, y + (ROW_H - font.lineHeight) / 2 + 1, e.state() == QuestState.DONE ? GRAY : WHITE);
            y += ROW_H;
        }
        g.disableScissor();
        int divider = x0 + LIST_W + PAD;
        g.fill(divider, listTop(), divider + 1, top + panelH - PAD, BORDER);

        // Right: the chosen quest.
        QuestDoc.Quest q = selected.quest();
        int dx = divider + PAD + 1;
        int dw = left + panelW - PAD - dx;
        int bottom = top + panelH - PAD;
        // Only an active quest shows progress: one handed in shows what was handed in ("× 10").
        boolean active = selected.state() == QuestState.ACTIVE && player != null;
        Component status = switch (selected.state()) {
            case DONE -> Component.translatable("lorebench.quests.done").withColor(GREEN);
            case WAITING -> Component.translatable("lorebench.quests.waiting").withColor(LIGHT); // no days left shown (0013)
            case READY -> Component.translatable("lorebench.quests.take").withColor(GREEN);
            default -> active && Quests.goalsMet(player.getInventory(), selected.progress(), q, card::condition)
                    ? Component.translatable("lorebench.quests.ready").withColor(GREEN) : null;
        };
        y = listTop();
        g.enableScissor(dx, y, dx + dw, bottom);
        g.drawString(font, q.title(), dx, y, GOLD);
        y += font.lineHeight + 2;
        if (status != null) {
            g.drawString(font, status, dx, y, WHITE);
            y += font.lineHeight + 2;
        }
        if (!q.text().isEmpty()) {
            y += 2;
            for (FormattedCharSequence line : font.split(Component.literal(q.text()), dw)) {
                g.drawString(font, line, dx, y, LIGHT);
                y += font.lineHeight;
            }
        }
        ItemStack hovered = card.needsAndRewards(g, font, q, selected.progress(), active, false, true,
                dx, y, mouseX, mouseY);
        g.disableScissor();
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }
}
