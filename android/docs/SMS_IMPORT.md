# Purchase expenses from SMS notifications — version 1.3

Enable **تسجيل مشتريات الرسائل تلقائيًا** in Settings and grant Rihla notification
access in Android's system screen. The feature is off by default. It processes
only the current default SMS application's notifications, using Android's
notification listener. No SMS inbox, SEND_SMS/READ_SMS permission, default SMS
role, banking login, network service, or periodic background polling is used.

The owner uses Al Rajhi. Tests cover representative Arabic/English purchase
layouts with card suffixes, SAR amounts, merchants, timestamps and available
balances. These fixtures are synthetic; the owner's exact message template
has not been supplied. A format that cannot be parsed confidently is skipped.

## Recording behavior

- A clear purchase amount in SAR/ريال/ر.س is converted exactly to halalas.
  Arabic/Persian digits, Arabic decimal/thousands separators and supported
  comma formats are normalized. Balance, fees and VAT fields are excluded
  from purchase-amount selection. Conflicting amounts are not guessed.
- OTP, verification, refunds, reversals, declines, transfers, cash withdrawals,
  balance-only messages and unsupported foreign-currency amounts are skipped.
- MessagingStyle notifications are processed as individual timestamped messages.
  A conversation/group summary is not parsed as a purchase. The activation
  timestamp prevents older conversation entries from being imported.
- Expenses appear immediately in the existing ledger and totals, labeled as
  imported, with the merchant when available. They start under OTHER; users can
  choose FUEL/FOOD/OTHER or delete them with confirmation.
- Linking uses SMS arrival time and the outing covering that time. Delayed bank
  SMS can therefore be unlinked or belong to the outing at arrival, rather than
  at the earlier purchase time. No route/location is invented.
- Fuel-category purchases are paid expenses. They do not invent liters, tank
  fullness, fuel grades, or calibrated fuel-economy records. Manual fill-ups
  remain available. Already-entered manual expenses are not automatically matched
  to SMS receipts, so users should remove duplicates they entered manually.
- A timestamp/content fingerprint and persistent receipt table prevent repeated
  notifications from recreating an expense, including after a restart or its
  deliberate deletion. Distinct messages with different timestamps remain
  distinct purchases. Deduplication depends on the SMS app preserving its message
  timestamp/content when reposting notifications.

The full message body is processed in memory and not stored or logged. The
database stores the extracted amount, merchant, arrival time, source, and a hash.
Other applications' notifications are discarded before text extraction. All
processing stays on the device. Disabling the feature stops imports; granting
notification access alone does not enable imports. Delete-all disables imports
before clearing the ledger and receipt table.

## Limits and verification

The SMS app must show message text in notifications. Muted, hidden, redacted or
truncated messages cannot be recovered through notification access. Android/OEM
delivery behavior still needs a real-phone check; the cloud tests do not validate
a physical Al Rajhi SMS reaching Google/Samsung Messages. Old inbox history is
not imported. Keep SMS notifications enabled and text visible, activate this
feature, then check the next purchase against its message.

Automated checks cover the parser, Android notification formats, Android 8
permission lookup, enable/disable time boundaries, atomic concurrent deduplication,
deletion, database reopening, arrival-time outing links, and absence of fabricated
fuel records. Room schema 4 adds expense metadata and receipt hashes through an
explicit 3→4 migration; original data from schemas 1–3 is preserved and tested.
See [safe upgrades](../UPDATE.md) before replacing an existing installation.
