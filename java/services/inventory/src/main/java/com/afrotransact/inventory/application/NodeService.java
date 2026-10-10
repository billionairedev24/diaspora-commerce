package com.afrotransact.inventory.application;

import java.util.List;

import com.afrotransact.inventory.domain.Node;

/** Fulfilment centres, origin hubs and warehouses. */
public interface NodeService {

    List<Node> list();

    Node create(Node node);
}
