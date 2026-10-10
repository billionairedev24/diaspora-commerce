package nodes_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/nodes"
)

func TestDirectory(t *testing.T) {
	var c nodes.FCConfig
	c.ID, c.ZIP, c.Timezone, c.Cutoff = "fc_edison_nj", "08837", "America/New_York", "15:00"
	c.Delivery.SameDayZIP3, c.Delivery.NextDayZIP3 = []string{"088"}, []string{"100"}
	d, err := nodes.New([]nodes.FCConfig{c})
	if err != nil {
		t.Fatal(err)
	}
	fc, ok := d.FC("fc_edison_nj")
	if !ok || fc.Cutoff.Hour != 15 || fc.SameDayCutoff.Hour != 15 || !fc.Serves("100") || fc.Serves("770") || !fc.Zone("088").SameDay {
		t.Fatalf("%+v", fc)
	}
	c.Cutoff = "3pm"
	if _, err := nodes.New([]nodes.FCConfig{c}); err == nil {
		t.Fatal("malformed cutoff must fail start-up")
	}
}
