package port

import (
	"context"
	"time"
)

// AssistantMessage is one message of a model conversation.
type AssistantMessage struct {
	Role       string              `json:"role"` // system | user | assistant | tool
	Content    string              `json:"content,omitempty"`
	ToolCalls  []AssistantToolCall `json:"toolCalls,omitempty"`
	ToolCallID string              `json:"toolCallId,omitempty"`
	ToolName   string              `json:"toolName,omitempty"`
}

// AssistantToolCall is a tool the model asked for; Arguments is a JSON object as text.
type AssistantToolCall struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

// AssistantTool is a tool offered to the model; InputSchema is a JSON schema as text.
type AssistantTool struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	InputSchema string `json:"inputSchema"`
}

// AssistantCompletion is what the model answered: text, or tool calls to run before asking again.
type AssistantCompletion struct {
	Text             string              `json:"text"`
	ToolCalls        []AssistantToolCall `json:"toolCalls"`
	PromptTokens     int64               `json:"promptTokens"`
	CompletionTokens int64               `json:"completionTokens"`
	Model            string              `json:"model"`
}

// AssistantModelClient is one model turn at ai-gateway (POST /v1/ai/seller-assistant/complete) with the seller's token,
// so the usage is billed to the organisation as AI credits.
type AssistantModelClient interface {
	Complete(ctx context.Context, bearer string, messages []AssistantMessage, tools []AssistantTool, maxTokens int) (AssistantCompletion, error)
}

// DailyCounter counts per key and UTC day (it starts again at midnight UTC).
type DailyCounter interface {
	// Add adds n to the key's count for today and returns the new count; n = 0 only reads it, a negative n gives back.
	Add(ctx context.Context, key string, n int) (int, error)
}

// DailyCounterTTL is how long a day's count is kept.
const DailyCounterTTL = 48 * time.Hour
