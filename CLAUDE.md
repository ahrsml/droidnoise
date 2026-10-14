# DroidNoise: player libpd para Android (notas de trabajo)

Contexto para Claude Code en la raíz del repo `~/droidnoise/`.

## Qué es

Instrumento gestual de ruido (para SÜDBÖS) sobre un **Redmi Note 13 Pro 4G** (`emerald`, HyperOS 3 / **Android 16**, SDK 36, arm64, páginas 4 KB; audio 48 kHz, HAL 1024 frames) dañado:
- **Pantalla rota**: el aparato es headless. La UI es mínima y no se depende de ella.
- **USB-C sin datos, pero CARGA**: queda enchufado 24/7. Cero cable de datos.
- **Sin cuenta Mi ni Google** en el celu.
- **Salida**: jack 3.5 mm → pedales → Korg monologue AUDIO IN (line level).
- **Entrada**: mic interno (fuente y feedback acústico).
- **Control**: sensores (acelerómetro, giroscopio, rotation vector, proximidad, luz, magnetómetro).

Arquitectura: **APK "player" tonta + todo el instrumento en un parche Pure Data**.
- La APK embebe libpd, abre audio (in = mic, out = jack), manda los sensores CRUDOS a Pd y carga `main.pd` desde `/sdcard/DroidNoise/`. Recarga en caliente cuando el archivo cambia.
- DSP, mapeo, suavizado, umbrales, histéresis y detección de gestos: **todo en Pd**. Se itera sin recompilar.

Ethos: FOSS, offline, sin Google Play ni servicios de Google. Nada de analytics ni red en la app.

## Entorno (Arch Linux, ThinkPad)

- Build: **solo CLI**, sin Android Studio. Gradle wrapper + Android SDK commandline-tools.
- Shell del usuario: bash (`~/.bashrc`).
- **Todo viaja por adb sobre WiFi** (depuración inalámbrica de Android 11+): `adb push`, `logcat`, `am start`, `shell`. Nunca por cable. **No hay Syncthing**, ni en el PC ni en el celu.
- **Nada de `adb install` ni `pm install`**: HyperOS los bloquea sin cuenta Mi. El APK se sube con `adb push` y se instala con el instalador del sistema desde la propia app (ver abajo). La primera instalación se hace a mano con el gestor de archivos.

## Fase 0. Toolchain en Arch

Hazlo y verifica cada paso. Pídeme `sudo` cuando haga falta; no asumas.

1. `sudo pacman -S --needed jdk17-openjdk android-tools unzip` (`android-tools` trae adb). Si ya hay otro JDK por defecto, usa `archlinux-java` o `JAVA_HOME` solo para este proyecto. AGP 9.4 requiere JDK 17+.
2. SDK en `~/Android/Sdk` con las **commandlinetools** oficiales (zip de developer.android.com → `~/Android/Sdk/cmdline-tools/latest/`). Luego:
   `sdkmanager "platform-tools" "platforms;android-34" "build-tools;36.0.0"`
   y acepta licencias (`sdkmanager --licenses`). AGP 9.4 exige build-tools ≥ 36.0.0 y Gradle ≥ 9.6.
   **No hace falta NDK**: el AAR de libpd ya trae los `.so` compilados.
3. Exporta `ANDROID_HOME=~/Android/Sdk` en `~/.bashrc` y escribe `local.properties` con `sdk.dir`.
4. adb inalámbrico (esto lo hace el usuario en el celu, guíalo):
   - Opciones de desarrollador → Depuración inalámbrica → "Vincular con código".
   - En el PC: `./pair.sh CODIGO` (descubre IP y puertos por mDNS, vincula y conecta). Requiere el adb del SDK: el de Arch viene sin mDNS.
   - Verifica con `adb devices`. Ojo: el puerto de conexión cambia entre reinicios del celu.

## Fase 1. El player (`cl.droidnoise.player`)

### Stack
- Kotlin (Kotlin integrado de AGP 9), Gradle Kotlin DSL, wrapper incluido.
- `minSdk 29`, `targetSdk 34`, `compileSdk 34`.
- libpd: `implementation("io.github.libpd.android:pd-core:1.2.1-rc6")` desde `mavenCentral()` (verificado: es la última publicada).
- Sin AndroidX. Sin Compose. Una Activity con vistas simples basta.

### Notas verificadas de libpd 1.2.1-rc6
- En Android, `PdBaseLoader` carga `libpdnativeopensl` → `PdBase.implementsAudio()` es true y el audio va por OpenSL nativo.
- En ese modo `PdAudio.initAudio(sr, in, out, ticksPerBuffer, restart)` **ignora `ticksPerBuffer`**: `opensl_open` usa `libpd_blocksize()` (64). La latencia no se ajusta desde la app.
- Los mensajes Pd → Java (`print`, receivers suscritos) se despachan en el hilo principal (`PdAudio` hace `pollPdMessageQueue` cada 5 ms en el main looper).

### Componentes
1. **`AudioService`**: foreground service, dueño de libpd. Debe seguir sonando con pantalla apagada.
   - Android 14+: `foregroundServiceType="microphone|mediaPlayback"` con sus permisos `FOREGROUND_SERVICE_MICROPHONE` y `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, más `RECORD_AUDIO` y `POST_NOTIFICATIONS`.
   - `PARTIAL_WAKE_LOCK` mientras corre.
   - Init: `AudioParameters.init(ctx)`, `AudioParameters.suggestSampleRate()` (esperable 48000), `PdAudio.initAudio(sr, 1, 2, 8, true)`.
   - Carga de libpd y del parche **fuera del hilo principal**.
   - Notificación persistente con acción "Recargar" y "Detener".
2. **Sensores → Pd** (en el service, `SensorManager`, `SENSOR_DELAY_GAME`, en un `HandlerThread`). Mandar crudo, sin suavizar (el craft va en Pd). Receivers:

   | receiver | contenido |
   |---|---|
   | `acc` | lista x y z (m/s², `TYPE_LINEAR_ACCELERATION` si existe, si no `TYPE_ACCELEROMETER`) |
   | `grav` | lista x y z (`TYPE_GRAVITY`) |
   | `gyro` | lista x y z (rad/s) |
   | `ori` | lista alpha beta gamma en grados (de `TYPE_ROTATION_VECTOR` → `getRotationMatrixFromVector` → `getOrientation`) |
   | `prox` | float en cm (y `proxnear` 0/1 usando `maximumRange`) |
   | `light` | float lux |
   | `mag` | lista x y z (µT) |

   Usa `PdBase.sendList(receiver, ...)` / `sendFloat`. Throttle por receiver a ~200 Hz máx; no inundar.
   Al inicio (y tras cada recarga) manda `info` con `sr` y la lista de sensores disponibles, y loguea a logcat cuáles faltan.
3. **Carga del parche**:
   - Carpeta: `/sdcard/DroidNoise/`, entrypoint `main.pd`. Abstracciones y tablas relativas a esa carpeta (`PdBase.addToSearchPath`).
   - Acceso: pedir **"Acceso a todos los archivos"** (`MANAGE_EXTERNAL_STORAGE`), aceptable porque es sideload personal.
   - Los parches llegan con `./push-patch.sh <carpeta>` (`adb push <carpeta>/. /sdcard/DroidNoise/`).
   - **Hot reload**: `FileObserver` sobre la carpeta, debounce de ~500 ms para que un push de varios archivos dispare **una sola recarga**. Ignorar la subcarpeta `apk/` y archivos que no sean `.pd`/`.txt`/`.wav`. Al cambiar: `closePatch` + `openPatch`, sin reiniciar el audio.
   - Si `main.pd` no existe o falla: cargar un parche interno de fallback (assets) que suene un beep corto, para saber que el player vive.
4. **Puente Pd → Android** (mínimo): receiver `toapp` con `vibrate <ms>` y `log <msg>` (logcat tag `DroidNoise`). Útil para feedback háptico sin mirar la pantalla.
5. **Activity**: pantalla rota → botones GRANDES y de alto contraste: Iniciar/Detener, Recargar, **Actualizar**, **HOLD** y 6 faders (`dn-vol dn-mic dn-core dn-chaos dn-drone dn-grains`, 0..1, guardados y reenviados tras cada recarga), estado (versionCode, sr, parche cargado, último error). Pedir permisos ahí. Que el service arranque solo al abrir la app.
6. **Auto-actualización**: el botón "Instalar actualización" abre el instalador del sistema con `/sdcard/DroidNoise/apk/droidnoise.apk` vía content URI + `ACTION_VIEW` (permiso `REQUEST_INSTALL_PACKAGES`; el usuario habilita "Instalar apps desconocidas" para DroidNoise una vez). Como no se usa AndroidX, el content URI lo sirve un `ContentProvider` mínimo propio en vez de `androidx.core.content.FileProvider`.
7. **Arranque**: opcional `BOOT_COMPLETED`, detrás del flag `boot=1` en `config.txt`. Android 14+ no deja iniciar un FGS de micrófono desde `BOOT_COMPLETED`, así que el receiver abre la Activity (requiere "Mostrar sobre otras apps", que exime del bloqueo de inicio en segundo plano) y esta arranca el service.

### Firma y versiones
- **Siempre el mismo keystore de debug** (`keystore/debug.keystore`, fuera de git; respaldo en `~/droidnoise-keystore/`) para que cada APK se instale encima del anterior. Sin él, el build usa el debug estándar de Android (y no instala encima de la app ya instalada).
- **versionCode creciente**: minutos desde epoch al momento del build (`-PversionCode=` lo puede forzar).

### Build y deploy (scripts en la raíz)
- `connect.sh [IP:PUERTO]`: `adb connect` con el destino guardado en `.adb_target` (no versionado). Si se pasa un destino, lo guarda. Si falla, guía para re-vincular.
- `deploy.sh`: `./gradlew assembleDebug` → `adb push` del APK a `/sdcard/DroidNoise/apk/droidnoise.apk` → avisa que toques "Instalar actualización" → espera a que el versionCode instalado (`dumpsys package cl.droidnoise.player`) llegue al nuevo → `am start` + `adb logcat -s DroidNoise:* AndroidRuntime:E`.
- `push-patch.sh <carpeta>`: `adb push <carpeta>/. /sdcard/DroidNoise/`.

### Verificación de Fase 1 (no la des por cerrada sin esto)
- Con `pd/test/main.pd`: el mic suena por el jack, el tilt cambia el filtro, tapar el sensor de proximidad corta el audio.
- Con pantalla apagada 10 minutos sigue sonando.
- Editar `main.pd` en el PC → `push-patch.sh` → recarga sola en < 2 s, una sola vez, sin glitch de audio largo.
- `deploy.sh` → "Instalar actualización" → la app nueva arranca y el script lo detecta.
- logcat limpio, sin xruns visibles en el log durante 1 minuto.

## Parche de prueba (`pd/test/main.pd`, Pd vanilla)

Escríbelo a mano en formato `.pd` (texto) y valídalo con `pd -nogui -send` o `pd -batch` si Pd está instalado (`pacman -S puredata`; pregunta).
- `[adc~]` → `[lop~]` con cutoff mapeado desde `ori` beta (−90..90 → 200..8000 Hz, curva exponencial, `[line~]` para suavizar) → `[*~]` gate → `[dac~ 1 2]`.
- Gate desde `proxnear` (cerca = mudo) con rampa de 20 ms.
- `[print]` de `info` al cargar.
- **Cuidado con el feedback**: arrancar con ganancia baja (0.3) y un `[clip~ -0.9 0.9]` antes del `dac~`.

## Fase 2. El instrumento (solo después de cerrar Fase 1)

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
- Respuestas en español chileno neutro (tú, sin voseo), directas.
- Verifica APIs de libpd contra el código fuente de `pd-for-android` antes de usarlas; no inventes firmas.
- Commits chicos por paso en git. Nada de dependencias que llamen a red.
- Si algo del celu no se puede verificar desde el PC (sonido real, sensores), di exactamente qué probar y qué debería verse en logcat.

## Debloat del celu (`debloat/`)
- `debloat/debloat.sh` quita lo de `debloat/packages.txt` con `pm uninstall --user 0`; si HyperOS lo protege (`Failure [-1000]`) usa `pm disable-user`. Registra en `removed.log` / `disabled.log`.
- `debloat/restore.sh [paquete...]` revierte (`cmd package install-existing` + `pm enable`). Sin argumentos revierte todo.
- Intocables: settings, systemui, `com.miui.home`, `com.miui.securitycenter` (permisos), instaladores, documentsui, webview, teclado, gms/gsf, wifi/telefonía/audio.
- Gestores de archivos (`com.mi.android.globalFileexplorer`, `com.google.android.apps.nbu.files`) se quedan hasta la primera instalación de DroidNoise.

## El celu como instrumento
- DroidNoise es la **pantalla de inicio** (rol HOME): `adb shell cmd role add-role-holder android.app.role.HOME cl.droidnoise.player`.
  Volver al launcher de HyperOS: `adb shell cmd role add-role-holder android.app.role.HOME com.miui.home`.
- Pantalla completa (barras ocultas, aparecen deslizando el borde); "atrás" anulado.
- Las demás apps se abren desde el buscador de la hoja `•••` (sin tildes ni mayúsculas).
- Fondo de inicio y bloqueo: el original va en `private/wallpaper.png` (fuera de git; en el repo, `wallpaper/wallpaper.png` es la copia marcada) → `adb push private/wallpaper.png /sdcard/DroidNoise/wallpaper.png`; la app lo aplica al volver al frente si cambió.
- Logo de SÜDBÖS: original en `private/res/drawable-nodpi/logo_sudbos.png` (fuera de git, lo usa el build); en el repo solo la copia marcada "derechos reservados" (`app/branding/`).
- Modo oscuro del sistema: `adb shell cmd uimode night yes`.
- Paleta rojo sangre (ver `Palette.kt`), tipografía Blackcraft (GGBotNet, OFL con nombre reservado: se distribuye sin modificar, `licenses/Blackcraft-OFL.txt`), logo SÜDBÖS de Isidora Mansilla. App por Adolfo Rosas.

## Banco de parches, REC y samples
- Generador: `python3 tools/gen_bank.py` → `pd/droidnoise/lib/` (abstracciones), `pd/droidnoise/bank/NN/` (parches) y `app/src/main/assets/master.pd`. Subir: `./push-patch.sh`.
- Contrato de un parche (`bank/NN/main.pd` + `faders.txt`): faders `[fader N default]` (dn-f1..6), gestos `[held ori|gyro|light]` / `[r acc]`, salida `[out~ $0]` (fade, gate de proximidad, throw~ al master), medidores `[meter~ N]`. Nombres de delay/tabla con `$0` (en el crossfade suenan dos parches). `faders.txt`: `cat:`, `desc:` y 6 líneas `ETIQUETA default`.
- El nombre de cada parche es su **número romano** (carpeta `bank/VI/`, `bank/CCXLVII/`): el usuario no quiere nombres. I a VII hechos a mano (`tools/gen_bank.py`); del VIII en adelante, generados (`tools/gen_algo.py`) y validados por la granja `python3 tools/validate_bank.py CANTIDAD --seed N` (Pd sin pantalla con el master: descarta errores, mudos y NaN, ajusta el nivel a ~88 dB y numera a partir del primer romano libre).
- Master (`assets/master.pd`, siempre cargado): `catch~ dn-out-L/R` → jack + `writesf~ 2` (dn-rec). REC: `open -bytes 3 <ruta>`, y `start` 100 ms después (en el mismo instante lógico lo rechaza).
- Grabaciones: `/sdcard/DroidNoise/rec/<parche>/AAAA-MM-DD_HH-MM-SS.wav` (estéreo 24 bit), una carpeta por parche. Bajar: `./pull-rec.sh` → `~/Música/DroidNoise/<parche>/`.
- Samples: WAV en `/sdcard/DroidNoise/samples/` (y las grabaciones de rec/). Los parches de `cat: samples` reciben `dn-sample <ruta>` al cargar y al pedir `toapp sample next` (sacudón); sin samples, `dn-sample-none` y graban el mic.
- Validación en el PC: cargar master + parche en `pd -nogui -nosound -path pd/droidnoise/lib` con sensores simulados y leer `dn-m-*`.
