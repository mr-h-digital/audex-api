package za.co.mrhdigital.audex.scoring;

import java.util.List;

/**
 * Outcome of scoring one audit. Points are kept in half-points (weight x 2 when Compliant, weight x 1 when
 * Partially Compliant) so that 50% credit is exact.
 */
public record ScoreResult(
        Status status,
        int earnedHalf,
        int possibleHalf,
        double percent,
        int criticalFailures,
        int unanswered,
        int naCount,
        List<String> triggers,
        List<String> flags,
        List<SectionScore> sectionScores) {

    public record SectionScore(String code, boolean highRisk, int earnedHalf, int possibleHalf, double percent) {}
}
