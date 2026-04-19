package dev.nekoobfuscator.native_.codegen.emit;

import dev.nekoobfuscator.native_.codegen.CCodeGenerator.IcacheDirectStubRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.IcacheMetaRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.IcacheSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestFieldSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestInvokeSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestLdcSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.OwnerResolution;
import dev.nekoobfuscator.native_.codegen.SymbolTableGenerator;
import org.objectweb.asm.Type;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public final class CEmissionContext {
    /**
     * Reference returns and parameters use decoded raw oop pointers in the native ABI.
     */
    public static final String RAW_OOP_ABI_C_TYPE = "void*";

    private final SymbolTableGenerator symbols;
    private final LinkedHashMap<String, Integer> classSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> methodSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> fieldSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> ownerBindIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, OwnerResolution> ownerResolutions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> icacheMethodIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheSiteRef> icacheSites = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheDirectStubRef> icacheDirectStubs = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheMetaRef> icacheMetas = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> manifestMethodIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<ManifestFieldSiteRef>> manifestFieldSites = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<ManifestInvokeSiteRef>> manifestInvokeSites = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<ManifestLdcSiteRef>> manifestLdcSites = new LinkedHashMap<>();
    private final LinkedHashSet<String> manifestOwnerInternals = new LinkedHashSet<>();
    private int stringCacheCount;

    public CEmissionContext(long masterSeed) {
        this.symbols = new SymbolTableGenerator(masterSeed);
    }

    public SymbolTableGenerator symbols() {
        return symbols;
    }

    public LinkedHashMap<String, Integer> classSlotIndex() {
        return classSlotIndex;
    }

    public LinkedHashMap<String, Integer> methodSlotIndex() {
        return methodSlotIndex;
    }

    public LinkedHashMap<String, Integer> fieldSlotIndex() {
        return fieldSlotIndex;
    }

    public LinkedHashMap<String, Integer> ownerBindIndex() {
        return ownerBindIndex;
    }

    public LinkedHashMap<String, OwnerResolution> ownerResolutions() {
        return ownerResolutions;
    }

    public LinkedHashMap<String, Integer> icacheMethodIndex() {
        return icacheMethodIndex;
    }

    public LinkedHashMap<String, IcacheSiteRef> icacheSites() {
        return icacheSites;
    }

    public LinkedHashMap<String, IcacheDirectStubRef> icacheDirectStubs() {
        return icacheDirectStubs;
    }

    public LinkedHashMap<String, IcacheMetaRef> icacheMetas() {
        return icacheMetas;
    }

    public LinkedHashMap<String, Integer> manifestMethodIndex() {
        return manifestMethodIndex;
    }

    public LinkedHashMap<String, List<ManifestFieldSiteRef>> manifestFieldSites() {
        return manifestFieldSites;
    }

    public LinkedHashMap<String, List<ManifestInvokeSiteRef>> manifestInvokeSites() {
        return manifestInvokeSites;
    }

    public LinkedHashMap<String, List<ManifestLdcSiteRef>> manifestLdcSites() {
        return manifestLdcSites;
    }

    public LinkedHashSet<String> manifestOwnerInternals() {
        return manifestOwnerInternals;
    }

    public int stringCacheCount() {
        return stringCacheCount;
    }

    public void setStringCacheCount(int stringCacheCount) {
        this.stringCacheCount = stringCacheCount;
    }

    public String signatureKey(String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        StringBuilder key = new StringBuilder();
        key.append('(');
        for (Type argumentType : argumentTypes) {
            key.append(collapseKind(argumentType));
        }
        return key.append(')').append(returnKind(descriptor)).toString();
    }

    public char returnKind(String descriptor) {
        return collapseKind(Type.getReturnType(descriptor));
    }

    public String rawAbiType(Type type) {
        return rawAbiType(collapseKind(type));
    }

    public String rawAbiType(char kind) {
        return switch (kind) {
            case 'V' -> "void";
            case 'J' -> "int64_t";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> RAW_OOP_ABI_C_TYPE;
            default -> "int32_t";
        };
    }

    public char collapseKind(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> 'V';
            case Type.BOOLEAN -> 'Z';
            case Type.BYTE -> 'B';
            case Type.SHORT -> 'S';
            case Type.CHAR -> 'C';
            case Type.INT -> 'I';
            case Type.LONG -> 'J';
            case Type.FLOAT -> 'F';
            case Type.DOUBLE -> 'D';
            default -> 'L';
        };
    }
}
