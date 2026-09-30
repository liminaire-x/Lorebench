/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.client;

import kr.guinnessgroup.lorebench.Cues;
import kr.guinnessgroup.lorebench.DialogueLines;
import kr.guinnessgroup.lorebench.npc.NpcDoc;
import kr.guinnessgroup.lorebench.npc.NpcEntity;
import kr.guinnessgroup.lorebench.quest.Dialogue;
import kr.guinnessgroup.lorebench.quest.DialogueChoicePayload;
import kr.guinnessgroup.lorebench.quest.DialoguePayload;
import kr.guinnessgroup.lorebench.quest.QuestDoc;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Talking to an NPC (0009). The talk starts with the first thing the player can do
 * (hand in, then a new offer), else with the NPC's greeting; afterwards a list shows
 * everything else the NPC can talk about. Lines show one page at a time, typing out with
 * their cues (0014); a click, Space or Enter shows the rest of the page, then turns it. A
 * line's animation plays as its page shows (once, or looped until the page turns), on this
 * screen only (other players don't see this talk).
 * The first page waits while the NPC plays its talk start (0011); a click skips the wait. Accepting, declining,
 * handing in and taking the rewards after a wait (0013) go to the server, which checks them and sends back what
 * the NPC says to that and the list. A stage with nothing to hand in or get is just talked through (0015).
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
    /** A letter's sound, softer than the game's own sounds: it plays many times a second. */
    private static final float VOICE_VOLUME = 0.5F;

    /**
     * WAIT: nothing shows while the NPC plays its talk start (the user's choice; a click still skips it).
     * SENT: a talk-only stage went to the server; nothing shows until it answers.
     */
    private enum Mode { WAIT, PAGES, CARD, LIST, SENT }

    private final QuestCard card = new QuestCard();
    private DialoguePayload talk;
    private Mode mode;
    private List<DialogueLines.Line> pages = List.of();
    private int page;
    /** The shown page typing out (0014). */
    private Typing typing;
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
        if (mode == Mode.PAGES && typing != null) {
            typing.tick();
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
        if (!entries.isEmpty() && entries.get(0).kind().startsTalk()) {
            talkAbout(entries.get(0));
        } else {
            say(talk.greeting(), this::showList);
        }
    }

    private void talkAbout(DialoguePayload.Entry entry) {
        say(entry.lines(), () -> {
            if (talkOnly(entry)) {
                // Nothing to hand in or get: the talk itself is the stage (0015).
                shown = entry;
                mode = Mode.SENT;
                choose(DialogueChoicePayload.Action.HAND_IN);
            } else {
                showCard(entry);
            }
        });
    }

    /** A stage that asks for nothing, whose hand-in gives nothing: there is no card to show. */
    private static boolean talkOnly(DialoguePayload.Entry entry) {
        QuestDoc.Stage stage = entry.quest().current();
        return entry.kind() == Dialogue.Kind.READY && stage != null && stage.goals().isEmpty()
                && entry.quest().rewards().isEmpty();
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

    /** A click, Space or Enter: the rest of a page still typing, else the next page. */
    private void nextPage() {
        if (typing != null && !typing.done()) {
            typing.finish();
            return;
        }
        if (++page >= pages.size()) {
            typing = null;
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
     * Told on every page, so a line looping on the page before stops. Then the page starts
     * typing, and its cues play theirs as the typing reaches them.
     */
    private void animate() {
        DialogueLines.Line line = pages.get(page);
        NpcEntity npc = npc();
        if (npc != null) {
            npc.showLine(line.animation(), line.play() == DialogueLines.Play.LOOP);
        }
        typing = new Typing(line.text(), this::cue, this::letterSound);
    }

    /**
     * The NPC's voice for one letter (0014), on this screen only and from nowhere in the world,
     * under the game's Voice/Speech volume. An unknown sound plays nothing (the game logs it).
     */
    private void letterSound() {
        NpcDoc.Voice voice = talk.voice();
        ResourceLocation sound = voice.isEmpty() ? null : ResourceLocation.tryParse(voice.sound());
        if (sound != null && minecraft != null) {
            minecraft.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.VOICE, VOICE_VOLUME, voice.pitch(),
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
        }
    }

    private void cue(Cues.Animate cue) {
        NpcEntity npc = npc();
        if (npc != null) {
            npc.showLine(cue.animation(), cue.loop());
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

    /**
     * Under the card's title: the quest's story for an offer, else what the stage is (0015): its ready text
     * when it can be handed in, as the quest screen says then.
     */
    private String cardText() {
        QuestDoc.Quest q = shown.quest();
        if (shown.kind() == Dialogue.Kind.OFFER) {
            return q.text();
        }
        QuestDoc.Stage stage = q.current();
        if (stage == null) {
            return "";
        }
        return shown.kind() == Dialogue.Kind.READY ? stage.doneText() : stage.text();
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
        // Sized for the whole page, so the box doesn't grow as it types.
        int lines = font.split(Component.literal(Cues.plain(pages.get(page).text())), boxW() - PAD * 2).size();
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
        String text = cardText();
        int textH = text.isEmpty() ? 0 : 4 + font.split(Component.literal(text), cardW() - PAD * 2).size() * font.lineHeight;
        int status = shown.kind() == Dialogue.Kind.WAITING ? 2 + font.lineHeight : 0;
        return Math.min(height - 20, PAD * 2 + font.lineHeight + status + textH
                + card.height(font, q, shown.kind() == Dialogue.Kind.OFFER, shown.kind() != Dialogue.Kind.TAKE) + 10 + BUTTON_H);
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
                    button("lorebench.dialogue.decline", x + PAD * 2 + half, y, half, () -> choose(DialogueChoicePayload.Action.DECLINE));
                }
                case READY -> {
                    button(handInKey(shown.quest().current()), x + PAD, y, half,
                            () -> choose(DialogueChoicePayload.Action.HAND_IN));
                    button("lorebench.dialogue.later", x + PAD * 2 + half, y, half, this::showList);
                }
                // The wait is over: taking the rewards is handing in again, the server knows which (0013).
                case TAKE -> {
                    button("lorebench.dialogue.take", x + PAD, y, half, () -> choose(DialogueChoicePayload.Action.HAND_IN));
                    button("lorebench.dialogue.later", x + PAD * 2 + half, y, half, this::showList);
                }
                case ACTIVE, WAITING -> button("lorebench.dialogue.back", x + PAD, y, cardW() - PAD * 2, this::showList);
            }
        } else if (mode == Mode.LIST) {
            int x = boxX() + PAD;
            int y = boxY() + PAD;
            int w = boxW() - PAD * 2;
            for (DialoguePayload.Entry e : talk.entries()) {
                boolean pending = e.kind() == Dialogue.Kind.ACTIVE || e.kind() == Dialogue.Kind.WAITING;
                Component label = Component.literal(e.kind() == Dialogue.Kind.OFFER ? "! " : "? ")
                        .withColor(pending ? GRAY : GOLD)
                        .append(Component.literal(e.quest().title()).withColor(WHITE));
                Button b = addRenderableWidget(Button.builder(label, btn -> talkAbout(e)).bounds(x, y, w, BUTTON_H).build());
                // In progress or waiting with nothing to say: shown, but there is nothing to open (0009).
                b.active = !waiting && (!pending || !e.lines().isEmpty());
                y += BUTTON_H + 2;
            }
            button("lorebench.dialogue.goodbye", x, y, w, this::onClose);
        }
    }

    /**
     * The hand-in button of a stage (0015): Show when it only shows items and takes none, Take when it
     * asks for nothing but gives the rewards, else Hand over.
     */
    private static String handInKey(QuestDoc.Stage stage) {
        if (stage == null) {
            return "lorebench.dialogue.hand_in";
        }
        if (stage.goals().isEmpty()) {
            return "lorebench.dialogue.take";
        }
        boolean shows = stage.goals().stream().anyMatch(QuestDoc.Goal::keep);
        return shows && stage.goals().stream().noneMatch(QuestDoc.Goal::takes)
                ? "lorebench.dialogue.show" : "lorebench.dialogue.hand_in";
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
        } else if (mode == Mode.PAGES || mode == Mode.LIST) {
            drawBox(g);
        }
        super.render(g, mouseX, mouseY, partialTick); // the buttons, on top
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    /** The bottom box with the NPC's name above it: a page of lines, or the list. */
    private void drawBox(GuiGraphics g) {
        int x = boxX();
        int y = boxY();
        int w = boxW();
        int h = boxH();
        int nameW = font.width(title) + PAD * 2;
        g.fill(x - 1, y - font.lineHeight - 7, x + nameW + 1, y, BORDER);
        g.fill(x, y - font.lineHeight - 6, x + nameW, y, PANEL);
        g.drawString(font, title, x + PAD, y - font.lineHeight - 2, GOLD);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, PANEL);
        if (mode == Mode.PAGES && typing != null) {
            // The page is wrapped whole, then each row shows the letters typed so far, so a word
            // never jumps to the next row as it types. Wrapping may drop the space at a break, so
            // each row is found in the page from where the last one ended.
            String plain = typing.plain();
            int from = 0;
            int ty = y + PAD;
            for (FormattedText row : font.getSplitter().splitLines(plain, w - PAD * 2, Style.EMPTY)) {
                String s = row.getString();
                int start = Math.max(from, plain.indexOf(s, from));
                int visible = Math.clamp(typing.shown() - start, 0, s.length());
                g.drawString(font, s.substring(0, visible), x + PAD, ty, WHITE);
                from = start + s.length();
                ty += font.lineHeight + 2;
            }
            // Blinks once the page is all there: click for the next page.
            if (typing.done() && (System.currentTimeMillis() / 500) % 2 == 0) {
                g.drawString(font, "▼", x + w - PAD - font.width("▼"), y + h - PAD - font.lineHeight + 2, GRAY);
            }
        }
    }

    /** A quest's card: title, its story for an offer (else what the stage is), then needs and rewards. */
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
        if (shown.kind() == Dialogue.Kind.WAITING) {
            ty += 2;
            g.drawString(font, Component.translatable("lorebench.quests.waiting"), x + PAD, ty, LIGHT);
            ty += font.lineHeight;
        }
        g.enableScissor(x, ty, x + w, y + h - PAD - BUTTON_H - 4);
        String text = cardText();
        if (!text.isEmpty()) {
            ty += 4;
            for (FormattedCharSequence line : font.split(Component.literal(text), w - PAD * 2)) {
                g.drawString(font, line, x + PAD, ty, LIGHT);
                ty += font.lineHeight;
            }
        }
        // An offer shows what accepting gives and what it will take ("× 10"); a quest in
        // progress shows how far along it is. One handed in shows what was handed in ("× 10")
        // while waiting, and only the rewards when they can be taken (0013).
        boolean offer = shown.kind() == Dialogue.Kind.OFFER;
        boolean progress = shown.kind() == Dialogue.Kind.READY || shown.kind() == Dialogue.Kind.ACTIVE;
        ItemStack hovered = card.needsAndRewards(g, font, q, shown.progress(), progress, offer,
                shown.kind() != Dialogue.Kind.TAKE, x + PAD, ty, mouseX, mouseY);
        g.disableScissor();
        return hovered;
    }
}
