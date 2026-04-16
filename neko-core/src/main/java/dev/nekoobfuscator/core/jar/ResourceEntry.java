package dev.nekoobfuscator.core.jar;

/**
 * Represents a non-class resource entry in a JAR file.
 */
public final class ResourceEntry {
    private final String name;
    private byte[] data;

    public ResourceEntry(String name, byte[] data) {
        this.name = name;
        this.data = data;
    }

    public String name() { return name; }
    public byte[] data() { return data; }
    public void setData(byte[] data) { this.data = data; }
    public int size() { return data.length; }

    @Override
    public String toString() {
        return "ResourceEntry{" + name + ", " + data.length + " bytes}";
    }
}
