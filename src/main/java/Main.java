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

    private static String markerFor(int index, int size) {
        if (index == size - 1) {
            return "+";
        } else if (index == size - 2) {
            return "-";
        } else {
            return " ";
        }
    }

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

    private static boolean isBuiltin(String command) {
        return command.equals("echo") || command.equals("exit") || command.equals("type")
                || command.equals("pwd") || command.equals("cd") || command.equals("jobs");
    }

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

    private static class PipelineHandle extends Process {
        private final List<Thread> threads;
        private final List<Process> processes;
        private final long fakePid;
        private static long nextFakePid = 1_000_000L; // out of range of real PIDs

        PipelineHandle(List<Thread> threads, List<Process> processes) {
            this.threads = threads;
            this.processes = processes;
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

    private static class CommandSpec {
        List<String> commandParts;
        String outFile;
        boolean appendOut;
        String errFile;
        boolean appendErr;
    }

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
            }
        }
    }

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

    private static void runMixedPipeline(List<CommandSpec> specs, String currentDir, boolean runInBackground, String originalInputForJobs) throws Exception {
        int n = specs.size();

        java.io.PipedOutputStream[] pipeOuts = new java.io.PipedOutputStream[n - 1];
        java.io.PipedInputStream[] pipeIns = new java.io.PipedInputStream[n - 1];
        for (int i = 0; i < n - 1; i++) {
            pipeOuts[i] = new java.io.PipedOutputStream();
            pipeIns[i] = new java.io.PipedInputStream(pipeOuts[i], 65536);
        }

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
            lastStageOut = System.out;
        }

        List<Thread> threads = new ArrayList<>();
        List<Process> externalProcesses = new ArrayList<>();
        String[] currentDirHolder = new String[] { currentDir };

        for (int idx = 0; idx < n; idx++) {
            CommandSpec spec = specs.get(idx);
            boolean isFirst = (idx == 0);
            boolean isLast = (idx == n - 1);
            String command = spec.commandParts.get(0);

            java.io.InputStream stageIn = isFirst ? null : pipeIns[idx - 1];
            java.io.OutputStream stageOut = isLast ? lastStageOut : pipeOuts[idx];

            if (isBuiltin(command)) {
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
                final boolean closeAfter = !isLast; 
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
                pb.redirectInput(isFirst ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
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

                if (!isFirst) {
                    final java.io.InputStream from = stageIn;
                    final java.io.OutputStream to = process.getOutputStream();
                    Thread pumpIn = new Thread(() -> pump(from, to));
                    threads.add(pumpIn);
                    pumpIn.start();
                }

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