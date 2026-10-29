package exportfiles

import (
	"testing"

	"codeberg.org/go-pdf/fpdf"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestBarAndLineChartsGetAKeyNamingTheirSeries(t *testing.T) {
	f := fpdf.New("L", "mm", "Letter", "")
	f.AddPage()
	d := &pdfDoc{Fpdf: f, tr: f.UnicodeTranslatorFromDescriptor("")}
	short := drawKey(d, domain.ExportChart{ValueLabel: "Sales"}, 200, 20, 80, 0)
	long := drawKey(d, domain.ExportChart{ValueLabel: "Gross sales shipped to the state"}, 200, 20, 80, 1)
	if short <= 0 || long <= short {
		t.Fatalf("key widths: short %.1f, long %.1f", short, long)
	}
	tiny := drawKey(d, domain.ExportChart{ValueLabel: "A very long series name that cannot fit"}, 200, 20, 20, 2)
	if tiny > 22 {
		t.Fatalf("key must stay inside its budget, got %.1f", tiny)
	}
}
