package domain

import "errors"

// ErrUnknownPlace is returned when a country or region code is not in the gazetteer.
var ErrUnknownPlace = errors.New("unknown place")

// Place types of a search match.
const (
	PlaceCountryType = "COUNTRY"
	PlaceRegionType  = "REGION"
	PlaceCityType    = "CITY"
)

// PlaceCountry is a country of the gazetteer.
type PlaceCountry struct {
	Code        string `json:"code"`
	Name        string `json:"name"`
	Currency    string `json:"currency,omitempty"`
	RegionLabel string `json:"regionLabel"`
	RegionCount int    `json:"regionCount"`
}

// PlaceRegion is a state, province or region of a country.
type PlaceRegion struct {
	Code      string `json:"code"`
	Name      string `json:"name"`
	CityCount int    `json:"cityCount"`
}

// PlaceMatch is a search hit: a country, a region or a city.
type PlaceMatch struct {
	Key         string `json:"key"`
	Code        string `json:"code"`
	Name        string `json:"name"`
	Type        string `json:"type"`
	Country     string `json:"country,omitempty"`
	CountryName string `json:"countryName,omitempty"`
	Region      string `json:"region,omitempty"`
	RegionName  string `json:"regionName,omitempty"`
}

// regionLabels is what a country calls its regions (the design's at-preorder.js LABEL); Region otherwise.
var regionLabels = map[string]string{
	"US": "State", "CA": "Province", "GB": "Nation", "IE": "County", "KE": "County", "JM": "Parish", "ZA": "Province",
	"NG": "State", "GH": "Region", "AU": "State", "IN": "State", "MX": "State", "FR": "Region", "DE": "State",
	"IT": "Region", "ES": "Province", "BR": "State",
}

// RegionLabel is what country calls its regions: State, Province, Nation, County, Parish or Region.
func RegionLabel(country string) string {
	if l, ok := regionLabels[country]; ok {
		return l
	}
	return "Region"
}
