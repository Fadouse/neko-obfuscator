package dev.nekoobfuscator.native_.codegen.emit;

import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses each binding's descriptor into a small set of unique
 * (argument-shape × return-kind × static-or-instance) shapes so the C / asm
 * generators only emit one dispatcher + trampoline pair per shape.
 *
 * Shape kinds (single character):
 *   V = void return only
 *   I = byte/char/short/int/boolean (single 32-bit GP slot)
 *   J = long
 *   F = float
 *   D = double
 *   L = reference (object or array)
 */
public final class SignaturePlan {

    public static final class Shape {
        private final char returnKind;
        private final char[] argKinds;
        private final boolean isStatic;

        Shape(char returnKind, char[] argKinds, boolean isStatic) {
            this.returnKind = returnKind;
            this.argKinds = argKinds;
            this.isStatic = isStatic;
        }

        public char returnKind() { return returnKind; }
        public char[] argKinds() { return argKinds.clone(); }
        public int argCount() { return argKinds.length; }
        public boolean isStatic() { return isStatic; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Shape s)) return false;
            return returnKind == s.returnKind
                && isStatic == s.isStatic
                && Arrays.equals(argKinds, s.argKinds);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(returnKind, isStatic, Arrays.hashCode(argKinds));
        }
    }

    private final List<Shape> shapes;
    private final int[] bindingSignatureId;

    private SignaturePlan(List<Shape> shapes, int[] bindingSignatureId) {
        this.shapes = shapes;
        this.bindingSignatureId = bindingSignatureId;
    }

    public List<Shape> shapes() { return shapes; }
    public int signatureIdFor(int bindingIndex) { return bindingSignatureId[bindingIndex]; }

    public static SignaturePlan build(List<NativeMethodBinding> bindings) {
        List<Shape> shapes = new ArrayList<>();
        Map<Shape, Integer> indexByKey = new LinkedHashMap<>();
        int[] ids = new int[bindings.size()];
        for (int i = 0; i < bindings.size(); i++) {
            NativeMethodBinding b = bindings.get(i);
            Type[] args = Type.getArgumentTypes(b.descriptor());
            char[] argKinds = new char[args.length];
            for (int j = 0; j < args.length; j++) argKinds[j] = collapseKind(args[j]);
            Shape shape = new Shape(collapseKind(Type.getReturnType(b.descriptor())), argKinds, b.isStatic());
            Integer existing = indexByKey.get(shape);
            int id;
            if (existing == null) {
                id = shapes.size();
                shapes.add(shape);
                indexByKey.put(shape, id);
            } else {
                id = existing;
            }
            ids[i] = id;
        }
        return new SignaturePlan(List.copyOf(shapes), ids);
    }

    public static char collapseKind(Type t) {
        return switch (t.getSort()) {
            case Type.VOID -> 'V';
            case Type.LONG -> 'J';
            case Type.FLOAT -> 'F';
            case Type.DOUBLE -> 'D';
            case Type.OBJECT, Type.ARRAY -> 'L';
            default -> 'I';
        };
    }

    public static String cAbiType(char kind) {
        return switch (kind) {
            case 'V' -> "void";
            case 'J' -> "int64_t";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> "void*";
            default -> "int32_t";
        };
    }

    public static String jniArgType(char kind) {
        return switch (kind) {
            case 'J' -> "jlong";
            case 'F' -> "jfloat";
            case 'D' -> "jdouble";
            case 'L' -> "jobject";
            default -> "jint";
        };
    }
}
