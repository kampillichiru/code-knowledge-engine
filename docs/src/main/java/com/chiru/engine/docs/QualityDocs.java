package com.chiru.engine.docs;

import com.chiru.engine.model.*;
import com.chiru.engine.validation.Coverage;
import java.util.*;

/** 12 open questions and gaps, 13 analysis coverage report. */
final class QualityDocs {

    private QualityDocs() {}

    // ---------------------------------------------------------------- 12

    static String gaps(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("12 Open questions and gaps",
                "Everything the analysis could not establish, and what a person should check."));
        sb.append("> Nothing is hidden: every missing link, approximation and unsupported construct found is listed here.\n\n");

        sb.append(Md.h(2, "Validation findings"));
        List<AnalysisWarning> validation = c.model.warnings().stream().filter(w -> w.stage().equals("validation")).toList();
        sb.append(Md.table(List.of("Severity", "Finding"), validation.stream()
                .sorted(Comparator.comparing((AnalysisWarning w) -> w.severity().ordinal()).reversed())
                .map(w -> List.of(w.severity().name(), w.message())).toList()));
        if (validation.isEmpty()) {
            sb.append("_The validation stage did not run, or found nothing._\n\n");
        }

        sb.append(Md.h(2, "Output fields that are not fully traced"));
        List<List<String>> fields = new ArrayList<>();
        for (Entity f : c.fields()) {
            String status = Optional.ofNullable(c.attr(f, "traceStatus")).orElse("UNRESOLVED");
            if (!"VERIFIED".equals(status)) {
                fields.add(List.of(f.name(), status, Optional.ofNullable(c.attr(f, "gaps")).orElse("no trace was produced"), c.loc(f)));
            }
        }
        sb.append(Md.table(List.of("Output field", "Status", "Missing link", "Evidence"), fields));

        sb.append(Md.h(2, "Rules that need human review"));
        List<List<String>> rules = new ArrayList<>();
        for (Entity r : c.of(EntityType.BUSINESS_RULE)) {
            if (!"VERIFIED".equals(c.attr(r, "traceStatus"))) {
                rules.add(List.of(c.ruleLabel(r.id().value()), Optional.ofNullable(c.attr(r, "traceStatus")).orElse("UNRESOLVED"),
                        c.method(c.attr(r, "method")), c.loc(r)));
            }
        }
        rules.sort(Comparator.comparing(r -> r.get(0)));
        sb.append("_A rule is PARTIAL when its condition or value passes through a call outside the analysed sources (for example a "
                + "library not on the pure list). The rule text is still exact; what is uncertain is the value behind the call._\n\n");
        sb.append(Md.table(List.of("Rule", "Status", "Method", "Evidence"), rules));

        sb.append(Md.h(2, "SQL needing attention"));
        List<List<String>> sql = new ArrayList<>();
        for (Entity s : c.of(EntityType.SQL_STATEMENT)) {
            List<String> issues = new ArrayList<>();
            if ("true".equals(c.attr(s, "dynamic"))) issues.add("dynamic SQL");
            if ("false".equals(c.attr(s, "parsed"))) issues.add("not parsed: " + c.attr(s, "error"));
            if (c.attr(s, "unsupported") != null) issues.add("unsupported: " + c.attr(s, "unsupported"));
            if (c.attr(s, "notes") != null) issues.add(c.attr(s, "notes"));
            if (!issues.isEmpty()) {
                sql.add(List.of(s.name(), String.join("; ", issues), c.loc(s)));
            }
        }
        sb.append(Md.table(List.of("Statement", "Issue", "Evidence"), sql));

        sb.append(Md.h(2, "Warnings from the analysis stages"));
        List<List<String>> warnings = new ArrayList<>();
        for (AnalysisWarning w : c.model.warnings()) {
            if (!w.stage().equals("validation") && w.severity() != AnalysisWarning.Severity.INFO) {
                warnings.add(List.of(w.severity().name(), w.stage(), w.message(), w.location() == null ? Md.NONE : w.location().toString()));
            }
        }
        sb.append(Md.table(List.of("Severity", "Stage", "Message", "Location"), warnings));

        sb.append(Md.h(2, "Notes from the stages (what was approximated or not modelled)"));
        List<List<String>> notes = new ArrayList<>();
        for (AnalysisWarning w : c.model.warnings()) {
            if (!w.stage().equals("validation") && w.severity() == AnalysisWarning.Severity.INFO) {
                notes.add(List.of(w.stage(), w.message()));
            }
        }
        sb.append(Md.table(List.of("Stage", "Note"), notes));

        sb.append(Md.h(2, "Standing limits of the engine"));
        sb.append("- Static analysis only: reflection, dependency injection, callbacks and runtime-generated behaviour are not resolved.\n");
        sb.append("- The bytecode call graph is class-hierarchy based: virtual and interface calls list every compatible target.\n");
        sb.append("- Data flow is field-based and call-site-insensitive; definitions are not ordered by control flow, so a value can "
                + "appear to depend on a later reassignment.\n");
        sb.append("- Only fixed-width layouts built by string concatenation are detected as outputs; database writes are not yet traced "
                + "as outputs.\n");
        sb.append("- Business meaning is never inferred. Explanations must come from a person or an AI that cites this evidence.\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 13

    static String coverage(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("13 Analysis coverage report", "How much of what was found was handled, with denominators."));
        sb.append("> Every percentage names what it is a share of. Coverage says how much of what the analysis *discovered* was handled; it "
                + "cannot see code it never found, and **it says nothing about whether a derivation is correct**. A field traced VERIFIED "
                + "has an unbroken chain of verified static links, not a business sign-off.\n\n");
        Map<String, List<List<String>>> byGroup = new LinkedHashMap<>();
        for (Coverage.Metric m : Coverage.compute(c.model)) {
            byGroup.computeIfAbsent(m.group(), k -> new ArrayList<>()).add(List.of(m.name(), m.ratio(), m.percent(), m.basis()));
        }
        byGroup.forEach((group, rows) -> {
            sb.append(Md.h(2, group));
            sb.append(Md.table(List.of("Metric", "Count", "Share", "Share of / basis"), rows));
        });
        return sb.toString();
    }
}
