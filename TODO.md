# PikaReader Roadmap:

## v1.0 Cleanup / Bug Fixes

## v1.1 PikaRelay Support
- Send reads to relay server
- Rewind
- Start / Stop Reader
- Status updates

## v1.2 Web Config
- Set gating
- Upload URL, enable/disable
- Timezone override
- Basic Reader config
    - Add/remove
    - Set IP
    - Set Type
    - enable / disable antenna ports
    - set power level

## v1.3+ (or whenever)
- Bug Fixes and general cleanup
- logging cleanup (keep last 10)
- Migrate to Jackson for json encoding and parsing
- On-Disk Cleanup via web config

- Update Octane SDK to v4.x and add initial support for R700 based readers
- Add support for UART based readers
- Add support for Zebra/Moto FX series readers
- Settable Profiles: Start / Finish / Side Panels
    - Sets the reader mode, population size, and gating
    - Can be toggled when not in read mode
