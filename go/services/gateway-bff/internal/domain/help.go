package domain

import "regexp"

// HelpTopics are the help center's topic keys (content-service HelpTopic), in display order.
var HelpTopics = []string{"ORDERS_DELIVERY", "RETURNS_REFUNDS", "ACCOUNT_PAYMENTS", "SELLING", "COMPANY"}

// HelpHub is the slug of the help center hub rendered at /help.
const HelpHub = "help-center"

// HelpPageMax is the largest page of help articles the BFF asks content for.
const HelpPageMax = 50

var helpSlug = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{1,59}$`)

// IsHelpSlug reports whether s can name a help article.
func IsHelpSlug(s string) bool { return helpSlug.MatchString(s) }

// IsHelpTopic reports whether s is a help center topic key.
func IsHelpTopic(s string) bool {
	for _, t := range HelpTopics {
		if t == s {
			return true
		}
	}
	return false
}

// HelpSection is one heading of a help page and its Markdown body.
type HelpSection struct {
	Heading string `json:"heading"`
	Body    string `json:"body"`
}

// HelpArticle is a published help page (content-service HelpArticle without the editorial fields).
type HelpArticle struct {
	Slug       string        `json:"slug"`
	Topic      string        `json:"topic"`
	TopicTitle string        `json:"topicTitle"`
	Title      string        `json:"title"`
	Summary    string        `json:"summary"`
	Sections   []HelpSection `json:"sections"`
	UpdatedAt  string        `json:"updatedAt"`
}

// HelpArticleSummary is one row of the help center's article list.
type HelpArticleSummary struct {
	Slug       string `json:"slug"`
	Topic      string `json:"topic"`
	TopicTitle string `json:"topicTitle"`
	Title      string `json:"title"`
	Summary    string `json:"summary"`
	UpdatedAt  string `json:"updatedAt"`
}

// HelpTopicCount is a topic with its number of published articles.
type HelpTopicCount struct {
	Key   string `json:"key"`
	Title string `json:"title"`
	Count int    `json:"count"`
}

// HelpArticlePage is one page of help articles; NextCursor is nil on the last page.
type HelpArticlePage struct {
	Items      []HelpArticleSummary `json:"items"`
	Topics     []HelpTopicCount     `json:"topics"`
	NextCursor *string              `json:"nextCursor"`
}

// HelpQuery filters the help article list.
type HelpQuery struct {
	Topic  string
	Q      string
	Cursor string
	Limit  int
}
