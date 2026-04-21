# LDC String Native Infrastructure Map (dev HEAD c603da7)

Source: explore agent bg_6d6d884f, session ses_251d3ddc5ffezMtY009Ias5tIV

## Critical finding: `__nekoStringRoots` revert INCOMPLETE

TODO.md claims commit d3ce944 fully reverted `__nekoStringRoots`. False.
Still 2 code references:
- `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/BootstrapEmitter.java:1413` — `neko_resolve_field_offset(...klass_neko_native_loader, "__nekoStringRoots", 17u, "[Ljava/lang/Object;", ...)`
- `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/BootstrapEmitter.java:1416` — trace string

Resolver precondition `off_loader_string_roots >= 0` fails → all STRING LDC returns null.

## 4 replacement-point map for pure-native GC root redesign

| # | File | Lines | Current behavior |
|---|---|---|---|
| 1 | `neko-native/.../codegen/CCodeGenerator.java` | 430 | `int32_t off_loader_string_roots;` field in NekoVmLayout |
| 2 | `neko-native/.../codegen/emit/BootstrapEmitter.java` | 1403-1451 | `neko_resolve_string_intern_layout()` + `neko_string_intern_prewarm_and_publish()` — currently writes `root_array` into Java mirror field |
| 3 | `neko-native/.../codegen/emit/Wave2FieldLdcEmitter.java` | 555-581 | resolver intern-write path: `neko_store_heap_oop_at_unpublished(g_neko_string_roots_array, elem_off, string_oop)` |
| 4 | `neko-native/.../codegen/emit/Wave2FieldLdcEmitter.java` | 721-727 | resolver read path: `neko_load_heap_oop_from_published(loader_mirror, off_loader_string_roots)` |

## Intern table data structure

```c
// ManifestEmitter.java:295-305
typedef struct NekoStringInternEntry {
    uint32_t coder;
    uint32_t char_length;
    uint32_t payload_length;
    uint32_t slot_index;
    const uint8_t* payload;
    struct NekoStringInternEntry* next;
} NekoStringInternEntry;

static NekoStringInternEntry* g_neko_string_intern_buckets[NEKO_STRING_INTERN_BUCKET_COUNT];
static NekoStringInternEntry g_neko_string_intern_entries[NEKO_STRING_INTERN_SLOT_COUNT];
static uint32_t g_neko_string_intern_filled;
static void* g_neko_string_roots_array = NULL;   // BootstrapEmitter.java:69 — native-side root container pointer
```

## Resolver call chain

- `OpcodeTranslator.java:279-281` emits for LDC String: `jobject __ldc = neko_ldc_string_site_oop(env, <siteExpr>); PUSH_O(neko_handle_oop(__ldc));`
- `Wave2FieldLdcEmitter.java:710-728` — `neko_ldc_string_site_oop(env, site)` reads from Java mirror field
- `Wave2FieldLdcEmitter.java:422-585` — `neko_resolve_ldc_string(site)` constructs String raw-oop, interns, writes to root_array
- `Wave2FieldLdcEmitter.java:406-420` — `neko_string_intern_hash(...)` — bucket hash
- `JniOnLoadEmitter.java:49` — bootstrap calls `neko_string_intern_prewarm_and_publish(env)`

## NekoVmLayout extensions landed

```
// CCodeGenerator.java:423-430
size_t constant_pool_size;
int32_t off_constant_pool_tags;
int32_t off_constant_pool_length;
int32_t off_symbol_length;
int32_t off_symbol_body;
int32_t off_instance_klass_fieldinfo_stream;
int32_t off_string_hash;
int32_t off_loader_string_roots;      // <-- REMOVE, no Java field backs this
```

VMStructs mappings resolved in `BootstrapEmitter.java:970-1029`:
- `ConstantPool::_pool_holder/_tags/_length`
- `Symbol::_length/_body`
- `InstanceKlass::_constants/_fields/_java_fields_count/_init_state/_java_mirror/_static_field_size/_static_oop_field_count/_fieldinfo_stream`

## Manifest site struct

```c
// ManifestEmitter.java:151-162
typedef struct NekoManifestLdcSite {
    uint32_t site_id;
    uint32_t owner_class_index;
    uint32_t kind;
    uint32_t reserved0;
    uint32_t reserved1;
    const uint8_t* raw_constant_utf8;
    size_t raw_constant_utf8_len;
    jclass *owner_class_slot;
    void* cached_klass;
    void* resolved_cache_handle;   // points to NekoStringInternEntry for STRING sites
} NekoManifestLdcSite;
```

## SafetyChecker admission

`NativeTranslationSafetyChecker.java:321-339` — `unsupportedLdcReason()`:
- Integer/Float/Long/Double/String → admitted (returns null)
- Type (class/array) → admitted
- Type (method-type) → deferred string (reject)
- Handle → reject

## Next steps

When Oracle-1 returns, design will target replacing 4 sites above. Deep worker cleans up:
1. `CCodeGenerator.java:430` — drop `off_loader_string_roots` field
2. `BootstrapEmitter.java:1403-1418` — drop `neko_resolve_string_intern_layout()` that probes `__nekoStringRoots`
3. `BootstrapEmitter.java:1420-1452` — rewrite `neko_string_intern_prewarm_and_publish()` with pure-native GC root
4. `Wave2FieldLdcEmitter.java:555-581,721-727` — resolver read/write path using new GC root scheme
