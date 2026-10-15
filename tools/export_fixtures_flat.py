"""Expand shared fixtures into a flat text file so the dependency-free Java engine can be checked with plain javac
(used when Maven Central is unreachable). Does not score anything; only applies each fixture's overrides."""
import json, sys
shared, out = sys.argv[1], sys.argv[2]
tpl = json.load(open(f"{shared}/template.json"))
rulesets = {r["code"]: r for r in json.load(open(f"{shared}/rulesets.json"))}
items = [(s, i) for s in tpl["sections"] for i in s["items"]]
b = lambda v: "1" if v else "0"
with open(out, "w") as f:
    for fx in json.load(open(f"{shared}/scoring-fixtures.json")):
        r = rulesets[fx["ruleSet"]]
        f.write(f"FIXTURE\t{fx['name']}\n")
        f.write("RULES\t" + "\t".join(str(x) for x in (
            r["compliantMinTenths"], r["notYetMinTenths"], b(r["criticalNonCompliantOverride"]),
            b(r["criticalPartialCap"]["enabled"]), b(r["majorFailureCap"]["enabled"]), r["majorFailureCap"]["count"],
            b(r["highRiskSectionFloor"]["enabled"]), r["highRiskSectionFloor"]["minTenths"],
            b(r["naReviewTrigger"]["enabled"]), r["naReviewTrigger"]["maxShareTenths"])) + "\n")
        for s, i in items:
            res = fx["overrides"].get(i["key"], fx["default"]) if i["key"] in fx["overrides"] else fx["default"]
            f.write(f"ITEM\t{i['key']}\t{s['code']}\t{b(s['highRisk'])}\t{i['severity']}\t{i['weight']}\t{res or '-'}\n")
        e = fx["expected"]
        f.write(f"EXPECT\t{e['status']}\t{e['earnedHalf']}\t{e['possibleHalf']}\t{e['criticalFailures']}\t"
                f"{','.join(e['triggers'])}\t{','.join(e['flags'])}\nEND\n")
