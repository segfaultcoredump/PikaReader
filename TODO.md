# PikaReader Roadmap:

## v1.0 Cleanup / Bug Fixes
- R700: validate on hardware (Octane 6.x support and R700 profile defaults are in place)
    - Region detection from the reader, and that the profile modes (4/5/2, and static modes 141, 288, 380) are accepted
- Validate the side-panel RSSI peak read filter on hardware at finish and split panels (6 dB drop held 1 s, 3-read average, 3 s unseen, 15 s max): walkers and runners should give one read each, timed near the closest approach
- Reader profiles (Start Mats, Start Panels, Finish Mats, Finish Panels, Split Side Panels) are in place. Validate on hardware:
    - R420 outside FCC: confirm which Max Miller mode (4 or 5) the reader accepts
    - Zebra: confirm the reader keeps the MotoAntennaConfig A/B flip settings (logged after ADD_ROSPEC) and that bibs are read repeatedly with it
    - FX7400, FX9500 and FXR90: record which Zebra extensions each accepts (logged as the ROSpec attempts), and confirm the standard fallbacks (Select reset, one AISpec per antenna) give repeated reads with a bib parked over a mat
    - FX7400 and FX9500: check which link the nearest rule picked against the reader's mode table, and compare reads per pass with an FX7500/FX9600 on the same layout
    - FX7400 on the ETSI band: it stops continuous RF after 20 s with no tags seen. At a sparse split, confirm the first runner after a long gap is still read
    - Zebra model numbers: note the LLRP model number each model reports (logged on connect), so "Model" could be detected automatically
    - R220: record which reader modes it accepts, and compare reads per pass with an R420 on the same side-panel or split layout
    - R720: confirm it accepts the same modes as the R700, including the static modes (141, 288, 380)
    - Zebra finish mats in FCC: compare the 640 kHz m4_fast link against m4_mid_alt (mode 21) and keep whichever gives more reads per pass
    - Zebra in ETSI: check whether the reader offers faster M4 links than Zebra's guide shows (rows 35-38 look misprinted)
    - Zebra FX7500 in Japan: confirm its mode table
- Optional per-reader channel plans for ETSI LB / Japan / ETSI HB (reader-profiles.json regional_notes, e.g. finish mats on 865.7/866.3 MHz, side panels on 866.9/867.5 MHz)


## v1.1 PikaRelay Support
- Send reads to relay server
- Rewind
- Start / Stop Reader
- Status updates

## v1.2 Web Config
- Set gating
- Upload URL, enable/disable
- Timezone override? 
- Basic Reader config
    - Add/remove
    - Set IP
    - Set Type
    - enable / disable antenna ports
    - set power level

## v1.3+ (or whenever)
- Bug Fixes and general cleanup
- Migrate to Jackson for json encoding and parsing
- On-Disk Cleanup via web config

- Add support for UART based readers
