package dev.nekoobfuscator.cli;

import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
    name = "info",
    description = "Print information about a JAR file"
)
public final class InfoCommand implements Runnable {

    @CommandLine.Parameters(index = "0", description = "JAR file to inspect")
    private Path jarFile;

    @Override
    public void run() {
        try {
            JarInput input = new JarInput(jarFile);

            System.out.println("JAR: " + jarFile);
            System.out.println("Classes: " + input.classes().size());
            System.out.println("Resources: " + input.resources().size());
            System.out.println();

            int totalMethods = 0;
            int totalInstructions = 0;
            for (L1Class l1 : input.classes()) {
                int methods = l1.methods().size();
                int insns = 0;
                for (L1Method m : l1.methods()) {
                    insns += m.instructionCount();
                }
                totalMethods += methods;
                totalInstructions += insns;

                System.out.printf("  %-60s  JDK%-3d  %3d methods  %5d insns%n",
                    l1.name(), l1.javaVersion() - 44, methods, insns);
            }

            System.out.println();
            System.out.println("Total methods: " + totalMethods);
            System.out.println("Total instructions: " + totalInstructions);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
