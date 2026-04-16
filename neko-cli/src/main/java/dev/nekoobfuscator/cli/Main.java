package dev.nekoobfuscator.cli;

import picocli.CommandLine;

@CommandLine.Command(
    name = "neko",
    description = "NekoObfuscator - Advanced Java Bytecode Obfuscator",
    mixinStandardHelpOptions = true,
    version = "1.0.0-SNAPSHOT",
    subcommands = {
        ObfuscateCommand.class,
        InfoCommand.class
    }
)
public final class Main implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
