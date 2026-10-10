package com.afrotransact.inventory.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.inventory.domain.Node;

/** Stock-holding nodes. */
public interface NodeRepository {

    List<Node> findAll();

    Optional<Node> findById(String id);

    Node insert(Node node);
}
