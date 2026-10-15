package za.co.mrhdigital.audex.scoring;

/**
 * A versioned set of scoring rules. Percentages are stored as integer tenths of a percent
 * (950 = 95.0%) so threshold comparisons are exact integer arithmetic, identical in Java and TypeScript.
 */
public record RuleSet(
        String code,
        String name,
        int compliantMinTenths,
        int notYetMinTenths,
        boolean criticalNonCompliantOverride,
        CriticalPartialCap criticalPartialCap,
        MajorFailureCap majorFailureCap,
        HighRiskSectionFloor highRiskSectionFloor,
        NaReviewTrigger naReviewTrigger) {

    /** A Partially Compliant Critical item caps the status at Not Yet Compliant. */
    public record CriticalPartialCap(boolean enabled) {}

    /** This many Non-Compliant Major items (or more) caps the status at Not Yet Compliant. */
    public record MajorFailureCap(boolean enabled, int count) {}

    /** Any high-risk section scoring below minTenths caps the status at Not Yet Compliant. */
    public record HighRiskSectionFloor(boolean enabled, int minTenths) {}

    /** More than maxShareTenths of all items marked N/A flags the audit for review (no status change). */
    public record NaReviewTrigger(boolean enabled, int maxShareTenths) {}
}
