import java.nio.file.*;
import java.util.*;
import za.co.mrhdigital.audex.scoring.*;

/** Runs flattened shared fixtures through the real ScoringEngine without Maven. Usage: JavacHarness <flat file> */
public class JavacHarness {
    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;
        String name = null; RuleSet rules = null; List<ScoredItem> items = new ArrayList<>(); String[] exp = null;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] p = line.split("\t", -1);
            switch (p[0]) {
                case "FIXTURE" -> { name = p[1]; items = new ArrayList<>(); }
                case "RULES" -> rules = new RuleSet("T", "T", Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3].equals("1"),
                        new RuleSet.CriticalPartialCap(p[4].equals("1")),
                        new RuleSet.MajorFailureCap(p[5].equals("1"), Integer.parseInt(p[6])),
                        new RuleSet.HighRiskSectionFloor(p[7].equals("1"), Integer.parseInt(p[8])),
                        new RuleSet.NaReviewTrigger(p[9].equals("1"), Integer.parseInt(p[10])));
                case "ITEM" -> items.add(new ScoredItem(p[1], p[2], p[3].equals("1"), Severity.valueOf(p[4]),
                        Integer.parseInt(p[5]), p[6].equals("-") ? null : Result.valueOf(p[6])));
                case "EXPECT" -> exp = p;
                case "END" -> {
                    ScoreResult a = ScoringEngine.evaluate(items, rules);
                    String got = a.status() + "|" + a.earnedHalf() + "|" + a.possibleHalf() + "|" + a.criticalFailures()
                            + "|" + String.join(",", a.triggers()) + "|" + String.join(",", a.flags());
                    String want = exp[1] + "|" + exp[2] + "|" + exp[3] + "|" + exp[4] + "|" + exp[5] + "|" + exp[6];
                    if (got.equals(want)) pass++;
                    else { fail++; System.out.println("FAIL " + name + "\n  want " + want + "\n  got  " + got); }
                }
                default -> {}
            }
        }
        System.out.println("Java engine: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
