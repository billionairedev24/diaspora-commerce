package domain

// Navigation, departments, communities and sitemap sources (contract SiteNavigation, SiteDepartment, SiteCommunity,
// SitemapEntry). All read one snapshot of eligible-only counts, so the home, nav, footer and sitemap cannot disagree.

// SiteDepartment is a catalog department with its eligible count and a real product photo.
type SiteDepartment struct {
	Key      string       `json:"key"`
	Name     string       `json:"name"`
	Label    string       `json:"label"`
	Labels   LocaleLabels `json:"labels"`
	LabelKey string       `json:"labelKey,omitempty"`
	Count    int          `json:"count"`
	Sellers  int          `json:"sellers"`
	Href     string       `json:"href,omitempty"`
	ImageURL *string      `json:"imageUrl"`
}

// SiteCommunity is a visible community.
type SiteCommunity struct {
	Key         string       `json:"key"`
	Label       string       `json:"label"`
	Labels      LocaleLabels `json:"labels"`
	Count       int          `json:"count"`
	Sellers     int          `json:"sellers"`
	Href        string       `json:"href"`
	ImageURL    *string      `json:"imageUrl"`
	ImageSource string       `json:"imageSource"`
}

// SiteServices is the Services nav entry: available whenever any provider exists in the market.
type SiteServices struct {
	Available bool   `json:"available"`
	Providers int    `json:"providers"`
	Href      string `json:"href"`
}

// SiteNavigation is the header bar, the All departments menu and the footer communities.
type SiteNavigation struct {
	Bar         []SiteDepartment `json:"bar"`
	All         []SiteDepartment `json:"all"`
	Communities []SiteCommunity  `json:"communities"`
	Services    SiteServices     `json:"services"`
	Deals       NavAvailable     `json:"deals"`
	Brand       NavAvailable     `json:"brand"`
	GeneratedAt string           `json:"generatedAt"`
}

// NavAvailable is a yes/no nav entry.
type NavAvailable struct {
	Available bool `json:"available"`
}

// SitemapEntry is one URL of the sitemap source.
type SitemapEntry struct {
	Href    string `json:"href"`
	Count   int    `json:"count"`
	LastMod string `json:"lastmod,omitempty"`
}

// SitemapSources are the department and community URLs the sitemap lists.
type SitemapSources struct {
	Departments []SitemapEntry `json:"departments"`
	Communities []SitemapEntry `json:"communities"`
}

// Copy keys of the dynamic home (contracts/i18n/home-copy.json). The gateway sends keys and params, never English.
const (
	KeyBrandHeroTitle   = "home.brandHero.title"
	KeyBrandHeroSub     = "home.brandHero.sub"
	KeyBrandHeroCTA     = "home.brandHero.cta"
	KeySellBandTitle    = "home.sellBand.title"
	KeySellBandBody     = "home.sellBand.body"
	KeySellBandCTA      = "home.sellBand.cta"
	KeyDepartmentsTitle = "home.departments.title"
	KeyCommunitiesTitle = "home.communities.title"
	KeyServicesNear     = "home.services.nearYou.title"
	KeyServicesPopular  = "home.services.popular.title"
	KeyServicesCTA      = "home.services.cta"
	KeyNewArrivalsTitle = "home.newArrivals.title"
	KeyTopSellersTitle  = "home.topSellers.title"
	KeyDealsTitle       = "home.deals.title"
	KeyGroceriesTitle   = "home.groceries.title"
	KeyTopInTitle       = "home.topIn.title"
	KeySeeAll           = "home.seeAll"
	KeyContinueTitle    = "home.continue.title"
	KeyRecentTitle      = "home.recent.title"
)

// HomeCopyKeys are every key the gateway can send; a test checks each exists in five locales in the contract file.
var HomeCopyKeys = []string{KeyBrandHeroTitle, KeyBrandHeroSub, KeyBrandHeroCTA, KeySellBandTitle, KeySellBandBody, KeySellBandCTA,
	KeyDepartmentsTitle, KeyCommunitiesTitle, KeyServicesNear, KeyServicesPopular, KeyServicesCTA, KeyNewArrivalsTitle,
	KeyTopSellersTitle, KeyDealsTitle, KeyGroceriesTitle, KeyTopInTitle, KeySeeAll, KeyContinueTitle, KeyRecentTitle}
