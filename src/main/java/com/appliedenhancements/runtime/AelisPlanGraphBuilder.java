package com.appliedenhancements.runtime;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.appliedenhancements.api.AelisExactCraftingPlanApi;
import com.appliedenhancements.plan.AelisPlanGraph;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Reads only scheduled patterns. Never runs the planner or changes its task/input counts. */
public final class AelisPlanGraphBuilder {
    private final LinkedHashMap<AEKey, Integer> ids = new LinkedHashMap<>();
    private final LinkedHashSet<AelisPlanGraph.Edge> edges = new LinkedHashSet<>();
    private boolean complete = true;

    private AelisPlanGraphBuilder() {}

    public static AelisPlanGraph build(ICraftingPlan plan) {
        var builder = new AelisPlanGraphBuilder();
        builder.id(plan.finalOutput().what());
        int patterns = 0;
        int inspected = 0;
        tasks:
        for (var task : AelisExactCraftingPlanApi.getPatternTimes(plan).entrySet()) {
            if (task.getValue().signum() <= 0) continue;
            if (++patterns > AelisPlanGraph.MAX_EDGES) { builder.complete = false; break; }
            var outputs = task.getKey().getOutputs();
            var inputs = task.getKey().getInputs();
            if (outputs.size() > AelisPlanGraph.MAX_NODES || inputs.length > AelisPlanGraph.MAX_NODES) {
                builder.complete = false;
                continue;
            }
            for (var output : outputs) {
                if (output == null || output.amount() <= 0) continue;
                int consumer = builder.id(output.what());
                for (var input : inputs) {
                    var choices = input.getPossibleInputs();
                    int count = Math.min(choices.length, AelisPlanGraph.MAX_NODES);
                    if (count != choices.length) builder.complete = false;
                    for (int i = 0; i < count; i++) {
                        if (++inspected > 32_768 || builder.edges.size() >= AelisPlanGraph.MAX_EDGES) {
                            builder.complete = false;
                            break tasks;
                        }
                        var choice = choices[i];
                        if (choice == null || choice.amount() <= 0) continue;
                        int dependency = builder.id(choice.what());
                        if (consumer < 0 || dependency < 0) continue;
                        builder.edges.add(new AelisPlanGraph.Edge(consumer, dependency, choices.length > 1));
                    }
                }
            }
        }
        return new AelisPlanGraph(new ArrayList<>(builder.ids.keySet()),
                new ArrayList<>(builder.edges), 0, builder.complete);
    }

    private int id(AEKey key) {
        var previous = ids.get(key);
        if (previous != null) return previous;
        if (ids.size() >= AelisPlanGraph.MAX_NODES) { complete = false; return -1; }
        int id = ids.size();
        ids.put(key, id);
        return id;
    }
}
