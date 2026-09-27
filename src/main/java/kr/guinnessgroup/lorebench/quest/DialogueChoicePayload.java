/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import kr.guinnessgroup.lorebench.Lorebench;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Player → server: the player accepted or turned down an offer, or handed a quest in, in
 * the dialogue they have open. The server checks everything again before doing it
 * ({@link Dialogues}). Registered with {@link DialoguePayload#register}.
 */
public record DialogueChoicePayload(Action action, String questId) implements CustomPacketPayload {

    public enum Action {
        ACCEPT,
        DECLINE,
        HAND_IN
    }

    public static final Type<DialogueChoicePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Lorebench.MODID, "dialogue_choice"));

    public static final StreamCodec<FriendlyByteBuf, DialogueChoicePayload> CODEC =
            StreamCodec.ofMember(DialogueChoicePayload::write, DialogueChoicePayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(questId);
    }

    private static DialogueChoicePayload read(FriendlyByteBuf buf) {
        return new DialogueChoicePayload(buf.readEnum(Action.class), buf.readUtf());
    }
}
