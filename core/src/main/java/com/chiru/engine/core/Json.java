package com.chiru.engine.core;

import com.chiru.engine.model.EntityId;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;

/** Shared, strict JSON mapper. Unknown properties fail so schema drift is noticed. */
public final class Json {

    private static final ObjectMapper MAPPER = build();

    private Json() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static ObjectMapper build() {
        SimpleModule module = new SimpleModule("engine-ids");
        module.addSerializer(EntityId.class, new JsonSerializer<>() {
            @Override
            public void serialize(EntityId id, JsonGenerator gen, SerializerProvider p) throws IOException {
                gen.writeString(id.value());
            }
        });
        module.addDeserializer(EntityId.class, new JsonDeserializer<>() {
            @Override
            public EntityId deserialize(JsonParser parser, DeserializationContext ctx) throws IOException {
                return new EntityId(parser.getValueAsString());
            }
        });
        return new ObjectMapper()
                .registerModule(module)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }
}
