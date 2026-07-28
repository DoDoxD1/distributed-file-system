package com.distributedfs.placement;

import com.distributedfs.error.ValidationException;
import com.distributedfs.service.StorageNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses replicas across failure domains before falling back to same-domain nodes.
 */
public class RackAwarePlacementStrategy {

    /**
     * Selects candidate nodes for chunk placement.
     *
     * @param nodes all known storage nodes
     * @param replicaCount required number of target nodes
     * @param excludeNodeIds existing node IDs to avoid
     * @return selected nodes in deterministic order
     */
    public List<StorageNode> chooseNodes(
        Collection<StorageNode> nodes,
        int replicaCount,
        Set<String> excludeNodeIds
    ) {
        if (replicaCount <= 0) {
            throw new ValidationException("replicaCount must be positive, got " + replicaCount);
        }

        List<StorageNode> healthyCandidates = nodes.stream()
            .filter(StorageNode::isHealthy)
            .filter(node -> !excludeNodeIds.contains(node.nodeId()))
            .sorted(Comparator.comparing(StorageNode::nodeId))
            .toList();

        if (healthyCandidates.size() < replicaCount) {
            throw new ValidationException(
                "Not enough healthy nodes for requested replicas: "
                    + healthyCandidates.size() + " < " + replicaCount
            );
        }

        Map<String, List<StorageNode>> byDomain = new HashMap<>();
        for (StorageNode candidate : healthyCandidates) {
            byDomain.computeIfAbsent(candidate.failureDomain(), ignored -> new ArrayList<>())
                .add(candidate);
        }

        List<StorageNode> selected = new ArrayList<>();
        Set<String> selectedNodeIds = new HashSet<>();

        List<String> domains = byDomain.keySet().stream().sorted().toList();
        int index = 0;
        while (selected.size() < replicaCount) {
            boolean addedAny = false;
            for (String domain : domains) {
                List<StorageNode> domainNodes = byDomain.get(domain);
                if (index >= domainNodes.size()) {
                    continue;
                }
                StorageNode candidate = domainNodes.get(index);
                if (selectedNodeIds.add(candidate.nodeId())) {
                    selected.add(candidate);
                    addedAny = true;
                    if (selected.size() == replicaCount) {
                        return selected;
                    }
                }
            }

            if (!addedAny) {
                break;
            }
            index++;
        }

        if (selected.size() < replicaCount) {
            throw new ValidationException(
                "Unable to select required replicas: " + selected.size() + " < " + replicaCount
            );
        }

        return selected;
    }
}
