# IAQ app <-> MatePet bridge

The bridge code now lives **inside the updated IAQ app** (`app/.../bridge/`, documented in its `docs/AI_BRIDGE.md`), already wired to MissionManager, RoutineManager, SensorRepository and the navigation. Nothing to copy here.

Protocol summary: MatePet sends broadcast `com.leslie.iaqieqmornitoring.action.COMMAND` (`cmd`, `arg`) and reads `content://com.leslie.iaqieqmornitoring.bridge/status` (JSON). Commands: START_MISSION, STOP_MISSION, START_ROUTINE, STOP_ROUTINE, OPEN_TAB.

Install order: IAQ app first, then MatePet, both signed with the same key (the permission is signature-level).
