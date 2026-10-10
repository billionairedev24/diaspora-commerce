package com.afrotransact.ai.it;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible response bodies as OpenRouter returns them: chat completions, streamed chunks (SSE) including
 * tool calls and a final usage chunk, and embeddings.
 */
final class OpenRouterStubs {

    private OpenRouterStubs() {
    }

    /** Non-streaming chat completion with text. */
    static String completion(String model, String text) {
        return """
                {"id":"gen-1","object":"chat.completion","created":1760000000,"model":"%s",
                 "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":120,"completion_tokens":40,"total_tokens":160}}
                """.formatted(model, json(text));
    }

    /** Streamed answer: one chunk per delta, then finish, usage and [DONE]. */
    static String streamText(String model, String... deltas) {
        List<String> events = new ArrayList<>();
        boolean first = true;
        for (String d : deltas) {
            events.add(chunk(model, (first ? "\"role\":\"assistant\"," : "") + "\"content\":" + json(d), "null"));
            first = false;
        }
        events.add(chunk(model, "", "\"stop\""));
        events.add(usage(model, 300, 60));
        return sse(events);
    }

    /** Streamed tool call: the arguments arrive in two fragments, as real providers send them. */
    static String streamToolCall(String model, String callId, String tool, String argumentsJson) {
        int half = argumentsJson.length() / 2;
        List<String> events = new ArrayList<>();
        events.add(chunk(model, "\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\""
                + callId + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\",\"arguments\":"
                + json(argumentsJson.substring(0, half)) + "}}]", "null"));
        events.add(chunk(model, "\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":"
                + json(argumentsJson.substring(half)) + "}}]", "null"));
        events.add(chunk(model, "", "\"tool_calls\""));
        events.add(usage(model, 250, 30));
        return sse(events);
    }

    static String embedding(String model, float... vector) {
        StringBuilder v = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            v.append(i == 0 ? "" : ",").append(vector[i]);
        }
        return """
                {"object":"list","data":[{"object":"embedding","index":0,"embedding":[%s]}],"model":"%s",
                 "usage":{"prompt_tokens":6,"total_tokens":6}}
                """.formatted(v, model);
    }

    static String error(int status, String message) {
        return "{\"error\":{\"message\":" + json(message) + ",\"code\":" + status + "}}";
    }

    private static String chunk(String model, String delta, String finishReason) {
        return "{\"id\":\"gen-s\",\"object\":\"chat.completion.chunk\",\"created\":1760000000,\"model\":\"" + model
                + "\",\"choices\":[{\"index\":0,\"delta\":{" + delta + "},\"finish_reason\":" + finishReason + "}]}";
    }

    private static String usage(String model, int prompt, int completion) {
        return "{\"id\":\"gen-s\",\"object\":\"chat.completion.chunk\",\"created\":1760000000,\"model\":\"" + model
                + "\",\"choices\":[],\"usage\":{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":" + completion
                + ",\"total_tokens\":" + (prompt + completion) + "}}";
    }

    private static String sse(List<String> events) {
        StringBuilder b = new StringBuilder();
        for (String e : events) {
            b.append("data: ").append(e).append("\n\n");
        }
        return b.append("data: [DONE]\n\n").toString();
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
