package com.afrotransact.inventory.application;

import java.util.List;

import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NodeServiceImpl implements NodeService {

    private final NodeRepository nodes;
    private final InventoryAccess access;

    NodeServiceImpl(NodeRepository nodes, InventoryAccess access) {
        this.nodes = nodes;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Node> list() {
        access.readScope();
        return nodes.findAll();
    }

    @Override
    @Transactional
    public Node create(Node node) {
        access.require(InventoryPermissions.ADJUST);
        if (nodes.findById(node.id()).isPresent()) {
            throw new ConflictException("inventory.node-exists", "Node " + node.id() + " already exists");
        }
        return nodes.insert(node);
    }
}
