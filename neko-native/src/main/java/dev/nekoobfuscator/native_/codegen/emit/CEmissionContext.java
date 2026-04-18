package dev.nekoobfuscator.native_.codegen.emit;

import dev.nekoobfuscator.native_.codegen.CCodeGenerator.IcacheDirectStubRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.IcacheMetaRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.IcacheSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestFieldSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestInvokeSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestLdcSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.OwnerResolution;
import dev.nekoobfuscator.native_.codegen.SymbolTableGenerator;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public final class CEmissionContext {
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
}
