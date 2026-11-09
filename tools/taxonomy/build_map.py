#!/usr/bin/env python3
"""Generate and validate the category tax map (single source of truth for the category/tax build).

    python3 tools/taxonomy/build_map.py            # write category_tax_map.json and scenarios.json
    python3 tools/taxonomy/build_map.py --check    # fail if the committed files are stale or the data is invalid

Inputs: source_scenarios.json (Appendix A of the build specification, verbatim, 105 rows) and the definitions below
(facts, groups, categories, decision rows). Outcome, Stripe code, rate wording and condition text of every decision row
come from the Appendix row it covers, so the document stays the only source of those values.

NOT TAX OR LEGAL ADVICE. DRAFT: a Texas tax professional must approve before production use.
Decision tables are evaluated top to bottom, first match wins; the rows are also written to be mutually exclusive
(the validator proves exactly one row matches for every combination of facts a category accepts).
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
RULE_VERSION = "tx-2026.11.09-draft.1"

# ---------------------------------------------------------------- expression helpers
def T(f): return {"op": "isTrue", "fact": f}
def Fa(f): return {"op": "isFalse", "fact": f}
def EQ(f, v): return {"op": "eq", "fact": f, "value": v}
def IN(f, vs): return {"op": "in", "fact": f, "values": vs}
def GT(f, v): return {"op": "gt", "fact": f, "value": v}
def GTE(f, v): return {"op": "gte", "fact": f, "value": v}
def LT(f, v): return {"op": "lt", "fact": f, "value": v}
def LTE(f, v): return {"op": "lte", "fact": f, "value": v}
def AND(*e): return {"all": list(e)}
def OR(*e): return {"any": list(e)}
def NOT(e): return {"not": e}

# ---------------------------------------------------------------- facts
def fact(id, label, kind, owner, help, unit=None, evidence=False, choices=None):
    d = {"id": id, "label": label, "kind": kind, "unit": unit, "owner": owner, "help": help, "evidence": evidence}
    if choices:
        d["choices"] = [{"value": v, "label": l} for v, l in choices]
    return d

FACTS = [
    fact("net_weight_oz", "Net weight (oz)", "DECIMAL", "VARIANT", "The net weight of one package in ounces, as printed on the label.", unit="oz"),
    fact("servings_per_package", "Servings per package", "INTEGER", "VARIANT", "How many servings the label says one package holds."),
    fact("individual_serving", "Sold as a single serving", "BOOLEAN", "VARIANT", "Yes if the package is one portion meant for one person at one time."),
    fact("is_candy", "Candy or confectionery", "BOOLEAN", "VARIANT", "Yes if this is candy, chocolate, gum or a sweet confection. Candy is taxed at any size."),
    fact("candy_coated", "Coated or sugar-glazed", "BOOLEAN", "VARIANT", "Yes if the nuts or seeds are candy-coated, chocolate-covered, honey-glazed or otherwise confectionery."),
    fact("sold_heated", "Sold hot or heated", "BOOLEAN", "OFFER", "Yes if you serve or sell this warm, or warm it before the customer gets it."),
    fact("heated_by_seller", "You heat it", "BOOLEAN", "OFFER", "Yes if you (not the customer) do the heating."),
    fact("utensils_provided", "You provide eating utensils", "BOOLEAN", "OFFER", "Yes if forks, spoons, straws or plates for eating it are included with the order."),
    fact("seller_is_bakery", "Your shop is a bakery", "BOOLEAN", "SELLER", "Taken from your seller profile: yes if your business is a bakery."),
    fact("prepared_meal", "Sold as a prepared meal", "BOOLEAN", "VARIANT", "Yes if it is ready to eat as a meal (cooked and portioned), not raw or for cooking at home."),
    fact("cooked_hot", "Sold cooked and hot", "BOOLEAN", "OFFER", "Yes if you sell it freshly cooked and hot."),
    fact("juice_percent", "Fruit or vegetable juice content", "PERCENT", "VARIANT", "The percentage of real fruit or vegetable juice, as stated on the label.", unit="%"),
    fact("sweetened", "Sweetened", "BOOLEAN", "VARIANT", "Yes if sugar, syrup, honey or another sweetener is added."),
    fact("soft_drink", "Classified as a soft drink", "BOOLEAN", "VARIANT", "Yes if it is a carbonated or sweetened drink in the soft-drink style (soda, sports or energy drink)."),
    fact("contains_milk", "Contains milk or a milk substitute", "BOOLEAN", "VARIANT", "Yes if milk, cream or a plant milk (oat, soy, almond...) is an ingredient."),
    fact("label_type", "Label on the package", "CHOICE", "VARIANT", "The panel printed on the package: Drug Facts, Supplement Facts or Nutrition Facts.",
         choices=[("DRUG_FACTS", "Drug Facts"), ("SUPPLEMENT_FACTS", "Supplement Facts"), ("NUTRITION_FACTS", "Nutrition Facts"), ("NONE", "No such label")]),
    fact("qualifying_label", "Qualifies as a labelled drug or supplement", "BOOLEAN", "VARIANT", "Yes only if the label is on the package and the product fits that label's definition. Our tax team checks this."),
    fact("label_evidence", "Photo or PDF of the label", "FILE", "VARIANT", "Upload a clear picture of the whole label. It is kept as the proof for the exemption.", evidence=True),
    fact("hygiene_kind", "Kind of hygiene product", "CHOICE", "VARIANT", "Pick what the product is.",
         choices=[("ORDINARY_NONMEDICATED", "Soap, toothpaste or other non-medicated item"), ("OTC_MEDICINE", "Over-the-counter medicine with Drug Facts"), ("FEMININE_HYGIENE", "Feminine hygiene product")]),
    fact("device_kind", "Kind of device", "CHOICE", "VARIANT", "Pick what fits best. Prescribed or medically qualifying devices need proof.",
         choices=[("QUALIFYING_EXEMPT_DEVICE", "Qualifying medical device"), ("PRESCRIBED_ELIGIBLE_DEVICE", "Prescribed eligible device"), ("NONQUALIFYING_CONSUMER_DEVICE", "Ordinary consumer device")]),
    fact("device_evidence", "Proof the device qualifies", "FILE", "VARIANT", "Upload the product listing, FDA clearance or the prescription rule that shows it qualifies.", evidence=True),
    fact("publication_type", "Type of publication", "CHOICE", "VARIANT", "Pick what the publication is.",
         choices=[("ORDINARY_MAGAZINE_PERIODICAL", "Magazine or periodical"), ("QUALIFYING_EXEMPT_NEWSPAPER_OR_OTHER", "Newspaper or other publication with a specific exemption")]),
    fact("publication_evidence", "Proof of the exemption", "FILE", "VARIANT", "Upload the page or notice that shows why it is exempt.", evidence=True),
    fact("wellness_class", "What this product is", "CHOICE", "VARIANT", "Pick what the product is under its label.",
         choices=[("SUPPLEMENT", "Dietary supplement (Supplement Facts)"), ("FOOD", "Food or food product"), ("ORDINARY_GOODS", "Other wellness goods")]),
    fact("delivery_mode", "How the course is delivered", "CHOICE", "VARIANT", "Live classes taught by an instructor, or a recording or other digital deliverable.",
         choices=[("LIVE_INSTRUCTOR_LED", "Live, led by an instructor"), ("RECORDED_OR_DIGITAL_DELIVERABLE", "Recorded or a digital file or access")]),
    fact("delivery_method", "How the customer gets it", "CHOICE", "VARIANT", "Download of a file, or online access only.",
         choices=[("DOWNLOAD", "Download"), ("STREAM_OR_ACCESS", "Online access or streaming")]),
    fact("exemption_confirmed", "Exemption confirmed by tax review", "BOOLEAN", "STAFF", "Set only by our tax team after review. Sellers do not fill this in."),
    fact("deliverable_kind", "What the buyer receives", "CHOICE", "VARIANT", "A file or artwork to keep, or only a personal service.",
         choices=[("DOWNLOADED_FILE", "A downloaded file, artwork or photo"), ("PERSONAL_SERVICE_NO_DELIVERABLE", "A personal service with no file delivered")]),
    fact("output_kind", "What the customer gets", "CHOICE", "VARIANT", "Photos or prints and files to keep, or only the service.",
         choices=[("TAXABLE_PRINTS_OR_DIGITAL_DELIVERABLE", "Photos, prints or digital files"), ("SERVICE_ONLY_NO_TAXABLE_DELIVERABLE", "Service only, nothing to keep")]),
    fact("admission_kind", "Kind of event", "CHOICE", "VARIANT", "Pick what fits the event. Exempt events need proof.",
         choices=[("TAXABLE_AMUSEMENT_ADMISSION", "Entertainment or amusement admission"), ("NONTAXABLE_ADMISSION_OR_EXEMPT_EVENT", "Admission that is not taxable, or an exempt event")]),
    fact("admission_evidence", "Proof the event is exempt", "FILE", "VARIANT", "Upload the organiser's exemption letter or event details.", evidence=True),
    fact("fitness_kind", "Class or membership", "CHOICE", "VARIANT", "A class led by an instructor, or access to a gym or facility.",
         choices=[("INSTRUCTOR_LED_CLASS", "Class or training led by an instructor"), ("MEMBERSHIP_OR_FACILITY_ACCESS", "Membership or facility access")]),
    fact("holiday_eligible", "Eligible for a tax holiday or special exemption", "BOOLEAN", "VARIANT", "Yes only if this item is listed for a legislated tax holiday or exemption. Our tax team checks this."),
    fact("exemption_window_active", "Sale date inside an exemption window", "BOOLEAN", "CONTEXT", "Worked out by the system from the sale date and the exemption calendar; never typed by a person."),
    fact("bundle_line_kind", "How the bundle is itemised", "CHOICE", "VARIANT", "Each item in a gift basket must be its own line with its own category and price.",
         choices=[("EXEMPT_FOOD_LINE_ITEMIZED", "Exempt food, listed as its own line"), ("TAXABLE_GOODS_LINE_ITEMIZED", "Taxable goods, listed as their own line"), ("UNITEMIZED_MIXED_BUNDLE", "Mixed items sold as one price, not itemised")]),
    fact("gift_card_event", "Gift card transaction", "CHOICE", "CONTEXT", "Set by the order system: the sale of the card, or its use to buy a taxable or an exempt item.",
         choices=[("SALE_MULTIPURPOSE_CARD", "Sale of a multipurpose card"), ("REDEMPTION_TAXABLE_ITEM", "Used to buy a taxable item"), ("REDEMPTION_EXEMPT_ITEM", "Used to buy an exempt item")]),
]

GROUPS = [
    ("package_size_servings", "Package size and servings", "Exempt only if more than 1 serving AND at least 2.5 oz; otherwise taxable. Candy is taxable at any size.", ["net_weight_oz", "servings_per_package", "individual_serving"]),
    ("candy_confectionery", "Candy / confectionery", "Candy classification makes the item taxable regardless of size or servings.", ["is_candy", "candy_coated"]),
    ("heating_utensils", "Heating and utensils", "Heating by the seller, or utensils from a non-bakery seller, turns an otherwise exempt food item taxable.", ["sold_heated", "heated_by_seller", "utensils_provided", "seller_is_bakery"]),
    ("prepared_vs_home_cooking", "Prepared vs. home cooking", "Raw or frozen food for home cooking is exempt; cooked or served prepared food is taxable.", ["prepared_meal", "cooked_hot"]),
    ("juice_sweetening", "Juice and sweetening", "More than 50% juice is exempt; sweetened drinks at 50% or less are taxable soft drinks.", ["juice_percent", "sweetened", "soft_drink", "contains_milk"]),
    ("fda_label_type", "FDA label type", "A qualifying Drug Facts or Supplement Facts product is exempt; the same shelf category without it is taxable.", ["label_type", "qualifying_label", "label_evidence"]),
    ("product_type_mixed", "Product type within a mixed subcategory", "Exempt and taxable products sit in the same subcategory, so type, not category, decides.", ["hygiene_kind", "device_kind", "device_evidence", "publication_type", "publication_evidence", "wellness_class"]),
    ("live_vs_recorded", "Live vs. recorded / deliverable", "Several digital and service outcomes turn on delivery and rights; the workbook requires these rules approved before launch.", ["delivery_mode", "delivery_method", "exemption_confirmed", "deliverable_kind", "output_kind", "admission_kind", "admission_evidence", "fitness_kind"]),
    ("date_bound_eligibility", "Date-bound eligibility", "Exempt only when the item, date and value requirements of the specific exemption are all met.", ["holiday_eligible", "exemption_window_active"]),
    ("bundle_itemization", "Bundle itemisation", "Itemised components are taxed line by line; an unitemised mixed bundle is blocked from publication.", ["bundle_line_kind", "gift_card_event"]),
]

# ---------------------------------------------------------------- departments
DEPARTMENTS = [
    ("groceries-food", "Groceries & Food"), ("beverages", "Beverages"), ("beauty-personal-care", "Beauty & Personal Care"),
    ("fashion-accessories", "Fashion & Accessories"), ("home-household", "Home & Household"), ("electronics", "Electronics"),
    ("books-media", "Books & Media"), ("health-wellness", "Health & Wellness"), ("baby-kids", "Baby & Kids"),
    ("pets", "Pets"), ("digital-products", "Digital Products"), ("services", "Services"), ("other-specialty", "Other & Specialty"),
]
NORMALISE = {"pets": "Pets"}  # the workbook spells one category lower case

# ---------------------------------------------------------------- categories
def ref(fid, required_when=None, optional=False, choices=None):
    d = {"fact": fid, "required": not optional, "requiredWhen": required_when}
    if choices:
        d["choices"] = choices
    return d

def row(n, slug, when, ex, gate=None, note=None):
    return {"n": n, "slug": slug, "when": when, "ex": ex, "gate": gate, "note": note}

def single(n, ex=None):
    return [row(n, "STANDARD", None, ex or {})]

H = AND(T("sold_heated"), T("heated_by_seller"))
NOTCANDY_NUTS = AND(Fa("is_candy"), Fa("candy_coated"))
BIG = AND(GT("servings_per_package", 1), Fa("individual_serving"), GTE("net_weight_oz", 2.5))
SMALL = OR(LTE("servings_per_package", 1), T("individual_serving"), LT("net_weight_oz", 2.5))
PKG_INTERPRETATION = ("Interpretation: exempt only when servings > 1 AND net weight >= 2.5 oz AND not a single serving; "
                      "the document words 'multi-serving pack' and '>= 2.5 oz with > 1 serving' differently for snacks. Confirm with the tax professional.")

A_ENERGY = AND(EQ("label_type", "SUPPLEMENT_FACTS"), T("qualifying_label"))
B_ENERGY = AND(NOT(A_ENERGY), OR(T("contains_milk"), GT("juice_percent", 50)))
A_DRUG = AND(EQ("label_type", "DRUG_FACTS"), T("qualifying_label"))
RTD_MILK = T("contains_milk")
RTD_NOMILK = Fa("contains_milk")

# code, department, name, stripe codes, facts (refs), rows, extras
CATS = [
    # ---- Groceries & Food
    ("baby-food-formula", "groceries-food", "Baby food & formula", ["txcd_40090005"], [], single(23)),
    ("bakery-products", "groceries-food", "Bakery products", ["txcd_40040021"],
     [ref("sold_heated"), ref("heated_by_seller", T("sold_heated")), ref("utensils_provided"), ref("seller_is_bakery", T("utensils_provided"))],
     [row(11, "SELLER_HEATS_OR_NONBAKERY_UTENSILS", OR(H, AND(T("utensils_provided"), Fa("seller_is_bakery"))),
          {"sold_heated": True, "heated_by_seller": True, "utensils_provided": False}),
      row(10, "UNHEATED_NO_UTENSILS", AND(NOT(H), NOT(AND(T("utensils_provided"), Fa("seller_is_bakery")))),
          {"sold_heated": False, "utensils_provided": False})], {}),
    ("beans-pantry-sauces", "groceries-food", "Beans, pantry foods & sauces", ["txcd_40040000"], [], single(2)),
    ("candy-gum", "groceries-food", "Candy & gum", ["txcd_40100001"], [], single(15)),
    ("cooking-oils", "groceries-food", "Cooking oils", ["txcd_40020002"], [], single(5)),
    ("dairy-eggs", "groceries-food", "Dairy & eggs", ["txcd_40040000"], [], single(22)),
    ("flours-starches", "groceries-food", "Flours & starches", ["txcd_40020005"], [], single(3)),
    ("fresh-produce", "groceries-food", "Fresh produce", ["txcd_40040019"], [], single(17)),
    ("frozen-meals", "groceries-food", "Frozen meals", ["txcd_40040000", "txcd_40060003"],
     [ref("sold_heated"), ref("heated_by_seller", T("sold_heated")), ref("prepared_meal"), ref("utensils_provided", optional=True)],
     [row(21, "HEATED_BY_SELLER_OR_PREPARED", OR(H, T("prepared_meal")), {"sold_heated": True, "heated_by_seller": True, "prepared_meal": False}),
      row(20, "FROZEN_FOR_BUYER_REHEAT", AND(NOT(H), Fa("prepared_meal")), {"sold_heated": False, "prepared_meal": False})], {}),
    ("meat-poultry-fish", "groceries-food", "Meat, poultry & fish", ["txcd_40040013", "txcd_40060003"],
     [ref("prepared_meal"), ref("cooked_hot")],
     [row(19, "COOKED_HOT_OR_PREPARED", OR(T("cooked_hot"), T("prepared_meal")), {"prepared_meal": False, "cooked_hot": True}),
      row(18, "RAW_OR_FROZEN_FOR_HOME_COOKING", AND(Fa("cooked_hot"), Fa("prepared_meal")), {"prepared_meal": False, "cooked_hot": False})], {}),
    ("nuts-seeds", "groceries-food", "Nuts & seeds", ["txcd_40040016", "txcd_40100001"],
     [ref("is_candy"), ref("candy_coated"), ref("net_weight_oz", NOTCANDY_NUTS), ref("servings_per_package", NOTCANDY_NUTS), ref("individual_serving", NOTCANDY_NUTS)],
     [row(9, "CANDY_COATED_OR_CONFECTIONERY", OR(T("is_candy"), T("candy_coated")), {"is_candy": False, "candy_coated": True}),
      row(7, "PLAIN_MULTI_SERVING_2_5OZ_PLUS", AND(NOTCANDY_NUTS, BIG),
          {"is_candy": False, "candy_coated": False, "net_weight_oz": 8.0, "servings_per_package": 4, "individual_serving": False}, note=PKG_INTERPRETATION),
      row(8, "PLAIN_SINGLE_OR_UNDER_2_5OZ", AND(NOTCANDY_NUTS, SMALL),
          {"is_candy": False, "candy_coated": False, "net_weight_oz": 1.0, "servings_per_package": 1, "individual_serving": True})], {}),
    ("packaged-snacks", "groceries-food", "Packaged snacks", ["txcd_40070005", "txcd_40100001"],
     [ref("is_candy"), ref("net_weight_oz", Fa("is_candy")), ref("servings_per_package", Fa("is_candy")), ref("individual_serving", Fa("is_candy"))],
     [row(14, "CANDY_ITEM", T("is_candy"), {"is_candy": True}),
      row(12, "MULTI_SERVING_2_5OZ_PLUS", AND(Fa("is_candy"), BIG),
          {"is_candy": False, "net_weight_oz": 6.0, "servings_per_package": 5, "individual_serving": False}, note=PKG_INTERPRETATION),
      row(13, "SINGLE_OR_UNDER_2_5OZ", AND(Fa("is_candy"), SMALL),
          {"is_candy": False, "net_weight_oz": 1.5, "servings_per_package": 1, "individual_serving": True})], {}),
    ("pasta-noodles", "groceries-food", "Pasta & noodles", ["txcd_40010001"], [], single(4)),
    ("prepared-meals", "groceries-food", "Prepared meals", ["txcd_40060003"], [], single(16)),
    ("rice-grains", "groceries-food", "Rice & grains", ["txcd_40010002"], [], single(1)),
    ("spices-seasonings", "groceries-food", "Spices & seasonings", ["txcd_40020007"], [], single(6)),
    # ---- Beverages
    ("coffee-tea-dry", "beverages", "Coffee & tea, dry", ["txcd_41050006"], [], single(29)),
    ("energy-protein-drink-mixes", "beverages", "Energy, protein & drink mixes", ["txcd_41053001"],
     [ref("label_type", choices=["SUPPLEMENT_FACTS", "NUTRITION_FACTS"]), ref("qualifying_label", EQ("label_type", "SUPPLEMENT_FACTS")),
      ref("label_evidence", A_ENERGY),
      ref("contains_milk", OR(EQ("label_type", "NUTRITION_FACTS"), Fa("qualifying_label"))),
      ref("juice_percent", OR(EQ("label_type", "NUTRITION_FACTS"), Fa("qualifying_label"))),
      ref("sweetened", OR(EQ("label_type", "NUTRITION_FACTS"), Fa("qualifying_label")))],
     [row(33, "QUALIFYING_SUPPLEMENT_FACTS", A_ENERGY, {"label_type": "SUPPLEMENT_FACTS", "qualifying_label": True, "label_evidence": "evidence://label.pdf"}),
      row(34, "MILK_OR_OVER_50_PERCENT_JUICE", B_ENERGY, {"label_type": "NUTRITION_FACTS", "contains_milk": True, "juice_percent": 0, "sweetened": True}),
      row(35, "SWEETENED_SOFT_ENERGY_SPORTS_DRINK", AND(NOT(A_ENERGY), NOT(B_ENERGY), T("sweetened")),
          {"label_type": "NUTRITION_FACTS", "contains_milk": False, "juice_percent": 0, "sweetened": True}),
      row(36, "ORDINARY_FOOD_INGREDIENT_MIX", AND(NOT(A_ENERGY), NOT(B_ENERGY), Fa("sweetened")),
          {"label_type": "NUTRITION_FACTS", "contains_milk": False, "juice_percent": 0, "sweetened": False})], {}),
    ("flavored-water-soda", "beverages", "Flavored water & soda", ["txcd_41040002"], [], single(25),
     {"status": "CONFLICT_OPEN", "decision": 1, "reason": "Open decision 1: the row carries txcd_41040002 but its own note says flavored water uses txcd_41040004. Confirm which code applies to flavored water and whether soda keeps txcd_41040002.",
      "conflictingCodes": ["txcd_41040002", "txcd_41040004"]}),
    ("fruit-vegetable-juice", "beverages", "Fruit & vegetable juice", ["txcd_41040024", "txcd_41040025", "txcd_41040027"],
     [ref("juice_percent"), ref("sweetened", LTE("juice_percent", 50)), ref("soft_drink", LTE("juice_percent", 50))],
     [row(26, "OVER_50_PERCENT_JUICE", GT("juice_percent", 50), {"juice_percent": 100}),
      row(27, "UP_TO_50_PERCENT_SWEETENED_SOFT_DRINK", AND(LTE("juice_percent", 50), OR(T("sweetened"), T("soft_drink"))),
          {"juice_percent": 10, "sweetened": True, "soft_drink": True},
          note="Interpretation: a drink at 50% juice or less is taxable when it is sweetened OR classified as a soft drink; confirm with the tax professional."),
      row(28, "UNSWEETENED_NOT_SOFT_DRINK", AND(LTE("juice_percent", 50), Fa("sweetened"), Fa("soft_drink")), {"juice_percent": 20, "sweetened": False, "soft_drink": False})], {}),
    ("plain-water", "beverages", "Plain water", ["txcd_41030001"], [], single(24)),
    ("ready-to-drink-coffee-tea", "beverages", "Ready-to-drink coffee & tea", ["txcd_41050001", "txcd_41050002", "txcd_41050003"],
     [ref("contains_milk"), ref("sweetened", RTD_NOMILK), ref("sold_heated", RTD_NOMILK), ref("utensils_provided", RTD_NOMILK)],
     [row(32, "CONTAINS_MILK_OR_SUBSTITUTE", RTD_MILK, {"contains_milk": True},
          note="Document says 'qualifying' beverage containing milk; the qualifying threshold is not defined in the workbook. Confirm with the tax professional."),
      row(31, "SWEETENED_HOT_OR_WITH_UTENSILS", AND(RTD_NOMILK, OR(T("sweetened"), T("sold_heated"), T("utensils_provided"))),
          {"contains_milk": False, "sweetened": True, "sold_heated": False, "utensils_provided": False}),
      row(30, "UNSWEETENED_UNHEATED_NO_UTENSILS", AND(RTD_NOMILK, Fa("sweetened"), Fa("sold_heated"), Fa("utensils_provided")),
          {"contains_milk": False, "sweetened": False, "sold_heated": False, "utensils_provided": False})], {}),
    # ---- Beauty & Personal Care
    ("hair-accessories", "beauty-personal-care", "Hair accessories", ["txcd_30060005"], [], single(42)),
    ("haircare", "beauty-personal-care", "Haircare", ["txcd_32050036"], [], single(40),
     {"note": "Medicated Drug Facts products are classified separately: list them under Medicated skincare or OTC medicines."}),
    ("medicated-skincare", "beauty-personal-care", "Medicated skincare", ["txcd_32050014"],
     [ref("label_type", choices=["DRUG_FACTS", "NONE"]), ref("qualifying_label", EQ("label_type", "DRUG_FACTS")), ref("label_evidence", A_DRUG)],
     [row(38, "QUALIFYING_OTC_DRUG_FACTS", A_DRUG, {"label_type": "DRUG_FACTS", "qualifying_label": True, "label_evidence": "evidence://label.pdf"}),
      row(39, "COSMETIC_NO_DRUG_EXEMPTION", NOT(A_DRUG), {"label_type": "NONE"})], {}),
    ("perfumes-fragrances", "beauty-personal-care", "Perfumes & fragrances", ["txcd_32050006"], [], single(43),
     {"note": "Exact fragrance code must be verified at SKU level (workbook note)."}),
    ("skincare-cosmetics", "beauty-personal-care", "Skincare & cosmetics", ["txcd_32050013"], [], single(37),
     {"note": "Medicated Drug Facts products are classified separately: list them under Medicated skincare."}),
    ("wigs-extensions", "beauty-personal-care", "Wigs & extensions", ["txcd_30060014"], [], single(41)),
    # ---- Fashion & Accessories
    ("adult-clothing-footwear", "fashion-accessories", "Adult clothing & footwear", ["txcd_30011000"], [], single(44),
     {"note": "Footwear/product subtype needs an exact Stripe code (workbook note); provider code map per product type can refine it.",
      "productTypes": [("dress", "Women's dresses", True), ("top", "Women's tops & wrappers", True), ("menwear", "Men's clothing", True), ("footwear", "Footwear", True)]}),
    ("bags-purses", "fashion-accessories", "Bags & purses", ["txcd_30060001"], [], single(46)),
    ("childrens-clothing", "fashion-accessories", "Children’s clothing", ["txcd_30011200"], [], single(45),
     {"productTypes": [("kids", "Kids' clothing", True)]}),
    ("jewelry-accessories", "fashion-accessories", "Jewelry & accessories", ["txcd_30060007"], [], single(47),
     {"note": "Accessory subtype needs an exact Stripe code (workbook note)."}),
    # ---- Home & Household
    ("bedding-linens", "home-household", "Bedding & linens", ["txcd_33080301"], [], single(50)),
    ("cleaning-paper-products", "home-household", "Cleaning & paper products", ["txcd_99999999"], [], single(48)),
    ("decor-candles", "home-household", "Decor & candles", ["txcd_33110005"], [], single(51)),
    ("furniture-small-appliances", "home-household", "Furniture & small appliances", ["txcd_99999999"], [], single(52)),
    ("kitchenware-cookware", "home-household", "Kitchenware & cookware", ["txcd_99999999"], [], single(49)),
    # ---- Electronics
    ("computers-laptops", "electronics", "Computers & laptops", ["txcd_37010000"], [], single(54)),
    ("gaming", "electronics", "Gaming", ["txcd_34022000"], [], single(56)),
    ("phones", "electronics", "Phones", ["txcd_34021000"], [], single(53)),
    ("tvs-audio-accessories", "electronics", "TVs, audio & accessories", ["txcd_34020006"], [], single(55),
     {"note": "Audio and accessory subtypes need their own codes (workbook note)."}),
    # ---- Books & Media
    ("magazines", "books-media", "Magazines", ["txcd_35020100", "txcd_35020200"],
     [ref("publication_type"), ref("publication_evidence", EQ("publication_type", "QUALIFYING_EXEMPT_NEWSPAPER_OR_OTHER"))],
     [row(58, "ORDINARY_MAGAZINE_PERIODICAL", EQ("publication_type", "ORDINARY_MAGAZINE_PERIODICAL"), {"publication_type": "ORDINARY_MAGAZINE_PERIODICAL"}),
      row(59, "QUALIFYING_EXEMPT_NEWSPAPER_OR_OTHER", EQ("publication_type", "QUALIFYING_EXEMPT_NEWSPAPER_OR_OTHER"),
          {"publication_type": "QUALIFYING_EXEMPT_NEWSPAPER_OR_OTHER", "publication_evidence": "evidence://notice.pdf"})], {}),
    ("printed-books", "books-media", "Printed books", ["txcd_35010000"], [], single(57),
     {"note": "Textbooks and religious books may have specific codes (workbook note)."}),
    # ---- Health & Wellness
    ("dietary-supplements", "health-wellness", "Dietary supplements", ["txcd_40090001"],
     [ref("wellness_class"), ref("qualifying_label", EQ("wellness_class", "SUPPLEMENT")),
      ref("label_evidence", OR(AND(EQ("wellness_class", "SUPPLEMENT"), T("qualifying_label")), EQ("wellness_class", "FOOD")))],
     [row(60, "QUALIFYING_SUPPLEMENT_FACTS", AND(EQ("wellness_class", "SUPPLEMENT"), T("qualifying_label")),
          {"wellness_class": "SUPPLEMENT", "qualifying_label": True, "label_evidence": "evidence://label.pdf"}),
      row(62, "ACTUAL_FOOD_OR_QUALIFYING_FOOD", EQ("wellness_class", "FOOD"), {"wellness_class": "FOOD", "label_evidence": "evidence://label.pdf"}),
      row(61, "ORDINARY_NONEXEMPT_WELLNESS_GOODS", OR(EQ("wellness_class", "ORDINARY_GOODS"), AND(EQ("wellness_class", "SUPPLEMENT"), Fa("qualifying_label"))),
          {"wellness_class": "ORDINARY_GOODS"})], {}),
    ("feminine-hygiene", "health-wellness", "Feminine hygiene", ["txcd_32040005"], [], single(67)),
    ("first-aid-wound-care", "health-wellness", "First aid & wound care", ["txcd_32070003"], [], single(64),
     {"note": "Only qualifying wound-care products (workbook note)."}),
    ("medical-devices", "health-wellness", "Medical devices", ["txcd_32010001"],
     [ref("device_kind"), ref("device_evidence", IN("device_kind", ["QUALIFYING_EXEMPT_DEVICE", "PRESCRIBED_ELIGIBLE_DEVICE"]))],
     [row(68, "QUALIFYING_OR_PRESCRIBED_DEVICE", IN("device_kind", ["QUALIFYING_EXEMPT_DEVICE", "PRESCRIBED_ELIGIBLE_DEVICE"]),
          {"device_kind": "QUALIFYING_EXEMPT_DEVICE", "device_evidence": "evidence://device.pdf"}),
      row(69, "NONQUALIFYING_CONSUMER_DEVICE", EQ("device_kind", "NONQUALIFYING_CONSUMER_DEVICE"), {"device_kind": "NONQUALIFYING_CONSUMER_DEVICE"})], {}),
    ("otc-medicines", "health-wellness", "OTC medicines", ["txcd_32020002"], [ref("label_evidence")],
     [row(63, "STANDARD", None, {"label_evidence": "evidence://drug-facts.pdf"})],
     {"note": "Drug Facts evidence is mandatory (workbook: FDA Drug Facts / qualifying medicine)."}),
    ("personal-hygiene", "health-wellness", "Personal hygiene", ["txcd_32050019"],
     [ref("hygiene_kind"), ref("label_evidence", EQ("hygiene_kind", "OTC_MEDICINE"))],
     [row(65, "OTC_MEDICINE_OR_FEMININE_HYGIENE", IN("hygiene_kind", ["OTC_MEDICINE", "FEMININE_HYGIENE"]),
          {"hygiene_kind": "FEMININE_HYGIENE"}),
      row(66, "ORDINARY_NONMEDICATED", EQ("hygiene_kind", "ORDINARY_NONMEDICATED"), {"hygiene_kind": "ORDINARY_NONMEDICATED"})], {}),
    # ---- Baby & Kids
    ("baby-bottles", "baby-kids", "Baby bottles", ["txcd_31040001"], [], single(71)),
    ("car-seats", "baby-kids", "Car seats", ["txcd_31070001"], [ref("holiday_eligible"), ref("exemption_window_active")],
     [row(73, "TAX_HOLIDAY_OR_EXEMPTION", AND(T("holiday_eligible"), T("exemption_window_active")), {"holiday_eligible": True, "exemption_window_active": True}),
      row(72, "ORDINARY_SALE", NOT(AND(T("holiday_eligible"), T("exemption_window_active"))), {"holiday_eligible": False, "exemption_window_active": False})],
     {"note": "The exemption calendar is not built yet (TX-08): until it is, exemption_window_active is always false."}),
    ("diapers-wipes", "baby-kids", "Diapers & wipes", ["txcd_31031202"], [], single(70)),
    ("toys-games", "baby-kids", "Toys & games", ["txcd_99999999"], [], single(74)),
    # ---- Pets
    ("pet-grooming-accessories", "pets", "Grooming & accessories", ["txcd_99999999"], [], single(76),
     {"note": "Generic tangible goods; classify the actual SKU (workbook note)."}),
    ("pet-food", "pets", "Pet food", ["txcd_36010004"], [], single(75)),
    # ---- Digital Products
    ("audiobooks", "digital-products", "Audiobooks", ["txcd_10301000"], [ref("delivery_method"), ref("exemption_confirmed")],
     [row(79, "TAXABLE_DIGITAL_GOOD", Fa("exemption_confirmed"), {"delivery_method": "DOWNLOAD", "exemption_confirmed": False}),
      row(80, "CONFIRMED_EXEMPT", T("exemption_confirmed"), {"delivery_method": "DOWNLOAD", "exemption_confirmed": True})],
     {"status": "NEEDS_APPROVAL", "decision": 4}),
    ("digital-art-downloads", "digital-products", "Digital art & downloads", ["txcd_10501000"], [ref("deliverable_kind")],
     [row(84, "DOWNLOADED_ARTWORK_OR_PHOTO", EQ("deliverable_kind", "DOWNLOADED_FILE"), {"deliverable_kind": "DOWNLOADED_FILE"}),
      row(85, "PERSONAL_SERVICE_NO_DELIVERABLE", EQ("deliverable_kind", "PERSONAL_SERVICE_NO_DELIVERABLE"), {"deliverable_kind": "PERSONAL_SERVICE_NO_DELIVERABLE"})],
     {"status": "NEEDS_APPROVAL", "decision": 4}),
    ("downloadable-ebooks", "digital-products", "Downloadable e-books", ["txcd_10302000"], [ref("delivery_method"), ref("exemption_confirmed")],
     [row(77, "TAXABLE_DIGITAL_GOOD", Fa("exemption_confirmed"), {"delivery_method": "DOWNLOAD", "exemption_confirmed": False}),
      row(78, "CONFIRMED_EXEMPT", T("exemption_confirmed"), {"delivery_method": "DOWNLOAD", "exemption_confirmed": True})],
     {"status": "NEEDS_APPROVAL", "decision": 4}),
    ("downloadable-software", "digital-products", "Downloadable software", ["txcd_10202000"], [], single(83)),
    ("online-courses", "digital-products", "Online courses", ["txcd_20060158"], [ref("delivery_mode")],
     [row(81, "LIVE_INSTRUCTOR_LED", EQ("delivery_mode", "LIVE_INSTRUCTOR_LED"), {"delivery_mode": "LIVE_INSTRUCTOR_LED"}),
      row(82, "RECORDED_OR_DIGITAL_DELIVERABLE", EQ("delivery_mode", "RECORDED_OR_DIGITAL_DELIVERABLE"), {"delivery_mode": "RECORDED_OR_DIGITAL_DELIVERABLE"})],
     {"status": "NEEDS_APPROVAL", "decision": 4}),
    # ---- Services
    ("event-tickets", "services", "Event tickets", ["txcd_50013001"],
     [ref("admission_kind"), ref("admission_evidence", EQ("admission_kind", "NONTAXABLE_ADMISSION_OR_EXEMPT_EVENT"))],
     [row(93, "TAXABLE_AMUSEMENT_ADMISSION", EQ("admission_kind", "TAXABLE_AMUSEMENT_ADMISSION"), {"admission_kind": "TAXABLE_AMUSEMENT_ADMISSION"}),
      row(94, "NONTAXABLE_ADMISSION_OR_EXEMPT_EVENT", EQ("admission_kind", "NONTAXABLE_ADMISSION_OR_EXEMPT_EVENT"),
          {"admission_kind": "NONTAXABLE_ADMISSION_OR_EXEMPT_EVENT", "admission_evidence": "evidence://event.pdf"})],
     {"status": "NEEDS_APPROVAL", "decision": 4}),
    ("fitness-classes", "services", "Fitness classes", ["txcd_50021003"], [ref("fitness_kind")],
     [row(95, "MEMBERSHIP_OR_FACILITY_ACCESS", EQ("fitness_kind", "MEMBERSHIP_OR_FACILITY_ACCESS"), {"fitness_kind": "MEMBERSHIP_OR_FACILITY_ACCESS"}),
      row(96, "INSTRUCTOR_LED_CLASS", EQ("fitness_kind", "INSTRUCTOR_LED_CLASS"), {"fitness_kind": "INSTRUCTOR_LED_CLASS"})], {}),
    ("hair-beauty-appointments", "services", "Hair & beauty appointments", ["txcd_20040001"], [], single(86),
     {"note": "Standalone service; goods are sold on separate lines (rule 7)."}),
    ("laundry-dry-cleaning", "services", "Laundry & dry cleaning", ["txcd_20090004"], [], single(90)),
    ("photography-media", "services", "Photography & media", ["txcd_20030000"], [ref("output_kind")],
     [row(91, "TAXABLE_PRINTS_OR_DIGITAL_DELIVERABLE", EQ("output_kind", "TAXABLE_PRINTS_OR_DIGITAL_DELIVERABLE"), {"output_kind": "TAXABLE_PRINTS_OR_DIGITAL_DELIVERABLE"}),
      row(92, "SERVICE_ONLY_NO_TAXABLE_DELIVERABLE", EQ("output_kind", "SERVICE_ONLY_NO_TAXABLE_DELIVERABLE"), {"output_kind": "SERVICE_ONLY_NO_TAXABLE_DELIVERABLE"})],
     {"status": "NEEDS_APPROVAL", "decision": 4}),
    ("repairs", "services", "Repairs", ["txcd_20080010"], [], single(89)),
    ("residential-cleaning", "services", "Residential cleaning", ["txcd_20010006"], [], single(87)),
    ("tutoring-consulting", "services", "Tutoring & consulting", ["txcd_20060059"], [], single(88),
     {"status": "CONFLICT_OPEN", "decision": 2, "reason": "Open decision 2: the row carries txcd_20060059 but its note says consulting uses txcd_20060048. Decide two subcategories, or one with a service-type field that selects the code.",
      "conflictingCodes": ["txcd_20060059", "txcd_20060048"]}),
    # ---- Other & Specialty
    ("gift-baskets-bundles", "other-specialty", "Gift baskets / bundles", ["txcd_40040000", "txcd_40050004", "txcd_99999999"], [ref("bundle_line_kind")],
     [row(100, "EXEMPT_FOOD_ITEMIZED", EQ("bundle_line_kind", "EXEMPT_FOOD_LINE_ITEMIZED"), {"bundle_line_kind": "EXEMPT_FOOD_LINE_ITEMIZED"}),
      row(99, "TAXABLE_GOODS_ITEMIZED", EQ("bundle_line_kind", "TAXABLE_GOODS_LINE_ITEMIZED"), {"bundle_line_kind": "TAXABLE_GOODS_LINE_ITEMIZED"}),
      row(101, "UNITEMIZED_MIXED_BUNDLE", EQ("bundle_line_kind", "UNITEMIZED_MIXED_BUNDLE"), {"bundle_line_kind": "UNITEMIZED_MIXED_BUNDLE"})],
     {"status": "BLOCKED_BUNDLE", "reason": "Unitemised mixed bundles have no rate in the workbook. Require full component itemisation or a tax-approved bundle rule before publishing or checkout.",
      "extraReasons": [("REFINE_CODE", 3, "The taxable-goods line uses the generic code txcd_99999999.")]}),
    ("gift-cards", "other-specialty", "Gift cards", ["txcd_10502000"], [ref("gift_card_event")],
     [row(103, "SALE_OF_MULTIPURPOSE_CARD", EQ("gift_card_event", "SALE_MULTIPURPOSE_CARD"), {"gift_card_event": "SALE_MULTIPURPOSE_CARD"}),
      row(104, "REDEMPTION_TAXABLE_ITEM", EQ("gift_card_event", "REDEMPTION_TAXABLE_ITEM"), {"gift_card_event": "REDEMPTION_TAXABLE_ITEM"}),
      row(105, "REDEMPTION_EXEMPT_ITEM", EQ("gift_card_event", "REDEMPTION_EXEMPT_ITEM"), {"gift_card_event": "REDEMPTION_EXEMPT_ITEM"})],
     {"status": "NEEDS_APPROVAL", "decision": 4,
      "reason": "The workbook notes say 'approve rule before launch' on all gift card rows; the document's gate list does not name gift cards. Held conservatively until approved."}),
    ("handmade-products", "other-specialty", "Handmade products", ["txcd_99999999"], [], single(102)),
    ("school-office-supplies", "other-specialty", "School & office supplies", ["txcd_37060012"], [], single(97)),
    ("sports-goods", "other-specialty", "Sports goods", ["txcd_30071000"], [], single(98)),
]

REFINE_CODES = {"cleaning-paper-products", "kitchenware-cookware", "furniture-small-appliances", "toys-games", "pet-grooming-accessories", "handmade-products"}
PRECEDENCE = ["CONFLICT_OPEN", "BLOCKED_BUNDLE", "NEEDS_APPROVAL", "REFINE_CODE", "ACTIVE"]

DECISIONS = [
    {"id": 1, "title": "Flavored water Stripe code conflict", "detail": "txcd_41040002 vs txcd_41040004 for flavored water; does soda keep txcd_41040002?", "affects": ["flavored-water-soda"]},
    {"id": 2, "title": "Tutoring / consulting code split", "detail": "txcd_20060059 vs txcd_20060048: two subcategories or one with a service-type field.", "affects": ["tutoring-consulting"]},
    {"id": 3, "title": "Refine every generic-code subcategory at SKU level", "detail": "txcd_99999999 on 7 subcategories: owner and deadline for verified SKU-level codes.", "affects": sorted(REFINE_CODES | {"gift-baskets-bundles"})},
    {"id": 4, "title": "Approve the digital and services rules before launch", "detail": "E-books, audiobooks, online courses, digital art, photography deliverables, event tickets (and gift cards, held conservatively): name the approver for each.",
     "affects": ["audiobooks", "digital-art-downloads", "downloadable-ebooks", "online-courses", "photography-media", "event-tickets", "gift-cards"]},
    {"id": 5, "title": "Delivery-fee attribution for mixed baskets", "detail": "TX-07: legally reviewed method for splitting delivery between taxable and exempt portions; interacts with the open delivery price.", "affects": []},
]

def build():
    src = json.loads((HERE / "source_scenarios.json").read_text(encoding="utf-8"))["scenarios"]
    by_n = {s["n"]: s for s in src}
    facts_by_id = {f["id"]: f for f in FACTS}
    group_of = {fid: g[0] for g in GROUPS for fid in g[3]}
    dept_by_code = dict(DEPARTMENTS)
    categories, scenarios = [], []
    for ci, c in enumerate(CATS):
        code, dept, name, stripe, refs, rows = c[:6]
        extra = c[6] if len(c) > 6 else {}
        # status: explicit, REFINE_CODE for generic code subcategories, else ACTIVE
        status = extra.get("status") or ("REFINE_CODE" if code in REFINE_CODES else "ACTIVE")
        reasons = []
        if status != "ACTIVE" or extra.get("reason"):
            if status == "REFINE_CODE":
                reasons.append({"status": "REFINE_CODE", "decision": 3, "text": "Generic Stripe code txcd_99999999: verify the specific code at SKU level before it goes live."})
            else:
                reasons.append({"status": status, "decision": extra.get("decision"), "text": extra.get("reason") or "Workbook notes require the rule to be verified and approved before launch (open decision 4)."})
        for st, dec, text in extra.get("extraReasons", []):
            reasons.append({"status": st, "decision": dec, "text": text})
        out_rows, outcomes = [], set()
        for r in rows:
            s = by_n[r["n"]]
            outcome = {"Exempt / not taxed": "EXEMPT", "Taxable": "TAXABLE", "Blocked": "BLOCKED"}[s["outcome"].split(" (")[0].strip()]
            outcomes.add(outcome)
            internal = "AT.%s.%s" % (code.upper().replace("-", "_"), r["slug"])
            out_rows.append({
                "id": "S%03d" % r["n"], "scenario": r["n"], "when": r["when"], "condition": s["condition"], "outcome": outcome,
                "internalTaxCode": internal, "stripeCode": s["stripeCode"], "providerCodes": {"STRIPE": s["stripeCode"], "AVALARA": None},
                "rateDisplay": s["rate"], "note": r["note"],
            })
            ex = dict(r["ex"])
            if c[0] == "gift-baskets-bundles" and r["n"] == 101:
                pass
            scen_facts, behaviour, context = {}, {}, {}
            for k, v in ex.items():
                o = facts_by_id[k]["owner"]
                {"VARIANT": scen_facts, "STAFF": scen_facts, "OFFER": behaviour, "SELLER": behaviour, "CONTEXT": context}[o][k] = v
            scenarios.append({
                "scenario": r["n"], "rowId": "S%03d" % r["n"], "department": dept_by_code[dept], "category": name, "categoryCode": code,
                "condition": s["condition"], "inputs": {"facts": scen_facts, "sellerBehaviour": behaviour, "context": context},
                "expect": {"outcome": outcome, "internalTaxCode": internal, "stripeCode": s["stripeCode"], "rateDisplay": s["rate"],
                           "resolveStatus": "BLOCKED" if outcome == "BLOCKED" else {"ACTIVE": "RESOLVED"}.get(status, status if status != "BLOCKED_BUNDLE" else "BLOCKED")},
            })
        product_types = [{"code": t[0], "label": t[1], "sized": t[2], "default": i == 0} for i, t in enumerate(extra["productTypes"])] if "productTypes" in extra else \
                        [{"code": code, "label": name, "sized": False, "default": True}]
        groups = sorted({group_of[x["fact"]] for x in refs if x["fact"] in group_of}, key=lambda g: [k[0] for k in GROUPS].index(g))
        categories.append({
            "code": code, "department": dept, "name": name, "position": ci, "stripeCodes": stripe,
            "defaultOutcome": outcomes.pop() if len(outcomes) == 1 else "CONDITIONAL",
            "status": status, "statusReasons": reasons, "conflictingCodes": extra.get("conflictingCodes", []),
            "factGroups": groups, "facts": refs, "decisionTable": out_rows, "productTypes": product_types, "note": extra.get("note"),
        })
    scenarios.sort(key=lambda s: s["scenario"])
    for c in categories:
        # the category status shown to staff is the highest-precedence reason
        if c["statusReasons"]:
            c["status"] = min((r["status"] for r in c["statusReasons"]), key=PRECEDENCE.index)
    tax_map = {
        "_comment": "GENERATED by tools/taxonomy/build_map.py from source_scenarios.json (do not edit). DRAFT. NOT tax or legal advice: a Texas tax professional must approve before production use.",
        "ruleVersion": RULE_VERSION, "jurisdiction": "US-TX",
        "source": {"document": "AfroTransact Product Categories and Rules for Creating Products (DRAFT, prepared for Nov 9 2026)", "workbook": "AfroTransact_Category_Tax_Map_With_Rates.xlsm"},
        "rates": {"stateRate": "6.25%", "localMax": "2%", "combinedMax": "8.25%", "exempt": "0%"},
        "providers": ["STRIPE", "AVALARA"], "outcomes": ["EXEMPT", "TAXABLE", "BLOCKED"],
        "statuses": PRECEDENCE, "statusPrecedence": PRECEDENCE,
        "owners": ["VARIANT", "OFFER", "SELLER", "STAFF", "CONTEXT"], "factKinds": ["BOOLEAN", "DECIMAL", "INTEGER", "PERCENT", "CHOICE", "FILE"],
        "decisions": DECISIONS,
        "departments": [{"code": c, "name": n, "position": i} for i, (c, n) in enumerate(DEPARTMENTS)],
        "factGroups": [{"id": g[0], "label": g[1], "effect": g[2], "facts": g[3],
                        "appliesTo": [c["code"] for c in categories if g[0] in c["factGroups"]]} for g in GROUPS],
        "facts": FACTS, "categories": categories,
    }
    return tax_map, {"_comment": "GENERATED by tools/taxonomy/build_map.py. Golden fixture: one scenario per Appendix A row; see docs/engineering/category-tax-design.md.",
                     "ruleVersion": RULE_VERSION, "scenarios": scenarios}

def dump(o):
    return json.dumps(o, ensure_ascii=False, indent=1) + "\n"

def main():
    tax_map, scen = build()
    out = {"category_tax_map.json": dump(tax_map), "scenarios.json": dump(scen)}
    if "--check" in sys.argv:
        sys.path.insert(0, str(HERE))
        from validate import validate
        errs = validate(tax_map, scen, json.loads((HERE / "source_scenarios.json").read_text(encoding="utf-8")))
        if errs:
            print("\n".join(errs))
            return 1
        bad = [f for f, t in out.items() if not (HERE / f).exists() or (HERE / f).read_text(encoding="utf-8") != t]
        if bad:
            print("stale: " + ", ".join(bad) + " (run tools/taxonomy/build_map.py)")
            return 1
        return 0
    sys.path.insert(0, str(HERE))
    from validate import validate
    errs = validate(tax_map, scen, json.loads((HERE / "source_scenarios.json").read_text(encoding="utf-8")))
    if errs:
        print("\n".join(errs))
        return 1
    for f, t in out.items():
        (HERE / f).write_text(t, encoding="utf-8")
    print("wrote", ", ".join(out), "-", len(tax_map["departments"]), "departments,", len(tax_map["categories"]), "categories,", len(scen["scenarios"]), "scenarios")
    return 0

if __name__ == "__main__":
    sys.exit(main())
