package com.afrotransact.inventory.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.PostalAddress;

/** Stock-holding nodes. */
public interface NodeRepository {

    List<Node> findAll();

    Optional<Node> findById(String id);

    Node insert(Node node);

    /** Street address of a node (FC addresses for inbound labels, S257); name = the node's name. */
    Optional<PostalAddress> address(String nodeId);

    void updateAddress(String nodeId, PostalAddress address);

    /** Units on hand, held and inbound per node id (fulfilment centre load). */
    Map<String, Long> unitsHeldByNode();
}
