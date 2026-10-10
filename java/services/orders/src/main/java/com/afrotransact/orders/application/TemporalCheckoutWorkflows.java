package com.afrotransact.orders.application;

import com.afrotransact.orders.domain.OrdersStateConflict;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** {@link CheckoutWorkflows} on the Temporal client (absent when Temporal is switched off, e.g. contract export). */
@Component
class TemporalCheckoutWorkflows implements CheckoutWorkflows {

    private static final Logger LOG = LoggerFactory.getLogger(TemporalCheckoutWorkflows.class);

    private final ObjectProvider<WorkflowClient> clients;

    TemporalCheckoutWorkflows(ObjectProvider<WorkflowClient> clients) {
        this.clients = clients;
    }

    @Override
    public void start(CheckoutInput input) {
        CheckoutWorkflow wf = client().newWorkflowStub(CheckoutWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(input.workflowId())
                .setTaskQueue(CheckoutWorkflow.TASK_QUEUE)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        try {
            WorkflowClient.start(wf::checkout, input);
        } catch (WorkflowExecutionAlreadyStarted e) {
            LOG.debug("Checkout workflow {} already started", input.workflowId());
        }
    }

    @Override
    public boolean exists(String workflowId) {
        try {
            client().newUntypedWorkflowStub(workflowId).describe();
            return true;
        } catch (WorkflowNotFoundException e) {
            return false;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void signalPayment(String workflowId, boolean authorized, String reason) {
        WorkflowStub stub = client().newUntypedWorkflowStub(workflowId);
        try {
            if (authorized) {
                stub.signal("paymentAuthorized");
            } else {
                stub.signal("paymentFailed", reason == null ? "The payment failed" : reason);
            }
        } catch (WorkflowNotFoundException e) {
            LOG.info("Checkout {} already finished; payment {} signal ignored", workflowId,
                    authorized ? "authorised" : "failed");
        }
    }

    private WorkflowClient client() {
        WorkflowClient client = clients.getIfAvailable();
        if (client == null) {
            throw new OrdersStateConflict("orders.checkout-unavailable", "Checkout is not available right now");
        }
        return client;
    }
}
