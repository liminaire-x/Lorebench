/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.client;

import kr.guinnessgroup.lorebench.DialogueLines;
import kr.guinnessgroup.lorebench.npc.NpcEntity;
import kr.guinnessgroup.lorebench.quest.Dialogue;
import kr.guinnessgroup.lorebench.quest.DialogueChoicePayload;
import kr.guinnessgroup.lorebench.quest.DialoguePayload;
import kr.guinnessgroup.lorebench.quest.QuestDoc;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Talking to an NPC (0009). The talk starts with the first thing the player can do
 * (hand in, then a new offer), else with the NPC's greeting; afterwards a list shows
 * everything else the NPC can talk about. Lines show one page at a time; a click,
 * Space or Enter turns the page, and a line's animation plays as its page shows (once,
 * or looped until the page turns), on this screen only (other players don't see this talk).
 * The first page waits while the NPC plays its talk start (0011); a click skips the wait. Accepting and handing in go
 * to the server, which checks them and sends the list back.
 */
final class DialogueScreen extends Screen {

    private static final int PAD = 8;
    private static final int BUTTON_H = 20;
    private static final int PANEL = 0xE0101010;
    private static final int BORDER = 0xFF505050;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int LIGHT = 0xFFD0D0D0;
    private static final int GRAY = 0xFF909090;
    private static final int GOLD = 0xFFFFD84A;

    /** WAIT: only the NPC's name shows while it plays its talk start. */
    private enum Mode { WAIT, PAGES, CARD, LIST }

    private final QuestCard card = new QuestCard();
    private DialoguePayload talk;
    private Mode mode;
    private List<DialogueLines.Line> pages = List.of();
    private int page;
    private Runnable afterPages;
    private DialoguePayload.Entry shown;
    /** A choice went to the server; buttons stay off until it answers. */
    private boolean waiting;

    DialogueScreen(DialoguePayload talk) {
        super(Component.literal(talk.npcName()));
        this.talk = talk;
    }

    /** The server's answer to a choice, or a fresh talk. */
    void update(DialoguePayload fresh) {
        if (fresh.npcEntity() != talk.npcEntity()) {
            setTalking(false);
            talk = fresh;
            setTalking(true);
        }
        talk = fresh;
        waiting = false;
        if (mode == Mode.WAIT) {
            return; // the talk begins with the fresh one when the wait ends
        }
        if (fresh.resume()) {
            say(fresh.said(), this::showList); // e.g. what the NPC says right after an accept
        } else {
            begin();
        }
    }

    @Override
    protected void init() {
        // Also called when the window resizes: keep the talk where it is.
        if (mode == null) {
            setTalking(true);
            if (npcStarting()) {
                mode = Mode.WAIT;
            } else {
                begin();
            }
        } else {
            rebuild();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (mode == Mode.WAIT && !npcStarting()) {
            begin();
        }
    }

    /**
     * The NPC is putting down what it was doing. Only when it is drawn with its model: as
     * Steve it doesn't animate, so its start would never finish.
     */
    private boolean npcStarting() {
        NpcEntity npc = npc();
        return npc != null && NpcGeoModel.isAvailable(npc.model()) && npc.isStartingTalk();
    }

    private void begin() {
        List<DialoguePayload.Entry> entries = talk.entries();
        if (!entries.isEmpty() && entries.get(0).kind() != Dialogue.Kind.ACTIVE) {
            talkAbout(entries.get(0));
        } else {
            say(talk.greeting(), this::showList);
        }
    }

    private void talkAbout(DialoguePayload.Entry entry) {
        say(entry.lines(), () -> showCard(entry));
    }

    private void say(List<DialogueLines.Line> lines, Runnable then) {
        if (lines.isEmpty()) {
            then.run();
            return;
        }
        pages = lines;
        page = 0;
        afterPages = then;
        mode = Mode.PAGES;
        rebuild();
        animate();
    }

    private void nextPage() {
        if (++page >= pages.size()) {
            NpcEntity npc = npc();
            if (npc != null) {
                npc.showLine("", false); // a line looping on the last page stops
            }
            Runnable then = afterPages;
            afterPages = null;
            then.run();
        } else {
            animate();
        }
    }

    /**
     * The shown line's animation, played by the NPC being talked to, on this screen only.
     * Told on every page, so a line looping on the page before stops.
     */
    private void animate() {
        DialogueLines.Line line = pages.get(page);
        NpcEntity npc = npc();
        if (npc != null) {
            npc.showLine(line.animation(), line.play() == DialogueLines.Play.LOOP);
        }
    }

    /** While the screen is open the NPC plays its talk set and turns to this player, on this screen (0011). */
    private void setTalking(boolean talking) {
        NpcEntity npc = npc();
        if (npc != null) {
            npc.setTalking(talking ? minecraft.player : null);
        }
    }

    @Override
    public void removed() {
        // Closed, or replaced by another screen.
        setTalking(false);
        super.removed();
    }

    private NpcEntity npc() {
        return minecraft != null && minecraft.level != null
                && minecraft.level.getEntity(talk.npcEntity()) instanceof NpcEntity npc ? npc : null;
    }

    private void showCard(DialoguePayload.Entry entry) {
        shown = entry;
        mode = Mode.CARD;
        rebuild();
    }

    /** Everything else the NPC can talk about. Nothing left: the talk is over. */
    private void showList() {
        if (talk.entries().isEmpty()) {
            onClose();
            return;
        }
        mode = Mode.LIST;
        rebuild();
    }

    private void choose(DialogueChoicePayload.Action action) {
        waiting = true;
        rebuild();
        PacketDistributor.sendToServer(new DialogueChoicePayload(action, shown.quest().id()));
    }

    // --- layout ---

    private int boxW() {
        return Math.min(420, width - 20);
    }

    private int boxX() {
        return (width - boxW()) / 2;
    }

    /** The bottom box for pages and the list. */
    private int boxH() {
        if (mode == Mode.LIST) {
            return PAD * 2 + (talk.entries().size() + 1) * (BUTTON_H + 2);
        }
        if (mode == Mode.WAIT) {
            return 48;
        }
        int lines = font.split(Component.literal(pages.get(page).text()), boxW() - PAD * 2).size();
        return Math.max(48, PAD * 2 + lines * (font.lineHeight + 2) + 8);
    }

    private int boxY() {
        return height - boxH() - 12;
    }

    private int cardW() {
        return Math.min(300, width - 20);
    }

    private int cardH() {
        QuestDoc.Quest q = shown.quest();
        int text = q.text().isEmpty() || shown.kind() != Dialogue.Kind.OFFER ? 0
                : 4 + font.split(Component.literal(q.text()), cardW() - PAD * 2).size() * font.lineHeight;
        return Math.min(height - 20, PAD * 2 + font.lineHeight + text
                + card.height(font, q, shown.kind() == Dialogue.Kind.OFFER) + 10 + BUTTON_H);
    }

    private void rebuild() {
        clearWidgets();
        if (mode == Mode.CARD) {
            int x = (width - cardW()) / 2;
            int y = (height - cardH()) / 2 + cardH() - PAD - BUTTON_H;
            int half = (cardW() - PAD * 3) / 2;
            switch (shown.kind()) {
                case OFFER -> {
                    button("lorebench.dialogue.accept", x + PAD, y, half, () -> choose(DialogueChoicePayload.Action.ACCEPT));
                    button("lorebench.dialogue.decline", x + PAD * 2 + half, y, half, this::showList);
                }
                case READY -> {
                    button("lorebench.dialogue.hand_in", x + PAD, y, half, () -> choose(DialogueChoicePayload.Action.HAND_IN));
                    button("lorebench.dialogue.later", x + PAD * 2 + half, y, half, this::showList);
                }
                case ACTIVE -> button("lorebench.dialogue.back", x + PAD, y, cardW() - PAD * 2, this::showList);
            }
        } else if (mode == Mode.LIST) {
            int x = boxX() + PAD;
            int y = boxY() + PAD;
            int w = boxW() - PAD * 2;
            for (DialoguePayload.Entry e : talk.entries()) {
                boolean ready = e.kind() == Dialogue.Kind.READY;
                Component label = Component.literal(e.kind() == Dialogue.Kind.OFFER ? "! " : "? ")
                        .withColor(e.kind() == Dialogue.Kind.ACTIVE ? GRAY : GOLD)
                        .append(Component.literal(e.quest().title()).withColor(WHITE));
                Button b = addRenderableWidget(Button.builder(label, btn -> talkAbout(e)).bounds(x, y, w, BUTTON_H).build());
                // In progress with nothing to say: shown, but there is nothing to open (0009).
                b.active = !waiting && (ready || e.kind() == Dialogue.Kind.OFFER || !e.lines().isEmpty());
                y += BUTTON_H + 2;
            }
            button("lorebench.dialogue.goodbye", x, y, w, this::onClose);
        }
    }

    private void button(String key, int x, int y, int w, Runnable onPress) {
        Button b = addRenderableWidget(Button.builder(Component.translatable(key), btn -> onPress.run())
                .bounds(x, y, w, BUTTON_H).build());
        b.active = !waiting;
    }

    // --- input ---

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (mode == Mode.WAIT) {
            begin(); // skip the wait
            return true;
        }
        if (mode == Mode.PAGES) {
            nextPage();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean next = keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER
                || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (mode == Mode.WAIT && next) {
            begin(); // skip the wait
            return true;
        }
        if (mode == Mode.PAGES && next) {
            nextPage();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // --- drawing ---

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No blur or dimming: the NPC stays in view while it talks.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ItemStack hovered = ItemStack.EMPTY;
        if (mode == Mode.CARD) {
            hovered = drawCard(g, mouseX, mouseY);
        } else if (mode != null) {
            drawBox(g);
        }
        super.render(g, mouseX, mouseY, partialTick); // the buttons, on top
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    /** The bottom box with the NPC's name above it: a page of lines, or the list (waiting: the name only). */
    private void drawBox(GuiGraphics g) {
        int x = boxX();
        int y = boxY();
        int w = boxW();
        int h = boxH();
        int nameW = font.width(title) + PAD * 2;
        g.fill(x - 1, y - font.lineHeight - 7, x + nameW + 1, y, BORDER);
        g.fill(x, y - font.lineHeight - 6, x + nameW, y, PANEL);
        g.drawString(font, title, x + PAD, y - font.lineHeight - 2, GOLD);
        if (mode == Mode.WAIT) {
            return;
        }
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, PANEL);
        if (mode == Mode.PAGES) {
            int ty = y + PAD;
            for (FormattedCharSequence line : font.split(Component.literal(pages.get(page).text()), w - PAD * 2)) {
                g.drawString(font, line, x + PAD, ty, WHITE);
                ty += font.lineHeight + 2;
            }
            // Blinks: click for the next page.
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                g.drawString(font, "▼", x + w - PAD - font.width("▼"), y + h - PAD - font.lineHeight + 2, GRAY);
            }
        }
    }

    /** A quest's card: title, (for an offer) its story, then needs and rewards. */
    private ItemStack drawCard(GuiGraphics g, int mouseX, int mouseY) {
        QuestDoc.Quest q = shown.quest();
        int w = cardW();
        int h = cardH();
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, PANEL);
        int ty = y + PAD;
        g.drawString(font, q.title(), x + PAD, ty, GOLD);
        ty += font.lineHeight;
        g.enableScissor(x, ty, x + w, y + h - PAD - BUTTON_H - 4);
        if (shown.kind() == Dialogue.Kind.OFFER && !q.text().isEmpty()) {
            ty += 4;
            for (FormattedCharSequence line : font.split(Component.literal(q.text()), w - PAD * 2)) {
                g.drawString(font, line, x + PAD, ty, LIGHT);
                ty += font.lineHeight;
            }
        }
        // An offer shows what accepting gives and what it will take ("× 10"); a quest in
        // progress shows how far along it is.
        boolean offer = shown.kind() == Dialogue.Kind.OFFER;
        ItemStack hovered = card.needsAndRewards(g, font, q, shown.progress(), !offer, offer,
                x + PAD, ty, mouseX, mouseY);
        g.disableScissor();
        return hovered;
    }
}
