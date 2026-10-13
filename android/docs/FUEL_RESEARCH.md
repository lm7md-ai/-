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

The user's engine/transmission is **unknown** and they may use either fuel grade.
Do not infer their engine from model year or from the gasoline grade.

Auto-Data lists these international variants covering 2015–2017:

| Variant | Combined consumption | Liters/km |
|---|---:|---:|
| 1.6 MIVEC 117 hp, manual | 5.5 L/100 km | 0.055 |
| 2.0 148 hp, CVT | 7.8 L/100 km | 0.078 |

Sources:
- https://www.auto-data.net/en/mitsubishi-lancer-ix-facelift-2015-1.6-mivec-117hp-33293
- https://www.auto-data.net/en/mitsubishi-lancer-ix-facelift-2015-2.0-148hp-cvt-29739

These are third-party reference specifications for different-market versions,
**not a verified rating of the user's Saudi-market vehicle**. The 1.6 manual
figure must not be applied as a precise figure to a 1.6 automatic. The unknown
profile displays a **reference interval**, 5.5–7.8 L/100 km; it is not a guaranteed
real-world upper/lower bound. Traffic, air conditioning, driving style, vehicle
condition, and transmission can produce consumption outside that interval.

Settings offers the explicit manual/CVT references, a custom figure, and an
enabled-by-default preference for sufficiently supported personal full-tank
measurements. Personal measurements replace the reference range once available.

## Automatic estimates

Liters = GPS distance in km × consumption in L/100 km ÷ 100.
Cost = liters × the chosen SAR/liter price. Currency uses decimal arithmetic
and rounds to the nearest halala only at the final outing cost.

For a 100 km outing with an unknown engine, the published reference interval
produces **11.99–17.00 SAR with PG91**, or **12.82–18.17 SAR with PG95**.
These are estimates, not actual purchases. They are displayed separately from
expense totals so fuel fills are never counted twice.

New outings persist their selected grade, price, consumption interval, and
estimate source at start. Subsequent settings/calibration changes do not reprice
them. Old version-1.0 outings have no stored estimate; their UI explicitly labels
estimates as using current settings. Manual expense price entry remains possible.
