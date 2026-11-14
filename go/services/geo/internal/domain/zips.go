package domain

import "math"

// ZipPlace is a US ZIP code's city, state and centroid.
type ZipPlace struct {
	Zip   string  `json:"zip"`
	City  string  `json:"city"`
	State string  `json:"state"`
	Lat   float64 `json:"lat"`
	Lon   float64 `json:"lon"`
}

// NearbyCity is a city (several ZIPs collapse into one) with the distance from the query point to its nearest ZIP.
type NearbyCity struct {
	City          string   `json:"city"`
	State         string   `json:"state"`
	Lat           float64  `json:"lat"`
	Lon           float64  `json:"lon"`
	DistanceMiles float64  `json:"distanceMiles"`
	Zips          []string `json:"zips"`
}

const earthRadiusMiles = 3958.8

// HaversineMiles is the great-circle distance between two points in miles.
func HaversineMiles(lat1, lon1, lat2, lon2 float64) float64 {
	p1, p2 := lat1*math.Pi/180, lat2*math.Pi/180
	dp, dl := p2-p1, (lon2-lon1)*math.Pi/180
	a := math.Sin(dp/2)*math.Sin(dp/2) + math.Cos(p1)*math.Cos(p2)*math.Sin(dl/2)*math.Sin(dl/2)
	return 2 * earthRadiusMiles * math.Asin(math.Min(1, math.Sqrt(a)))
}

// CityZips are the ZIPs the table holds for one city. Zip is the representative one: the ZIP whose centroid is nearest
// the mean of the city's ZIP centroids (ties: the lowest ZIP), so the answer is stable and central, never a guess outside
// the table.
type CityZips struct {
	City  string   `json:"city"`
	State string   `json:"state"`
	Zip   string   `json:"zip"`
	Zips  []string `json:"zips"`
}
