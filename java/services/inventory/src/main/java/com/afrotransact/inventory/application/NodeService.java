package com.afrotransact.inventory.application;

import java.util.List;

import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.PostalAddress;

/** Fulfilment centres, origin hubs and warehouses. */
public interface NodeService {

    List<Node> list();

    Node create(Node node);

    /** The node's street address (inventory:read): where sellers send FBA stock. */
    PostalAddress address(String nodeId);

    /** Sets the street address (inventory:adjust); inbound labels need line 1, city, postal code and country. */
    PostalAddress updateAddress(String nodeId, PostalAddress address);
}
