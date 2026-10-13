# Fuel-cost sources — checked 13 October 2026

## Saudi retail prices

Saudi Aramco's official page displayed **October 2026** prices:
https://www.aramco.com/en/what-we-do/energy-products/retail-fuels

| Grade | SAR/liter |
|---|---:|
| PG91 | 2.18 |
| PG95 | 2.33 |

These are stored offline as 218 and 233 halalas/liter. They are a dated verified
snapshot, not a promise that prices never change. Users may switch grade and
edit the stored price in Settings. Selecting a grade resets the field to the
dated Aramco reference; confirm/save it before starting the next outing.

## Lancer 2016 reference consumption

The owner has now supplied a **1.6L 4A92, 4-speed automatic** profile. They may
use either fuel grade (91 minimum). This replaces the earlier unknown profile.

Auto-Data lists these international variants covering 2015–2017:

| Variant | Combined consumption | Liters/km |
|---|---:|---:|
| 1.6 MIVEC 117 hp, manual | 5.5 L/100 km | 0.055 |
| 2.0 148 hp, CVT | 7.8 L/100 km | 0.078 |

Sources:
- https://www.auto-data.net/en/mitsubishi-lancer-ix-facelift-2015-1.6-mivec-117hp-33293
- https://www.auto-data.net/en/mitsubishi-lancer-ix-facelift-2015-2.0-148hp-cvt-29739

These are third-party specifications for different-market versions,
**not a verified rating of the user's vehicle**. Neither figure is used as the
new default for the confirmed automatic car. Attempts to retrieve matching
official Mitsubishi specifications/brochures did not produce a verified
combined-consumption value. Failed URLs and unrelated search results are not
evidence for a precise number.

Settings now offers the confirmed vehicle profile, an editable consumption
input, and custom calibration. Its initial 8L/100km is labeled **PROVISIONAL**
in the UI: it is an assumption, not a researched manufacturer specification.
Reliable full-tank measurements are preferred by default. The supplied VIN
is not saved or published. Old archived cost inputs are preserved.

## Automatic estimates

Liters = GPS distance in km × consumption in L/100 km ÷ 100.
Cost = liters × the chosen SAR/liter price. Currency uses decimal arithmetic
and rounds to the nearest halala only at the final outing cost.

For a 100km outing at the provisional 8L/100km input, the estimate is
**17.44 SAR with PG91**, or **18.64 SAR with PG95**.
These are estimates, not actual purchases. They are displayed separately from
expense totals so fuel fills are never counted twice.

New outings persist their selected grade, price, consumption interval, and
estimate source at start. Subsequent settings/calibration changes do not reprice
them. Old version-1.0 outings have no stored estimate; their UI explicitly labels
estimates as using current settings. Manual expense price entry remains possible.
