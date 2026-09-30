/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A stage's needs were just met (0015 "다 채웠을 때 문장"): the quest's icon and title, and where to go now,
 * with a small bell, looking like an advancement's. A line too long for one row shows after the title,
 * the way an advancement's long title does.
 */
final class ReadyToast implements Toast {

    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final int DISPLAY_TIME = 5000;
    private static final int TEXT_W = 125;
    private static final int GOLD = 0xFFFFD84A;

    private final ItemStack icon;
    private final String title;
    private final Component text;
    private boolean rang;

    ReadyToast(ItemStack icon, String title, String text) {
        this.icon = icon;
        this.title = title;
        this.text = Component.literal(text);
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
        Font font = toasts.getMinecraft().font;
        g.blitSprite(BACKGROUND, 0, 0, width(), height());
        String name = font.plainSubstrByWidth(title, TEXT_W);
        List<FormattedCharSequence> lines = font.split(text, TEXT_W);
        if (lines.size() == 1) {
            g.drawString(font, name, 30, 7, GOLD, false);
            g.drawString(font, lines.getFirst(), 30, 18, 0xFFFFFFFF, false);
        } else if (time < 1500L) {
            int alpha = Mth.floor(Mth.clamp((1500L - time) / 300.0F, 0.0F, 1.0F) * 255.0F) << 24 | 0x04000000;
            g.drawString(font, name, 30, 11, GOLD & 0xFFFFFF | alpha, false);
        } else {
            int alpha = Mth.floor(Mth.clamp((time - 1500L) / 300.0F, 0.0F, 1.0F) * 252.0F) << 24 | 0x04000000;
            int y = height() / 2 - lines.size() * font.lineHeight / 2;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, 30, y, 0xFFFFFF | alpha, false);
                y += font.lineHeight;
            }
        }
        if (!rang && time > 0L) {
            rang = true;
            toasts.getMinecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0F));
        }
        g.renderFakeItem(icon, 8, 8);
        return time >= DISPLAY_TIME * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
