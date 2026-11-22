package com.chiru.engine.spoon;

import com.chiru.engine.model.EntityId;
import com.chiru.engine.model.EntityType;
import com.chiru.engine.model.SourceLocation;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.stream.Collectors;
import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.reference.CtTypeReference;

/** Identity and location helpers shared by every stage that reads the Spoon model, so IDs always agree. */
public final class SpoonIds {

    private SpoonIds() {}

    /** {@code method:<declaring type>#<name>(<param types>)}; constructors are named {@code <init>}. */
    public static EntityId methodId(CtTypeMember m) {
        return EntityId.of(EntityType.METHOD, methodKey(m));
    }

    public static String methodKey(CtTypeMember m) {
        String name = m instanceof CtMethod<?> method ? method.getSimpleName() : "<init>";
        String params = parameters(m).stream().map(p -> typeName(p.getType())).collect(Collectors.joining(","));
        return m.getDeclaringType().getQualifiedName() + "#" + name + "(" + params + ")";
    }

    /** {@code invocation:<method key>@line:col-end}: the ID the source stage gives a call site. */
    public static EntityId invocationId(CtAbstractInvocation<?> inv, CtTypeMember member) {
        SourcePosition pos = inv.getPosition();
        return EntityId.of(EntityType.INVOCATION,
                methodKey(member) + "@" + pos.getLine() + ":" + pos.getColumn() + "-" + pos.getSourceEnd());
    }

    public static List<CtParameter<?>> parameters(CtTypeMember m) {
        return m instanceof CtMethod<?> method ? method.getParameters() : ((CtConstructor<?>) m).getParameters();
    }

    public static String typeName(CtTypeReference<?> ref) {
        return ref == null ? "?" : ref.getQualifiedName();
    }

    /** The innermost method or constructor containing {@code e}, or null (field initialisers, static blocks). */
    public static CtTypeMember enclosingMember(CtElement e) {
        CtElement p = e;
        while (p != null) {
            if (p instanceof CtMethod<?> || p instanceof CtConstructor<?>) {
                return (CtTypeMember) p;
            }
            if (!p.isParentInitialized()) {
                return null;
            }
            p = p.getParent();
        }
        return null;
    }

    /** File (display path) and line range of {@code el}, or null when Spoon has no position for it. */
    public static SourceLocation locationOf(CtElement el, Map<Path, String> displayPaths) {
        SourcePosition p = el == null ? null : el.getPosition();
        if (p == null || !p.isValidPosition() || p.getFile() == null) {
            return null;
        }
        File file = p.getFile();
        String display = displayPaths.get(file.toPath().toAbsolutePath().normalize());
        if (display == null) {
            display = file.getName();
        }
        return new SourceLocation(display, p.getLine(), Math.max(p.getEndLine(), p.getLine()));
    }
}
