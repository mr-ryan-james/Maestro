// Runs automatically when the dylib is loaded via DYLD_INSERT_LIBRARIES, before the app's
// own code gets going. Keeps zero logic here — just hands off to the Swift entrypoint.
extern void maestro_copilot_start(void);

__attribute__((constructor))
static void maestro_copilot_ctor(void) {
    maestro_copilot_start();
}
