package com.apprentice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApprenticeApplication {

  public static void main(String[] args) {
    loadProjectEnv();
    SpringApplication.run(ApprenticeApplication.class, args);
  }

  private static void loadProjectEnv() {
    java.nio.file.Path directory = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (directory != null) {
      java.nio.file.Path file = directory.resolve(".env");
      if (java.nio.file.Files.isRegularFile(file)) {
        try {
          for (String raw : java.nio.file.Files.readAllLines(file)) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String name = line.substring(0, equals).trim(), value = line.substring(equals + 1).trim();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
              value = value.substring(1, value.length() - 1);
            if (System.getenv(name) == null && System.getProperty(name) == null) System.setProperty(name, value);
          }
        } catch (java.io.IOException e) { throw new IllegalStateException("No se pudo cargar .env.", e); }
        return;
      }
      directory = directory.getParent();
    }
  }
}
