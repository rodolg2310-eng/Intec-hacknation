import java.nio.file.*;
import java.util.*;

/** Ejecuta esta clase desde VS Code para preparar y arrancar toda la aplicación. */
public class Main {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("Main.ps1"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Ejecuta Main desde la carpeta Prototipo_hack.");
        Path modernShell=Path.of(System.getenv("ProgramFiles"),"PowerShell","7","pwsh.exe");
        String shell=Files.isRegularFile(modernShell)?modernShell.toString():"powershell.exe";
        List<String> command = new ArrayList<>(List.of(shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", root.resolve("Main.ps1").toString()));
        command.addAll(Arrays.asList(args));
        int code = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start().waitFor();
        if (code != 0) System.exit(code);
    }
}
