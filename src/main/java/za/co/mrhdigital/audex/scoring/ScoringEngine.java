package za.co.mrhdigital.audex.scoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure scoring logic, no framework dependencies. A TypeScript twin (frontend/src/scoring) runs on the phone so
 * the auditor sees a live score offline; both are tested against the same shared fixtures.
 * <p>Order of decisions:
 * <ol>
 * <li>Any unanswered applicable item: INCOMPLETE</li>
 * <li>Any Critical item Non-Compliant (if enabled): NON_COMPLIANT</li>
 * <li>Percentage thresholds: COMPLIANT / NOT_YET_COMPLIANT / NON_COMPLIANT</li>
 * <li>If COMPLIANT and any cap rule triggered: NOT_YET_COMPLIANT (caps never lower a worse status)</li>
 * </ol>
 */
public final class ScoringEngine {

    public static final String TRIGGER_CRITICAL_PARTIAL = "criticalPartialCap";
    public static final String TRIGGER_MAJOR_FAILURES = "majorFailureCap";
    public static final String TRIGGER_HIGH_RISK_SECTION = "highRiskSectionFloor";
    public static final String FLAG_NA_REVIEW = "naReview";

    private ScoringEngine() {}

    public static ScoreResult evaluate(List<ScoredItem> items, RuleSet rules) {
        int earned = 0, possible = 0, unanswered = 0, naCount = 0;
        int criticalNonCompliant = 0, criticalPartial = 0, majorNonCompliant = 0;
        Map<String, int[]> sections = new LinkedHashMap<>(); // code -> {earned, possible, highRisk(0/1)}

        for (ScoredItem item : items) {
            int[] sec = sections.computeIfAbsent(item.sectionCode(), k -> new int[] {0, 0, item.highRisk() ? 1 : 0});
            if (item.result() == null) {
                unanswered++;
                continue;
            }
            if (item.result() == Result.NA) {
                naCount++;
                continue;
            }
            int full = item.weight() * 2;
            int got = switch (item.result()) {
                case COMPLIANT -> full;
                case PARTIALLY_COMPLIANT -> item.weight();
                default -> 0;
            };
            earned += got;
            possible += full;
            sec[0] += got;
            sec[1] += full;
            if (item.severity() == Severity.CRITICAL) {
                if (item.result() == Result.NON_COMPLIANT) criticalNonCompliant++;
                if (item.result() == Result.PARTIALLY_COMPLIANT) criticalPartial++;
            } else if (item.severity() == Severity.MAJOR && item.result() == Result.NON_COMPLIANT) {
                majorNonCompliant++;
            }
        }

        List<String> triggers = new ArrayList<>();
        if (rules.criticalPartialCap().enabled() && criticalPartial > 0) {
            triggers.add(TRIGGER_CRITICAL_PARTIAL);
        }
        if (rules.majorFailureCap().enabled() && majorNonCompliant >= rules.majorFailureCap().count()) {
            triggers.add(TRIGGER_MAJOR_FAILURES);
        }
        List<ScoreResult.SectionScore> sectionScores = new ArrayList<>();
        boolean sectionBelowFloor = false;
        for (Map.Entry<String, int[]> e : sections.entrySet()) {
            int[] s = e.getValue();
            boolean highRisk = s[2] == 1;
            sectionScores.add(new ScoreResult.SectionScore(e.getKey(), highRisk, s[0], s[1], percent(s[0], s[1])));
            if (highRisk && s[1] > 0 && rules.highRiskSectionFloor().enabled()
                    && s[0] * 1000 < s[1] * rules.highRiskSectionFloor().minTenths()) {
                sectionBelowFloor = true;
            }
        }
        if (sectionBelowFloor) triggers.add(TRIGGER_HIGH_RISK_SECTION);

        List<String> flags = new ArrayList<>();
        if (rules.naReviewTrigger().enabled()
                && naCount * 1000 > items.size() * rules.naReviewTrigger().maxShareTenths()) {
            flags.add(FLAG_NA_REVIEW);
        }

        Status status;
        if (unanswered > 0) {
            status = Status.INCOMPLETE;
        } else if (rules.criticalNonCompliantOverride() && criticalNonCompliant > 0) {
            status = Status.NON_COMPLIANT;
        } else if (possible > 0 && earned * 1000 >= possible * rules.compliantMinTenths()) {
            status = triggers.isEmpty() ? Status.COMPLIANT : Status.NOT_YET_COMPLIANT;
        } else if (possible > 0 && earned * 1000 >= possible * rules.notYetMinTenths()) {
            status = Status.NOT_YET_COMPLIANT;
        } else {
            status = Status.NON_COMPLIANT;
        }

        return new ScoreResult(status, earned, possible, percent(earned, possible), criticalNonCompliant,
                unanswered, naCount, List.copyOf(triggers), List.copyOf(flags), List.copyOf(sectionScores));
    }

    public static double percent(int earnedHalf, int possibleHalf) {
        return possibleHalf == 0 ? 0.0 : Math.round(earnedHalf * 10000.0 / possibleHalf) / 100.0;
    }
}
