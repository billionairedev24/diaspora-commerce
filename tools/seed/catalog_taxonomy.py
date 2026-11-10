"""Generates the catalog's product-type schema resource for the category tax tree.

    python3 tools/seed/catalog_taxonomy.py            # prints java/services/catalog/src/main/resources/taxonomy/schemas.json
    python3 tools/seed/catalog_taxonomy.py --write    # writes it
    python3 tools/seed/catalog_taxonomy.py --check    # fails when the committed file is stale (CI / make taxonomy-check)

The tree (13 departments > 73 categories > product types) and the tax facts of every category come from
tools/taxonomy/category_tax_map.json. What that dataset does not say is what a seller fills in besides the tax
questions: brand, material, ingredients, the variations a product comes in and the sizing of fashion. That lives here,
per department (a *family* of detail questions), with a few category and product-type overrides. The catalog's importer
(`TaxonomyImportService`, and the generated baseline data `tools/taxonomy/gen_catalog_baseline.py`) combines the two; see docs/engineering/taxonomy.md.

This is the initial and re-importable definition. Staff keep editing details of a product type in the console afterwards;
re-importing only creates what is missing and never overwrites a schema staff changed (unless asked to with `force`).
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "java/services/catalog/src/main/resources/taxonomy/schemas.json"

SIZES = {"women": ["XS", "S", "M", "L", "XL", "2XL", "3XL"], "men": ["S", "M", "L", "XL", "2XL", "3XL"],
         "kids": ["2T", "3T", "4", "6", "8", "10", "12"], "shoes": ["5", "6", "7", "8", "9", "10", "11", "12", "13"]}
COLORS = ["Black", "White", "Gold", "Indigo", "Wine", "Emerald", "Royal blue", "Multicolour"]


def a(key, label, options=None, required=True, kind=None, units=None, section="DETAILS", help=None, multiline=False):
    d = {"key": key, "label": label, "options": options or [], "required": required, "multiline": multiline,
         "kind": kind or ("ENUM" if options else "MULTILINE" if multiline else "TEXT"), "units": units or [],
         "section": section, "applies": True}
    if help:
        d["help"] = help
    return d


def brand(label="Brand", required=False, options=("UNBRANDED",), help="The brand on the product, if it has one."):
    return a("brand", label, list(options), required, "BRAND", help=help)


def theme(name, *dims, custom=()):
    return {"name": name, "dimensions": [{"name": d[0], "options": d[1], **({"allowCustomValues": True} if d[0] in custom else {})}
                                         for d in dims]}


FOOD = [brand("Brand", False, ("UNBRANDED",), "The brand on the package. Leave it empty or choose \"No brand\" for loose or unlabelled produce."),
        a("ingredients", "Ingredients", required=True, multiline=True, section="FOOD"),
        a("allergens", "Allergens (FALCPA)", ["None", "Contains peanuts", "Contains shellfish", "Contains fish", "Contains wheat", "Contains soy", "Contains milk", "Contains eggs", "Contains tree nuts"], section="FOOD"),
        a("shelf", "Shelf life", required=False, kind="NUMBER", units=["days", "months"], section="FOOD", help="From the date of manufacture, unopened"),
        a("storage", "Storage", ["Room temperature", "Cool and dry", "Refrigerate", "Freeze"], required=False, section="FOOD"),
        a("origin", "Made in", required=False)]
PERISHABLE = FOOD[:4] + [a("temp", "Storage temperature", ["Frozen (<= 0 F)", "Chilled (<= 40 F)", "Room temperature"], section="FOOD", help="How the product must be kept until it reaches the shopper"),
                         a("permit", "Food facility registration or state permit no.", required=False, section="REGULATED", help="FDA food facility registration or state permit number")]
BEAUTY = [brand("Brand", True, (), "The brand printed on the product. Regulated products must show their brand or responsible business."),
          a("inci", "Ingredients (INCI)", multiline=True), a("skin", "Skin / hair type", ["All", "Dry", "Oily", "Sensitive"], required=False),
          a("mocra", "MoCRA product listing no.", required=False, section="REGULATED", help="FDA Cosmetics Direct product listing number (MoCRA)"),
          a("warn", "Warnings", required=False, kind="MULTILINE", multiline=True)]
HAIR = [brand("Brand", True, (), "The brand printed on the product."), a("fibre", "Fibre", ["Kanekalon", "Toyokalon", "Human hair", "Pre-stretched synthetic"], required=False),
        a("packs", "Packs per bundle", required=False, kind="NUMBER")]
FASHION = [brand("Brand or label", True, ("UNBRANDED", "HANDMADE"), "The label on the item. Choose \"No brand\" or \"Handmade\" if it has none."),
           a("material", "Material", required=False), a("care", "Care", required=False), a("origin", "Made in", required=False)]
HOME = [brand("Maker or brand", False, ("HANDMADE", "UNBRANDED"), "Who made it: a studio, a brand or the artisan's name."),
        a("material", "Material", required=False), a("dims", "Dimensions", required=False, help="e.g. 18 x 18 in"),
        a("handmade", "Handmade", ["Yes", "No"], required=False)]
ELECTRONICS = [brand("Brand", True, (), "The brand printed on the product."), a("model", "Model number", help="Printed on the product label or box"),
               a("voltage", "Voltage", ["110-120V (US)", "220-240V", "Dual voltage", "Battery or USB powered"]),
               a("warranty", "Warranty", required=False, kind="NUMBER", units=["months", "years"])]
BOOK = [a("author", "Author"), a("publisher", "Publisher"), a("language", "Language", ["English", "French", "Yoruba", "Swahili", "Amharic"]),
        a("pages", "Pages", required=False, kind="NUMBER")]
HEALTH = [brand("Brand", True, (), "The brand printed on the product."), a("ingredients", "Ingredients / active ingredients", multiline=True),
          a("warn", "Warnings", required=False, kind="MULTILINE", multiline=True)]
BABY = [brand("Brand", False), a("material", "Material", required=False), a("age", "Suitable for", ["Newborn", "0-12 months", "1-3 years", "3+ years"], required=False),
        a("safety", "CPSIA children's product certificate no.", required=False, section="REGULATED")]
PETS = [brand("Brand", False), a("species", "For", ["Dogs", "Cats", "Birds", "Small animals", "Fish", "Other"]), a("ingredients", "Ingredients", required=False, multiline=True)]
DIGITAL = [a("format", "File format or access", required=False), a("licence", "Licence", ["Personal use", "Commercial use"], required=False)]
SERVICE = [a("duration", "Duration", required=False, help="How long it takes, e.g. 60 minutes"), a("area", "Where it is done", required=False)]
OTHER = [brand("Brand", False), a("material", "Material / main ingredient", required=False), a("details", "Key details shoppers need", required=False, multiline=True)]

T_FOOD = [theme("Net weight", ("Net weight", ["8 oz", "1 lb", "2 lb", "5 lb", "10 lb"])),
          theme("Net weight × Pack", ("Net weight", ["1 lb", "5 lb"]), ("Pack", ["1", "2", "4"]))]
T_OPTION = [theme("Option", ("Option", ["Standard", "Small", "Medium", "Large"])),
            theme("Size × Color", ("Size", ["S", "M", "L", "XL"]), ("Color", ["Black", "White", "Gold", "Multicolour"]))]

FAMILIES = {
    "food": {"identifierRule": "GTIN", "traits": {"food": True, "preorder": True}, "attributes": FOOD, "themes": T_FOOD},
    "perishable": {"identifierRule": "GTIN", "traits": {"food": True, "perishable": True, "regulated": True, "preorder": True},
                   "attributes": PERISHABLE, "themes": [theme("Net weight", ("Net weight", ["1 lb", "2 lb", "5 lb"]))]},
    "beverage": {"identifierRule": "GTIN", "traits": {"food": True, "preorder": True}, "attributes": FOOD,
                 "themes": [theme("Size", ("Size", ["8 oz", "12 oz", "16 oz", "1 L", "2 L"])), T_FOOD[0]]},
    "beauty": {"identifierRule": "GTIN", "traits": {"regulated": True}, "attributes": BEAUTY,
               "themes": [theme("Size", ("Size", ["4 oz", "8 oz", "16 oz"])), theme("Size × Scent", ("Size", ["8 oz", "16 oz"]), ("Scent", ["Unscented", "Lemongrass", "Lavender", "Coconut"]))]},
    "hair": {"identifierRule": "GTIN", "traits": {}, "attributes": HAIR,
             "themes": [theme("Length × Color", ("Length", ["20 in", "26 in", "30 in"]), ("Color", ["1B", "2", "4", "27", "30", "613"]))]},
    "fashion": {"identifierRule": "GTIN", "traits": {}, "attributes": FASHION,
                "themes": [theme("Color", ("Color", COLORS), custom=("Color",)), theme("Size × Color", ("Size", ["S", "M", "L", "XL"]), ("Color", COLORS), custom=("Color",))]},
    "home": {"identifierRule": "GTIN", "traits": {}, "attributes": HOME,
             "themes": [theme("Size × Pattern", ("Size", ["18 in", "22 in"]), ("Pattern", ["Classic", "Arrow", "Dots"]))]},
    "electronics": {"identifierRule": "GTIN", "traits": {}, "attributes": ELECTRONICS,
                    "themes": [theme("Color", ("Color", ["Black", "White", "Silver", "Gold", "Blue"])), theme("Storage", ("Storage", ["64 GB", "128 GB", "256 GB", "512 GB"]))]},
    "book": {"identifierRule": "ISBN", "traits": {}, "attributes": BOOK, "themes": [theme("Format", ("Format", ["Hardcover", "Paperback"]))]},
    "health": {"identifierRule": "GTIN", "traits": {"regulated": True}, "attributes": HEALTH,
               "themes": [theme("Count", ("Count", ["30", "60", "90", "120"])), theme("Size", ("Size", ["4 oz", "8 oz", "16 oz", "1 lb"]))]},
    "baby": {"identifierRule": "GTIN", "traits": {}, "attributes": BABY, "themes": T_OPTION},
    "pets": {"identifierRule": "GTIN", "traits": {}, "attributes": PETS, "themes": T_FOOD[:1]},
    "digital": {"identifierRule": "GTIN", "traits": {}, "attributes": DIGITAL, "themes": []},
    "service": {"identifierRule": "GTIN", "traits": {}, "attributes": SERVICE, "themes": []},
    "other": {"identifierRule": "GTIN", "traits": {}, "attributes": OTHER, "themes": T_OPTION},
}

DEPARTMENT_FAMILY = {"groceries-food": "food", "beverages": "beverage", "beauty-personal-care": "beauty",
                     "fashion-accessories": "fashion", "home-household": "home", "electronics": "electronics",
                     "books-media": "other", "health-wellness": "health", "baby-kids": "baby", "pets": "pets",
                     "digital-products": "digital", "services": "service", "other-specialty": "other"}
CATEGORY_FAMILY = {"dairy-eggs": "perishable", "fresh-produce": "perishable", "frozen-meals": "perishable",
                   "meat-poultry-fish": "perishable", "printed-books": "book",
                   "wigs-extensions": "hair", "hair-accessories": "fashion", "baby-food-formula": "food",
                   "plain-water": "beverage"}

# Sized fashion product types: the sizing trait (suggested measurements for the seller's size chart) and the
# variation themes with the Size and Color dimensions open to the seller's own values (see docs/engineering/size-charts.md).
SIZING = {
    "dress": [("bust", "Bust", "Around the fullest part of the bust"), ("waist", "Waist", "Around the narrowest part"), ("hip", "Hip", "Around the fullest part of the hips")],
    "top": [("bust", "Bust", "Around the fullest part of the bust"), ("waist", "Waist", "Around the narrowest part"), ("hip", "Hip", "Around the fullest part of the hips")],
    "menwear": [("chest", "Chest", "Around the fullest part of the chest"), ("waist", "Waist", "Around the natural waist"), ("sleeve_length", "Sleeve length", "From the shoulder seam to the wrist")],
    "footwear": [("foot_length", "Foot length", "Heel to the longest toe, standing")],
    "kids": [("height", "Height", "Standing, without shoes"), ("chest", "Chest", "Around the fullest part of the chest"), ("waist", "Waist", "Around the natural waist")],
}


def sized(code, sizes, extra):
    return {"identifierRule": "GTIN", "attributes": FASHION[:1] + extra,
            "themes": [theme("Size × Color", ("Size", sizes), ("Color", COLORS), custom=("Size", "Color")),
                       theme("Size", ("Size", sizes), custom=("Size",))],
            "sizing": {"sizeChartRequired": False, "sizeDimension": "Size",
                       "suggestedMeasurements": [{"key": k, "label": lab, "unit": "CM", "help": h} for k, lab, h in SIZING[code]]}}


PRODUCT_TYPES = {
    "dress": sized("dress", SIZES["women"], [a("material", "Material", ["Cotton wax print", "Cotton", "Lace", "Silk", "Polyester blend"], required=False), a("fit", "Fit", ["Regular", "Fitted", "Relaxed", "Plus size"], required=False), a("length", "Length", ["Mini", "Knee", "Midi", "Maxi"], required=False), a("occasion", "Occasion", ["Everyday", "Wedding / owambe", "Church", "Work"], required=False), a("care", "Care", required=False), a("origin", "Made in", required=False)]),
    "top": sized("top", SIZES["women"], [a("material", "Material", ["Cotton wax print", "Cotton", "Aso-oke", "Lace"], required=False), a("fit", "Fit", ["Regular", "Fitted", "Relaxed"], required=False), a("care", "Care", required=False), a("origin", "Made in", required=False)]),
    "menwear": sized("menwear", SIZES["men"], [a("material", "Material", ["Cotton", "Aso-oke", "Brocade", "Linen"], required=False), a("fit", "Fit", ["Regular", "Slim", "Relaxed"], required=False), a("pieces", "Pieces", ["1", "2 (top + trousers)", "3 (agbada set)"], required=False), a("care", "Care", required=False), a("origin", "Made in", required=False)]),
    "footwear": sized("footwear", SIZES["shoes"], [a("material", "Upper material", required=False), a("origin", "Made in", required=False)]),
    "kids": sized("kids", SIZES["kids"], [a("gender", "For", ["Girls", "Boys", "Unisex"], required=False), a("material", "Material", ["Cotton", "Cotton wax print"], required=False), a("safety", "CPSIA children's product certificate no.", required=False, section="REGULATED")]),
}
PRODUCT_TYPES["kids"]["traits"] = {"regulated": True}
PRODUCT_TYPES["fabrics-textiles"] = {
    "identifierRule": "GTIN",
    "attributes": [brand("Mill or brand", False, ("UNBRANDED", "HANDMADE"), "The mill or print house named on the selvedge, if any."),
                   a("fibre", "Fibre", ["100% cotton", "Silk-cotton", "Polyester", "Lace"], required=False),
                   a("width", "Width", ["44 in", "46 in", "60 in"], required=False), a("origin", "Made in", required=False)],
    "themes": [theme("Length × Pattern", ("Length", ["2 yd", "4 yd", "6 yd", "12 yd"]), ("Pattern", ["Classic", "Floral", "Geometric", "Fish", "Abstract"]))]}

# The source document has no category for fabric by the yard. A provisional one keeps sellers' fabric listings possible
# as drafts; it is NEEDS_APPROVAL (cannot be published) until the owner and the tax professional decide. It has no tax
# profile in tax-returns (taxProfileId is empty), so the importer never invents one.
PROVISIONAL = [{"code": "fabrics-textiles", "department": "fashion-accessories", "name": "Fabrics & textiles",
                "status": "NEEDS_APPROVAL", "productType": "fabrics-textiles",
                "statusReasons": ["Not in the category tax document: the owner decides whether fabric by the yard is its own category and which tax rule applies."]}]
DEPARTMENT_COMPLIANCE = {"beauty-personal-care": ["BEAUTY_LABELLING"]}

# Preorder windows sell food: these categories are pre-order eligible by default (trait `preorder`).


def build():
    return {"_comment": "GENERATED by tools/seed/catalog_taxonomy.py (do not edit). Detail questions per department family; the tax facts come from tools/taxonomy/category_tax_map.json.",
            "families": FAMILIES, "departments": DEPARTMENT_FAMILY, "categories": CATEGORY_FAMILY, "productTypes": PRODUCT_TYPES, "provisional": PROVISIONAL,
            "departmentCompliance": DEPARTMENT_COMPLIANCE}


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, indent=1, sort_keys=False) + "\n"


def main(argv):
    text = dump(build())
    if "--check" in argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print("catalog_taxonomy: %s is stale; run python3 tools/seed/catalog_taxonomy.py --write" % OUT.relative_to(ROOT))
            return 1
        return 0
    if "--write" in argv:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(text, encoding="utf-8")
        return 0
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
