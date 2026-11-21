package com.appliedenhancements.integration.jei;

import java.lang.reflect.Method;
import mezz.jei.common.Internal;
import mezz.jei.common.config.IClientConfig;

/** Bridges JEI's renamed config accessor without linking to either version-specific return type. */
final class JeiClientConfigAccess {
    private JeiClientConfigAccess() {}

    static IClientConfig getClientConfig() {
        try {
            Method getter;
            try {
                getter = Internal.class.getMethod("getClientConfigs");
            } catch (NoSuchMethodException olderJei) {
                getter = Internal.class.getMethod("getJeiClientConfigs");
            }
            Object configs = getter.invoke(null);
            return (IClientConfig) getter.getReturnType()
                    .getMethod("getClientConfig").invoke(configs);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not access JEI's client config", failure);
        }
    }
}
