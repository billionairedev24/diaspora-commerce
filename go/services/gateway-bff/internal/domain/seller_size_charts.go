package domain

// ---------------------------------------------------------------- size charts and sizes (fashion)

// Fit notes a seller can give a size chart or a product.
var FitNotes = []string{"RUNS_SMALL", "TRUE_TO_SIZE", "RUNS_LARGE"}

// SuggestedMeasurement is a measurement a sized product type suggests for the seller's size chart (bust, waist, foot
// length ...). Sellers can use, rename or extend them.
type SuggestedMeasurement struct {
	Key   string  `json:"key"`
	Label string  `json:"label"`
	Unit  *string `json:"unit"`
	Help  *string `json:"help"`
}

// SizingTrait marks a product type as sized. Staff switch it on per product type; sellers write the charts and the
// size labels themselves.
type SizingTrait struct {
	// SizeChartRequired: a listing needs a size chart whose rows cover every size it sells.
	SizeChartRequired bool `json:"sizeChartRequired"`
	// SizeDimension is the variation dimension that holds the size (Size); its options are the suggested sizes.
	SizeDimension         string                 `json:"sizeDimension"`
	SuggestedMeasurements []SuggestedMeasurement `json:"suggestedMeasurements"`
}

// SizeChartColumn is one measurement of a size chart.
type SizeChartColumn struct {
	Key   string `json:"key"`
	Label string `json:"label"`
	Unit  string `json:"unit"`
}

// SizeChartValue is one measurement of one size: a single value (max equals min) or a range, in the chart's unit.
type SizeChartValue struct {
	Min float64 `json:"min"`
	Max float64 `json:"max"`
}

// SizeChartRow is one size of a chart; AlternateValues are the same values converted to the other unit.
type SizeChartRow struct {
	Size            string                    `json:"size"`
	Values          map[string]SizeChartValue `json:"values"`
	AlternateValues map[string]SizeChartValue `json:"alternateValues"`
}

// ProductSizeChart is the size chart shown on the product page.
type ProductSizeChart struct {
	Name          string            `json:"name"`
	UnitSystem    string            `json:"unitSystem"`
	AlternateUnit string            `json:"alternateUnit"`
	Columns       []SizeChartColumn `json:"columns"`
	Rows          []SizeChartRow    `json:"rows"`
	// FitNote is the product's own fit note, else the chart's; empty when neither says.
	FitNote      *string `json:"fitNote"`
	HowToMeasure *string `json:"howToMeasure"`
}
