package dev.nekoobfuscator.native_.codegen;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SymbolTableGenerator {
    private final byte[] xorKey;
    private final Map<String, Integer> indexes = new LinkedHashMap<>();
    private final List<String> values = new ArrayList<>();

    SymbolTableGenerator(long masterSeed) {
        this.xorKey = deriveKey(masterSeed);
    }

    int intern(String value) {
        Integer existing = indexes.get(value);
        if (existing != null) {
            return existing;
        }
        int index = values.size();
        values.add(value);
        indexes.put(value, index);
        return index;
    }

    String emitC() {
        List<Integer> offsets = new ArrayList<>(values.size());
        List<Integer> lengths = new ArrayList<>(values.size());
        List<Byte> blob = new ArrayList<>();
        int offset = 0;
        for (String value : values) {
            byte[] plain = value.getBytes(StandardCharsets.UTF_8);
            offsets.add(offset);
            lengths.add(plain.length);
            for (int i = 0; i < plain.length; i++) {
                blob.add((byte) (plain[i] ^ xorKey[i % xorKey.length]));
            }
            offset += plain.length;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#define NEKO_SYM_COUNT ").append(values.size()).append("\n");
        sb.append("static const uint8_t neko_sym_blob[] = {");
        for (int i = 0; i < blob.size(); i++) {
            if (i % 16 == 0) {
                sb.append("\n    ");
            }
            sb.append(String.format("0x%02X", blob.get(i) & 0xFF));
            if (i + 1 < blob.size()) {
                sb.append(", ");
            }
        }
        if (!blob.isEmpty()) {
            sb.append('\n');
        }
        sb.append("};\n");
        sb.append("static const uint32_t neko_sym_offsets[] = {");
        appendInts(sb, offsets);
        sb.append("};\n");
        sb.append("static const uint32_t neko_sym_lengths[] = {");
        appendInts(sb, lengths);
        sb.append("};\n");
        sb.append("static const uint8_t neko_sym_key[] = {");
        for (int i = 0; i < xorKey.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format("0x%02X", xorKey[i] & 0xFF));
        }
        sb.append("};\n");
        sb.append("static char* neko_sym_cache[NEKO_SYM_COUNT];\n");
        sb.append("static uint8_t neko_sym_ready[NEKO_SYM_COUNT];\n");
        sb.append("static const char* neko_sym(uint32_t idx) {\n");
        sb.append("    if (!neko_sym_ready[idx]) {\n");
        sb.append("        uint32_t len = neko_sym_lengths[idx];\n");
        sb.append("        uint32_t off = neko_sym_offsets[idx];\n");
        sb.append("        char* out = (char*)malloc((size_t)len + 1u);\n");
        sb.append("        if (out == NULL) {\n            return \"\";\n        }\n");
        sb.append("        for (uint32_t i = 0; i < len; i++) {\n");
        sb.append("            out[i] = (char)(neko_sym_blob[off + i] ^ neko_sym_key[i % sizeof(neko_sym_key)]);\n");
        sb.append("        }\n");
        sb.append("        out[len] = '\\0';\n");
        sb.append("        neko_sym_cache[idx] = out;\n");
        sb.append("        neko_sym_ready[idx] = 1;\n");
        sb.append("    }\n");
        sb.append("    return neko_sym_cache[idx];\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private void appendInts(StringBuilder sb, List<Integer> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
    }

    private byte[] deriveKey(long masterSeed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ByteBuffer.allocate(Long.BYTES).putLong(masterSeed).array());
            byte[] key = new byte[16];
            System.arraycopy(hash, 0, key, 0, key.length);
            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
