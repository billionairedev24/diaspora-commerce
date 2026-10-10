// Package nodes implements port.NodeDirectory from configuration: fulfilment centres with time zone, order cutoff,
// Saturday operations, pickup counter hours and AfroTransact Delivery zones (ZIP3 lists).
package nodes

import (
	"fmt"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/port"
)

// FCConfig is one fulfilment centre in config/<env>.yaml.
type FCConfig struct {
	ID            string `yaml:"id"`
	ZIP           string `yaml:"zip"`
	Timezone      string `yaml:"timezone"`
	Cutoff        string `yaml:"cutoff"`
	WorksSaturday bool   `yaml:"worksSaturday"`
	Pickup        struct {
		ReadyHours int                   `yaml:"readyHours"`
		Hours      []domain.OpeningHours `yaml:"hours"`
	} `yaml:"pickup"`
	Delivery struct {
		SameDayCutoff    string   `yaml:"sameDayCutoff"`
		DeliversSaturday bool     `yaml:"deliversSaturday"`
		SameDayZIP3      []string `yaml:"sameDayZip3"`
		NextDayZIP3      []string `yaml:"nextDayZip3"`
	} `yaml:"delivery"`
}

type directoryImpl struct {
	byID map[string]domain.FulfilmentCentre
	all  []domain.FulfilmentCentre
}

var _ port.NodeDirectory = (*directoryImpl)(nil)

// New validates the configured FCs and returns the directory.
func New(cfgs []FCConfig) (port.NodeDirectory, error) {
	d := &directoryImpl{byID: map[string]domain.FulfilmentCentre{}}
	for _, c := range cfgs {
		loc, err := time.LoadLocation(c.Timezone)
		if err != nil {
			return nil, fmt.Errorf("fc %s timezone: %w", c.ID, err)
		}
		cutoff, err := domain.ParseClock(c.Cutoff)
		if err != nil {
			return nil, fmt.Errorf("fc %s cutoff: %w", c.ID, err)
		}
		sameDay := cutoff
		if c.Delivery.SameDayCutoff != "" {
			if sameDay, err = domain.ParseClock(c.Delivery.SameDayCutoff); err != nil {
				return nil, fmt.Errorf("fc %s same-day cutoff: %w", c.ID, err)
			}
		}
		zip, err := domain.NormalizeZIP(c.ZIP)
		if err != nil {
			return nil, fmt.Errorf("fc %s zip: %w", c.ID, err)
		}
		fc := domain.FulfilmentCentre{ID: c.ID, ZIP: zip, Location: loc, Cutoff: cutoff, WorksSaturday: c.WorksSaturday,
			PickupReadyHours: c.Pickup.ReadyHours, PickupHours: c.Pickup.Hours, SameDayCutoff: sameDay,
			DeliversSaturday: c.Delivery.DeliversSaturday, SameDayZIP3: c.Delivery.SameDayZIP3, NextDayZIP3: c.Delivery.NextDayZIP3}
		d.byID[c.ID] = fc
		d.all = append(d.all, fc)
	}
	return d, nil
}

func (d *directoryImpl) FC(id string) (domain.FulfilmentCentre, bool) {
	fc, ok := d.byID[id]
	return fc, ok
}

func (d *directoryImpl) All() []domain.FulfilmentCentre { return d.all }
