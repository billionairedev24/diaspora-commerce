"""Generates the catalog taxonomy seed migration from the Claude Design product builder data.

Usage: python3 tools/seed/catalog_taxonomy.py > java/services/catalog/src/main/resources/db/migration/V2__seed_taxonomy.sql
Ids are deterministic (uuid5) so re-running produces identical SQL.

This is the INITIAL data of a new environment, nothing more. Since S325 staff create, rename, move, retire and edit
categories, product types and their details in the staff console (Catalog > Categories), audited and with a reason;
see docs/engineering/taxonomy.md. Never regenerate V2 (Flyway would reject the changed checksum on every existing
database) and never add a migration to change the live tree: use the console or the /v1/taxonomy API.

Sizing (seller-written size charts and sizes) is structural taxonomy, not demo data: the fashion product types below
are sized (SIZING) and let sellers write their own Size and Color values (the flag `allowCustomValues` on a
dimension; the listed options are then suggestions). V2 cannot carry it (it predates traits), so V15 applies the same
configuration to existing databases; `python3 tools/seed/catalog_taxonomy.py --sizing` prints that SQL from this file.
"""
import json
import uuid

NS = uuid.UUID("6f1c1d4e-2a1b-4c7e-9a10-3f0d8b6a1c00")

SIZES = {"women": ["XS", "S", "M", "L", "XL", "2XL", "3XL"], "men": ["S", "M", "L", "XL", "2XL", "3XL"],
         "kids": ["2T", "3T", "4", "6", "8", "10", "12"]}
COLORS = ["Black", "White", "Gold", "Indigo", "Wine", "Emerald", "Royal blue", "Multicolour"]

TREE = {
    "Fashion": {"Women": {"Dresses": "dress", "Tops": "top", "Wrappers & skirts": "top", "Headwraps": "headwrap"},
                "Men": {"Agbada & kaftans": "menwear", "Shirts": "menwear", "Trousers": "menwear"},
                "Kids": {"Kids clothing": "kids"},
                "Fabrics": {"Ankara & wax prints": "fabric", "Kente & aso-oke": "fabric", "Lace": "fabric"}},
    "Groceries": {"Pantry": {"Grains & rice": "grocery", "Spices & seasonings": "grocery",
                             "Sauces & pastes": "grocery", "Snacks": "grocery"},
                  "Fresh": {"Frozen & perishables": "perishable"},
                  "Drinks": {"Teas & hibiscus": "grocery", "Coffee": "grocery"}},
    "Beauty": {"Skin care": {"Butters & oils": "beauty", "Soaps": "beauty"},
               "Hair care": {"Braiding hair": "hair", "Hair oils": "beauty"}},
    "Books": {"Books": {"Cookbooks": "book", "Fiction": "book", "Children's books": "book"}},
    "Home & Art": {"Decor": {"Pillows & throws": "home", "Wall art": "home"}},
    "Health": {"Wellness": {"Herbal teas & remedies": "grocery"}},
    "Event & party": {"Party supplies": {"Decorations": "home"}},
}


def a(key, label, options=None, multiline=False, required=True):
    return {"key": key, "label": label, "options": options or [], "required": required, "multiline": multiline}


# Dimensions sellers may extend with values of their own, per fashion product type (suggestions stay the options).
CUSTOM_DIMENSIONS = {"dress": ("Size", "Color"), "top": ("Size", "Color"), "menwear": ("Size", "Color"),
                     "kids": ("Size", "Color"), "headwrap": ("Color",)}

# The sizing trait staff switch on per product type: suggested measurements for the seller's size chart editor and the
# bulk template. sizeChartRequired is a staff decision per category: off here so existing listings stay editable.
SIZING = {
    "dress": [("bust", "Bust", "Around the fullest part of the bust"), ("waist", "Waist", "Around the narrowest part"),
              ("hip", "Hip", "Around the fullest part of the hips")],
    "top": [("bust", "Bust", "Around the fullest part of the bust"), ("waist", "Waist", "Around the narrowest part"),
            ("hip", "Hip", "Around the fullest part of the hips")],
    "menwear": [("chest", "Chest", "Around the fullest part of the chest"), ("waist", "Waist", "Around the natural waist"),
                ("sleeve_length", "Sleeve length", "From the shoulder seam to the wrist")],
    "kids": [("height", "Height", "Standing, without shoes"), ("chest", "Chest", "Around the fullest part of the chest"),
             ("waist", "Waist", "Around the natural waist")],
}


def theme(name, *dims):
    return {"name": name, "dimensions": [{"name": d[0], "options": d[1]} for d in dims]}


def with_custom(code, themes):
    """The themes of a type with allowCustomValues on the dimensions sellers may extend."""
    custom = CUSTOM_DIMENSIONS.get(code, ())
    return [{**t, "dimensions": [{**d, "allowCustomValues": True} if d["name"] in custom else d
                                 for d in t["dimensions"]]} for t in themes]


TYPES = {
    "dress": ("Women's dresses", [a("brand", "Brand"), a("material", "Material", ["Cotton wax print", "Cotton", "Lace", "Silk", "Polyester blend"]), a("fit", "Fit", ["Regular", "Fitted", "Relaxed", "Plus size"]), a("length", "Length", ["Mini", "Knee", "Midi", "Maxi"]), a("sleeve", "Sleeve", ["Sleeveless", "Short", "Three-quarter", "Long"]), a("occasion", "Occasion", ["Everyday", "Wedding / owambe", "Church", "Work"]), a("care", "Care"), a("origin", "Made in")],
              [theme("Size × Color", ("Size", SIZES["women"]), ("Color", COLORS)), theme("Size", ("Size", SIZES["women"]))], "GTIN"),
    "top": ("Women's tops & wrappers", [a("brand", "Brand"), a("material", "Material", ["Cotton wax print", "Cotton", "Aso-oke", "Lace"]), a("fit", "Fit", ["Regular", "Fitted", "Relaxed"]), a("care", "Care"), a("origin", "Made in")],
            [theme("Size × Color", ("Size", SIZES["women"]), ("Color", COLORS))], "GTIN"),
    "headwrap": ("Headwraps", [a("brand", "Brand"), a("material", "Material", ["Aso-oke", "Damask", "Cotton"]), a("style", "Style", ["Pre-tied (auto gele)", "Untied"]), a("origin", "Made in")],
                 [theme("Color", ("Color", COLORS))], "GTIN"),
    "menwear": ("Men's clothing", [a("brand", "Brand"), a("material", "Material", ["Cotton", "Aso-oke", "Brocade", "Linen"]), a("fit", "Fit", ["Regular", "Slim", "Relaxed"]), a("pieces", "Pieces", ["1", "2 (top + trousers)", "3 (agbada set)"]), a("care", "Care"), a("origin", "Made in")],
                [theme("Size × Color", ("Size", SIZES["men"]), ("Color", COLORS)), theme("Size", ("Size", SIZES["men"]))], "GTIN"),
    "kids": ("Kids' clothing", [a("brand", "Brand"), a("gender", "For", ["Girls", "Boys", "Unisex"]), a("material", "Material", ["Cotton", "Cotton wax print"]), a("safety", "CPSIA children's product certificate no.")],
             [theme("Size × Color", ("Size", SIZES["kids"]), ("Color", COLORS))], "GTIN"),
    "fabric": ("Fabrics", [a("brand", "Mill / brand"), a("fibre", "Fibre", ["100% cotton", "Silk-cotton", "Polyester", "Lace"]), a("width", "Width", ["44 in", "46 in", "60 in"]), a("origin", "Made in")],
               [theme("Length × Pattern", ("Length", ["2 yd", "4 yd", "6 yd", "12 yd"]), ("Pattern", ["Classic", "Floral", "Geometric", "Fish", "Abstract"]))], "GTIN"),
    "grocery": ("Packaged food", [a("brand", "Brand"), a("ingredients", "Ingredients", multiline=True), a("allergens", "Allergens (FALCPA)", ["None", "Contains peanuts", "Contains shellfish", "Contains fish", "Contains wheat", "Contains soy"]), a("shelf", "Shelf life (days)"), a("storage", "Storage", ["Room temperature", "Refrigerate after opening"]), a("origin", "Country of origin"), a("cert", "Certification", ["None", "Halal", "Kosher", "Organic"])],
                [theme("Net weight", ("Net weight", ["8 oz", "1 lb", "2 lb", "5 lb", "10 lb"])), theme("Net weight × Pack", ("Net weight", ["1 lb", "5 lb"]), ("Pack", ["1", "2", "4"]))], "GTIN"),
    "perishable": ("Frozen & perishable food", [a("brand", "Brand"), a("ingredients", "Ingredients", multiline=True), a("allergens", "Allergens (FALCPA)", ["None", "Contains fish", "Contains shellfish"]), a("temp", "Storage temperature", ["Frozen (≤ 0°F)", "Chilled (33–40°F)"]), a("permit", "Food facility / permit no.")],
                   [theme("Net weight", ("Net weight", ["1 lb", "2 lb", "5 lb"]))], "GTIN"),
    "beauty": ("Beauty & personal care", [a("brand", "Brand"), a("inci", "Ingredients (INCI)", multiline=True), a("skin", "Skin / hair type", ["All", "Dry", "Oily", "Sensitive"]), a("mocra", "MoCRA product listing no."), a("warn", "Warnings")],
               [theme("Size", ("Size", ["4 oz", "8 oz", "16 oz"])), theme("Size × Scent", ("Size", ["8 oz", "16 oz"]), ("Scent", ["Unscented", "Lemongrass", "Lavender", "Coconut"]))], "GTIN"),
    "hair": ("Braiding hair", [a("brand", "Brand"), a("fibre", "Fibre", ["Kanekalon", "Toyokalon", "Human hair", "Pre-stretched synthetic"]), a("packs", "Packs per bundle")],
             [theme("Length × Color", ("Length", ["20 in", "26 in", "30 in"]), ("Color", ["1B", "2", "4", "27", "30", "613"]))], "GTIN"),
    "book": ("Books", [a("author", "Author"), a("publisher", "Publisher"), a("language", "Language", ["English", "French", "Yoruba", "Swahili", "Amharic"]), a("pages", "Pages")],
             [theme("Format", ("Format", ["Hardcover", "Paperback"]))], "ISBN"),
    "home": ("Home & decor", [a("brand", "Maker / brand"), a("material", "Material"), a("dims", "Dimensions"), a("handmade", "Handmade", ["Yes", "No"])],
             [theme("Size × Pattern", ("Size", ["18 in", "22 in"]), ("Pattern", ["Classic", "Arrow", "Dots"]))], "GTIN"),
    "custom": ("Your requested category", [a("brand", "Brand"), a("material", "Material / main ingredient"), a("details", "Key details shoppers need", multiline=True), a("origin", "Made in")],
               [theme("Option", ("Option", ["Standard", "Small", "Medium", "Large"])), theme("Size × Color", ("Size", ["S", "M", "L", "XL"]), ("Color", ["Black", "White", "Gold", "Multicolour"]))], "GTIN"),
}


def q(s):
    return "'" + s.replace("'", "''") + "'"


def sizing_sql():
    """The SQL that makes the fashion product types sized and lets sellers write their own sizes and colours (V15).

    It only sets the flag on the dimensions and adds the sizing trait, so options staff edited since V2 are kept."""
    out = []
    for code, dimensions in CUSTOM_DIMENSIONS.items():
        names = ", ".join(q(d) for d in dimensions)
        sets = ["""themes = (select jsonb_agg(jsonb_set(t.theme, '{dimensions}', (
                       select jsonb_agg(case when d.dim ->> 'name' in (%s) then d.dim || '{"allowCustomValues": true}'::jsonb
                                             else d.dim end order by d.pos)
                       from jsonb_array_elements(t.theme -> 'dimensions') with ordinality as d(dim, pos))) order by t.pos)
                   from jsonb_array_elements(themes) with ordinality as t(theme, pos))""" % names]
        if code in SIZING:
            sizing = {"sizeChartRequired": False, "sizeDimension": "Size",
                      "suggestedMeasurements": [{"key": k, "label": label, "unit": "CM", "help": h}
                                                for k, label, h in SIZING[code]]}
            sets.append("traits = traits || %s::jsonb" % q(json.dumps({"sizing": sizing}, ensure_ascii=False)))
        out.append("update product_type set %s,\n       version = version + 1, updated_at = now() where code = %s;\n"
                   % (",\n       ".join(sets), q(code)))
    return "\n".join(out)


def main():
    out = ["-- Generated by tools/seed/catalog_taxonomy.py from design/AfroTransact Catalog.dc.html. Do not edit by hand."]
    for code, (label, attrs, themes, rule) in TYPES.items():
        out.append("insert into product_type (code, label, identifier_rule, attributes, themes) values (%s, %s, %s, %s::jsonb, %s::jsonb);"
                   % (q(code), q(label), q(rule), q(json.dumps(attrs, ensure_ascii=False)), q(json.dumps(themes, ensure_ascii=False))))
    for di, (dept, cats) in enumerate(TREE.items()):
        did = uuid.uuid5(NS, dept)
        out.append("insert into category (id, parent_id, name, level, product_type_code, path, position) values ('%s', null, %s, 'DEPARTMENT', null, %s, %d);" % (did, q(dept), q(dept), di))
        for ci, (cat, leaves) in enumerate(cats.items()):
            cid = uuid.uuid5(NS, dept + "/" + cat)
            cpath = dept + " › " + cat
            out.append("insert into category (id, parent_id, name, level, product_type_code, path, position) values ('%s', '%s', %s, 'CATEGORY', null, %s, %d);" % (cid, did, q(cat), q(cpath), ci))
            for li, (leaf, code) in enumerate(leaves.items()):
                lid = uuid.uuid5(NS, dept + "/" + cat + "/" + leaf)
                out.append("insert into category (id, parent_id, name, level, product_type_code, path, position) values ('%s', '%s', %s, 'PRODUCT_TYPE', %s, %s, %d);" % (lid, cid, q(leaf), q(code), q(cpath + " › " + leaf), li))
    print("\n".join(out))


if __name__ == "__main__":
    import sys
    if "--sizing" in sys.argv:
        print(sizing_sql())
    else:
        main()
