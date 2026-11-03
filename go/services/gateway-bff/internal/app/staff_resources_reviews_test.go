package app

import (
	"strings"
	"testing"
)

func TestStarsRead(t *testing.T) {
	if got := starsOf(4); got != "★★★★☆ (4 of 5)" {
		t.Fatalf("stars = %q", got)
	}
	if starsOf(0) == "" || starsOf(9) != "" {
		t.Fatal("0 stars still reads; out of range does not")
	}
}

// The console shows a section made only of images as a gallery, so photos get a section of their own, https only.
func TestReviewPhotosAreAnImageOnlySection(t *testing.T) {
	item := jobj{"type": "PRODUCT_REVIEW", "review": jobj{"rating": float64(2), "reviewerName": "Amara O.", "text": "Cracked lid", "flags": []any{"LINK"},
		"photoUrls": []any{"https://img.example/a.jpg", "http://insecure.example/b.jpg"}, "reports": float64(2), "reportReason": "SPAM"}}
	secs := reviewItemSections(item)
	if len(secs) < 2 || secs[0].Title != "Review" || secs[1].Title != "Photos" {
		t.Fatalf("sections = %+v", secs)
	}
	if len(secs[1].Fields) != 1 || secs[1].Fields[0].Kind != "image" {
		t.Fatalf("photos = %+v", secs[1].Fields)
	}
	for _, f := range secs[0].Fields {
		if f.Kind == "image" {
			t.Fatalf("the review section holds an image field: %+v", f)
		}
	}
	var seen []string
	for _, f := range secs[0].Fields {
		seen = append(seen, f.Label+"="+f.Value)
	}
	joined := strings.Join(seen, "|")
	for _, want := range []string{"Shown as=Amara O.", "Automatic checks found=a link", "Reports=2", "First reported for=Spam"} {
		if !strings.Contains(joined, want) {
			t.Errorf("review section lacks %q: %s", want, joined)
		}
	}
}

func TestLiftReviewAuthorGivesTheConsoleAnIdToName(t *testing.T) {
	item := jobj{"review": jobj{"customerId": "cus-1"}}
	if got := jstr(liftReviewAuthor(item), "customerId"); got != "cus-1" {
		t.Fatalf("customerId = %q", got)
	}
	plain := jobj{"title": "Herbal tea"}
	if _, has := liftReviewAuthor(plain)["customerId"]; has {
		t.Fatal("a listing item is left alone")
	}
}
