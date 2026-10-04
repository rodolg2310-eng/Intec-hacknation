# Traina / Web

React 19, TanStack Start, TypeScript y Tailwind. La interfaz ofrece cuentas reales, espacios aislados, captura visual con privacidad, conversación con Claude, revisión del Work Map y práctica validada antes de guardar.

Para iniciar la aplicación completa, ejecuta `Main.cmd` desde la raíz del repositorio. El README de la raíz describe el entorno, las APIs y las verificaciones. Esta carpeta mantiene la integración original con Lovable.

## Desarrollo

Con la API Java activa en `127.0.0.1:8080`:

```sh
npm ci
npm run dev
```

Vite abre el puerto 3000 y redirige `/api` al backend. El servidor de producción usa `API_ORIGIN`; nunca envía las claves de proveedores al cliente.

```sh
npm exec -- tsc --noEmit
npm run lint
npm test
npm run build
npm start
```

Solo se guarda el tema visual en localStorage. Las cuentas, capturas, conversaciones, mapas y progreso se guardan mediante HTTP/JSON en el backend.
