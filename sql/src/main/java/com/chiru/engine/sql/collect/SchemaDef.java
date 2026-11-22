package com.chiru.engine.sql.collect;

import com.chiru.engine.model.SourceLocation;
import java.util.List;

/**
 * A table known from schema evidence.
 *
 * @param source      "ddl" (CREATE TABLE) or "orm" (annotated entity class)
 * @param mappedClass for ORM: the entity class name, else null
 * @param inferred    true when a name was taken from a default rule (class or field name) rather than written out
 */
public record SchemaDef(String table, String source, String mappedClass, boolean inferred, SourceLocation location,
                        List<Col> columns) {

    public record Col(String name, String type, String specs, boolean inferred, SourceLocation location) {}
}
