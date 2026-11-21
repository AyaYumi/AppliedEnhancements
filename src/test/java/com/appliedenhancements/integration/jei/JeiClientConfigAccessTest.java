package com.appliedenhancements.integration.jei;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "appliedenhancements.test.jei", matches = "true")
class JeiClientConfigAccessTest {
    @Test
    void readsTheInstalledJeiConfigWithoutLinkingToItsVersionSpecificAccessor() throws Exception {
        // Exercise the real JEI API selected by -Pjei_version, without starting Minecraft.
        var internal = Class.forName("mezz.jei.common.Internal");
        var setter = Arrays.stream(internal.getMethods())
                .filter(method -> method.getName().equals("setClientConfigs")
                        || method.getName().equals("setJeiClientConfigs"))
                .findFirst().orElseThrow();
        var optionalGetter = internal.getMethod("getOptional" + setter.getName().substring(3));
        Object previous = ((Optional<?>) optionalGetter.invoke(null)).orElse(null);

        var configType = Class.forName("mezz.jei.common.config.IClientConfig");
        Object expected = Proxy.newProxyInstance(
                configType.getClassLoader(), new Class<?>[] { configType },
                (proxy, method, args) -> {
                    throw new AssertionError("Config values should not be read during lookup");
                });
        var configsType = setter.getParameterTypes()[0];
        Object configs = Proxy.newProxyInstance(
                configsType.getClassLoader(), new Class<?>[] { configsType },
                (proxy, method, args) -> {
                    if (method.getName().equals("getClientConfig")) return expected;
                    throw new AssertionError("Unexpected JEI config method: " + method.getName());
                });

        try {
            setter.invoke(null, configs);
            var adapter = Class.forName("com.appliedenhancements.integration.jei.JeiClientConfigAccess");
            assertSame(expected, adapter.getDeclaredMethod("getClientConfig").invoke(null));
        } finally {
            setter.invoke(null, previous);
        }
    }
}
