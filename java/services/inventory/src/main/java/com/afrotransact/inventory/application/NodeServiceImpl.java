package com.afrotransact.inventory.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
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

    @Override
    @Transactional(readOnly = true)
    public PostalAddress address(String nodeId) {
        access.inboundReadScope();
        return nodes.address(code(nodeId)).orElseThrow(() -> notFound(nodeId));
    }

    @Override
    @Transactional
    public PostalAddress updateAddress(String nodeId, PostalAddress a) {
        access.require(InventoryPermissions.ADJUST);
        String id = code(nodeId);
        Node node = nodes.findById(id).orElseThrow(() -> notFound(nodeId));
        if (a == null || a.line1() == null || a.line1().isBlank() || a.city() == null || a.city().isBlank()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Line 1 and city are required",
                    Map.of("line1", "Required"));
        }
        nodes.updateAddress(id, new PostalAddress(null, a.line1().trim(), blank(a.line2()), a.city().trim(),
                blank(a.region()), blank(a.postalCode()), node.country(), blank(a.phone())));
        return nodes.address(id).orElseThrow();
    }

    private static String code(String id) {
        return id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("inventory.node-not-found", "Unknown node " + id);
    }
}
