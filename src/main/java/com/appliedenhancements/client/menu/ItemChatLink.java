package com.appliedenhancements.client.menu;

import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/** Converts shared item text into a harmless client-side JEI search link. */
final class ItemChatLink {
    static final String SEARCH_COMMAND = "appliedenhancements_jei_search";
    private static final Pattern ITEM_LINK = Pattern.compile(
            "\\[([^\\]\\r\\n]{1,128})]\\s+"
                    + "([a-z0-9_.-]+:[a-z0-9_./-]+)");

    private ItemChatLink() {
    }

    static Optional<Component> decorate(Component message) {
        MutableComponent result = Component.empty();
        boolean changed = false;
        for (Component flat : message.toFlatList()) {
            Decoration decoration = decorateText(
                    flat.getString(), flat.getStyle());
            result.append(decoration.component());
            changed |= decoration.changed();
        }
        return changed ? Optional.of(result) : Optional.empty();
    }

    private static Decoration decorateText(String text, Style baseStyle) {
        var matcher = ITEM_LINK.matcher(text);
        MutableComponent result = Component.empty();
        int copiedUntil = 0;
        boolean changed = false;
        while (matcher.find()) {
            ResourceLocation id = ResourceLocation.tryParse(matcher.group(2));
            if (id == null || !id.toString().equals(matcher.group(2))) {
                continue;
            }
            if (matcher.start() > copiedUntil) {
                result.append(Component.literal(
                        text.substring(copiedUntil, matcher.start()))
                        .setStyle(baseStyle));
            }
            String command = "/" + SEARCH_COMMAND + " " + id;
            Style linkStyle = baseStyle
                    .withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withInsertion(id.toString())
                    .withClickEvent(new ClickEvent(
                            ClickEvent.Action.RUN_COMMAND, command));
            result.append(Component.literal(matcher.group()).setStyle(linkStyle));
            copiedUntil = matcher.end();
            changed = true;
        }
        if (!changed) {
            return new Decoration(Component.literal(text).setStyle(baseStyle), false);
        }
        if (copiedUntil < text.length()) {
            result.append(Component.literal(text.substring(copiedUntil))
                    .setStyle(baseStyle));
        }
        return new Decoration(result, true);
    }

    private record Decoration(Component component, boolean changed) {
    }
}
