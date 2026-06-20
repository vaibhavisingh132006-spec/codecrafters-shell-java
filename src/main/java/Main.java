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
    private static int jobCounter = 0;
    private static final List<Job> jobList = new ArrayList<>();

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

    private static void printJobsList() {
        // Reap first so the listing never shows an already-exited job,
        // and so any newly-completed job is reported here exactly once
        // if this is the first place to notice it.
        reapJobs();

        int size = jobList.size();
        for (int i = 0; i < size; i++) {
            Job job = jobList.get(i);
            String marker = markerFor(i, size);
            String statusPadded = String.format("%-24s", "Running");
            System.out.println("[" + job.jobNumber + "]" + marker + "  " + statusPadded + job.commandString);
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
                        jobCounter++;
                        long pid = process.pid();
                        System.out.println("[" + jobCounter + "] " + pid);

                        String baseCommand = originalInputForJobs.trim();
                        if (baseCommand.endsWith("&")) {
                            baseCommand = baseCommand.substring(0, baseCommand.length() - 1).trim();
                        }
                        String commandForJob = baseCommand + " &";
                        jobList.add(new Job(jobCounter, pid, commandForJob, baseCommand, process));
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