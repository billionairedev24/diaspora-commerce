package upstreams

import (
	"encoding/json"
	"testing"
)

// TestPlacementsFromKeepsTileRulesAndHeroWindows: the slot payloads carry a tile cell's source rule and a hero slide's
// optional window through to the home page unchanged (the app resolves and filters them).
func TestPlacementsFromKeepsTileRulesAndHeroWindows(t *testing.T) {
	p := placementsFrom([]servedPlacement{
		{ID: "plc_hero", Slot: "hero_carousel", Payload: json.RawMessage(`{"title":"Sale","startsAt":"2026-11-10T09:00:00Z","endsAt":"2026-11-20T09:00:00Z"}`)},
		{ID: "plc_tiles", Slot: "home_tiles", Payload: json.RawMessage(`{"title":"Top picks","cta":"More","href":"/s","cells":[
			{"source":{"kind":"search","department":"Fashion","sort":"rating","limit":3}},
			{"label":"Nigerian","href":"/communities/nigerian","imageUrl":"https://cdn.example/ng.jpg"}]}`)},
	})
	if len(p.Hero) != 1 || p.Hero[0].StartsAt == nil || *p.Hero[0].StartsAt != "2026-11-10T09:00:00Z" || *p.Hero[0].EndsAt != "2026-11-20T09:00:00Z" {
		t.Fatalf("hero = %+v", p.Hero)
	}
	if len(p.Tiles) != 1 || len(p.Tiles[0].Cells) != 2 {
		t.Fatalf("tiles = %+v", p.Tiles)
	}
	src := p.Tiles[0].Cells[0].Source
	if src == nil || src.Kind != "search" || src.Department != "Fashion" || src.Sort != "rating" || src.Limit != 3 {
		t.Errorf("rule cell source = %+v", src)
	}
	if p.Tiles[0].Cells[1].Source != nil || p.Tiles[0].Cells[1].Label != "Nigerian" {
		t.Errorf("fixed cell = %+v", p.Tiles[0].Cells[1])
	}
}
