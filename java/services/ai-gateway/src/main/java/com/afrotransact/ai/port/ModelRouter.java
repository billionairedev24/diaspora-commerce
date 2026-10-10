package com.afrotransact.ai.port;

import java.util.List;
import java.util.function.Consumer;

import com.afrotransact.ai.domain.TaskType;

/**
 * Model access with per-task routing. Implementations select the models of the task's route (or only the cheapest
 * when {@link ModelRequest#degrade()} is set), apply the per-call timeout, retry the same model on 429 / 5xx and then
 * fall back to the next model in order. Throws {@link ModelUnavailableException} when every candidate failed.
 */
public interface ModelRouter {

    ModelReply chat(ModelRequest request);

    /**
     * Streams the answer: {@code onText} receives text deltas as they arrive; the returned reply carries the full
     * text, tool calls and usage. Falls back to the next model only while no text has been delivered yet.
     */
    ModelReply stream(ModelRequest request, Consumer<String> onText);

    EmbeddingReply embed(TaskType task, List<String> inputs, boolean degrade);
}
