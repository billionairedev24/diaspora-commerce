"""The demo dataset: sellers, providers, products, home content and pre-order windows.

Names, prices and copy follow the Claude Design prototypes (design/AfroTransact Shop.dc.html product list p1–p22,
design/at-preorder.js windows, design/AfroTransact Pre-Order v2.dc.html sellers). Image `queries` are search phrases for
the photo APIs (and file names for SEED_IMAGES_DIR).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Union

RETAIL_ORG = "org_afrotransact_retail"
RETAIL_NAME = "AfroTransact Retail"


@dataclass(frozen=True)
class Seller:
    key: str
    org_id: str
    name: str
    legal: str
    city: str
    region: str
    zip: str
    node: str  # AfroTransact fulfilment centre that holds its FBA stock
    fulfilment: str = "AFROTRANSACT"  # default for its offers: AFROTRANSACT (FBA) or SELLER
    kind: str = "SELLER"


SELLERS: list[Seller] = [
    Seller("retail", RETAIL_ORG, RETAIL_NAME, "AfroTransact Inc.", "Houston", "TX", "77042", "HOU-FC"),
    Seller("mama-put", "org_mama_put_pantry", "Mama Put Pantry", "Mama Put Pantry LLC", "Houston", "TX", "77036", "HOU-FC"),
    Seller("lagos-larder", "org_lagos_larder", "Lagos Larder", "Lagos Larder Foods LLC", "Houston", "TX", "77072", "HOU-FC"),
    Seller("accra-market", "org_accra_market_co", "Accra Market Co.", "Accra Market Company LLC", "Bronx", "NY", "10453", "EWR-FC"),
    Seller("bonwire", "org_bonwire_weavers", "Bonwire Weavers Collective", "Bonwire Weavers Collective Inc.", "Atlanta", "GA", "30310", "ATL-FC", "SELLER"),
    Seller("kente-co", "org_kente_and_co", "Kente & Co.", "Kente and Company LLC", "Atlanta", "GA", "30318", "ATL-FC"),
    Seller("tamale", "org_tamale_naturals", "Tamale Naturals", "Tamale Naturals LLC", "Columbus", "OH", "43219", "EWR-FC"),
    Seller("addis", "org_addis_spice_house", "Addis Spice House", "Addis Spice House LLC", "Silver Spring", "MD", "20910", "EWR-FC"),
    Seller("kingston", "org_kingston_kitchen", "Kingston Kitchen", "Kingston Kitchen Foods LLC", "Miami", "FL", "33138", "ATL-FC"),
    Seller("patel", "org_patel_grocers", "Patel Grocers", "Patel Grocers Inc.", "Edison", "NJ", "08817", "EWR-FC"),
    Seller("dakar", "org_dakar_market", "Dakar Market", "Dakar Market LLC", "Bronx", "NY", "10456", "EWR-FC"),
    Seller("books", "org_diaspora_books", "Diaspora Books", "Diaspora Books LLC", "Atlanta", "GA", "30303", "ATL-FC", "SELLER"),
    Seller("bamako", "org_bamako_home", "Bamako Home", "Bamako Home Goods LLC", "Brooklyn", "NY", "11216", "EWR-FC", "SELLER"),
    Seller("owambe", "org_owambe_party_co", "Owambe Party Co.", "Owambe Party Company LLC", "Houston", "TX", "77082", "HOU-FC"),
]
SELLER_BY_KEY = {s.key: s for s in SELLERS}


@dataclass(frozen=True)
class Provider:
    key: str
    org_id: str
    name: str
    legal: str
    location: str
    member: str
    services: tuple[tuple[str, str, str, int, int, int, tuple[str, ...]], ...]  # name, desc, category, mins, price, deposit, modes


PROVIDERS: list[Provider] = [
    Provider("aminata", "org_aminatas_braids", "Aminata's Braids", "Aminata's Braids LLC", "Houston, TX", "Aminata Diallo", (
        ("Knotless box braids (mid-back)", "Knotless, lightweight box braids. Hair included; studio or home visits within 20 miles.",
         "hair & beauty", 300, 18000, 4000, ("IN_PERSON", "AT_HOME")),
        ("Fulani braids with beads", "Fulani-style cornrows with a centre part, side braids and beads.", "hair & beauty", 240,
         15000, 4000, ("IN_PERSON",)),
        ("Kids' cornrows", "Neat cornrows for school, ages 4–12.", "hair & beauty", 90, 6000, 1500, ("IN_PERSON",)),
    )),
    Provider("jollof-co", "org_jollof_and_co_catering", "Jollof & Co. Catering", "Jollof and Co Catering LLC", "Dallas, TX",
             "Bisi Adeyemi", (
                 ("Owambe party trays (50 guests)", "Party jollof, fried rice, peppered chicken, moin moin and dodo for 50.",
                  "catering", 240, 42000, 10000, ("AT_HOME",)),
                 ("Naming ceremony small chops", "Puff-puff, samosa, spring rolls and suya skewers for 30 guests.", "catering",
                  180, 26000, 6000, ("AT_HOME",)),
             )),
    Provider("adire", "org_adire_tailors", "Adire Tailors", "Adire Tailors LLC", "Brooklyn, NY", "Tunde Bakare", (
        ("Agbada sewn to measure", "Three-piece agbada from your fabric; video fitting included.", "tailoring", 60, 22000, 3000,
         ("IN_PERSON", "VIRTUAL")),
        ("Aso-ebi fitting", "Measurements and fitting for aso-ebi outfits.", "tailoring", 45, 9500, 3000, ("IN_PERSON", "VIRTUAL")),
    )),
]

Options = Union[str, dict]  # noqa: UP007 (runs on Python 3.9, e.g. macOS)


@dataclass
class Product:
    key: str  # stable slug: idempotency, SKUs, image file names
    title: str
    path: str  # catalog product-type path, e.g. "Groceries › Pantry › Grains & rice"
    seller: str  # SELLER_BY_KEY key
    brand: str
    community: str
    origin: str
    bullets: tuple[str, ...]
    variants: tuple[tuple[Options, int], ...]  # (option value or {dimension: value}, price in cents)
    queries: tuple[str, ...]
    unit: str = ""
    deal: float = 0.0  # list price = price × (1 + deal): strike-through "deal" pricing
    stock: int = 60
    fulfilment: str = ""  # default: the seller's
    also: tuple[tuple[str, float], ...] = ()  # other sellers offering it: (seller key, price factor)
    attrs: dict = field(default_factory=dict)  # product-type attributes (defaults fill the rest)
    images: int = 3


def P(key, title, path, seller, brand, community, origin, bullets, variants, queries, **kw) -> Product:
    return Product(key, title, path, seller, brand, community, origin, tuple(bullets), tuple(variants), tuple(queries), **kw)


PANTRY = "Groceries › Pantry › "
GRAINS, SPICES, SAUCES, SNACKS = PANTRY + "Grains & rice", PANTRY + "Spices & seasonings", PANTRY + "Sauces & pastes", PANTRY + "Snacks"
TEAS, COFFEE = "Groceries › Drinks › Teas & hibiscus", "Groceries › Drinks › Coffee"
FROZEN = "Groceries › Fresh › Frozen & perishables"
WELLNESS = "Health › Wellness › Herbal teas & remedies"
BUTTERS, SOAPS = "Beauty › Skin care › Butters & oils", "Beauty › Skin care › Soaps"
HAIR_OILS, BRAIDING = "Beauty › Hair care › Hair oils", "Beauty › Hair care › Braiding hair"
ANKARA, KENTE, LACE = "Fashion › Fabrics › Ankara & wax prints", "Fashion › Fabrics › Kente & aso-oke", "Fashion › Fabrics › Lace"
DRESSES, TOPS, HEADWRAPS = "Fashion › Women › Dresses", "Fashion › Women › Tops", "Fashion › Women › Headwraps"
MEN_AGBADA, MEN_SHIRTS, KIDS = "Fashion › Men › Agbada & kaftans", "Fashion › Men › Shirts", "Fashion › Kids › Kids clothing"
COOKBOOKS, FICTION, CHILDRENS = "Books › Books › Cookbooks", "Books › Books › Fiction", "Books › Books › Children's books"
PILLOWS, WALL_ART = "Home & Art › Decor › Pillows & throws", "Home & Art › Decor › Wall art"
PARTY = "Event & party › Party supplies › Decorations"

SC = lambda size, color: {"Size": size, "Color": color}
LP = lambda length, pattern: {"Length": length, "Pattern": pattern}

PRODUCTS: list[Product] = [
    # ---------------------------------------------------------------------------------------- grains, flours, beans
    P("ofada-rice", "Ofada Rice, Destoned", GRAINS, "mama-put", "Mama Tee's", "Nigerian", "Nigeria",
      ["Unpolished local rice with its distinctive earthy aroma", "Destoned twice, vacuum-sealed in Houston",
       "Pairs with ayamase (designer stew)"], [("2 lb", 899), ("5 lb", 1850)], ["ofada rice", "nigerian rice", "brown rice grains"],
      unit="5 lb bag", attrs={"ingredients": "Ofada rice"}, also=(("lagos-larder", 1.06),)),
    P("at-basmati", "AfroTransact Brand Aged Basmati Rice", GRAINS, "retail", "AfroTransact Brand", "Indian", "India",
      ["Aged 18 months for long, separate grains", "For biryani, pulao and jeera rice", "Sold and fulfilled by AfroTransact"],
      [("5 lb", 1299), ("10 lb", 2199)], ["basmati rice", "biryani", "rice grains"], unit="10 lb", deal=0.18, stock=600,
      attrs={"ingredients": "Aged basmati rice", "cert": "Halal"}),
    P("ijebu-garri", "Ijebu Garri, Sharp and Sour", GRAINS, "mama-put", "Mama Put", "Nigerian", "Nigeria",
      ["Fine-grain yellow-free garri from Ijebu-Ode", "Soak with cold water and sugar, or make eba"],
      [("2 lb", 699), ("5 lb", 1299)], ["garri", "cassava flakes", "cassava"], unit="5 lb", attrs={"ingredients": "Cassava"}),
    P("teff-flour", "Ivory Teff Flour, Stone-Milled", GRAINS, "addis", "Addis Spice House", "Ethiopian", "Ethiopia",
      ["For injera that bubbles properly", "Naturally gluten-free grain"], [("2 lb", 899), ("5 lb", 1599)],
      ["teff", "injera", "teff flour"], unit="5 lb", attrs={"ingredients": "Whole-grain ivory teff"}),
    P("poundo-yam", "Poundo Yam Flour", GRAINS, "lagos-larder", "Ayoola", "Nigerian", "Nigeria",
      ["Smooth, stretchy pounded yam in five minutes", "Serve with egusi, ogbono or efo riro"], [("2 lb", 799), ("5 lb", 1699)],
      ["pounded yam", "yam flour", "fufu"], unit="5 lb", attrs={"ingredients": "Yam flour"}),
    P("plantain-fufu", "Plantain Fufu Flour", GRAINS, "accra-market", "Accra Market", "Ghanaian", "Ghana",
      ["Plantain and cassava blend for soft fufu", "Serve with light soup or groundnut soup"], [("2 lb", 749)],
      ["fufu", "plantain flour", "light soup"], unit="2 lb", attrs={"ingredients": "Plantain flour, cassava flour"}),
    P("egusi", "Ground Egusi (Melon Seeds)", GRAINS, "lagos-larder", "Lagos Larder", "Nigerian", "Nigeria",
      ["Freshly ground, no fillers", "For egusi soup with bitter leaf or spinach"], [("1 lb", 899), ("2 lb", 1449)],
      ["egusi", "melon seeds", "egusi soup"], unit="2 lb", also=(("mama-put", 1.04),), attrs={"ingredients": "Ground melon seeds"}),
    P("oloyin-beans", "Honey Beans (Oloyin)", GRAINS, "mama-put", "Mama Put", "Nigerian", "Nigeria",
      ["Naturally sweet brown beans", "For ewa agoyin and moin moin"], [("2 lb", 799), ("5 lb", 1699)],
      ["brown beans", "black-eyed peas", "beans"], unit="5 lb", attrs={"ingredients": "Brown beans"}),
    # ---------------------------------------------------------------------------------------- spices
    P("at-jollof-seasoning", "AfroTransact Brand Jollof Seasoning", SPICES, "retail", "AfroTransact Brand", "West African",
      "United States", ["Tomato, thyme, curry, bay and smoked pepper — balanced for party jollof", "No MSG, no fillers",
                        "Sold and fulfilled by AfroTransact"], [("8 oz", 649), ("1 lb", 1099)], ["jollof rice", "jollof", "spice blend"],
      unit="8 oz", deal=0.23, stock=900, attrs={"ingredients": "Tomato powder, thyme, curry powder, bay leaf, smoked paprika, salt"}),
    P("berbere", "Berbere Spice Blend", SPICES, "addis", "Addis Spice House", "Ethiopian", "Ethiopia",
      ["Sun-dried chilies, korarima and fenugreek", "Ground weekly for doro wat and misir"], [("8 oz", 975), ("1 lb", 1699)],
      ["berbere", "ethiopian spices", "chili powder"], unit="8 oz", attrs={"ingredients": "Chili, fenugreek, korarima, ginger, garlic, salt"}),
    P("suya-spice", "Suya Spice (Yaji)", SPICES, "lagos-larder", "Lagos Larder", "Nigerian", "Nigeria",
      ["Kuli-kuli, ginger and cayenne for authentic suya", "Rub on beef, chicken or plantain"], [("8 oz", 799)],
      ["suya", "yaji", "grilled meat skewers"], unit="8 oz", attrs={"ingredients": "Peanut powder, cayenne, ginger, garlic, salt",
                                                                     "allergens": "Contains peanuts"}),
    P("garam-masala", "Garam Masala, Whole Spice Blend", SPICES, "patel", "Patel Grocers", "Indian", "India",
      ["Roasted and ground in small batches", "Cardamom, clove, cinnamon, cumin and pepper"], [("8 oz", 699)],
      ["garam masala", "indian spices", "spice market"], unit="8 oz", attrs={"ingredients": "Coriander, cumin, cardamom, clove, cinnamon, black pepper"}),
    P("jamaican-curry", "Jamaican Curry Powder", SPICES, "kingston", "Kingston Kitchen", "Jamaican", "Jamaica",
      ["Turmeric-forward with allspice", "For curry goat and curry chicken"], [("8 oz", 599), ("1 lb", 999)],
      ["curry powder", "curry goat", "turmeric powder"], unit="8 oz", attrs={"ingredients": "Turmeric, coriander, cumin, allspice, fenugreek"}),
    P("ground-crayfish", "Ground Crayfish", SPICES, "mama-put", "Mama Put", "Nigerian", "Nigeria",
      ["Smoky, finely ground crayfish for soups and stews", "Resealable pouch"], [("8 oz", 1099)],
      ["crayfish", "dried shrimp", "dried seafood"], unit="8 oz", attrs={"ingredients": "Dried crayfish", "allergens": "Contains shellfish"}),
    P("iru", "Iru (Fermented Locust Beans)", SPICES, "lagos-larder", "Lagos Larder", "Nigerian", "Nigeria",
      ["Deep umami for efo riro and ofada sauce", "Keep refrigerated after opening"], [("8 oz", 699)],
      ["locust beans", "dawadawa", "fermented beans"], unit="8 oz",
      attrs={"ingredients": "Fermented locust beans, salt", "storage": "Refrigerate after opening"}),
    P("pepper-soup-spice", "Pepper Soup Spice Mix", SPICES, "mama-put", "Mama Put", "Nigerian", "Nigeria",
      ["Uda, uziza and calabash nutmeg", "For goat meat or catfish pepper soup"], [("8 oz", 649)],
      ["pepper soup", "spices", "calabash nutmeg"], unit="8 oz", attrs={"ingredients": "Grains of selim, uziza seed, calabash nutmeg, alligator pepper"}),
    P("mitmita", "Mitmita Spice Blend", SPICES, "addis", "Addis Spice House", "Ethiopian", "Ethiopia",
      ["Fiery bird's-eye chili with cardamom and clove", "Dust over kitfo and tibs"], [("8 oz", 899)],
      ["mitmita", "ethiopian food", "kitfo"], unit="8 oz", attrs={"ingredients": "Bird's-eye chili, cardamom, clove, salt"}),
    P("turmeric", "Ground Turmeric (Haldi)", SPICES, "patel", "Patel Grocers", "Indian", "India",
      ["Bright, high-curcumin turmeric", "For dal, sabzi and golden milk"], [("1 lb", 799)],
      ["turmeric", "turmeric powder", "haldi"], unit="1 lb", attrs={"ingredients": "Turmeric"}),
    P("at-dried-pepper", "AfroTransact Brand Dried Ata Rodo Pepper", SPICES, "retail", "AfroTransact Brand", "Nigerian", "Nigeria",
      ["Whole dried scotch bonnet peppers", "Rehydrate for obe ata or grind for pepper soup"], [("8 oz", 549)],
      ["scotch bonnet", "dried chili peppers", "red peppers"], unit="8 oz", stock=400, attrs={"ingredients": "Dried scotch bonnet pepper"}),
    # ---------------------------------------------------------------------------------------- sauces & pastes
    P("shito", "Shito Black Pepper Sauce, Hot", SAUCES, "accra-market", "Accra Kitchen Co.", "Ghanaian", "United States",
      ["Slow-fried with dried shrimp, herring and ginger", "Small batches every Thursday"], [("8 oz", 1149)],
      ["shito", "pepper sauce", "ghanaian food"], unit="12 oz jar",
      attrs={"ingredients": "Vegetable oil, dried shrimp, smoked herring, chili, ginger, onion", "allergens": "Contains shellfish",
             "storage": "Refrigerate after opening"}),
    P("jerk-paste", "Blue Mountain Jerk Paste, Hot", SAUCES, "kingston", "Kingston Kitchen", "Jamaican", "United States",
      ["Scotch bonnet, pimento and scallion", "Marinade for chicken, pork or jackfruit"], [("8 oz", 899), ("1 lb", 1499)],
      ["jerk chicken", "jerk seasoning", "jamaican food"], unit="10 oz jar",
      attrs={"ingredients": "Scallion, scotch bonnet, pimento, thyme, vinegar, salt", "storage": "Refrigerate after opening"}),
    P("red-palm-oil", "Red Palm Oil, Unrefined", SAUCES, "accra-market", "Accra Market", "West African", "Ghana",
      ["Cold-pressed, sustainably sourced", "For banga, red red and efo riro"], [("1 lb", 999), ("2 lb", 1699)],
      ["palm oil", "red palm oil", "oil palm fruit"], unit="1 L", attrs={"ingredients": "Red palm fruit oil"}),
    P("at-pepper-sauce", "AfroTransact Brand Ata Din Din Pepper Sauce", SAUCES, "retail", "AfroTransact Brand", "Nigerian",
      "United States", ["Fried pepper sauce with tatashe and onion", "Spoon over rice, yam or plantain"], [("8 oz", 699)],
      ["pepper sauce", "nigerian stew", "tomato stew"], unit="8 oz jar", deal=0.15, stock=300,
      attrs={"ingredients": "Red bell pepper, scotch bonnet, onion, vegetable oil, salt", "storage": "Refrigerate after opening"}),
    P("groundnut-paste", "Ghana Groundnut Paste", SAUCES, "accra-market", "Accra Market", "Ghanaian", "Ghana",
      ["Roasted peanuts, nothing added", "For nkatenkwan (groundnut soup)"], [("1 lb", 849)],
      ["peanut butter", "groundnut soup", "peanuts"], unit="1 lb", attrs={"ingredients": "Roasted peanuts", "allergens": "Contains peanuts"}),
    P("mango-pickle", "Mango Pickle (Aam Achar)", SAUCES, "patel", "Patel Grocers", "Indian", "India",
      ["Raw mango in mustard oil and spices", "Tangy, hot and aromatic"], [("1 lb", 699)],
      ["mango pickle", "achar", "indian pickle"], unit="1 lb jar", attrs={"ingredients": "Raw mango, mustard oil, salt, chili, fenugreek, fennel"}),
    P("ackee", "Jamaican Ackee in Brine", SAUCES, "kingston", "Kingston Kitchen", "Jamaican", "Jamaica",
      ["Ready for ackee and saltfish", "Drain, rinse and fold in gently"], [("1 lb", 1299)],
      ["ackee", "ackee and saltfish", "ackee fruit"], unit="19 oz can", attrs={"ingredients": "Ackee, water, salt"}),
    # ---------------------------------------------------------------------------------------- snacks
    P("plantain-chips", "Plantain Chips, Lightly Salted", SNACKS, "accra-market", "Accra Market", "West African", "Ghana",
      ["Thin-sliced green plantain fried in sunflower oil", "Lightly salted"], [("8 oz", 399), ("1 lb", 699)],
      ["plantain chips", "plantain", "fried plantain"], unit="6-pack", also=(("mama-put", 1.08),), deal=0.2,
      attrs={"ingredients": "Plantain, sunflower oil, salt"}),
    P("chin-chin", "Chin Chin, Crunchy", SNACKS, "mama-put", "Mama Put", "Nigerian", "United States",
      ["Lightly sweet, nutmeg-scented", "Fried fresh in Houston each week"], [("1 lb", 699)],
      ["chin chin", "fried snack", "nigerian snacks"], unit="1 lb", attrs={"ingredients": "Wheat flour, sugar, butter, milk, nutmeg",
                                                                          "allergens": "Contains wheat"}),
    P("kuli-kuli", "Kuli Kuli Peanut Snack", SNACKS, "lagos-larder", "Lagos Larder", "Nigerian", "Nigeria",
      ["Crunchy spiced peanut rings", "Snack with garri or kunu"], [("8 oz", 599)],
      ["kuli kuli", "peanut snack", "roasted peanuts"], unit="8 oz", attrs={"ingredients": "Peanuts, ginger, chili, salt",
                                                                             "allergens": "Contains peanuts"}),
    P("at-coconut-candy", "AfroTransact Brand Coconut Candy", SNACKS, "retail", "AfroTransact Brand", "West African", "Ghana",
      ["Chewy caramelised coconut", "A childhood favourite from Accra to Lagos"], [("8 oz", 449)],
      ["coconut candy", "coconut", "grated coconut"], unit="8 oz", stock=250, attrs={"ingredients": "Coconut, sugar"}),
    P("namkeen", "Spicy Mixture (Namkeen)", SNACKS, "patel", "Patel Grocers", "Indian", "India",
      ["Sev, peanuts, lentils and curry leaves", "Tea-time crunch"], [("1 lb", 549)],
      ["namkeen", "indian snack", "chivda"], unit="1 lb", attrs={"ingredients": "Gram flour, peanuts, lentils, curry leaves, spices",
                                                                 "allergens": "Contains peanuts"}),
    P("tamarind-balls", "Tamarind Balls", SNACKS, "kingston", "Kingston Kitchen", "Jamaican", "Jamaica",
      ["Sweet, sour and a little spicy", "Rolled in sugar the island way"], [("8 oz", 499)],
      ["tamarind", "tamarind pods", "caribbean sweets"], unit="8 oz", attrs={"ingredients": "Tamarind pulp, sugar, chili"}),
    # ---------------------------------------------------------------------------------------- drinks
    P("hibiscus", "Dried Hibiscus (Bissap / Zobo)", TEAS, "dakar", "Dakar Market", "Senegalese", "Senegal",
      ["Whole calyces for bissap, zobo and sorrel", "Resealable bag"], [("1 lb", 1099)],
      ["hibiscus", "bissap", "sorrel drink"], unit="1 lb", stock=120, attrs={"ingredients": "Dried hibiscus calyces"}),
    P("kenyan-coffee", "Kenyan AA Coffee, Whole Bean", COFFEE, "retail", "Nairobi Roast", "Kenyan", "Kenya",
      ["Blackcurrant and grapefruit notes", "Roasted to order, shipped same day"], [("1 lb", 1900)],
      ["kenyan coffee", "coffee beans", "coffee plantation"], unit="12 oz", attrs={"ingredients": "Arabica coffee"}),
    P("yirgacheffe", "Yirgacheffe Coffee, Whole Bean", COFFEE, "addis", "Addis Spice House", "Ethiopian", "Ethiopia",
      ["Floral, citrus and bergamot", "For the jebena coffee ceremony"], [("1 lb", 1799)],
      ["ethiopian coffee", "coffee ceremony", "jebena"], unit="1 lb", attrs={"ingredients": "Arabica coffee"}),
    P("cocoa-drink", "Ghana Cocoa Drink Powder", TEAS, "accra-market", "Accra Market", "Ghanaian", "Ghana",
      ["Rich chocolate drink from Ghanaian cocoa", "Mix with hot milk"], [("1 lb", 899)],
      ["cocoa", "cocoa beans", "hot chocolate"], unit="1 lb", attrs={"ingredients": "Cocoa powder, sugar, milk powder"}),
    P("masala-chai", "Masala Chai Loose Tea", TEAS, "patel", "Patel Grocers", "Indian", "India",
      ["Assam CTC with cardamom, ginger and clove", "Brew with milk and sugar"], [("8 oz", 899)],
      ["masala chai", "chai tea", "spiced tea"], unit="8 oz", attrs={"ingredients": "Black tea, cardamom, ginger, clove, cinnamon"}),
    # ---------------------------------------------------------------------------------------- fresh & frozen
    P("smoked-catfish", "Smoked Catfish, Whole", FROZEN, "lagos-larder", "Lagos Larder", "Nigerian", "Nigeria",
      ["Wood-smoked, ready for pepper soup and ogbono", "Shipped frozen"], [("1 lb", 1599), ("2 lb", 2899)],
      ["smoked fish", "catfish", "dried fish"], unit="1 lb", fulfilment="AFROTRANSACT",
      attrs={"ingredients": "Catfish", "allergens": "Contains fish", "temp": "Frozen (≤ 0°F)"}),
    P("goat-meat", "Halal Goat Meat, Cubed", FROZEN, "mama-put", "Mama Put", "West African", "United States",
      ["Bone-in cubes for stew and pepper soup", "Halal, shipped frozen"], [("2 lb", 2499), ("5 lb", 5499)],
      ["goat meat", "goat stew", "pepper soup"], unit="2 lb", attrs={"ingredients": "Goat meat", "temp": "Frozen (≤ 0°F)"}),
    P("frozen-okra", "AfroTransact Brand Chopped Okra, Frozen", FROZEN, "retail", "AfroTransact Brand", "West African",
      "United States", ["Chopped and flash-frozen", "For okra soup and gumbo"], [("1 lb", 499)], ["okra", "okra soup", "okra pods"],
      unit="1 lb", stock=200, attrs={"ingredients": "Okra", "temp": "Frozen (≤ 0°F)"}),
    P("cassava-leaves", "Cassava Leaves, Pounded, Frozen", FROZEN, "accra-market", "Accra Market", "Sierra Leonean", "Ghana",
      ["Ready for plasas", "Pounded fresh, frozen in 1 lb blocks"], [("1 lb", 699)], ["cassava leaves", "cassava plant", "leafy greens"],
      unit="1 lb", attrs={"ingredients": "Cassava leaves", "temp": "Frozen (≤ 0°F)"}),
    # ---------------------------------------------------------------------------------------- health
    P("moringa", "Moringa Leaf Powder", WELLNESS, "tamale", "Green Leaf Naturals", "West African", "Ghana",
      ["Shade-dried leaf powder", "Not intended to diagnose, treat, cure or prevent any disease"], [("8 oz", 1300)],
      ["moringa", "moringa powder", "moringa leaves"], unit="8 oz", attrs={"ingredients": "Moringa oleifera leaf powder"}),
    P("bitter-leaf", "Bitter Leaf, Washed and Dried", WELLNESS, "lagos-larder", "Lagos Larder", "Nigerian", "Nigeria",
      ["Pre-washed to tame the bitterness", "For ofe onugbu"], [("8 oz", 699)], ["bitter leaf", "vernonia amygdalina", "dried leaves"],
      unit="8 oz", attrs={"ingredients": "Vernonia amygdalina leaves"}),
    P("lemongrass-tea", "Ginger & Lemongrass Tea", WELLNESS, "accra-market", "Accra Market", "Ghanaian", "Ghana",
      ["Caffeine-free herbal infusion", "Sun-dried lemongrass with ginger root"], [("8 oz", 799)],
      ["lemongrass", "ginger tea", "herbal tea"], unit="8 oz", attrs={"ingredients": "Lemongrass, ginger root"}),
    P("baobab", "Baobab Fruit Powder", WELLNESS, "dakar", "Dakar Market", "Senegalese", "Senegal",
      ["Tangy pulp of the baobab fruit", "Stir into bouye, smoothies or yoghurt"], [("8 oz", 1199)],
      ["baobab", "baobab fruit", "baobab tree"], unit="8 oz", attrs={"ingredients": "Baobab fruit pulp powder"}),
    # ---------------------------------------------------------------------------------------- beauty
    P("shea-butter", "Raw Unrefined Shea Butter", BUTTERS, "tamale", "Tamale Shea Co-op", "Ghanaian", "Ghana",
      ["Grade A, from a women's cooperative", "Ingredients: Butyrospermum parkii (shea) butter"], [("8 oz", 999), ("16 oz", 1599)],
      ["shea butter", "shea nuts", "shea"], unit="16 oz", stock=150, also=(("retail", 1.1),),
      attrs={"inci": "Butyrospermum parkii (shea) butter"}),
    P("at-black-soap", "AfroTransact Brand African Black Soap", SOAPS, "retail", "AfroTransact Brand", "West African", "Ghana",
      ["Plantain-skin ash, cocoa pod and shea", "Ingredients listed in full (MoCRA)"], [("8 oz", 799)],
      ["african black soap", "black soap", "handmade soap"], unit="8 oz", stock=800, deal=0.12,
      attrs={"inci": "Plantain peel ash, cocoa pod ash, Butyrospermum parkii butter, Elaeis guineensis oil"}),
    P("coconut-oil", "Virgin Coconut Oil", BUTTERS, "tamale", "Tamale Naturals", "West African", "Ghana",
      ["Cold-pressed from fresh coconut", "For skin, hair and cooking"], [("8 oz", 899), ("16 oz", 1499)],
      ["coconut oil", "coconuts", "coconut"], unit="16 oz", attrs={"inci": "Cocos nucifera (coconut) oil"}),
    P("cocoa-butter", "Raw Cocoa Butter", BUTTERS, "accra-market", "Accra Market", "Ghanaian", "Ghana",
      ["Unrefined, chocolate-scented", "Melts at body temperature"], [("8 oz", 1099)],
      ["cocoa butter", "cocoa pods", "cacao"], unit="8 oz", attrs={"inci": "Theobroma cacao (cocoa) seed butter"}),
    P("neem-soap", "Neem & Turmeric Soap Bar", SOAPS, "patel", "Patel Grocers", "Indian", "India",
      ["Cold-process bar with neem oil", "For oily and blemish-prone skin"], [("4 oz", 499)],
      ["neem", "neem soap", "neem leaves"], unit="4 oz", attrs={"inci": "Sodium cocoate, Azadirachta indica oil, Curcuma longa powder",
                                                                "skin": "Oily"}),
    P("shea-body-butter", "Whipped Shea Body Butter", BUTTERS, "tamale", "Tamale Naturals", "Ghanaian", "Ghana",
      ["Whipped shea with coconut oil", "Lemongrass, lavender or unscented"],
      [({"Size": "8 oz", "Scent": "Lemongrass"}, 1299), ({"Size": "8 oz", "Scent": "Lavender"}, 1299),
       ({"Size": "16 oz", "Scent": "Unscented"}, 1999)], ["body butter", "lemongrass", "whipped butter"], unit="8 oz",
      attrs={"inci": "Butyrospermum parkii butter, Cocos nucifera oil, Cymbopogon citratus oil"}),
    P("chebe-oil", "Chebe Hair Oil", HAIR_OILS, "tamale", "Tamale Naturals", "Chadian", "Chad",
      ["Chebe powder infused in shea and castor oil", "Seal ends to reduce breakage"], [("4 oz", 1499)],
      ["chebe", "hair oil", "natural hair"], unit="4 oz", attrs={"inci": "Ricinus communis oil, Butyrospermum parkii oil, Croton zambesicus"}),
    P("castor-oil", "Jamaican Black Castor Oil", HAIR_OILS, "kingston", "Kingston Kitchen", "Jamaican", "Jamaica",
      ["Roasted-bean castor oil, ash-rich", "For edges, brows and scalp massage"], [("4 oz", 999), ("8 oz", 1599)],
      ["castor oil", "castor beans", "hair care"], unit="4 oz", attrs={"inci": "Ricinus communis (castor) seed oil"}),
    P("braiding-hair", "Pre-Stretched Braiding Hair, 8 Packs", BRAIDING, "retail", "AfroTransact Brand", "Pan-African",
      "United States", ["Hot-water set, itch-free fibre", "8 packs braid a full head of knotless"],
      [({"Length": "26 in", "Color": "1B"}, 1599), ({"Length": "26 in", "Color": "2"}, 1599),
       ({"Length": "30 in", "Color": "1B"}, 1799)], ["box braids", "braided hair", "braids"], unit="8 packs", stock=300,
      attrs={"fibre": "Pre-stretched synthetic", "packs": "8"}),
    # ---------------------------------------------------------------------------------------- fabrics & fashion
    P("ankara-fabric", "Ankara Wax Print Fabric", ANKARA, "kente-co", "Lagos Textile Hub", "Nigerian", "Nigeria",
      ["100% cotton, colour-fast", "Enough for iro and buba"],
      [(LP("6 yd", "Classic"), 3400), (LP("6 yd", "Geometric"), 3400), (LP("12 yd", "Classic"), 6200)],
      ["ankara", "african wax print", "wax print fabric"], unit="6 yd", stock=40, attrs={"width": "46 in"}),
    P("kente-cloth", "Kente Cloth, Hand-Woven", KENTE, "bonwire", "Bonwire Weavers", "Ghanaian", "Ghana",
      ["Strip-woven silk-cotton from Bonwire", "Each cloth tagged with its weaver's name"], [(LP("6 yd", "Classic"), 18000)],
      ["kente cloth", "kente", "kente weaving"], unit="6 yd", stock=6, attrs={"fibre": "Silk-cotton", "width": "44 in"}),
    P("kente-stole", "Hand-Woven Kente Graduation Stole", KENTE, "bonwire", "Bonwire Weavers", "Ghanaian", "Ghana",
      ["Strip-woven silk-cotton, 72 in", "Tagged with the weaver's name"], [(LP("2 yd", "Classic"), 6400)],
      ["kente stole", "graduation stole", "kente"], unit="72 in", stock=9, attrs={"fibre": "Silk-cotton", "width": "44 in"}),
    P("aso-oke", "Aso-Oke Fabric Set", KENTE, "kente-co", "Iseyin Looms", "Nigerian", "Nigeria",
      ["Hand-loomed in Iseyin", "Gele, iborun and wrapper in one set"], [(LP("4 yd", "Classic"), 8900)],
      ["aso oke", "aso-oke", "yoruba textile"], unit="4 yd", stock=15, attrs={"fibre": "Silk-cotton"}),
    P("swiss-lace", "Swiss Voile Lace", LACE, "kente-co", "Kente & Co.", "Nigerian", "Switzerland",
      ["Breathable cotton voile with eyelet embroidery", "For owambe and church"], [(LP("4 yd", "Floral"), 6500)],
      ["lace fabric", "voile lace", "embroidered lace"], unit="5 yd", stock=20, attrs={"fibre": "Lace"}),
    P("adire", "Adire Indigo Tie-Dye Fabric", ANKARA, "kente-co", "Abeokuta Dyers", "Nigerian", "Nigeria",
      ["Hand-dyed indigo from Abeokuta", "Each piece is one of a kind"], [(LP("4 yd", "Abstract"), 4500)],
      ["adire", "indigo dye", "tie-dye fabric"], unit="4 yd", stock=18),
    P("ankara-maxi-dress", "Ankara Print Maxi Dress", DRESSES, "kente-co", "Kente & Co.", "Nigerian", "United States",
      ["Wax-print cotton with side pockets", "Sewn in Atlanta"], [(SC(s, "Multicolour"), 6800) for s in ("S", "M", "L", "XL")],
      ["ankara dress", "african print dress", "wax print dress"], unit="One piece", stock=12),
    P("auto-gele", "Ready-to-Wear Auto Gele Headwrap", HEADWRAPS, "owambe", "Owambe Party Co.", "Nigerian", "United States",
      ["Pre-tied, pleated aso-oke", "Adjustable band"], [(c, 3500) for c in ("Gold", "Wine", "Royal blue")],
      ["gele", "headwrap", "head tie"], unit="One size", stock=14),
    P("ankara-wrap-top", "Ankara Wrap Top", TOPS, "kente-co", "Kente & Co.", "Ghanaian", "United States",
      ["Wrap front with tie waist", "Pairs with jeans or a matching skirt"], [(SC(s, "Multicolour"), 3900) for s in ("S", "M", "L")],
      ["ankara top", "wax print", "african fashion"], unit="One piece", stock=12),
    P("dashiki", "Dashiki Shirt, Cotton", MEN_SHIRTS, "kente-co", "Kente & Co.", "Pan-African", "Ghana",
      ["Classic angelina print", "Relaxed fit, short sleeves"], [(SC(s, "Multicolour"), 4500) for s in ("M", "L", "XL")],
      ["dashiki", "dashiki shirt", "angelina print"], unit="One piece", stock=12, attrs={"fit": "Relaxed"}),
    P("agbada", "Three-Piece Agbada Set", MEN_AGBADA, "owambe", "Owambe Party Co.", "Nigerian", "Nigeria",
      ["Agbada, buba and sokoto", "Embroidered neckline"], [(SC(s, "White"), 18900) for s in ("L", "XL", "2XL")],
      ["agbada", "yoruba attire", "nigerian wedding attire"], unit="3 pieces", stock=6,
      attrs={"material": "Brocade", "pieces": "3 (agbada set)"}),
    P("kids-ankara-dress", "Kids' Ankara Party Dress", KIDS, "kente-co", "Kente & Co.", "Nigerian", "United States",
      ["Puff sleeves and a twirl skirt", "Cotton wax print"], [(SC(s, "Multicolour"), 2900) for s in ("4", "6", "8")],
      ["children african print", "kids dress", "african print"], unit="One piece", stock=10),
    # ---------------------------------------------------------------------------------------- books
    P("pepper-and-palm", "Pepper & Palm: A West African Kitchen (Hardcover)", COOKBOOKS, "books", "Diaspora Books",
      "West African", "United States", ["120 recipes from Dakar to Lagos", "Photographed in home kitchens"],
      [("Hardcover", 2800)], ["west african cuisine", "jollof rice", "egusi soup"], unit="Hardcover, 288 pp", stock=50,
      attrs={"author": "Ama Mensah (demo title)", "publisher": "Diaspora Books", "pages": "288", "gtin": "9798891230019"}),
    P("things-fall-apart", "Things Fall Apart by Chinua Achebe (Paperback)", FICTION, "books", "Anchor Books", "Nigerian",
      "United States", ["Achebe's 1958 classic of Igbo life and colonialism", "Anchor paperback edition"], [("Paperback", 1500)],
      ["chinua achebe", "igbo", "nigerian village"], unit="Paperback, 209 pp", stock=40,
      attrs={"author": "Chinua Achebe", "publisher": "Anchor Books", "pages": "209", "gtin": "9780385474542"}),
    P("half-yellow-sun", "Half of a Yellow Sun by Chimamanda Ngozi Adichie (Paperback)", FICTION, "books", "Anchor Books",
      "Nigerian", "United States", ["Orange Prize-winning novel of the Biafran war", "Anchor paperback edition"],
      [("Paperback", 1700)], ["chimamanda ngozi adichie", "biafra", "nsukka"], unit="Paperback, 543 pp", stock=40,
      attrs={"author": "Chimamanda Ngozi Adichie", "publisher": "Anchor Books", "pages": "543", "gtin": "9781400095209"}),
    P("anansi-tales", "Anansi and the Talking Melon: Tales for Children", CHILDRENS, "books", "Diaspora Books", "Ghanaian",
      "United States", ["Ten Akan folk tales retold for ages 4–8", "Read-aloud length"], [("Hardcover", 1899)],
      ["anansi", "akan folklore", "children reading"], unit="Hardcover, 48 pp", stock=40,
      attrs={"author": "Kofi Owusu (demo title)", "publisher": "Diaspora Books", "pages": "48", "gtin": "9798891230026"}),
    # ---------------------------------------------------------------------------------------- home & art, party
    P("mudcloth-pillow", "Bogolan Mudcloth Pillow Cover", PILLOWS, "bamako", "Bamako Home", "Malian", "Mali",
      ["Hand-dyed with fermented mud", "Hidden zipper; insert not included"],
      [({"Size": "18 in", "Pattern": "Classic"}, 3200), ({"Size": "22 in", "Pattern": "Arrow"}, 3800)],
      ["mudcloth", "bogolan", "bogolanfini"], unit="18 × 18 in", stock=18,
      attrs={"material": "Hand-woven cotton, mud dye", "dims": "18 × 18 in / 22 × 22 in"}),
    P("adinkra-print", "Adinkra Symbols Wall Art Print", WALL_ART, "bamako", "Accra Studio", "Ghanaian", "Ghana",
      ["Gye Nyame, Sankofa and Dwennimmen", "Archival ink on cotton rag"], [({"Size": "18 in", "Pattern": "Classic"}, 4500)],
      ["adinkra", "adinkra symbols", "adinkra cloth"], unit="18 × 24 in", stock=15, attrs={"material": "Cotton rag paper", "dims": "18 × 24 in"}),
    P("tingatinga-print", "Tingatinga Painting Print", WALL_ART, "bamako", "Dar Studio", "Tanzanian", "Tanzania",
      ["Bright enamel-style animals of the Tingatinga school", "Giclée print"], [({"Size": "22 in", "Pattern": "Dots"}, 5900)],
      ["tingatinga", "tanzanian painting", "african painting"], unit="22 × 28 in", stock=10,
      attrs={"material": "Giclée on canvas paper", "dims": "22 × 28 in"}),
    P("ankara-bunting", "Ankara Print Bunting Banner", PARTY, "owambe", "Owambe Party Co.", "Nigerian", "United States",
      ["Ten wax-print flags on cotton tape", "Reusable for birthdays and naming ceremonies"],
      [({"Size": "18 in", "Pattern": "Classic"}, 1900), ({"Size": "22 in", "Pattern": "Arrow"}, 2400)],
      ["bunting", "party decorations", "wax print"], unit="10 ft", stock=30, attrs={"material": "Cotton wax print", "dims": "10 ft string"}),
    P("sequin-runner", "Gold Sequin Table Runner", PARTY, "owambe", "Owambe Party Co.", "Pan-African", "United States",
      ["Owambe-ready shimmer for the high table", "Machine washable"], [({"Size": "18 in", "Pattern": "Dots"}, 2900)],
      ["table runner", "sequin", "party table"], unit="12 × 108 in", stock=25, attrs={"material": "Sequin mesh", "dims": "12 × 108 in"}),
]

# ---------------------------------------------------------------------------------------- home content
# Images: (key, queries). Campaigns replace the image-less placements of content's V2 migration (same copy).
HEROES = [
    {"replaces": "plc_hero_party_jollof", "name": "AfroTransact Brand jollof", "priority": 40,
     "payload": {"theme": "gold", "kicker": "AfroTransact Brand", "title": "Party jollof, sorted.",
                 "sub": "Our own seasoning, rice and pepper sauce — sold and fulfilled by AfroTransact.",
                 "cta": "Shop AfroTransact Brand", "href": "/s?q=AfroTransact+Brand", "label": "AfroTransact",
                 "imageAlt": "A pot of party jollof rice"},
     "image": ("hero-jollof", ["jollof rice", "party jollof rice", "nigerian jollof"])},
    {"replaces": "plc_hero_diwali", "name": "Diwali table", "priority": 30,
     "payload": {"theme": "green", "kicker": "Diwali", "title": "Light up the table.",
                 "sub": "Aged basmati, diyas and spices from Edison to your door.", "cta": "Shop Indian kitchen",
                 "href": "/communities/indian", "label": "AfroTransact", "imageAlt": "Diya lamps lit for Diwali"},
     "image": ("hero-diwali", ["diwali diya", "diya lamps", "indian spices"])},
    {"replaces": "plc_hero_kingston_jerk", "name": "Kingston Kitchen jerk paste", "priority": 20,
     "payload": {"theme": "ink", "kicker": "Kingston Kitchen", "title": "Jerk season is every season.",
                 "sub": "Blue Mountain jerk paste, fulfilled by AfroTransact.", "cta": "Shop now",
                 "href": "/s?q=jerk+paste", "label": "AfroTransact", "imageAlt": "Jerk chicken on the grill"},
     "image": ("hero-jerk", ["jerk chicken", "jamaican jerk", "grilled chicken"])},
    {"replaces": "plc_hero_braider", "name": "Book a braider", "priority": 10,
     "payload": {"theme": "sand", "kicker": "Services", "title": "Book a braider for Saturday.",
                 "sub": "Licensed stylists near you. Deposits protected.", "cta": "Find a stylist", "href": "/services/hair",
                 "label": "AfroTransact", "imageAlt": "Box braids being styled"},
     "image": ("hero-braids", ["box braids hairstyle", "braiding hair salon", "cornrows"])},
    {"replaces": None, "name": "Kente for graduation", "priority": 5,
     "payload": {"theme": "gold", "kicker": "Graduation", "title": "Hand-woven kente for the big day.",
                 "sub": "Stoles from the Bonwire weavers, each tagged with its weaver's name.", "cta": "Shop kente",
                 "href": "/s?q=kente", "label": "AfroTransact", "imageAlt": "Kente cloth on a loom"},
     "image": ("hero-kente", ["kente cloth", "kente weaving", "kente"])},
]

TILES = [
    {"replaces": "plc_tiles_communities", "name": "Shop by community", "priority": 10,
     "payload": {"title": "Shop by community", "cta": "See all communities", "href": "/communities"},
     "cells": [("Nigerian", "/communities/nigerian", "tile-nigerian", ["egusi soup", "nigerian food", "suya"]),
               ("Ghanaian", "/communities/ghanaian", "tile-ghanaian", ["waakye", "ghanaian food", "kenkey"]),
               ("Ethiopian", "/communities/ethiopian", "tile-ethiopian", ["injera", "ethiopian food", "doro wat"]),
               ("Jamaican", "/communities/jamaican", "tile-jamaican", ["ackee and saltfish", "jamaican food", "rice and peas"])]},
    {"replaces": None, "name": "Shop by department", "priority": 9,
     "payload": {"title": "Shop by department", "cta": "See everything", "href": "/s"},
     "cells": [("Groceries", "/s?dept=Groceries", "tile-groceries", ["african market", "spice market", "market stall"]),
               ("Fashion", "/s?dept=Fashion", "tile-fashion", ["african fashion", "wax print dress", "ankara fashion"]),
               ("Beauty", "/s?dept=Beauty", "tile-beauty", ["shea butter cosmetics", "natural cosmetics", "skin care products"]),
               ("Home & Art", "/s?dept=Home+%26+Art", "tile-home", ["african art", "woven baskets", "african sculpture"])]},
    {"replaces": None, "name": "More kitchens to discover", "priority": 8,
     "payload": {"title": "More kitchens to discover", "cta": "See all communities", "href": "/communities"},
     "cells": [("Indian", "/communities/indian", "tile-indian", ["indian thali", "biryani", "indian food"]),
               ("Senegalese", "/communities/senegalese", "tile-senegalese", ["thieboudienne", "senegalese food", "bissap"]),
               ("Kenyan", "/communities/kenyan", "tile-kenyan", ["ugali", "kenyan food", "nyama choma"]),
               ("Caribbean", "/communities/jamaican", "tile-caribbean", ["caribbean food", "plantain", "callaloo"])]},
]

BANNERS = [
    {"replaces": "plc_banner_kente", "name": "Bonwire kente banner", "priority": 10,
     "payload": {"position": "home-mid", "advertiser": "Bonwire Weavers Collective",
                 "title": "Hand-woven kente for graduation season", "sub": "Each stole is tagged with its weaver’s name.",
                 "cta": "Shop kente", "href": "/s?q=kente"},
     "image": ("banner-kente", ["kente", "kente cloth pattern", "kente weaver"])},
    {"replaces": None, "name": "Tamale Naturals shea banner", "priority": 5,
     "payload": {"position": "home-bottom", "advertiser": "Tamale Naturals", "title": "Shea from the women of Tamale",
                 "sub": "Grade A unrefined shea butter, straight from the cooperative.", "cta": "Shop shea",
                 "href": "/s?q=shea+butter"},
     "image": ("banner-shea", ["shea butter", "shea nuts processing", "shea butter women"])},
]

# home_product_row: query rails (search; "A OR B" runs one query per term) and the deal rail (ATINs, filled in at run time).
RAILS = [
    {"name": "Deals of the week", "priority": 60, "payload": {"title": "Deals of the week", "href": "/s?q=AfroTransact"},
     "deals": True},
    {"name": "AfroTransact Brand", "priority": 55, "payload": {"title": "AfroTransact Brand", "href": "/s?q=AfroTransact+Brand",
                                                               "query": "AfroTransact Brand"}},
    {"name": "Spice cupboard", "priority": 45, "payload": {"title": "Spice cupboard", "href": "/s?q=spice",
                                                           "query": "spice OR seasoning OR pepper"}},
    {"name": "Fabrics & fashion", "priority": 40, "payload": {"title": "Fabrics & fashion", "href": "/s?dept=Fashion",
                                                              "query": "ankara OR kente OR dashiki OR gele"}},
    {"name": "Beauty from the source", "priority": 35, "payload": {"title": "Beauty from the source",
                                                                   "href": "/s?dept=Beauty",
                                                                   "query": "shea OR soap OR oil"}},
]

CATEGORY_BANNERS = [
    {"name": "Groceries category banner", "category": "Groceries",
     "payload": {"title": "Pantry staples from home", "sub": "Garri, egusi, teff and basmati — fulfilled by AfroTransact.",
                 "cta": "Shop pantry", "href": "/s?dept=Groceries"},
     "image": ("catbanner-groceries", ["african grocery store", "grains market", "spices in bowls"])},
    {"name": "Fashion category banner", "category": "Fashion",
     "payload": {"title": "Wax prints, kente and aso-oke", "sub": "Fabrics by the yard and ready-to-wear from independent makers.",
                 "cta": "Shop fabrics", "href": "/s?dept=Fashion"},
     "image": ("catbanner-fashion", ["wax print fabric market", "african textiles", "fabric market"])},
]

# Sponsored products (content's ads campaigns): advertiser seller, its products, keywords.
ADS = [
    {"seller": "kingston", "name": "Jerk season", "products": ["jerk-paste", "jamaican-curry"], "keywords": ["jerk", "curry"],
     "categories": ["Groceries"], "bid": 45, "budget": 25000},
    {"seller": "tamale", "name": "Shea for winter skin", "products": ["shea-butter", "shea-body-butter"],
     "keywords": ["shea", "body butter"], "categories": ["Beauty"], "bid": 40, "budget": 20000},
    {"seller": "bonwire", "name": "Graduation kente", "products": ["kente-stole", "kente-cloth"], "keywords": ["kente"],
     "categories": ["Fashion"], "bid": 60, "budget": 30000},
]

# Pre-order windows (design/at-preorder.js): one OPEN for Lubbock, one UPCOMING for El Paso. Dates are relative to the run.
PREORDERS = [
    {"name": "Lubbock Pre-order #7", "target": "US/TX/Lubbock", "opens_in_days": -3, "open_days": 12, "deliver_after_days": 3,
     "ribbon": "Lubbock pre-order is open", "headline": "Fresh egusi, garri and palm oil, delivered to Lubbock on {deliver}.",
     "pickup_point": "Lubbock: South Plains Mall, north lot", "sms": "AfroTransact: Lubbock pre-order is open until {close}. Garri, egusi, palm oil and more. Order: {url}",
     "hero": ("preorder-lubbock-hero", ["west african market", "african grocery", "market produce"]),
     "pdp": ("preorder-lubbock-pdp", ["egusi", "palm oil", "garri"])},
    {"name": "El Paso Pre-order #3", "target": "US/TX/El Paso", "opens_in_days": 4, "open_days": 15, "deliver_after_days": 4,
     "ribbon": "El Paso pre-order opens {open}", "headline": "El Paso, your next pre-order opens {open}.",
     "pickup_point": "El Paso: Cielo Vista Mall, east entrance", "sms": "AfroTransact: El Paso pre-order opens {open}. Get on the list: {url}",
     "hero": ("preorder-elpaso-hero", ["spices market", "african spices", "grocery basket"]),
     "pdp": ("preorder-elpaso-pdp", ["plantain chips", "jollof rice spices", "dried pepper"])},
]
PREORDER_CATEGORIES = ["Pantry", "Spices & seasonings", "Snacks", "Sauces & pastes", "Grains & rice"]

# Local test users (S276). identity creates them on the local profile (TestPersonas.java: accounts, roles, the saved
# addresses, organisation memberships as soon as the organisations above exist); the `users` step checks that each one
# signs in. (email, persona, app, organisation it works for, two-step code required)
TEST_USER_PASSWORD = "Test1234!Local"
TEST_USERS: list[tuple[str, str, str, Union[str, None], bool]] = [
    ("customer.houston@afrotransact.test", "Customer, Houston 77042 address", "Storefront", None, False),
    ("customer.lubbock@afrotransact.test", "Customer, Lubbock 79401 address (open pre-order)", "Storefront", None, False),
    ("seller.owner@afrotransact.test", "Owner, Mama Put Pantry", "Seller Center", "org_mama_put_pantry", False),
    ("seller.staff@afrotransact.test", "Catalog manager, Mama Put Pantry", "Seller Center", "org_mama_put_pantry", False),
    ("provider.owner@afrotransact.test", "Owner, Aminata's Braids", "Provider Hub", "org_aminatas_braids", False),
    ("retail.operator@afrotransact.test", "Operator, AfroTransact Retail (1P)", "Seller Center", RETAIL_ORG, False),
    ("staff.admin@afrotransact.test", "Super Admin", "Staff console", None, True),
    ("staff.ops@afrotransact.test", "Operations Manager", "Staff console", None, True),
    ("staff.trust@afrotransact.test", "Trust & Safety", "Staff console", None, True),
    ("staff.finance@afrotransact.test", "Finance", "Staff console", None, True),
    ("staff.support@afrotransact.test", "Customer Support", "Staff console", None, True),
    ("staff.marketing@afrotransact.test", "Marketing (content, ads)", "Staff console", None, True),
    ("staff.catalog@afrotransact.test", "Catalog Manager", "Staff console", None, True),
    ("staff.retail@afrotransact.test", "Retail Buyer (1P)", "Staff console", None, True),
    ("staff.fulfilment@afrotransact.test", "Fulfilment Ops", "Staff console", None, True),
    ("staff.inventory@afrotransact.test", "Inventory Manager", "Staff console", None, True),
    ("staff.dispatch@afrotransact.test", "Dispatcher (delivery runs)", "Staff console", None, True),
    ("staff.product@afrotransact.test", "Product Owner", "Staff console", None, True),
    ("staff.developer@afrotransact.test", "Developer", "Staff console", None, True),
    ("staff.auditor@afrotransact.test", "Auditor (read-only)", "Staff console", None, True),
    ("driver@afrotransact.test", "Driver", "Driver app", None, False),
]
