package apprentice;
import java.nio.file.*;
import java.util.*;
/** Entrada compatible: el flujo completo ahora vive en el navegador y la API. */
public class Main {
  public static void main(String[] args) throws Exception {
    Path root=Path.of("").toAbsolutePath();
    while(root!=null&&!Files.isRegularFile(root.resolve("Main.ps1")))root=root.getParent();
    if(root==null)throw new IllegalStateException("No se encontró Main.ps1 en Prototipo_hack.");
    Path modernShell=Path.of(System.getenv("ProgramFiles"),"PowerShell","7","pwsh.exe");
    String shell=Files.isRegularFile(modernShell)?modernShell.toString():"powershell.exe";
    List<String> command=new ArrayList<>(List.of(shell,"-NoProfile","-ExecutionPolicy","Bypass","-File",root.resolve("Main.ps1").toString()));
    for(String arg:args)command.add(arg.equals("--check")?"-CheckOnly":arg);
    int code=new ProcessBuilder(command).directory(root.toFile()).inheritIO().start().waitFor();
    if(code!=0)System.exit(code);
  }
}
