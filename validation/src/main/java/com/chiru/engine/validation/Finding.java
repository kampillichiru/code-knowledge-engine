package com.chiru.engine.validation;

import com.chiru.engine.model.AnalysisWarning;
import java.util.List;

/**
 * One validation result. A finding groups every occurrence of the same problem: {@code count} is how many there
 * were and {@code examples} names the first few, so a large model does not drown the report.
 */
public record Finding(String code, AnalysisWarning.Severity severity, String message, int count, List<String> examples) {

    public Finding {
        examples = List.copyOf(examples);
    }

    public String describe() {
        return "[" + code + "] " + message + (count > 1 ? " (" + count + " occurrences)" : "")
                + (examples.isEmpty() ? "" : ": " + String.join("; ", examples));
    }
}
