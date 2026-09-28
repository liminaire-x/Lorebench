/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.npc;

import kr.guinnessgroup.lorebench.Folders.Folder;
import kr.guinnessgroup.lorebench.Ids;
import kr.guinnessgroup.lorebench.Speech;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The saved NPC document ({@code npcs.json}): every NPC definition, authored in
 * the editor. A definition is placed in worlds any number of times; placements are
 * records, not part of this document. See docs/decisions/0002-npc.md.
 */
public record NpcDoc(List<Folder> folders, List<NpcDef> npcs) {

    public static final NpcDoc EMPTY = new NpcDoc(List.of(), List.of());

    /**
     * @param id    stable; graphs refer to it
     * @param name  shown above the NPC
     * @param model GeckoLib model name, or "" for the default look. Files come from
     *              resource packs: {@code assets/lorebench/geo/npc/<model>.geo.json},
     *              {@code animations/npc/<model>.animation.json}, {@code textures/npc/<model>.png}
     * @param idle  animation looped while nothing else plays, or ""
     * @param folder the editor folder id it sits in, or "" for the top ({@link kr.guinnessgroup.lorebench.Folders})
     * @param greeting what it says when the player has nothing to do with it, one page per line, or
     *                 groups of lines picked by where the player is with quests ({@link Speech}, 0012)
     *                 ({@link kr.guinnessgroup.lorebench.DialogueLines}, docs/decisions/0009-quest-workbench.md)
     * @param talk  how it moves while someone talks to it ({@link Talk#NONE}: keeps its idle)
     * @param voice the sound its letters make as its lines type out ({@link Voice#NONE}: silent)
     */
    public record NpcDef(String id, String name, String model, String idle, String folder, Speech greeting,
                         Talk talk, Voice voice) {

        public NpcDef(String id, String name) {
            this(id, name, "", "", "", Speech.NONE, Talk.NONE, Voice.NONE);
        }

        public NpcDef(String id, String name, String model, String idle) {
            this(id, name, model, idle, "", Speech.NONE, Talk.NONE, Voice.NONE);
        }
    }

    /**
     * An NPC's voice (docs/decisions/0014-typing.md): a sound played for the letters of its
     * lines, on the talking player's screen only. Any sound name the client knows: the game's,
     * or one a resource pack adds.
     *
     * @param sound a sound name like {@code minecraft:block.note_block.bass}, or "" for none
     * @param pitch 0.5 to 2 (the game plays nothing outside that), 1 as recorded
     */
    public record Voice(String sound, float pitch) {

        public static final Voice NONE = new Voice("", 1);

        public boolean isEmpty() {
            return sound.isEmpty();
        }
    }

    /**
     * The talk set (docs/decisions/0011-talk-gestures.md): played once when a talk opens,
     * looped while it lasts, played once when it closes. Each is an animation name or "".
     */
    public record Talk(String start, String loop, String end) {

        public static final Talk NONE = new Talk("", "", "");

        public boolean isEmpty() {
            return start.isEmpty() && loop.isEmpty() && end.isEmpty();
        }
    }

    /**
     * A problem for each quest a greeting's condition names that does not exist, or stage its quest does not have.
     *
     * @param stages every quest's id → its stage ids
     */
    public List<String> questErrors(Map<String, ? extends Collection<String>> stages) {
        List<String> errors = new ArrayList<>();
        for (NpcDef n : npcs) {
            for (String problem : n.greeting().namingErrors(stages)) {
                errors.add(Ids.named("NPC", n.id(), n.name()) + " greeting: " + problem);
            }
        }
        return errors;
    }

    public NpcDef find(String id) {
        for (NpcDef n : npcs) {
            if (n.id().equals(id)) {
                return n;
            }
        }
        return null;
    }
}
