package com.appliedenhancements.client.pattern;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import java.lang.reflect.Constructor;

/** Reflective constructor bridge for private AE2/ExtendedAE terminal row records. */
public final class PatternTerminalRowFactory {
    private final Constructor<?> headerConstructor;
    private final Constructor<?> slotsConstructor;

    private PatternTerminalRowFactory(
            Constructor<?> headerConstructor, Constructor<?> slotsConstructor) {
        this.headerConstructor = headerConstructor;
        this.slotsConstructor = slotsConstructor;
    }

    public static PatternTerminalRowFactory create(String ownerClassName) {
        try {
            ClassLoader loader = PatternTerminalRowFactory.class.getClassLoader();
            Class<?> headerClass = Class.forName(
                    ownerClassName + "$GroupHeaderRow", false, loader);
            Class<?> slotsClass = Class.forName(
                    ownerClassName + "$SlotsRow", false, loader);
            Constructor<?> header = headerClass.getDeclaredConstructor(
                    PatternContainerGroup.class);
            Constructor<?> slots = slotsClass.getDeclaredConstructor(
                    PatternContainerRecord.class, int.class, int.class);
            header.setAccessible(true);
            slots.setAccessible(true);
            return new PatternTerminalRowFactory(header, slots);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot access pattern-terminal row records for " + ownerClassName,
                    exception);
        }
    }

    public Object createHeader(PatternContainerGroup group) {
        try {
            return headerConstructor.newInstance(group);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create pattern-terminal header row", exception);
        }
    }

    public Object createSlots(PatternContainerRecord container, int offset, int slots) {
        try {
            return slotsConstructor.newInstance(container, offset, slots);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create pattern-terminal slots row", exception);
        }
    }
}
