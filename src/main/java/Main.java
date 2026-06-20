import java.io.File;
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
            String command = parts.get(0);
            
            if (command.equals("exit")) {
                break;
            } else if (command.equals("pwd")) {
                System.out.println(currentDir);
            } else if (command.equals("cd")) {
                if (parts.size() > 1) {
                    String targetPath = parts.get(1);
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
                for (int i = 1; i < parts.size(); i++) {
                    sb.append(parts.get(i));
                    if (i < parts.size() - 1) {
                        sb.append(" ");
                    }
                }
                System.out.println(sb.toString());
            } else if (command.equals("type")) {
                if (parts.size() > 1) {
                    String arg = parts.get(1);
                    if (arg.equals("echo") || arg.equals("exit") || arg.equals("type") || arg.equals("pwd") || arg.equals("cd")) {
                        System.out.println(arg + " is a shell builtin");
                    } else {
                        String fullPath = getPath(arg);
                        if (fullPath != null) {
                            System.out.println(arg + " is " + fullPath);
                        } else {
                            System.out.println(arg + ": not found");
                        }
                    }
                }
            } else {
                String fullPath = getPath(command);
                if (fullPath != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.directory(new File(currentDir));
                    pb.inheritIO();
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
            } else if (c == '\\'