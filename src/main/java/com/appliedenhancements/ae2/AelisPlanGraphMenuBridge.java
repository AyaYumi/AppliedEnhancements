package com.appliedenhancements.ae2;

import com.appliedenhancements.plan.AelisPlanGraph;

/** Internal menu-scoped, on-demand graph transfer. Null means no response yet. */
public interface AelisPlanGraphMenuBridge {
    AelisPlanGraph appliedenhancements$getPlanGraph();
    void appliedenhancements$requestPlanGraph();
    void appliedenhancements$sendPlanGraph(int request);
    void appliedenhancements$acceptPlanGraph(int request, AelisPlanGraph graph);
}
