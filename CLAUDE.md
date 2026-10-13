# DroidNoise — player libpd para Android (brief para Claude Code)

Contexto para Claude Code. Guardalo como `CLAUDE.md` en la raíz del repo `~/droidnoise/` y arrancá con:
`claude "Lee CLAUDE.md y ejecuta la Fase 0 y la Fase 1."`

## Qué es

Instrumento gestual de ruido (para SÜDBÖS) sobre un **Redmi Note 13 Pro 4G** (HyperOS / Android 14) dañado:
- **Pantalla rota**: el aparato es headless. La UI es mínima y no se depende de ella.
- **USB-C sin datos, pero CARGA**: queda enchufado 24/7. Cero cable de datos.
- **Salida**: jack 3.5 mm → pedales → Korg monologue AUDIO IN (line level).
- **Entrada**: mic interno (fuente y feedback acústico).
- **Control**: sensores (acelerómetro, giroscopio, rotation vector, proximidad, luz, magnetómetro).

Arquitectura: **APK "player" tonta + todo el instrumento en un parche Pure Data**.
- La APK embebe libpd, abre audio (in = mic, out = jack), manda los sensores CRUDOS a Pd y carga `main.pd` desde una carpeta sincronizada por Syncthing. Recarga en caliente cuando el archivo cambia.
- DSP, mapeo, suavizado, umbrales, histéresis y detección de gestos: **todo en Pd**. Se itera sin recompilar.

Ethos: FOSS, offline, sin Google Play ni servicios de Google. Nada de analytics ni red en la app.

## Entorno (Arch Linux, ThinkPad)

- Build: **solo CLI**, sin Android Studio. Gradle wrapper + Android SDK commandline-tools.
- Deploy: **adb por WiFi** (depuración inalámbrica de Android 11+). Nunca por cable.
- Parches: Syncthing (ya en uso) → carpeta `/sdcard/DroidNoise/` en el celu.

## Fase 0 — Toolchain en Arch

Hacelo y verificá cada paso. Pedime `sudo` cuando haga falta; no asumas.

1. `sudo pacman -S --needed jdk17-openjdk android-tools unzip` (`android-tools` trae adb). Si ya hay otro JDK por defecto, usá `archlinux-java` o `JAVA_HOME` solo para este proyecto. AGP actual requiere JDK 17+.
2. SDK en `~/Android/Sdk` con las **commandlinetools** oficiales (zip de developer.android.com → `~/Android/Sdk/cmdline-tools/latest/`). Luego:
   `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"`
   y aceptá licencias (`sdkmanager --licenses`).
   **No hace falta NDK**: el AAR de libpd ya trae los `.so` compilados.
3. Exportá `ANDROID_HOME=~/Android/Sdk` (en `~/.bashrc` o `~/.zshrc`, preguntame cuál shell uso) y escribí `local.properties` con `sdk.dir`.
4. adb inalámbrico (esto lo hago yo en el celu, guiame):
   - Opciones de desarrollador → Depuración inalámbrica → "Vincular con código".
   - En el PC: `adb pair IP:PUERTO_PAIR` (código de 6 dígitos), luego `adb connect IP:PUERTO`.
   - Verificá con `adb devices`. Ojo: el puerto de conexión cambia entre reinicios del celu.

## Fase 1 — El player (`cl.droidnoise.player`)

### Stack
- Kotlin, Gradle Kotlin DSL, wrapper incluido.
- `minSdk 29`, `targetSdk 34`, `compileSdk 34`.
- libpd: `implementation("io.github.libpd.android:pd-core:1.2.1-rc6")` desde `mavenCentral()`. Antes de fijarla, verificá en Maven Central si hay una versión estable más nueva y usá esa.
- Sin AndroidX innecesario. Sin Compose. Una Activity con vistas simples basta.

### Componentes
1. **`AudioService`** — foreground service, dueño de libpd. Debe seguir sonando con pantalla apagada.
   - Android 14: `foregroundServiceType="microphone|mediaPlayback"` con sus permisos `FOREGROUND_SERVICE_MICROPHONE` y `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, más `RECORD_AUDIO` y `POST_NOTIFICATIONS`.
   - `PARTIAL_WAKE_LOCK` mientras corre.
   - Init: `AudioParameters.suggestSampleRate()` (esperable 48000), `PdAudio.initAudio(sr, 1, 2, ticksPerBuffer, true)`. Arrancá con ticksPerBuffer bajo (4–8) y medí; exponelo en un `config.txt` de la carpeta si es fácil.
   - Carga de libpd y del parche **fuera del hilo principal**.
   - Notificación persistente con acción "Recargar" y "Detener".
2. **Sensores → Pd** (en el service, `SensorManager`, `SENSOR_DELAY_GAME`). Mandar crudo, sin suavizar (el craft va en Pd). Receivers:

   | receiver | contenido |
   |---|---|
   | `acc` | lista x y z (m/s², `TYPE_LINEAR_ACCELERATION` si existe, si no `TYPE_ACCELEROMETER`) |
   | `grav` | lista x y z (`TYPE_GRAVITY`) |
   | `gyro` | lista x y z (rad/s) |
   | `ori` | lista alpha beta gamma en grados (de `TYPE_ROTATION_VECTOR` → `getRotationMatrixFromVector` → `getOrientation`) |
   | `prox` | float en cm (y `proxnear` 0/1 usando `maximumRange`) |
   | `light` | float lux |
   | `mag` | lista x y z (µT) |

   Usá `PdBase.sendList(receiver, ...)` / `sendFloat`. Throttle por receiver a ~200 Hz máx; no inundar.
   Al inicio mandá `info` con `sr` y la lista de sensores disponibles, y logueá a logcat cuáles faltan.
3. **Carga del parche**:
   - Carpeta: `/sdcard/DroidNoise/`, entrypoint `main.pd`. Abstracciones y tablas relativas a esa carpeta (`PdBase.addToSearchPath`).
   - Acceso: pedir **"Acceso a todos los archivos"** (`MANAGE_EXTERNAL_STORAGE`), aceptable porque es sideload personal. Syncthing escribe ahí sin pelear con scoped storage.
   - **Hot reload**: `FileObserver` sobre la carpeta, debounce de ~500 ms (Syncthing escribe en varios pasos con archivos temporales `.syncthing.*.tmp`; ignoralos). Al cambiar un `.pd`: `closePatch` + `openPatch`, sin reiniciar el audio.
   - Si `main.pd` no existe o falla: cargar un parche interno de fallback (assets) que suene un beep corto, para saber que el player vive.
4. **Puente Pd → Android** (mínimo): receiver `toapp` con `vibrate <ms>` y `log <msg>` (logcat tag `DroidNoise`). Útil para feedback háptico sin mirar la pantalla.
5. **Activity**: pantalla rota → botones GRANDES y de alto contraste: Iniciar/Detener, Recargar, estado (sr, parche cargado, último error). Pedir permisos ahí. Que el service arranque solo al abrir la app.
6. **Arranque**: opcional `BOOT_COMPLETED` → iniciar service (aparato 24/7). Dejalo detrás de un flag en `config.txt`.

### Build y deploy
- `./gradlew assembleDebug` → `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Script `deploy.sh`: build + install + `adb shell am start` + `adb logcat -s DroidNoise:* AndroidRuntime:E`.
- Script `push-patch.sh` como alternativa a Syncthing: `adb push pd/ /sdcard/DroidNoise/`.

### Verificación de Fase 1 (no la des por cerrada sin esto)
- Con `pd/test/main.pd`: el mic suena por el jack, el tilt cambia el filtro, tapar el sensor de proximidad corta el audio.
- Con pantalla apagada 10 minutos sigue sonando.
- Editar `main.pd` en el PC → Syncthing → recarga sola en < 2 s, sin glitch de audio largo.
- logcat limpio, sin xruns visibles en el log durante 1 minuto.

## Parche de prueba (`pd/test/main.pd`, Pd vanilla)

Escribilo a mano en formato `.pd` (texto) y validalo con `pd -nogui -send` o `pd -batch` si tengo Pd instalado (`pacman -S puredata`; preguntame).
- `[adc~]` → `[lop~]` con cutoff mapeado desde `ori` beta (−90..90 → 200..8000 Hz, curva exponencial, `[line~]` para suavizar) → `[*~]` gate → `[dac~ 1 2]`.
- Gate desde `proxnear` (cerca = mudo) con rampa de 20 ms.
- `[print]` de `info` al cargar.
- **Cuidado con el feedback**: arrancar con ganancia baja (0.3) y un `[clip~ -0.9 0.9]` antes del `dac~`.

## Fase 2 — El instrumento (solo después de cerrar Fase 1)

Todo en `pd/droidnoise/main.pd` + abstracciones, vanilla puro (libpd no trae externals):
- **Granular del mic**: buffer circular ~4 s (`tabwrite~` con puntero de escritura en loop o `delwrite~`/`vd~`), voces de grano con `[clone]`, ventana Hann por `tabread~`/`vline~`, densidad y pitch controlables.
- **Banco de resonadores**: `vcf~`/`bp~` en paralelo, frecuencias derivadas de la orientación.
- **Línea de feedback**: `delwrite~`/`delread4~` con saturación (`tanh` via `expr~` o aproximación polinómica) y control de cantidad.
- **Mapeo** (abstracción `map.pd` reutilizable: escala, curva, slew):

  | gesto | parámetro |
  |---|---|
  | beta (inclinar ↕) | densidad de granos + cutoff |
  | gamma (inclinar ↔) | pitch / playback rate |
  | sacudón (`acc` → magnitud → derivada = jerk, umbral + histéresis + refractario) | ráfaga de granos |
  | proximidad | gate |
  | luz | cantidad de feedback / cutoff |
  | alpha (brújula) | feedback / pan |

- Salida caliente pero sin clipear duro: limitador simple antes del `dac~`. La salida va a line in del monologue; el drive lo pone el monologue.

## Reglas de trabajo
- Respuestas en español chileno, directas.
- Verificá APIs de libpd contra el código fuente de `pd-for-android` (GitHub) antes de usarlas; no inventes firmas.
- Commits chicos por paso en git. Nada de dependencias que llamen a red.
- Si algo del celu no se puede verificar desde el PC (sonido real, sensores), decime exactamente qué probar y qué deberías ver en logcat.
