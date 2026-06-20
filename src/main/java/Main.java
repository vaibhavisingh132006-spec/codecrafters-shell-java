import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
        printJobsList(System.out);
    }

    private static void printJobsList(java.io.PrintStream out) {
        int size = jobList.size();
        List<Job> toRemove = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            Job job = jobList.get(i);
            String marker = markerFor(i, size);

            if (!job.process.isAlive()) {
                String statusPadded = String.format("%-24s", "Done");
                out.println("[" + job.jobNumber + "]" + marker + "  " + statusPadded + job.baseCommandString);
                toRemove.add(job);
            } else {
                String statusPadded = String.format("%-24s", "Running");
                out.println("[" + job.jobNumber + "]" + marker + "  " + statusPadded + job.commandString);
            }
        }

        if (!toRemove.isEmpty()) {
            jobList.removeAll(toRemove);
        }
    }

    /** Names of all shell builtins, used to decide how a pipeline stage must run. */
    private static boolean isBuiltin(String command) {
        return command.equals("echo") || command.equals("exit") || command.equals("type")
                || command.equals("pwd") || command.equals("cd") || command.equals("jobs");
    }

    /**
     * Executes a single builtin command, writing its normal output to the
     * given PrintStream instead of always to System.out. This lets the same
     * builtin logic be reused both for the ordinary (non-pipeline) command
     * path and for a builtin acting as one stage of a pipeline, where its
     * output must instead flow into the next stage's input pipe.
     *
     * currentDir is passed by reference via a one-element array because
     * `cd` mutates it and Java cannot capture a mutable local otherwise.
     *
     * Returns true if `exit` was invoked (caller should terminate the shell
     * loop); the pipeline path never calls this with "exit" as a stage, but
     * the flag is threaded through for the single-command path's reuse too.
     */
    private static boolean runBuiltin(List<String> commandParts, String[] currentDirHolder, java.io.PrintStream out) {
        String command = commandParts.get(0);

        if (command.equals("exit")) {
            return true;
        } else if (command.equals("pwd")) {
            out.println(currentDirHolder[0]);
        } else if (command.equals("cd")) {
            if (commandParts.size() > 1) {
                String targetPath = commandParts.get(1);
                String originalTarget = targetPath;

                if (targetPath.equals("~")) {
                    String homeEnv = System.getenv("HOME");
                    if (homeEnv != null) targetPath = homeEnv;
                }

                try {
                    Path basePath = Paths.get(currentDirHolder[0]);
                    Path resolvedPath = basePath.resolve(targetPath).normalize();
                    File targetDir = resolvedPath.toFile();

                    if (targetDir.exists() && targetDir.isDirectory()) {
                        currentDirHolder[0] = resolvedPath.toString();
                    } else {
                        out.println("cd: " + originalTarget + ": No such file or directory");
                    }
                } catch (Exception e) {
                    out.println("cd: " + originalTarget + ": No such file or directory");
                }
            }
        } else if (command.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < commandParts.size(); i++) {
                sb.append(commandParts.get(i));
                if (i < commandParts.size() - 1) sb.append(" ");
            }
            out.println(sb.toString());
        } else if (command.equals("type")) {
            if (commandParts.size() > 1) {
                String arg = commandParts.get(1);
                String output;
                if (isBuiltin(arg)) {
                    output = arg + " is a shell builtin";
                } else {
                    String fullPath = getPath(arg);
                    output = (fullPath != null) ? (arg + " is " + fullPath) : (arg + ": not found");
                }
                out.println(output);
            }
        } else if (command.equals("jobs")) {
            printJobsList(out);
        }

        return false;
    }

    /**
     * Adapts a mixed pipeline (a mix of Threads for builtin stages and
     * Processes for external stages) into something that satisfies the
     * java.lang.Process contract, so it can be stored directly in the
     * existing Job.process field and works automatically with the
     * existing reapJobs() / printJobsList() logic (both of which only
     * ever call Process#isAlive()).
     *
     * Only the methods actually used elsewhere in this file (isAlive(),
     * pid(), waitFor()) are meaningfully implemented; the remaining
     * abstract Process methods are given minimal-but-correct
     * implementations since nothing else in this shell calls them.
     */
    private static class PipelineHandle extends Process {
        private final List<Thread> threads;
        private final List<Process> processes;
        private final long fakePid;
        private static long nextFakePid = 1_000_000L; // out of range of real PIDs

        PipelineHandle(List<Thread> threads, List<Process> processes) {
            this.threads = threads;
            this.processes = processes;
            // Prefer the last external process's real PID if there is one
            // (so `jobs`/reaping output shows a real PID when the final
            // stage happens to be external); otherwise synthesize one.
            long pid = -1;
            if (!processes.isEmpty()) {
                pid = processes.get(processes.size() - 1).pid();
            }
            this.fakePid = (pid != -1) ? pid : nextFakePid++;
        }

        @Override
        public long pid() {
            return fakePid;
        }

        /** Alive while any builtin-stage thread is still running or any external process hasn't exited. */
        @Override
        public boolean isAlive() {
            for (Thread t : threads) {
                if (t.isAlive()) return true;
            }
            for (Process p : processes) {
                if (p.isAlive()) return true;
            }
            return false;
        }

        @Override
        public int waitFor() throws InterruptedException {
            for (Thread t : threads) {
                t.join();
            }
            int last = 0;
            for (Process p : processes) {
                last = p.waitFor();
            }
            return last;
        }

        @Override
        public int exitValue() {
            if (isAlive()) {
                throw new IllegalThreadStateException("pipeline still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            for (Process p : processes) {
                p.destroy();
            }
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
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
     * Runs a pipeline ("cmd1 | cmd2 | ... | cmdN") where any stage may be
     * either an external command or a shell builtin (echo, type, pwd, cd,
     * jobs).
     *
     * Fast path: if every stage is external, this is unchanged from before
     * -- ProcessBuilder.startPipeline() lets the OS wire real pipes between
     * processes, which is simpler and cheaper than manual pumping.
     *
     * General path: if any stage is a builtin, we can't hand it to
     * ProcessBuilder.startPipeline (builtins aren't processes), so each
     * stage instead gets its own thread plus a real PipedInputStream /
     * PipedOutputStream pair connecting it to its neighbors:
     *   - External stages still run as real processes; their stdin/stdout
     *     are connected to the piped streams via Redirect.PIPE, with a
     *     pump thread copying bytes between the Process's stream and the
     *     pipeline's PipedInputStream/PipedOutputStream.
     *   - Builtin stages run their existing logic (factored into
     *     runBuiltin) directly against a PrintStream wrapping their
     *     PipedOutputStream destination. Builtins never read their stdin
     *     (matching real bash builtins like echo/type/pwd/cd/jobs), so a
     *     stage feeding a builtin just has its output drained and
     *     discarded -- exactly like piping into `true` in bash.
     *   - `cd` occurring inside a pipeline does NOT persist to the parent
     *     shell, matching default bash semantics (every pipeline stage --
     *     including the last, since this shell does not implement
     *     lastpipe -- runs in what is effectively its own subshell).
     */
    private static void runPipeline(List<String> tokens, String currentDir, boolean runInBackground, String originalInputForJobs) throws Exception {
        List<List<String>> segments = splitOnPipe(tokens);

        List<CommandSpec> specs = new ArrayList<>();
        boolean anyBuiltin = false;
        for (List<String> segment : segments) {
            CommandSpec spec = parseRedirectsForSegment(segment);
            if (spec.commandParts.isEmpty()) {
                System.out.println("syntax error near unexpected token `|'");
                return;
            }
            specs.add(spec);
            if (isBuiltin(spec.commandParts.get(0))) {
                anyBuiltin = true;
            }
        }

        if (!anyBuiltin) {
            runExternalPipeline(specs, currentDir, runInBackground, originalInputForJobs);
        } else {
            runMixedPipeline(specs, currentDir, runInBackground, originalInputForJobs);
        }
    }

    /** Original all-external fast path, extracted unchanged from before. */
    private static void runExternalPipeline(List<CommandSpec> specs, String currentDir, boolean runInBackground, String originalInputForJobs) throws Exception {
        List<ProcessBuilder> builders = new ArrayList<>();

        for (int idx = 0; idx < specs.size(); idx++) {
            CommandSpec spec = specs.get(idx);

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
            boolean isLast = (idx == specs.size() - 1);

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

    /**
     * General pipeline path used whenever at least one stage is a builtin.
     * Connects every adjacent pair of stages with a PipedOutputStream /
     * PipedInputStream pair and runs each stage on its own thread:
     *   - external stage: a real Process, with one pump thread copying its
     *     stdout to the pipeline's PipedOutputStream (or vice versa for the
     *     stage's stdin), since Process streams and Piped streams can't be
     *     wired together directly.
     *   - builtin stage: runs runBuiltin() directly, writing to a
     *     PrintStream over the pipeline's PipedOutputStream.
     *
     * The whole pipeline (all stages' threads + any external processes) is
     * waited on before returning, for a foreground pipeline. For a
     * background pipeline, the prompt loop is not blocked because every
     * stage already runs on its own thread/process started above; a
     * PipelineHandle (a Process subclass whose isAlive() polls every
     * underlying thread/process) is stored in the job table exactly like a
     * single Process would be for an all-external pipeline, so the
     * existing reaping code works unchanged.
     */
    private static void runMixedPipeline(List<CommandSpec> specs, String currentDir, boolean runInBackground, String originalInputForJobs) throws Exception {
        int n = specs.size();

        // outputDestinations[i] is where stage i's stdout should go:
        // a PipedOutputStream feeding stage i+1, or null for the last stage
        // (handled specially: file redirect or inherited System.out).
        java.io.PipedOutputStream[] pipeOuts = new java.io.PipedOutputStream[n - 1];
        java.io.PipedInputStream[] pipeIns = new java.io.PipedInputStream[n - 1];
        for (int i = 0; i < n - 1; i++) {
            pipeOuts[i] = new java.io.PipedOutputStream();
            pipeIns[i] = new java.io.PipedInputStream(pipeOuts[i], 65536);
        }

        // Resolve the final destination for the last stage's stdout, but
        // ONLY if the last stage is a builtin -- if the last stage is
        // external, its own ProcessBuilder redirect (set up inside the
        // loop below, same as the all-external fast path) is the sole
        // writer to the output file or terminal; opening a second handle
        // here would race with / duplicate that.
        final CommandSpec lastSpec = specs.get(n - 1);
        final boolean lastIsBuiltin = isBuiltin(lastSpec.commandParts.get(0));
        final java.io.OutputStream lastStageOut;
        if (lastIsBuiltin) {
            if (lastSpec.outFile != null) {
                prepareOutputFile(lastSpec.outFile);
                lastStageOut = new java.io.FileOutputStream(lastSpec.outFile, lastSpec.appendOut);
            } else {
                lastStageOut = System.out;
            }
        } else {
            // Not used by the external last-stage branch (it redirects
            // directly via ProcessBuilder), but stageOut below still needs
            // a non-null placeholder value of the right static type.
            lastStageOut = System.out;
        }

        List<Thread> threads = new ArrayList<>();
        List<Process> externalProcesses = new ArrayList<>();
        // currentDirHolder lets a `cd` builtin stage mutate a "directory"
        // value without affecting the real shell's currentDir (pipeline
        // stages run in an effective subshell, matching bash).
        String[] currentDirHolder = new String[] { currentDir };

        for (int idx = 0; idx < n; idx++) {
            CommandSpec spec = specs.get(idx);
            boolean isFirst = (idx == 0);
            boolean isLast = (idx == n - 1);
            String command = spec.commandParts.get(0);

            java.io.InputStream stageIn = isFirst ? null : pipeIns[idx - 1];
            java.io.OutputStream stageOut = isLast ? lastStageOut : pipeOuts[idx];

            if (isBuiltin(command)) {
                // Builtins never read stdin; if this stage isn't first, we
                // still must drain (and discard) the upstream pipe so the
                // previous stage never blocks writing into a full buffer.
                final java.io.InputStream drainSource = stageIn;
                if (drainSource != null) {
                    Thread drainer = new Thread(() -> {
                        try {
                            byte[] buf = new byte[8192];
                            while (drainSource.read(buf) != -1) {
                                // discard
                            }
                        } catch (IOException ignored) {
                        } finally {
                            try { drainSource.close(); } catch (IOException ignored) {}
                        }
                    });
                    threads.add(drainer);
                    drainer.start();
                }

                final java.io.OutputStream finalStageOut = stageOut;
                final boolean closeAfter = !isLast; // don't close System.out / the file early via this path's logic
                Thread builtinThread = new Thread(() -> {
                    java.io.PrintStream ps = new java.io.PrintStream(finalStageOut, true);
                    try {
                        runBuiltin(spec.commandParts, currentDirHolder, ps);
                    } finally {
                        ps.flush();
                        if (closeAfter) {
                            try { finalStageOut.close(); } catch (IOException ignored) {}
                        }
                    }
                });
                threads.add(builtinThread);
                builtinThread.start();

            } else {
                String fullPath = getPath(command);
                if (fullPath == null) {
                    System.out.println(command + ": command not found");
                    // Best-effort cleanup: close any pipes already opened so
                    // upstream/downstream threads don't hang forever.
                    closeQuietly(pipeOuts);
                    closeQuietly(pipeIns);
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
                pb.directory(new File(currentDirHolder[0]));

                // stdin: inherit from terminal if first stage, else PIPE
                // (a pump thread will copy bytes in from the previous stage).
                pb.redirectInput(isFirst ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);

                // stdout: if last stage, go straight to file or inherit;
                // otherwise PIPE (a pump thread copies bytes out to the next stage).
                if (isLast) {
                    if (spec.outFile != null) {
                        pb.redirectOutput(spec.appendOut
                                ? ProcessBuilder.Redirect.appendTo(new File(spec.outFile))
                                : ProcessBuilder.Redirect.to(new File(spec.outFile)));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }

                if (spec.errFile != null) {
                    prepareErrorFile(spec.errFile, spec.appendErr);
                    pb.redirectError(spec.appendErr
                            ? ProcessBuilder.Redirect.appendTo(new File(spec.errFile))
                            : ProcessBuilder.Redirect.to(new File(spec.errFile)));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process process = pb.start();
                externalProcesses.add(process);

                // Pump thread: previous stage's PipedInputStream -> this
                // process's stdin (only needed when not the first stage,
                // since the first stage's stdin is inherited directly).
                if (!isFirst) {
                    final java.io.InputStream from = stageIn;
                    final java.io.OutputStream to = process.getOutputStream();
                    Thread pumpIn = new Thread(() -> pump(from, to));
                    threads.add(pumpIn);
                    pumpIn.start();
                }

                // Pump thread: this process's stdout -> next stage's
                // PipedOutputStream (only needed when not the last stage).
                if (!isLast) {
                    final java.io.InputStream from = process.getInputStream();
                    final java.io.OutputStream to = stageOut;
                    Thread pumpOut = new Thread(() -> pump(from, to));
                    threads.add(pumpOut);
                    pumpOut.start();
                }
            }
        }

        if (runInBackground) {
            // Track the whole pipeline as one background job. There may be
            // no single Process to represent it (e.g. the last stage is a
            // builtin running on a plain Thread rather than a Process), so
            // we wrap every stage's Thread/Process in a PipelineHandle,
            // which implements Process#isAlive() by polling all of them
            // directly (non-blocking) -- no extra supervisor thread is
            // needed since isAlive() never blocks.
            PipelineHandle handle = new PipelineHandle(threads, externalProcesses);
            int jobNumber = nextJobNumber();
            System.out.println("[" + jobNumber + "] " + handle.pid());

            String baseCommand = originalInputForJobs.trim();
            if (baseCommand.endsWith("&")) {
                baseCommand = baseCommand.substring(0, baseCommand.length() - 1).trim();
            }
            String commandForJob = baseCommand + " &";
            jobList.add(new Job(jobNumber, handle.pid(), commandForJob, baseCommand, handle));
        } else {
            for (Thread t : threads) {
                t.join();
            }
            for (Process p : externalProcesses) {
                p.waitFor();
            }
            if (lastStageOut != System.out) {
                lastStageOut.close();
            } else {
                System.out.flush();
            }
        }
    }

    /** Copies all bytes from one stream to another, then closes both. Run on its own thread. */
    private static void pump(java.io.InputStream from, java.io.OutputStream to) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = from.read(buf)) != -1) {
                to.write(buf, 0, n);
                to.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { to.close(); } catch (IOException ignored) {}
            try { from.close(); } catch (IOException ignored) {}
        }
    }

    private static void closeQuietly(java.io.PipedOutputStream[] streams) {
        for (java.io.PipedOutputStream s : streams) {
            if (s != null) try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static void closeQuietly(java.io.PipedInputStream[] streams) {
        for (java.io.PipedInputStream s : streams) {
            if (s != null) try { s.close(); } catch (IOException ignored) {}
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