package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.selectors.ElementNode;
import org.spongepowered.asm.mixin.injection.selectors.ITargetSelector;
import org.spongepowered.asm.mixin.injection.selectors.TargetSelector;

class ForgeSubmitSelectorTest {
    @Test
    void absentOverloadNeverMatchesTheOtherSignatureDuringPermissiveRemap() throws IOException {
        var target = new ClassNode();
        target.name = "appeng/menu/me/crafting/CraftConfirmMenu";
        var upstream = new MethodNode(Opcodes.ACC_PUBLIC, "startJob", "()V", null, null);
        var uelm = new MethodNode(Opcodes.ACC_PUBLIC, "startJob", "(Z)V", null, null);
        var noArgs = selector("appliedenhancements$ownReservationThroughoutStart");
        var withFlag = selector("appliedenhancements$ownReservationThroughoutUelmStart");
        assertTrue(noArgs.match(ElementNode.of(target, upstream)).isExactMatch());
        assertFalse(noArgs.match(ElementNode.of(target, uelm)).isExactMatch());
        assertTrue(withFlag.match(ElementNode.of(target, uelm)).isExactMatch());
        assertFalse(withFlag.match(ElementNode.of(target, upstream)).isExactMatch());
    }

    private static ITargetSelector selector(String name) throws IOException {
        var mixin = new ClassNode();
        try (var input = ForgeSubmitSelectorTest.class.getResourceAsStream(
                "/com/appliedenhancements/mixin/CraftConfirmMenuMixin.class")) {
            new ClassReader(input).accept(mixin, ClassReader.SKIP_CODE);
        }
        var method = mixin.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst().orElseThrow();
        var annotation = method.visibleAnnotations.stream()
                .filter(candidate -> candidate.desc.endsWith("/WrapMethod;")).findFirst().orElseThrow();
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals("method")) {
                String expression = (String) ((List<?>) annotation.values.get(i + 1)).get(0);
                return TargetSelector.parse(expression, null).validate()
                        .configure(ITargetSelector.Configure.PERMISSIVE);
            }
        }
        throw new AssertionError("Missing submit selector");
    }
}
