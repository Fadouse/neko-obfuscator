package dev.nekoobfuscator.cli;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.config.ConfigParser;
import dev.nekoobfuscator.config.ConfigValidator;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.advanced.AdvancedJvmPass;
import dev.nekoobfuscator.transforms.data.*;
import dev.nekoobfuscator.transforms.flow.*;
import dev.nekoobfuscator.transforms.invoke.InvokeDynamicPass;
import dev.nekoobfuscator.transforms.structure.*;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(
    name = "obfuscate",
    description = "Obfuscate a JAR file"
)
public final class ObfuscateCommand implements Runnable {

    @CommandLine.Option(names = {"-c", "--config"}, description = "Config YAML file", required = true)
    private Path configFile;

    @CommandLine.Option(names = {"-i", "--input"}, description = "Input JAR (overrides config)")
    private Path inputJar;

    @CommandLine.Option(names = {"-o", "--output"}, description = "Output JAR (overrides config)")
    private Path outputJar;

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Verbose logging")
    private boolean verbose;

    @Override
    public void run() {
        try {
            System.out.println("[NekoObfuscator] Starting...");
            long startTime = System.currentTimeMillis();

            // Parse config
            ConfigParser parser = new ConfigParser();
            ObfuscationConfig config = parser.parse(configFile);

            // Override from CLI args
            if (inputJar != null) config.setInputJar(inputJar);
            if (outputJar != null) config.setOutputJar(outputJar);

            // Validate
            List<String> errors = ConfigValidator.validate(config);
            if (!errors.isEmpty()) {
                System.err.println("[NekoObfuscator] Configuration errors:");
                errors.forEach(e -> System.err.println("  - " + e));
                return;
            }

            // Register all transform passes
            PassRegistry registry = new PassRegistry();
            registry.register(new ControlFlowFlatteningPass());
            registry.register(new ExceptionObfuscationPass());
            registry.register(new ExceptionReturnPass());
            registry.register(new ControlFlowObfuscationPass());
            registry.register(new NumberEncryptionPass());
            registry.register(new InvokeDynamicPass());
            registry.register(new StringEncryptionPass());
            registry.register(new OutlinerPass());
            registry.register(new StackObfuscationPass());
            registry.register(new AdvancedJvmPass());

            System.out.println("[NekoObfuscator] Registered " + registry.size() + " transform passes");

            // Run pipeline
            ObfuscationPipeline pipeline = new ObfuscationPipeline(config, registry);
            pipeline.execute(config.inputJar(), config.outputJar());

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("[NekoObfuscator] Done in " + elapsed + "ms");

        } catch (Exception e) {
            System.err.println("[NekoObfuscator] Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
        }
    }
}
