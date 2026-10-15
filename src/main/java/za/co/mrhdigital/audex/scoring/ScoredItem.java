package za.co.mrhdigital.audex.scoring;

/** One checklist item together with the auditor's answer (result == null means unanswered). */
public record ScoredItem(
        String key,
        String sectionCode,
        boolean highRisk,
        Severity severity,
        int weight,
        Result result) {}
