# DroidNoise

Un celular roto convertido en instrumento de ruido para SÜDBÖS.

Es un Redmi Note 13 Pro 4G con la pantalla dañada y el USB que solo carga. Lo convertí en un aparato que hace ruido. La app es la pantalla de inicio, el audio sale por el jack y se toca moviendo el teléfono, tapándolo, girándolo, sacudiéndolo.

Por dentro corre Pure Data con libpd. La app solo carga parches, lee los sensores y graba. El sonido vive todo en los parches.

## Qué tiene

- Unos mil parches, cada uno con nombre en número romano. Del I al VII los armé a mano. El resto sale de un generador que combina fuentes (ruido, FM, sierras, clics, mic...), procesos (drive, plegado, crush, comb, resonadores, delay, granular, redes de feedback...) y formas de conectarlos. Después una granja los prueba uno por uno en Pd y bota los que fallan, los que no suenan y los que se van a NaN.
- Seis faders por parche, con su propio nombre y un medidor de lo que está sonando en esa parte.
- Gestos: inclinar, girar, la brújula, la luz, el sacudón y la proximidad. HOLD congela el gesto para seguir con los faders.
- REC graba lo que sale por el jack a WAV, en una carpeta por parche. El VII toma esas grabaciones y las vuelve a granular.
- Nada de Google ni internet. Todo pasa por adb sobre WiFi, y la app se actualiza sola desde el teléfono porque HyperOS no deja usar adb install sin cuenta Mi.

## Carpetas

```
app/                     la app de Android
pd/droidnoise/lib/       piezas de Pd que comparten todos los parches
pd/droidnoise/bank/<N>/  un parche por carpeta (main.pd y faders.txt)
tools/gen_bank.py        los parches I a VII, la librería y el master
tools/gen_algo.py        el generador
tools/validate_bank.py   la granja: python3 tools/validate_bank.py 500 --seed 20000
wallpaper/               fondo de pantalla del celu
*.sh                     vincular, conectar, instalar, subir parches, bajar grabaciones
```

En `CLAUDE.md` están las notas de trabajo con todo el detalle técnico.

## Cómo se usa

Hace falta JDK 17, el Android SDK (platforms;android-34 y build-tools;36.0.0) y Pure Data para probar parches en el PC.

```sh
./pair.sh CODIGO     # vincular el celu por depuración inalámbrica
./deploy.sh          # compilar, mandar el APK al celu y esperar que se instale
./push-patch.sh      # subir la librería y el banco al celu
./pull-rec.sh        # bajar las grabaciones a ~/Música/DroidNoise/
```

El keystore no está en el repo. Si no lo tienes, el build firma con el de debug normal de Android.

## Créditos

DroidNoise es de Adolfo Rosas.

El logo de SÜDBÖS lo hizo Isidora Mansilla por encargo. Es parte del proyecto pero no entra en la licencia del código: no se puede usar fuera de DroidNoise sin permiso.

La tipografía es Blackcraft, de GGBotNet (SIL Open Font License 1.1, sin modificar). El texto chico va en CaskaydiaCove Nerd Font (SIL OFL 1.1, recortada). Las licencias están en `licenses/`.

libpd y Pure Data tienen licencia BSD.

## Licencia

El código, los scripts y los parches van con GPL-3.0. Ver `LICENSE`.
