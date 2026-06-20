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
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        String currentDir = System.getProperty("user.dir");
        
        while (true) {
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
            
            String redirectFile = null;
int redirectIndex = -1;

for (int i = 0; i < parts.size(); i++) {

    String token = parts.get(i);

    if (token.equals(">") || token.equals("1>")) {

        if (i + 1 < parts.size()) {

            redirectFile = parts.get(i + 1);
            redirectIndex = i;
            break;
        }

    } else if (token.startsWith("1>")) {

        redirectFile = token.substring(2);
        redirectIndex = i;
        break;

    } else if (token.startsWith(">")) {

        redirectFile = token.substring(1);
        redirectIndex = i;
        break;
    }
}

List<String> commandParts;

if (redirectIndex != -1) {

    commandParts =
            new ArrayList<>(parts.subList(0, redirectIndex));

    File file = new File(redirectFile);

    File parent = file.getParentFile();

    if (parent != null) {
        parent.mkdirs();
    }

} else {

    commandParts = parts;
}
            
            if (commandParts.isEmpty()) {
                continue;
            }
            String command = commandParts.get(0);
            
            if (command.equals("exit")) {
                break;
            } else if (command.equals("pwd")) {
                if (redirectFile != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(redirectFile))) {
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
                        if (homeEnv != null) {
                            targetPath = homeEnv;
                        }
                    }
                    
                    try {
                        Path basePat = Paths.get(currentDir);
                        Path resolvedPath = basePat.resolve(targetPath).normalize();
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
                    if (i < commandParts.size() - 1) {
                        sb.append(" ");
                    }
                }
                
                if (redirectFile != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(redirectFile))) {
                        writer.println(sb.toString());
                    }
                } else {
                    System.out.println(sb.toString());
                }
            } else if (command.equals("type")) {
                if (commandParts.size() > 1) {
                    String arg = commandParts.get(1);
                    String output;
                    if (arg.equals("echo") || arg.equals("exit") || arg.equals("type") || arg.equals("pwd") || arg.equals("cd")) {
                        output = arg + " is a shell builtin";
                    } else {
                        String fullPath = getPath(arg);
                        if (fullPath != null) {
                            output = arg + " is " + fullPath;
                        } else {
                            output = arg + ": not found";
                        }
                    }
                    
                    if (redirectFile != null) {
                        try (PrintWriter writer = new PrintWriter(new FileWriter(redirectFile))) {
                            writer.println(output);
                        }
                    } else {
                        System.out.println(output);
                    }
                }
            } } else {

    String fullPath = getPath(command);

    if (fullPath != null) {

        List<String> executeArgs = new ArrayList<>();

        executeArgs.add(fullPath);

        for (int i = 1; i < commandParts.size(); i++) {
            executeArgs.add(commandParts.get(i));
        }

        ProcessBuilder pb = new ProcessBuilder(executeArgs);

        pb.directory(new File(currentDir));

        // Preserve argv[0]
        pb.environment().put("ARGV0", command);

        if (redirectFile != null) {

            pb.redirectOutput(new File(redirectFile));

            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        } else {

            pb.inheritIO();
        }

        Process process = pb.start();

        process.waitFor();

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