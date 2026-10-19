# Start the Google TV emulator.
emulator:
    ~/Library/Android/sdk/emulator/emulator -avd owntv_gtv

# Build and install the debug app.
install:
    ./gradlew :app:installDebug

# Build the web preview into web/build/dist/wasmJs/productionExecutable.
web-build:
    ./gradlew :web:wasmJsBrowserDistribution

# Serve the last web preview build, e.g. `just web-serve 9000`.
web-serve port="8088":
    @echo "OwnTV preview: http://localhost:{{port}}"
    python3 -m http.server {{port}} --bind 127.0.0.1 --directory web/build/dist/wasmJs/productionExecutable

# Build the web preview and serve it.
web port="8088": web-build (web-serve port)
