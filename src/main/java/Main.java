import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.ArrayList;
import java.util.List;

public class Main {
    private static final List<Job> jobList = new ArrayList<>();

    /**
     * Computes the next job number to assign. Job numbers are recycled,
     * not perpetually incremented: if the table is empty, start back at 1;
     * otherwise use one more than the highest job number currently present.
     * This must be computed fresh each time (not from a running counter),
     * since jobs are removed from the table as they're reaped.
     */
    private static int nextJobNumber() {
        int max = 0;
        for (Job job : jobList) {
            if (job.jobNumber > max) {
                max = job.jobNumber;
            }
        }
        return max + 1;
    }

    private static class Job {
        int jobNumber;
        long pid;
        String commandString;
        String baseCommandString;
        Process process;

        Job(int jobNumber, long pid, String commandString, String baseCommandString, Process process) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.commandString = commandString;
            this.baseCommandString = baseCommandString;
            this.process = process;
        }
    }

    /**
     * Returns the marker ('+', '-', or ' ') for the job at the given index,
     * based on a snapshot of jobList. The LAST job in insertion order is the
     * "current" job ('+'), the SECOND-TO-LAST is the "previous" job ('-').
     * Both reapJobs() and the jobs builtin call this so markers are always
     * computed the exact same way.
     */
    private static String markerFor(int index, int size) {
        if (index == size - 1) {
            return "+";
        } else if (index == size - 2) {
            return "-";
        } else {
            return " ";
        }
    }

    /**
     * Shared reaping logic. Checks every job in the table; for any whose
     * process has exited, prints a single "Done" line (marker computed from
     * the CURRENT snapshot of jobList, before removal) and queues it for
     * removal. Removal happens after the scan so markers for all jobs in
     * this pass are computed consistently. Called both before every prompt
     * and at the start of the jobs builtin, so a job's Done line is only
     * ever printed once, from whichever call sees it exit first.
     */
    private static void reapJobs() {
        int size = jobList.size();
        List<Job> toRemove = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            Job job = jobList.get(i);
            if (!job.process.isAlive()) {
                String marker = markerFor(i, size);
                String statusPadded = String.format("%-24s", "Done");
                System.out.println("[" + job.jobNumber + "]" + marker + "  " + statusPadded + job.baseCommandString);
                toRemove.add(job);
            }
        }

        if (!toRemove.isEmpty()) {
            jobList.removeAll(toRemove);
        }
    }

    /**
     * The jobs builtin. Takes ONE snapshot of the job table (fixed size),
     * computes each job's marker from that snapshot, and prints every job's
     * line (Done or Running) in its original table position, in a single
     * pass. This is important: if we reaped first and then recomputed
     * markers against the now-shrunk list, jobs that were never "+"/"-"
     * candidates could wrongly inherit a marker just because the list got
     * smaller. Removal of newly-completed jobs happens only after all
     * lines for this snapshot have been printed.
     */
    private static void printJobsList() {
        int size = jobList.size();
        List<Job> toRemove = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            Job job = jobList.get(i);
            String marker = markerFor(i, size);

            if (!job.process.isAlive()) {
                String statusPadded = String.format("%-24s", "Done");
                System.out.println("[" + job.jobNumber + "]" + marker + "  " + statusPadded + job.baseCommandString);
                toRemove.add(job);
            } else {
                String statusPadded = String.format("%-24s", "Running");
                System.out.println("[" + job.jobNumber + "]" + marker + "  " + statusPadded + job.commandString);
            }
        }

        if (!toRemove.isEmpty()) {
            jobList.removeAll(toRemove);
        }
    }

    /**
     * Holds one pipeline segment's command tokens plus any redirects that
     * were attached to that segment specifically.
     */
    private static class CommandSpec {
        List<String> commandParts;
        String outFile;
        boolean appendOut;
        String errFile;
        boolean appendErr;
    }

    /**
     * Same redirect-token scanning as the single-command path, but
     * extracted into a reusable form so each pipeline segment can be
     * parsed independently (e.g. "wc > out.txt" as the last stage of a
     * pipe still has its own output redirect honored).
     */
    private static CommandSpec parseRedirectsForSegment(List<String> tokens) {
        String outFile = null;
        String errFile = null;
        boolean appendOut = false;
        boolean appendErr = false;
        int firstRedirectIndex = -1;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < tokens.size()) {
                    outFile = tokens.get(i + 1);
                    appendOut = true;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                }
            } else if (token.equals("2>>")) {
                if (i + 1 < tokens.size()) {
                    errFile = tokens.get(i + 1);
                    appendErr = true;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                }
            } else if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < tokens.size()) {
                    outFile = tokens.get(i + 1);
                    appendOut = false;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < tokens.size()) {
                    errFile = tokens.get(i + 1);
                    appendErr = false;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                }
            } else if (token.startsWith("1>>")) {
                outFile = token.substring(3);
                appendOut = true;
                if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
            } else if (token.startsWith(">>")) {
                outFile = token.substring(2);
                appendOut = true;
                if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
            } else if (token.startsWith("2>>")) {
                errFile = token.substring(3);
                appendErr = true;
                if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
            } else if (token.startsWith("2>")) {
                errFile = token.substring(2);
                appendErr = false;
                if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
            } else if (token.startsWith("1>")) {
                outFile = token.substring(2);
                appendOut = false;
                if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
            } else if (token.startsWith(">")) {
                outFile = token.substring(1);
                appendOut = false;
                if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
            }
        }

        CommandSpec spec = new CommandSpec();
        spec.commandParts = (firstRedirectIndex != -1)
                ? new ArrayList<>(tokens.subList(0, firstRedirectIndex))
                : tokens;
        spec.outFile = outFile;
        spec.appendOut = appendOut;
        spec.errFile = errFile;
        spec.appendErr = appendErr;
        return spec;
    }

    /** Splits a token list on bare "|" tokens into pipeline segments. */
    private static List<List<String>> splitOnPipe(List<String> tokens) {
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals("|")) {
                segments.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        segments.add(current);
        return segments;
    }

    private static void prepareOutputFile(String path) throws Exception {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        if (!file.exists()) file.createNewFile();
    }

    private static void prepareErrorFile(String path, boolean append) throws Exception {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        if (append) {
            if (!file.exists()) file.createNewFile();
        } else {
            try (PrintWriter writer = new PrintWriter(new FileWriter(path))) {
                // truncate/create only
            }
        }
    }

    /**
     * Runs a pipeline of external commands ("cmd1 | cmd2 | ... | cmdN").
     * Uses ProcessBuilder.startPipeline so the OS wires real pipes between
     * each adjacent pair of processes (stdout of stage i -> stdin of stage
     * i+1), exactly like a shell pipeline. Only the first stage's stdin and
     * the last stage's stdout are configurable here (inherited from the
     * terminal, or redirected to a file if the last stage has one); every
     * stage's stderr is inherited unless that stage specifies its own
     * redirect.
     *
     * Foreground pipelines wait for EVERY stage to exit before returning to
     * the prompt -- this matters for cases like "tail -f file | head -n 5":
     * head exits after 5 lines, closing its end of the pipe, which causes
     * the upstream tail to receive SIGPIPE and exit shortly after. Waiting
     * on all processes (not just the last) ensures the whole pipeline has
     * actually finished before the next "$ " prompt is shown.
     */
    private static void runPipeline(List<String> tokens, String currentDir, boolean runInBackground, String originalInputForJobs) throws Exception {
        List<List<String>> segments = splitOnPipe(tokens);
        List<ProcessBuilder> builders = new ArrayList<>();

        for (int idx = 0; idx < segments.size(); idx++) {
            CommandSpec spec = parseRedirectsForSegment(segments.get(idx));

            if (spec.commandParts.isEmpty()) {
                System.out.println("syntax error near unexpected token `|'");
                return;
            }

            String command = spec.commandParts.get(0);
            String fullPath = getPath(command);
            if (fullPath == null) {
                System.out.println(command + ": command not found");
                return;
            }

            List<String> executeArgs = new ArrayList<>();
            executeArgs.add("/bin/bash");
            executeArgs.add("-c");
            executeArgs.add("exec -a \"$0\" \"$@\"");
            executeArgs.add(command);
            executeArgs.add(fullPath);
            for (int i = 1; i < spec.commandParts.size(); i++) {
                executeArgs.add(spec.commandParts.get(i));
            }

            ProcessBuilder pb = new ProcessBuilder(executeArgs);
            pb.directory(new File(currentDir));

            boolean isFirst = (idx == 0);
            boolean isLast = (idx == segments.size() - 1);

            // Only the first stage's stdin and last stage's stdout need
            // explicit handling here; startPipeline wires every adjacent
            // pair together automatically.
            if (isFirst) {
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }

            if (isLast) {
                if (spec.outFile != null) {
                    prepareOutputFile(spec.outFile);
                    pb.redirectOutput(spec.appendOut
                            ? ProcessBuilder.Redirect.appendTo(new File(spec.outFile))
                            : ProcessBuilder.Redirect.to(new File(spec.outFile)));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
            }

            if (spec.errFile != null) {
                prepareErrorFile(spec.errFile, spec.appendErr);
                pb.redirectError(spec.appendErr
                        ? ProcessBuilder.Redirect.appendTo(new File(spec.errFile))
                        : ProcessBuilder.Redirect.to(new File(spec.errFile)));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            builders.add(pb);
        }

        List<Process> processes = ProcessBuilder.startPipeline(builders);

        if (runInBackground) {
            // Track the pipeline as one job, represented by its last stage.
            Process lastProcess = processes.get(processes.size() - 1);
            int jobNumber = nextJobNumber();
            long pid = lastProcess.pid();
            System.out.println("[" + jobNumber + "] " + pid);

            String baseCommand = originalInputForJobs.trim();
            if (baseCommand.endsWith("&")) {
                baseCommand = baseCommand.substring(0, baseCommand.length() - 1).trim();
            }
            String commandForJob = baseCommand + " &";
            jobList.add(new Job(jobNumber, pid, commandForJob, baseCommand, lastProcess));
        } else {
            for (Process p : processes) {
                p.waitFor();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        String currentDir = System.getProperty("user.dir");

        while (true) {
            // Reap before every prompt: foreground command, builtin,
            // background launch, or even empty input all loop back here.
            reapJobs();

            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            List<String> parts = parseArguments(input);
            if (parts.isEmpty()) {
                continue;
            }

            String originalInputForJobs = input;

            boolean runInBackground = false;
            if (!parts.isEmpty()) {
                String lastToken = parts.get(parts.size() - 1);
                if (lastToken.equals("&")) {
                    runInBackground = true;
                    parts.remove(parts.size() - 1);
                } else if (lastToken.endsWith("&") && lastToken.length() > 1) {
                    runInBackground = true;
                    parts.set(parts.size() - 1, lastToken.substring(0, lastToken.length() - 1));
                }
            }

            if (parts.isEmpty()) {
                continue;
            }

            // Pipeline detection: if a bare "|" token is present, hand the
            // whole thing off to the pipeline handler and skip the normal
            // single-command path entirely (redirect parsing for each
            // segment happens inside runPipeline()).
            if (parts.contains("|")) {
                runPipeline(parts, currentDir, runInBackground, originalInputForJobs);
                continue;
            }

            String redirectFile = null;
            String stderrFile = null;
            boolean appendStdout = false;
            boolean appendStderr = false;
            int firstRedirectIndex = -1;

            for (int i = 0; i < parts.size(); i++) {
                String token = parts.get(i);

                if (token.equals(">>") || token.equals("1>>")) {
                    if (i + 1 < parts.size()) {
                        redirectFile = parts.get(i + 1);
                        appendStdout = true;
                        if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                    }
                } else if (token.equals("2>>")) {
                    if (i + 1 < parts.size()) {
                        stderrFile = parts.get(i + 1);
                        appendStderr = true;
                        if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                    }
                } else if (token.equals(">") || token.equals("1>")) {
                    if (i + 1 < parts.size()) {
                        redirectFile = parts.get(i + 1);
                        appendStdout = false;
                        if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                    }
                } else if (token.equals("2>")) {
                    if (i + 1 < parts.size()) {
                        stderrFile = parts.get(i + 1);
                        appendStderr = false;
                        if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                    }
                } else if (token.startsWith("1>>")) {
                    redirectFile = token.substring(3);
                    appendStdout = true;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                } else if (token.startsWith(">>")) {
                    redirectFile = token.substring(2);
                    appendStdout = true;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                } else if (token.startsWith("2>>")) {
                    stderrFile = token.substring(3);
                    appendStderr = true;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                } else if (token.startsWith("2>")) {
                    stderrFile = token.substring(2);
                    appendStderr = false;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                } else if (token.startsWith("1>")) {
                    redirectFile = token.substring(2);
                    appendStdout = false;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                } else if (token.startsWith(">")) {
                    redirectFile = token.substring(1);
                    appendStdout = false;
                    if (firstRedirectIndex == -1 || i < firstRedirectIndex) firstRedirectIndex = i;
                }
            }

            List<String> commandParts;
            if (firstRedirectIndex != -1) {
                commandParts = new ArrayList<>(parts.subList(0, firstRedirectIndex));
            } else {
                commandParts = parts;
            }

            if (redirectFile != null) {
                File file = new File(redirectFile);
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                if (!file.exists()) file.createNewFile();
            }
            if (stderrFile != null) {
                File file = new File(stderrFile);
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                if (appendStderr) {
                    if (!file.exists()) file.createNewFile();
                } else {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(stderrFile))) {
                        // truncate/create only
                    }
                }
            }

            if (commandParts.isEmpty()) {
                continue;
            }
            String command = commandParts.get(0);

            if (command.equals("exit")) {
                break;
            } else if (command.equals("pwd")) {
                if (redirectFile != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(redirectFile, appendStdout))) {
                        writer.println(currentDir);
                    }
                } else {
                    System.out.println(currentDir);
                }
            } else if (command.equals("cd")) {
                if (commandParts.size() > 1) {
                    String targetPath = commandParts.get(1);
                    String originalTarget = targetPath;

                    if (targetPath.equals("~")) {
                        String homeEnv = System.getenv("HOME");
                        if (homeEnv != null) targetPath = homeEnv;
                    }

                    try {
                        Path basePath = Paths.get(currentDir);
                        Path resolvedPath = basePath.resolve(targetPath).normalize();
                        File targetDir = resolvedPath.toFile();

                        if (targetDir.exists() && targetDir.isDirectory()) {
                            currentDir = resolvedPath.toString();
                        } else {
                            System.out.println("cd: " + originalTarget + ": No such file or directory");
                        }
                    } catch (Exception e) {
                        System.out.println("cd: " + originalTarget + ": No such file or directory");
                    }
                }
            } else if (command.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < commandParts.size(); i++) {
                    sb.append(commandParts.get(i));
                    if (i < commandParts.size() - 1) sb.append(" ");
                }

                if (redirectFile != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(redirectFile, appendStdout))) {
                        writer.println(sb.toString());
                    }
                } else {
                    System.out.println(sb.toString());
                }
            } else if (command.equals("type")) {
                if (commandParts.size() > 1) {
                    String arg = commandParts.get(1);
                    String output;
                    if (arg.equals("echo") || arg.equals("exit") || arg.equals("type") || arg.equals("pwd") || arg.equals("cd") || arg.equals("jobs")) {
                        output = arg + " is a shell builtin";
                    } else {
                        String fullPath = getPath(arg);
                        output = (fullPath != null) ? (arg + " is " + fullPath) : (arg + ": not found");
                    }

                    if (redirectFile != null) {
                        try (PrintWriter writer = new PrintWriter(new FileWriter(redirectFile, appendStdout))) {
                            writer.println(output);
                        }
                    } else {
                        System.out.println(output);
                    }
                }
            } else if (command.equals("jobs")) {
                printJobsList();
            } else {
                String fullPath = getPath(command);

                if (fullPath != null) {
                    List<String> executeArgs = new ArrayList<>();
                    executeArgs.add("/bin/bash");
                    executeArgs.add("-c");
                    executeArgs.add("exec -a \"$0\" \"$@\"");
                    executeArgs.add(command);
                    executeArgs.add(fullPath);

                    for (int i = 1; i < commandParts.size(); i++) {
                        executeArgs.add(commandParts.get(i));
                    }

                    ProcessBuilder pb = new ProcessBuilder(executeArgs);
                    pb.directory(new File(currentDir));

                    if (redirectFile != null) {
                        pb.redirectOutput(appendStdout
                                ? ProcessBuilder.Redirect.appendTo(new File(redirectFile))
                                : ProcessBuilder.Redirect.to(new File(redirectFile)));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (stderrFile != null) {
                        pb.redirectError(appendStderr
                                ? ProcessBuilder.Redirect.appendTo(new File(stderrFile))
                                : ProcessBuilder.Redirect.to(new File(stderrFile)));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                    Process process = pb.start();

                    if (runInBackground) {
                        int jobNumber = nextJobNumber();
                        long pid = process.pid();
                        System.out.println("[" + jobNumber + "] " + pid);

                        String baseCommand = originalInputForJobs.trim();
                        if (baseCommand.endsWith("&")) {
                            baseCommand = baseCommand.substring(0, baseCommand.length() - 1).trim();
                        }
                        String commandForJob = baseCommand + " &";
                        jobList.add(new Job(jobNumber, pid, commandForJob, baseCommand, process));
                    } else {
                        process.waitFor();
                    }
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    private static List<String> parseArguments(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean tokenStarted = false;
        boolean isEscaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (isEscaped) {
                currentArg.append(c);
                tokenStarted = true;
                isEscaped = false;
            } else if (c == '\\' && !inSingleQuotes) {
                if (inDoubleQuotes) {
                    if (i + 1 < input.length()) {
                        char nextChar = input.charAt(i + 1);
                        if (nextChar == '"' || nextChar == '\\' || nextChar == '$' || nextChar == '`') {
                            isEscaped = true;
                        } else {
                            currentArg.append(c);
                            tokenStarted = true;
                        }
                    } else {
                        currentArg.append(c);
                        tokenStarted = true;
                    }
                } else {
                    isEscaped = true;
                }
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                tokenStarted = true;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                tokenStarted = true;
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (tokenStarted) {
                    args.add(currentArg.toString());
                    currentArg.setLength(0);
                    tokenStarted = false;
                }
            } else {
                currentArg.append(c);
                tokenStarted = true;
            }
        }

        if (tokenStarted) {
            args.add(currentArg.toString());
        }

        return args;
    }

    private static String getPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] directories = pathEnv.split(File.pathSeparator);
            for (String dir : directories) {
                Path fullPath = Paths.get(dir, command);
                if (Files.isExecutable(fullPath)) {
                    return fullPath.toString();
                }
            }
        }
        return null;
    }
}