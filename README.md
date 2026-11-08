# IconX Control

App Android para controlar los **Samsung Gear IconX 2016 (SM-R150)** sin la app oficial de Samsung, que ya no se instala en Android moderno.

## Qué hace
- Batería de cada audífono y si están puestos
- Sonido ambiente
- Contestar llamadas automáticamente
- Elegir si la música viene del celular o de la memoria de los audífonos
- Aleatorio para la música guardada en los audífonos
- Espacio libre y número de canciones guardadas
- Registro de comunicación para diagnosticar problemas

## Instalar
1. En el celular, abre la sección **Releases** de este repositorio y descarga `IconX-Control.apk`.
2. Ábrelo y permite "instalar apps desconocidas" para tu navegador o administrador de archivos.
3. Vincula los IconX en *Ajustes → Conexiones → Bluetooth* (si no lo están).
4. Abre **IconX Control**, toca *Elegir* y luego *Conectar*.

## Cómo funciona
Los IconX 2016 se comunican por un canal Bluetooth RFCOMM con el UUID `00011101-0000-1000-8000-00805f9b34fb`.
Cada mensaje tiene la forma `EF | tipo<<4 | id | largo | datos | EE`. El detalle está en
[`Protocol.kt`](app/src/main/java/cl/javier/iconx/Protocol.kt).

## Compilar
GitHub Actions compila el APK automáticamente en cada cambio a `main` (ver `.github/workflows/build.yml`).
