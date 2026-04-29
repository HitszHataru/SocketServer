# Aircraft War Socket Server

LAN socket server for the Android Aircraft War online battle mode.

## Ports

- Easy: `9999`
- Normal: `12000`
- Hard: `10001`

Players are matched only with another player connected to the same difficulty port.

## Run

Open this project in Android Studio and run `MyServer/src/main/java/com/example/myserver/MyClass.java`.

You can also run it from the project root:

```bash
./gradlew :MyServer:run
```

On Windows:

```bat
gradlew.bat :MyServer:run
```

