package dev.nekoobfuscator.config;

import java.util.regex.Pattern;

/**
 * Matches class/method names against glob-style patterns.
 * Supports: ** (any package depth), * (single segment), ? (single char)
 */
public final class PatternMatcher {
    private PatternMatcher() {}

    /**
     * Check if an internal class name matches a pattern.
     * Pattern uses dot notation (com.example.**), class name uses slash notation (com/example/Foo).
     */
    public static boolean matchClass(String pattern, String internalClassName) {
        String className = internalClassName.replace('/', '.');
        return matchGlob(pattern, className);
    }

    /**
     * Check if a method matches a pattern like "com.example.Foo.bar(int, String)".
     */
    public static boolean matchMethod(String pattern, String className, String methodName, String methodDesc) {
        // Simple case: just class pattern, applies to all methods
        if (!pattern.contains("(")) {
            return matchClass(pattern, className);
        }

        // Extract class part and method part
        int lastDot = pattern.lastIndexOf('.', pattern.indexOf('('));
        if (lastDot < 0) return false;

        String classPattern = pattern.substring(0, lastDot);
        String methodPattern = pattern.substring(lastDot + 1);

        if (!matchClass(classPattern, className)) return false;

        // Match method name (ignoring descriptor for now - simplified)
        String methodNamePattern = methodPattern.substring(0, methodPattern.indexOf('('));
        return methodNamePattern.equals("*") || methodNamePattern.equals(methodName);
    }

    /**
     * Glob-style pattern matching.
     */
    public static boolean matchGlob(String pattern, String text) {
        String regex = globToRegex(pattern);
        return Pattern.matches(regex, text);
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append('^');
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i += 2;
                    if (i < glob.length() && glob.charAt(i) == '.') {
                        i++; // skip the dot after **
                        sb.append("(?:.*\\.)?");
                    }
                    continue;
                } else {
                    sb.append("[^.]*");
                }
            } else if (c == '?') {
                sb.append("[^.]");
            } else if (c == '.') {
                sb.append("\\.");
            } else if ("()[]{}+^$|\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
            i++;
        }
        sb.append('$');
        return sb.toString();
    }
}
