package com.appliedenhancements.plan;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.test.TestAEKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class AelisPlanGraphTest {
    @Test
    void snapshotsCollectionsAndValidatesBoundsAndIndexes() {
        var key = new TestAEKey("iron");
        var keys = new java.util.ArrayList<AEKey>();
        keys.add(key);
        var graph = new AelisPlanGraph(keys, List.of(), 0, true);
        keys.clear();
        assertEquals(List.of(key), graph.keys());
        assertThrows(UnsupportedOperationException.class, () -> graph.keys().clear());
        assertThrows(IllegalArgumentException.class, () ->
                new AelisPlanGraph(List.of(key), List.of(new AelisPlanGraph.Edge(1, 0, false)), 0, true));
        assertEquals(AelisPlanGraph.EMPTY, new AelisPlanGraph(List.of(), List.of(), -1, true));
    }

    @Test
    void requestAndResponseIdsRejectNegativeOrUncorrelatableValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new com.appliedenhancements.network.AelisPlanGraphRequest(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new com.appliedenhancements.network.AelisPlanGraphRequest(-1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new com.appliedenhancements.network.AelisPlanGraphPayload(1, 0,
                        AelisPlanGraph.EMPTY));
        assertThrows(NullPointerException.class,
                () -> new com.appliedenhancements.network.AelisPlanGraphPayload(1, 1, null));
    }
}
