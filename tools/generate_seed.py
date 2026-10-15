"""Generate shared/template.json, rulesets.json and scoring-fixtures.json from the WPC Aligned Audit workbook.
Usage: python3 generate_seed.py <WPC xlsx> <out dir>"""
import sys, re, json, openpyxl

HIGH_RISK = {
    "CHEMICALS & CHEMICAL STORAGE", "CLEANING EQUIPMENT & TROLLEY",
    "BATHROOM & TOILET CLEANING PROCEDURE", "PATIENT ROOM CLEANING PROCEDURE",
    "WASTE DISPOSAL", "SLUICE", "LINEN MANAGEMENT",
}
WEIGHT = {"Minor": 1, "Major": 2, "Critical": 3}

def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")

def main(xlsx, out):
    ws = openpyxl.load_workbook(xlsx)["Aligned Audit"]
    sections, by_name = [], {}
    for r in range(10, 301):
        sec, text, sev, w = (ws.cell(r, c).value for c in (1, 2, 4, 5))
        if not text:
            continue
        sec = sec.strip()
        if sec not in by_name:
            by_name[sec] = {"code": slug(sec), "name": sec,
                            "highRisk": sec.upper() in HIGH_RISK, "items": []}
            sections.append(by_name[sec])
        s = by_name[sec]
        assert WEIGHT[sev] == w, (r, sev, w)
        s["items"].append({"key": f"{s['code']}-{len(s['items'])+1:02d}",
                           "text": " ".join(text.split()), "severity": sev.upper(), "weight": w})
    template = {"name": "WPC Health Care Housekeeping Audit", "version": 1, "sections": sections}
    items = [i for s in sections for i in s["items"]]
    assert len(items) == 264 and sum(i["weight"] for i in items) == 322, (len(items),)
    json.dump(template, open(f"{out}/template.json", "w"), indent=1, ensure_ascii=False)

    rulesets = [
        {"code": "WPC_V1", "name": "WPC current rules", "compliantMinTenths": 950, "notYetMinTenths": 850,
         "criticalNonCompliantOverride": True,
         "criticalPartialCap": {"enabled": False},
         "majorFailureCap": {"enabled": False, "count": 3},
         "highRiskSectionFloor": {"enabled": False, "minTenths": 850},
         "naReviewTrigger": {"enabled": False, "maxShareTenths": 100}},
        {"code": "WPC_V2_PROPOSED", "name": "WPC proposed rules (pending IPC approval)", "compliantMinTenths": 950,
         "notYetMinTenths": 850, "criticalNonCompliantOverride": True,
         "criticalPartialCap": {"enabled": True},
         "majorFailureCap": {"enabled": True, "count": 3},
         "highRiskSectionFloor": {"enabled": True, "minTenths": 850},
         "naReviewTrigger": {"enabled": True, "maxShareTenths": 100}},
    ]
    json.dump(rulesets, open(f"{out}/rulesets.json", "w"), indent=1)

    def keys(sev=None, sec=None, n=None, skip=0):
        sel = [i["key"] for s in sections if sec is None or s["code"] == sec
               for i in s["items"] if sev is None or i["severity"] == sev]
        return sel[skip:skip + n] if n is not None else sel[skip:]
    C, P, N, A = "COMPLIANT", "PARTIALLY_COMPLIANT", "NON_COMPLIANT", "NA"
    def o(result, ks): return {k: result for k in ks}
    TOT = 644  # 322 weight * 2 half-points
    fixtures = []
    def fx(name, overrides, expect):
        for rs in ("WPC_V1", "WPC_V2_PROPOSED"):
            e = expect[rs] if rs in expect else expect["both"]
            fixtures.append({"name": f"{name} [{rs}]", "ruleSet": rs, "default": C,
                             "overrides": overrides, "expected": e})
    fx("all compliant", {}, {"both": dict(status="COMPLIANT", earnedHalf=644, possibleHalf=644, criticalFailures=0, triggers=[], flags=[])})
    # 8 Majors spread so that no high-risk section falls under 85% (isolates the Major-count rule)
    eight = (keys("MAJOR", sec="staff-bathrooms-toilets") + keys("MAJOR", sec="laundry")
             + keys("MAJOR", sec="documentation") + keys("MAJOR", sec="linen-management", n=2))
    assert len(eight) == 8
    crit1 = keys("CRITICAL", sec="bathroom-toilet-cleaning-procedure", n=1)
    fx("8 Major non-compliant", o(N, eight),
       {"WPC_V1": dict(status="COMPLIANT", earnedHalf=612, possibleHalf=644, criticalFailures=0, triggers=[], flags=[]),
        "WPC_V2_PROPOSED": dict(status="NOT_YET_COMPLIANT", earnedHalf=612, possibleHalf=644, criticalFailures=0, triggers=["majorFailureCap"], flags=[])})
    fx("2 Major non-compliant stays under the Major cap", o(N, keys("MAJOR", n=2)),
       {"both": dict(status="COMPLIANT", earnedHalf=636, possibleHalf=644, criticalFailures=0, triggers=[], flags=[])})
    fx("4 Chemical Critical items partial", o(P, keys("CRITICAL", sec="chemicals-chemical-storage")),
       {"WPC_V1": dict(status="COMPLIANT", earnedHalf=632, possibleHalf=644, criticalFailures=0, triggers=[], flags=[]),
        "WPC_V2_PROPOSED": dict(status="NOT_YET_COMPLIANT", earnedHalf=632, possibleHalf=644, criticalFailures=0,
                                triggers=["criticalPartialCap", "highRiskSectionFloor"], flags=[])})
    fx("Waste Disposal 2 Major + 3 Minor non-compliant",
       {**o(N, keys("MAJOR", sec="waste-disposal")), **o(N, keys("MINOR", sec="waste-disposal"))},
       {"WPC_V1": dict(status="COMPLIANT", earnedHalf=630, possibleHalf=644, criticalFailures=0, triggers=[], flags=[]),
        "WPC_V2_PROPOSED": dict(status="NOT_YET_COMPLIANT", earnedHalf=630, possibleHalf=644, criticalFailures=0,
                                triggers=["highRiskSectionFloor"], flags=[])})
    fx("one Critical non-compliant", o(N, crit1),
       {"both": dict(status="NON_COMPLIANT", earnedHalf=638, possibleHalf=644, criticalFailures=1, triggers=[], flags=[])})
    fx("Critical failure plus 8 Major failures stays Non-Compliant", {**o(N, crit1), **o(N, eight)},
       {"WPC_V1": dict(status="NON_COMPLIANT", earnedHalf=606, possibleHalf=644, criticalFailures=1, triggers=[], flags=[]),
        "WPC_V2_PROPOSED": dict(status="NON_COMPLIANT", earnedHalf=606, possibleHalf=644, criticalFailures=1, triggers=["majorFailureCap"], flags=[])})
    fx("unanswered item", {keys("MINOR", n=1)[0]: None},
       {"both": dict(status="INCOMPLETE", earnedHalf=642, possibleHalf=642, criticalFailures=0, triggers=[], flags=[])})
    mins = keys("MINOR")
    fx("30 Minor items N/A (over 10%)", o(A, mins[:30]),
       {"WPC_V1": dict(status="COMPLIANT", earnedHalf=584, possibleHalf=584, criticalFailures=0, triggers=[], flags=[]),
        "WPC_V2_PROPOSED": dict(status="COMPLIANT", earnedHalf=584, possibleHalf=584, criticalFailures=0, triggers=[], flags=["naReview"])})
    fx("below 85%: 60 Minor non-compliant", o(N, mins[:60]),
       {"both": dict(status="NON_COMPLIANT", earnedHalf=524, possibleHalf=644, criticalFailures=0, triggers=[], flags=[])})
    fx("exactly 95.0%: 2 N/A + 16 Minor non-compliant", {**o(A, mins[:2]), **o(N, mins[2:18])},
       {"both": dict(status="COMPLIANT", earnedHalf=608, possibleHalf=640, criticalFailures=0, triggers=[], flags=[])})
    fx("just under 95%: as above plus one Minor partial", {**o(A, mins[:2]), **o(N, mins[2:18]), mins[18]: P},
       {"both": dict(status="NOT_YET_COMPLIANT", earnedHalf=607, possibleHalf=640, criticalFailures=0, triggers=[], flags=[])})
    fx("exactly 85.0%: 2 N/A + 48 Minor non-compliant", {**o(A, mins[:2]), **o(N, mins[2:50])},
       {"both": dict(status="NOT_YET_COMPLIANT", earnedHalf=544, possibleHalf=640, criticalFailures=0, triggers=[], flags=[])})
    json.dump(fixtures, open(f"{out}/scoring-fixtures.json", "w"), indent=1)
    print("sections", len(sections), "items", len(items), "fixtures", len(fixtures))

main(sys.argv[1], sys.argv[2])
