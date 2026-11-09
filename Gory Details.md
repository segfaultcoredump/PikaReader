# PikaReader: Gory Details

***

The low-level detail behind the reader profiles: what each profile sets on each kind of reader, how sessions are assigned, the profile settings in the config file and the per-reader overrides. Also the setup notes and config options for the Impinj and Zebra / Motorola FX readers.
For an overview of the profiles and the read filters, see [Reader Profiles](README.md#reader-profiles) in the README.

## Reader Profiles
What a profile sets on each kind of reader:
- **Impinj Speedway** (R420 and R220, and any Impinj reader other than the R700 Series): reader mode `MaxMiller` (4), or 5 if the reader rejects 4 (as some non-FCC readers do), or `DenseReaderM4` (2) on readers without Max Miller, such as the R220. Start Panels uses `DenseReaderM4` (2). Search mode `DualTargetBtoASelect`.
- **Impinj R700 Series** (R700 and R720): the reader mode for the reader's region. In FCC: 4 for mats, R700 mode 141 for finish panels and splits, 2 for start panels. In ETSI LB, Japan and other non-FCC regions: 5 for mats, 2 for panels. In ETSI HB: 4 for mats, 2 for panels. Search mode `DualTargetBtoASelect`.
- **Zebra FX** (FX7500, FX9600, FXR90, and the older FX7400 and FX9500): an air link picked from the reader's own RF mode table (the fastest Miller-4 link for mats, Miller-4 at about 320 kHz for finish panels and splits, Miller-4 at about 256 kHz for start panels), the `ABFlip` search mode, and 1 or 2 inventory rounds per antenna.
- **Every reader**: the tag population in the table, and the session (see below).

All of the values live in `src/main/resources/reader-profiles.json`. When a reader connects, PikaReader logs the settings it applied for the profile and the fallback reader modes or links you can switch to with an override. `/profile` shows the same.

Sessions: if more than one reader is configured, they get sessions 2, 3, 2, 3... in `Index` order, so two readers watching the same tags don't fight over the same session flag. A lone reader gets session 2 or 3 at random each time PikaReader starts. Use a `Session` override to pin it.

Profile settings go in the `Reader` section of the config file:

```json
"Reader": {
  "Profile": "finish_mats",
  "Region": "Auto",
  "Gating Style": "READER",
  "Finish Mats Gating": 2,
  "Readers": [ ... ]
}
```

- `Profile`: the selected profile, by id or name. Saved whenever the profile is changed. Missing or unknown defaults to `finish_mats`.
- `Gating Style`: `READER` (default), `BOX` or `ANTENNA`. See the read filters in the [README](README.md#reader-profiles).
- `Finish Mats Gating`: optional, the Finish Mats read window in seconds (default 2). The old `Gating` setting is no longer used; PikaReader logs a warning if it's still there.
- `Region`: `Auto` (default) uses the region each reader reports. Or set one of `fcc`, `global_other` (non-FCC regions in 902-928 MHz, such as Australia), `etsi_lb` (865-868 MHz), `etsi_hb` (915-921 MHz) or `japan`. Only the R700's reader modes depend on the region. If PikaReader can't tell an R700's region, or the reader reports a different region than the one set here, the R700 uses its own default reader mode and an error is logged.

Each reader can override any profile's settings in its own `Profiles` entry. Anything not overridden uses the profile's default for that reader model and region. Profiles can be named by id or by name:

```json
"Profiles": {
  "finish_side_panels": {"Reader Mode": "DenseReaderM8", "Tag Population": 64},
  "Start Mats": {"Session": 3, "Power Level": "Max"}
}
```

The settings you can override are listed for each reader type below. `Reader Mode`, `Search Mode`, `Session` and `Tag Population` are no longer read from the top level of a reader's entry. PikaReader logs a warning if it finds them there; move them into `Profiles`.

## Impinj Readers
Impinj Speedway R220/R420 and R700/R720 readers (and other readers the Octane 6.x SDK supports) are driven via the Impinj Octane SDK over LLRP.
The R700 ships with Impinj's REST (IoT device) interface turned on. Switch its RFID interface to LLRP in the reader's web interface before using it with PikaReader.

PikaReader reads the model from the reader:
- **R220**: has fewer reader modes than the R420 and is expected to reject Max Miller, so the profiles fall through to `DenseReaderM4` (2). Its 200 tags/s limit (the R420 does 1,100) gives only about 5 reads per tag when 10 runners cross at once, so it suits side panels and quiet splits. PikaReader warns when an R220 is given the Start Mats profile, and cautions on Finish Mats. The warnings are logged, shown by `/profile`, and shown in the web UI after changing the profile. It has two ports (more with Speedway Antenna Hubs). It is only sold for FCC, GX and EU1 (ETSI LB) regions, so an R220 reporting any other region is treated as a region mismatch.
- **R720**: uses the R700's reader modes. Impinj lists it as end of life.

Add one entry per reader to the `Readers` list in the config file. Each reader needs a unique `Index`. Everything except `Index`, `Type` and `IP` is optional:

```json
{
  "Index": 1,
  "Type": "IMPINJ",
  "IP": "192.0.2.10",
  "TLS": false,
  "Power Level": "High",
  "Antenna Config": {"4": "Disabled"},
  "Clock": "Local",
  "Profiles": {
    "finish_side_panels": {"Reader Mode": "DenseReaderM8", "Tag Population": 64}
  }
}
```

- `TLS`: `true` connects with LLRP over TLS (the reader must have LLRP over TLS enabled). Default `false`. The reader's certificate is not checked, and old TLS algorithms are allowed, because the readers are expected to be directly connected to PikaReader.
- `Port`: LLRP port. Default `5085` with TLS, otherwise `5084`.
- `Power Level`: `Low` (24 dBm), `Medium` (27 dBm), `High` (30 dBm, default) or `Max` (the reader's maximum power). Not case sensitive. Can also be set per profile.
- `Antenna Config`: every antenna port is enabled by default. Mark a port `Disabled` to turn it off. Ports that aren't listed, or that have any other value (such as `Enabled`), stay enabled.
- `Profiles`: per-profile overrides (see [Reader Profiles](#reader-profiles)):
    - `Reader Mode`: an Impinj reader mode, by name or by Impinj mode number. Names are not case sensitive, and spaces or underscores are ignored.
        - Fixed modes: `MaxThroughput` (0), `Hybrid` (1), `DenseReaderM4` (2), `DenseReaderM8` (3), `MaxMiller` (4), `DenseReaderM4Two` (5). Avoid `MaxThroughput` when other readers are nearby.
        - Auto modes: `AutoSetDenseReader` (1000), `AutoSetDenseReaderDeepScan` (1002), `AutoSetStaticFast` (1003), `AutoSetStaticDRM` (1004).
        - Any other mode number is passed to the reader as-is, so newer readers (such as the R700) can use their own modes (e.g. 141, 142, 185, 288, 380).
        - The modes the reader supports are written to the log when PikaReader connects. If the reader rejects the reader mode, PikaReader tries the profile's next choice, and then the reader's own default mode, rather than not reading.
    - `Search Mode`: `DualTargetBtoASelect` (the profiles' choice: alternates between the A and B targets, and resets tags back to A so new tags entering the field aren't missed), `DualTarget`, `SingleTarget`, `SingleTargetReset`, `TagFocus`, or `ReaderSelected` (`ReaderDefault` is accepted as an alias) to let the reader decide.
    - `Session`: Gen2 session 0-3.
    - `Tag Population`: expected number of tags in the field.
    - `Power Level`: as above.
- `Clock`:
    - `Local` (default; `Pi` and `Delta` are accepted as aliases): the clock on the computer running PikaReader is the authority. Every time PikaReader connects to the reader and every time it starts reading, it logs into the reader over SSH, turns off the reader's NTP and sets the reader's clock from the local clock. This needs the reader's default `root` SSH login. Older SSH algorithms (such as `ssh-rsa`) are allowed so that older Speedway firmware still works. A warning is logged if the reader rejects a clock command.
    - `NTP`: the reader gets its time from NTP and PikaReader leaves the reader's clock and NTP settings alone. Configure NTP on the reader itself (web interface or RShell). When it connects, PikaReader logs the reader's NTP status (`show network ntp`).
    - In both modes, PikaReader compares each tag read's timestamp with the local clock and logs a warning if the reader's clock is more than a second off.

After applying a profile, PikaReader reads the settings back from the reader and logs any that differ.

## Zebra / Motorola FX Readers
FX7400, FX7500, FX9500, FX9600 and FXR90 readers are driven directly over LLRP (TCP port 5084, or LLRP over TLS), so no Zebra SDK is needed and PikaReader runs on any platform.
The reader must be in its standard LLRP mode. If the reader's IoT Connector or another application is controlling the radio, PikaReader will not be able to connect.
On the FXR90, LLRP and the IoT Connector share the reader and the first connection wins, so make sure no IoT Connector or cloud connection claims it first. The FXR90 uses port 5084, or 5085 with secure mode on.
Only one LLRP client can be connected to a reader at a time.

Model notes:
- **FX9500**: needs firmware 1.5.5 or later. Older firmware rejects the Select that the standard A/B flip fallback uses. Configure it over LLRP only, since it doesn't support most Reader Management commands.
- **FX7400**: its links are built around 250 and 300 kHz, so the nearest rule (below) picks them. ETSI models stop transmitting after 20 s with no tags in view, so check that a sparse split doesn't miss the first runner after a gap.
- The FX7400 and FX9500 don't have Zebra's A/B flip and antenna-stop extensions (Zebra's interface guide), so they use the standard LLRP fallbacks.

Port count, power levels, RF modes and clock support are read from the reader when PikaReader connects, so the same settings work across the FX models.
The capabilities, the reader's RF mode table and the choices PikaReader made are all written to the log.

Add one entry per reader to the `Readers` list in the config file. Each reader needs a unique `Index`. Everything except `Index`, `Type` and `IP` is optional:

```json
{
  "Index": 2,
  "Type": "ZEBRA",
  "IP": "192.0.2.20",
  "Port": 5084,
  "TLS": false,
  "Power Level": "High",
  "Antenna Config": {"4": "Disabled"},
  "Dwell": 500,
  "Clock": "Delta",
  "Model": "FX9600",
  "Profiles": {
    "finish_mats": {"Reader Mode": "m4_mid_alt"}
  }
}
```

- `Model`: `FX7400`, `FX7500`, `FX9500`, `FX9600` or `FXR90`. Optional. LLRP only reports a model number, so this tells PikaReader which model it is talking to: it logs PikaReader's notes for that model, checks the FX9500's minimum firmware, and skips the Zebra extensions on the FX7400 and FX9500. Without it, PikaReader finds out what the reader accepts by trying.

- `TLS`: `true` connects with LLRP over TLS (the reader must have secure LLRP enabled). Default `false`. The reader's certificate is not checked, and old TLS algorithms are allowed, because the readers are expected to be directly connected to PikaReader.
- `Port`: LLRP port. Default `5085` with TLS, otherwise `5084`. Set it if your reader uses a different port for secure LLRP.
- `Power Level`: `Low`, `Medium`, `High` (default) or `Max` (the same levels as the Impinj readers), or a number in dBm such as `29.5`. The closest reader power at or below that value is used. Can also be set per profile.
- `Antenna Config`: every antenna port the reader reports is enabled by default. Mark a port `Disabled` to turn it off. Ports that aren't listed, or that have any other value (such as `Enabled`), stay enabled.
- `Dwell`: milliseconds per target in `DualTarget` mode, default `500`. Can also be set per profile.
- `Profiles`: per-profile overrides (see [Reader Profiles](#reader-profiles)):
    - `Reader Mode`: Zebra numbers its RF modes (link profiles) differently on each model, region and firmware, so the profiles name a link class instead, and PikaReader picks the first matching link (Miller value, data rate, PIE and Tari) from the reader's own mode table. If nothing matches exactly (as on older readers such as the FX7400), the class's nearest rule picks the closest link with the right Miller value: by link frequency, then dense-reader spectral mask, then PIE, then Tari. Every nearest-rule pick is logged. The override can be:
        - A link class from `reader-profiles.json`: `m4_fast` (the fastest Miller-4 link), `m4_mid` (Miller-4, about 320 kHz, long Tari), `m4_mid_alt` (Miller-4, about 320 kHz, short Tari), `m4_dense` (Miller-4, about 256 kHz) or `m8` (Miller-8, the most sensitive).
        - A named mode, which picks the closest entry in the reader's table: `DenseReaderM4` (`DenseReaderM4Two` is an alias), `MaxMiller`, `DenseReaderM8`, `Hybrid` (Miller-2), `MaxThroughput` (FM0, a poor choice near other readers).
        - A raw mode number from the mode table in the log.
    - `Search Mode`:
        - `ABFlip` (the profiles' choice): every tag is read many times, so the strongest read can be kept, and the reader moves to the next antenna after the profile's inventory rounds. PikaReader first tries Zebra's own custom LLRP parameters for this (A/B flip with SL All, and an antenna stop condition). If the reader rejects one or both, it rebuilds the ROSpec with the standard-LLRP replacement:
            - A/B flip: a Select before each inventory round sets every tag to A, and the round reads the A tags and flips them to B, like Impinj's B to A Select. Needs a reader that supports state-aware singulation.
            - Antenna stop: one AISpec per antenna, each ending after the profile's inventory rounds (or 100 ms), and the ROSpec is restarted each time it finishes.

          If none of these work, PikaReader falls back to `DualTarget`. The log says which parts fell back. PikaReader remembers what each reader (by serial number and firmware) accepted, so later connections skip the attempts it rejected, until PikaReader restarts.
        - `DualTarget`: alternates between the A and B inventory targets every `Dwell` milliseconds with standard LLRP.
        - `SingleTarget`: target A only. Fewest duplicate reads.
        - `ReaderDefault`: let the reader decide.
    - `Session`: Gen2 session 0-3.
    - `Tag Population`: expected number of tags in the field.
    - `Inventory Cycles`: in `ABFlip` mode, the inventory rounds on each antenna before moving to the next one. The profiles use 1 or 2.
    - `Power Level`, `Dwell`: as above.
- `Clock`:
    - `Delta` (default; `Local` and `Pi` are accepted as aliases): the clock on the computer running PikaReader is the authority. PikaReader measures the offset between the reader's clock and the local clock from the timestamps on reader events and tag reads, and corrects every tag read to local time. The offset is tracked continuously, so reader clock drift during a long event is corrected too.
    - `NTP`: use the reader's own clock as-is. Only use this if NTP is configured on the reader. PikaReader still measures the offset and logs a warning if the reader's clock is more than a second off.

After adding the ROSpec, PikaReader reads it back from the reader and logs any setting that differs, and whether the reader kept the Zebra custom settings. `/profile` shows each reader's link, A/B flip method and any warnings.

If the network connection to a reader drops, PikaReader reconnects automatically and puts the reader back into read mode if it was reading.
