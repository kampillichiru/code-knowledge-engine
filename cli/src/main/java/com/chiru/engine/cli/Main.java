package com.chiru.engine.cli;

import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar code-intelligence-engine.jar analyze --source path/to/src --output analysis-output
 * java -jar code-intelligence-engine.jar docs  --model-dir analysis-output
 * java -jar code-intelligence-engine.jar trace --model-dir analysis-output --field total
 * </pre>
 *
 * Exit codes: 0 success, 1 a stage failed, 2 bad usage or input, 3 validation errors with {@code --strict}.
 */
@Command(name = "code-intelligence-engine", mixinStandardHelpOptions = true, version = "code-intelligence-engine 0.1.0",
        description = "Documents an unknown Java codebase and traces every output field back to its source, with evidence.",
        subcommands = {PipelineCommand.class, AnalyzeCommand.class, DocsCommand.class, TraceCommand.class, ValidateCommand.class})
public final class Main implements Runnable {

    public static final int OK = 0;
    public static final int STAGE_FAILED = 1;
    public static final int BAD_INPUT = 2;
    public static final int VALIDATION_ERRORS = 3;
    public static final int NEEDS_INPUT = 4;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static void main(String[] args) {
        System.exit(run(new PrintWriter(System.out, true), new PrintWriter(System.err, true), args));
    }

    /** Runs the CLI with explicit output streams (the tests capture them). Returns the exit code. */
    public static int run(PrintWriter out, PrintWriter err, String... args) {
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(out);
        cmd.setErr(err);
        cmd.setExecutionExceptionHandler((e, c, parseResult) -> {
            c.getErr().println("error: " + e.getMessage());
            return STAGE_FAILED;
        });
        int code = cmd.execute(args);
        out.flush();
        err.flush();
        return code;
    }
}
