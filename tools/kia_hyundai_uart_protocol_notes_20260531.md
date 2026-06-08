# Kia/Hyundai UART protocol notes, 2026-05-31

Live head unit:
- ADB: `192.168.114.122:7575`
- Model: `CC4PRO`
- Active profile: `SETTING_FACTORY_CAR=17367078` = `0x1090026`
- Active CarModel: `CAR_MODEL_RAISE_HYUNDAI_19_MISTRA`
- CANBOX: `HYK-RZ-10-0008-N1`
- Previous Sportage profile discussed: `17367094` = `0x1090036` = `CAR_MODEL_RAISE_HYUNDAI_18_19_KIA_SPORTAGE`

Important boundary:
- Current active parser is `com.spd.carinfo.protocol.afterload.hyundaikia.raise.RaiseHyundaiKia`.
- Sending Hiworld-style UART while Raise is active produced no `HiworldHyundaiKia`, no `CarInfoManage`, and no `cmd=40002`.
- Hiworld data is useful for patching or for a real profile switch, but foreign Hiworld frames are not routed by the active Raise profile.

Raise known RX/popup-working commands:

Driver heat RX, level 1:
`ON  FD 06 52 06 01 00 5F`
`OFF FD 06 52 06 00 00 5E`

Driver heat RX, level 2:
`ON  FD 06 52 06 02 00 60`
`OFF FD 06 52 06 00 00 5E`

Driver heat RX, level 3:
`ON  FD 06 52 06 03 00 61`
`OFF FD 06 52 06 00 00 5E`

AQS RX:
`ON  FD 06 52 02 01 00 5B`
`OFF FD 06 52 02 00 00 5A`

Lock car RX:
`ON  FD 0B 03 10 0D 00 00 FF 00 40 01 6A`
`OFF FD 0B 03 10 0D 00 00 FF 00 00 01 2A`

Raise TX/API-only commands seen by current UART but not mapped to popup state by stock Raise RX:

Passenger heat:
`L1 ON  FD 06 83 AE 01 01 38`
`L2 ON  FD 06 83 AE 02 01 39`
`L3 ON  FD 06 83 AE 03 01 3A`
`OFF    FD 06 83 AE 00 01 37`

Driver ventilation/cold:
`L1 ON  FD 06 83 BF 01 01 49`
`L2 ON  FD 06 83 BF 02 01 4A`
`L3 ON  FD 06 83 BF 03 01 4B`
`OFF    FD 06 83 BF 00 01 48`

Passenger ventilation/cold:
`L1 ON  FD 06 83 C0 01 01 4A`
`L2 ON  FD 06 83 C0 02 01 4B`
`L3 ON  FD 06 83 C0 03 01 4C`
`OFF    FD 06 83 C0 00 01 49`

MAX:
`ON  FD 06 83 BB 02 01 46`
`OFF FD 06 83 BB 00 01 44`

Raise API mapping from `setSeats`:
- `cmd=0x9C41` (`40001`) arg `0` -> driver heat -> `setControlCommand(0xAD, value)`
- `cmd=0x9C41` (`40001`) arg `1` -> passenger heat -> `setControlCommand(0xAE, value)`
- `cmd=0x9C42` (`40002`) arg `0` -> driver cold/vent -> `setControlCommand(0xBF, value)`
- `cmd=0x9C42` (`40002`) arg `1` -> passenger cold/vent -> `setControlCommand(0xC0, value)`

Raise stock RX mapping:
- `FD 06 52 06 xx` updates `SeatsBean.driverHeat` and emits `notifyToUI(0x9C41, value, 0)`.
- Stock `RaiseHyundaiKia.rxData()` does not update `SeatsBean.driverCold` / `passengerCold` from tested `83 BF` / `83 C0`.
- Therefore a Raise-side patch should map:
  - `83 AE` -> `SeatsBean.passengerHeat`, `notifyToUI(0x9C41, value, 1)`
  - `83 BF` -> `SeatsBean.driverCold`, `notifyToUI(0x9C42, value, 0)`
  - `83 C0` -> `SeatsBean.passengerCold`, `notifyToUI(0x9C42, value, 1)`
  - `83 BB` -> `AirConditionBean.acMax`, climate popup notify

Hiworld implementation found in installed `CarInfoService`:
- Class: `com.spd.carinfo.protocol.afterload.hyundaikia.hiworld.HiworldHyundaiKia`
- Implemented: `rxData`, `parseAirCondition`, `getSeats`, `setSeats`, `setAirCondition`
- Hiworld profiles include:
  - `0x209001B` = `CAR_MODEL_HIWORLD_HYUNDAI_2018_SPORTAGE`
  - `0x2090031` = `CAR_MODEL_HIWORLD_HYUNDAI_IX35_SPORTAGE_3C_ALL_HIGH_AMP`

Hiworld seat keys from `setSeats`:
- driver heat -> key `0x11`
- passenger heat -> key `0x12`
- driver cold/vent -> key `0x17`
- passenger cold/vent -> key `0x18`

Hiworld RX state in climate frame `type=0x31`:
- `byte4 bits 0..1` -> driver heat
- `byte4 bits 2..3` -> passenger heat
- `byte5 bits 0..1` -> driver cold/vent
- `byte5 bits 2..3` -> passenger cold/vent
- `parseAirCondition` emits popup via `cmd=0x753E` when climate/seat state changes.

Hiworld test sent through current Raise profile:
- Driver cold level 1 adapter packet:
  `BB 41 A1 14 70 0C 31 00 00 00 01 00 00 72 72 00 00 72 FF B4`
- Driver cold off adapter packet:
  `BB 41 A1 14 70 0C 31 00 00 00 00 00 00 72 72 00 00 72 FF B3`
- Result on active Raise profile: no relevant logcat reaction.
