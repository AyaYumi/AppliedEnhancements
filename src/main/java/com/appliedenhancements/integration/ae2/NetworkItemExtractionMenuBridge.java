package com.appliedenhancements.integration.ae2;

/** Server-side bridge for an exact terminal extraction into player inventory. */
public interface NetworkItemExtractionMenuBridge {
    long appliedenhancements$extractNetworkItem(long serial, long amount);
}
