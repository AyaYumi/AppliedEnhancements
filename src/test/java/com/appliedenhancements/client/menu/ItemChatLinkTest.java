package com.appliedenhancements.client.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

class ItemChatLinkTest {
    @Test
    void decoratesSharedItemWithoutChangingVisibleText() {
        Component original = Component.literal(
                "<Dev> [Unobtainium Block] allthemodium:unobtainium_block");

        Component decorated = ItemChatLink.decorate(original).orElseThrow();
        assertEquals(original.getString(), decorated.getString());
        Component link = clickablePart(decorated).orElseThrow();
        assertEquals(
                "[Unobtainium Block] allthemodium:unobtainium_block",
                link.getString());
        assertEquals(ClickEvent.Action.RUN_COMMAND,
                link.getStyle().getClickEvent().getAction());
        assertEquals(
                "/appliedenhancements_jei_search "
                        + "allthemodium:unobtainium_block",
                link.getStyle().getClickEvent().getValue());
        assertTrue(link.getStyle().isUnderlined());
    }

    @Test
    void preservesSurroundingStylesAndSupportsLocalizedNames() {
        Component original = Component.empty()
                .append(Component.literal("<Dev> ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(
                        "看看 [难得素块] allthemodium:unobtainium_block !")
                        .withStyle(ChatFormatting.WHITE));

        Component decorated = ItemChatLink.decorate(original).orElseThrow();
        assertEquals(original.getString(), decorated.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY),
                decorated.toFlatList().getFirst().getStyle().getColor());
        assertTrue(clickablePart(decorated).isPresent());
    }

    @Test
    void ignoresOrdinaryOrInvalidChatText() {
        assertTrue(ItemChatLink.decorate(
                Component.literal("ordinary chat minecraft:stone")).isEmpty());
        assertTrue(ItemChatLink.decorate(
                Component.literal("[Stone] INVALID:Stone")).isEmpty());
    }

    private static Optional<Component> clickablePart(Component component) {
        return component.toFlatList().stream()
                .filter(part -> part.getStyle().getClickEvent() != null)
                .findFirst();
    }
}
