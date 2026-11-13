package domain

import "time"

// ---------------------------------------------------------------- stock CSV import (S395)

// Stock file modes: ABSOLUTE sets what is on hand to the counted number, DELTA adds a change to it.
const (
	StockImportAbsolute = "ABSOLUTE"
	StockImportDelta    = "DELTA"
)

// Stock file vocabularies (inventory-service names).
var (
	StockImportModes    = []string{StockImportAbsolute, StockImportDelta}
	StockImportStatuses = []string{"VALID", "WARNING", "ERROR"}
	StockImportOutcomes = []string{"PENDING", "APPLIED", "UNCHANGED", "FAILED", "SKIPPED"}
)

// StockImport is one stock file: validation counts and import progress. The organisation is never part of it: inventory
// reads it from the member's token.
type StockImport struct {
	ID            string     `json:"id"`
	Reference     string     `json:"reference"`
	FileName      string     `json:"fileName"`
	FileSHA256    *string    `json:"fileSha256"`
	SameFileAs    *string    `json:"sameFileAs"`
	Mode          string     `json:"mode"`
	Status        string     `json:"status"`
	TotalRows     int        `json:"totalRows"`
	ValidRows     int        `json:"validRows"`
	WarningRows   int        `json:"warningRows"`
	ErrorRows     int        `json:"errorRows"`
	ProcessedRows int        `json:"processedRows"`
	AppliedRows   int        `json:"appliedRows"`
	UnchangedRows int        `json:"unchangedRows"`
	FailedRows    int        `json:"failedRows"`
	Header        []string   `json:"header"`
	CreatedAt     time.Time  `json:"createdAt"`
	StartedAt     *time.Time `json:"startedAt"`
	FinishedAt    *time.Time `json:"finishedAt"`
	ReportURL     string     `json:"reportUrl"`
}

// StockImportPage is the stock file history.
type StockImportPage struct {
	Items []StockImport `json:"items"`
	Total int64         `json:"total"`
	Page  int           `json:"page"`
	Size  int           `json:"size"`
}

// StockImportMessage is a problem of one row, on one column.
type StockImportMessage struct {
	Column   *string `json:"column"`
	Severity string  `json:"severity"`
	Message  string  `json:"message"`
}

// StockImportRow is one row of the preview.
type StockImportRow struct {
	RowNumber   int                  `json:"rowNumber"`
	SKU         string               `json:"sku"`
	Location    string               `json:"location"`
	Lot         *string              `json:"lot"`
	Quantity    *int                 `json:"quantity"`
	Expiry      *string              `json:"expiry"`
	Status      string               `json:"status"`
	Outcome     string               `json:"outcome"`
	OutcomeNote *string              `json:"outcomeNote"`
	Before      *int                 `json:"before"`
	After       *int                 `json:"after"`
	Messages    []StockImportMessage `json:"messages"`
	Cells       []string             `json:"cells"`
}

// StockImportRowPage is one page of rows.
type StockImportRowPage struct {
	Items []StockImportRow `json:"items"`
	Total int64            `json:"total"`
	Page  int              `json:"page"`
	Size  int              `json:"size"`
}
