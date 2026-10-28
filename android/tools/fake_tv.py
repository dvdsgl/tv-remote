"""Run pyatv's fake Companion Apple TV (tests/fake_device/companion.py) for CompanionE2ETest.

Usage (tools/e2e.sh does this):
  uv run --no-project --with pyatv==0.18.0 --with srptools==1.0.1 \
      python -I -u tools/fake_tv.py <pyatv v0.18.0 source checkout>

Prints "FAKE_TV=127.0.0.1:<companion port>:<control port>". The control port takes one
command per line and answers with one JSON line:
  state            the fake TV's recorded state
  status <n>       set the system status (1 asleep, 3 awake); pushes a SystemStatus event
  focus <0|1>      unfocus or focus the text field; pushes _tiStopped or _tiStarted
"""

import asyncio
import json
import sys

sys.path.insert(0, sys.argv[1])

from pyatv.const import KeyboardFocusState  # noqa: E402
from pyatv.protocols.companion.api import SystemStatus  # noqa: E402
from tests.fake_device.companion import (  # noqa: E402
    FakeCompanionServiceFactory,
    FakeCompanionState,
    FakeCompanionUseCases,
)


def snapshot(state: FakeCompanionState) -> dict:
    action = getattr(state, "action", None)
    return {
        "has_paired": state.has_paired,
        "latest_button": state.latest_button,
        "powered_on": state.powered_on,
        "duration": state.duration,
        "rti_text": state.rti_text,
        "system_info": {k: (v.hex() if isinstance(v, bytes) else v) for k, v in (state.system_info or {}).items()},
        "service_type": state.service_type,
        "sid": state.sid,
        "touch_width": state.touch_width,
        "touch_height": state.touch_height,
        "tv_rc_protocol_version": state.tv_rc_protocol_version,
        "interests": sorted(state.interests),
        "clients": len(state.clients),
        "touch": None if action is None else {
            "mode": action.press_mode.value, "x": action.x, "y": action.y, "ns": action.ns},
    }


async def main():
    loop = asyncio.get_running_loop()
    state = FakeCompanionState()
    cases = FakeCompanionUseCases(state)
    factory = FakeCompanionServiceFactory(state, None, loop)
    await factory.start(False)

    async def control(reader, writer):
        while line := await reader.readline():
            cmd, _, arg = line.decode().strip().partition(" ")
            if cmd == "status":
                cases.set_system_status(SystemStatus(int(arg)))
            elif cmd == "focus":
                cases.set_rti_focus_state(
                    KeyboardFocusState.Focused if arg == "1" else KeyboardFocusState.Unfocused)
            writer.write((json.dumps(snapshot(state)) + "\n").encode())
            await writer.drain()
        writer.close()

    server = await asyncio.start_server(control, "127.0.0.1", 0)
    print(f"FAKE_TV=127.0.0.1:{factory.port}:{server.sockets[0].getsockname()[1]}", flush=True)
    await asyncio.Event().wait()


if __name__ == "__main__":
    asyncio.run(main())
