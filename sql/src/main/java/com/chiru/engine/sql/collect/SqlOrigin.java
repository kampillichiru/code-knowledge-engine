package com.chiru.engine.sql.collect;

import com.chiru.engine.model.EntityId;
import com.chiru.engine.model.SourceLocation;
import java.util.List;

/**
 * A SQL text found in the code base, with where it came from and (for Java) how it is used.
 *
 * @param key         stable key used to build the statement ID
 * @param text        SQL with {@code ?} placeholders; runtime-built parts appear as {@code __DYN_n__} tokens
 * @param dynamic     the text is built or selected at runtime, so what was extracted is a superset or approximation
 * @param notes       human-readable caveats collected while extracting (dynamic parts, unsupported tags, ...)
 * @param paramNames  for mapper XML: the {@code #{name}} of each placeholder, in order
 * @param java        usage in Java source; null for XML and .sql sources
 */
public record SqlOrigin(
        Kind kind,
        String key,
        SourceLocation location,
        String text,
        boolean dynamic,
        List<String> notes,
        List<String> paramNames,
        JavaUse java) {

    public enum Kind { JAVA, XML, SQL_FILE, CONFIG }

    public SqlOrigin {
        notes = notes == null ? List.of() : List.copyOf(notes);
        paramNames = paramNames == null ? List.of() : List.copyOf(paramNames);
    }

    /** Java-side usage: where the text is defined and each place that executes it. */
    public record JavaUse(EntityId definedIn, List<Consumer> consumers) {
        public JavaUse {
            consumers = List.copyOf(consumers);
        }
    }

    /**
     * One place that executes the SQL. {@code bindingsDerived} says whether parameter and result reads could be
     * followed (a local statement object); when false the lists are empty because they were not derived.
     */
    public record Consumer(
            EntityId method,
            String api,
            SourceLocation location,
            boolean bindingsDerived,
            List<Binding> bindings,
            List<ResultRead> reads,
            String note) {
        public Consumer {
            bindings = List.copyOf(bindings);
            reads = List.copyOf(reads);
        }
    }

    /** {@code ps.setXxx(index, expression)}. {@code index} is -1 when it is not a constant. */
    public record Binding(int index, String setter, String expression, List<String> variables, SourceLocation location) {}

    /** {@code rs.getXxx(label | index)} with where the value goes next. */
    public record ResultRead(String label, int index, String getter, String expression, String flowsTo,
                             SourceLocation location) {}
}
