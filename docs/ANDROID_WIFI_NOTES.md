# Android Wi-Fi Notes

Android does not expose classic Wi-Fi ad-hoc mode to normal apps. AirChat therefore uses two practical paths.

## LAN mode

LAN mode uses Android Network Service Discovery and TCP sockets.

Use it when:

- All phones are connected to the same router.
- One phone is running a hotspot and the others join it.
- The access point has no internet access.

This is the most reliable way to test AirChat today.

## Wi-Fi Direct mode

Wi-Fi Direct lets Android devices discover and form a local peer-to-peer group without a router. In practice, one device becomes the group owner. AirChat starts a TCP server for the group-owner path and sends framed mesh packets over that socket.

Wi-Fi Direct behavior varies by hardware and Android version. Some devices require location services to be enabled even when the app uses `NEARBY_WIFI_DEVICES` on Android 13+.

Large payloads should be sent as chunked protocol packets instead of single messages. AirChat caps selected files at 10 MB and keeps chunk payloads small enough to pass router guard rails and to avoid monopolizing the local link.

## Background mode

AirChat includes a foreground service for background mesh mode. When enabled from the top bar, the service keeps the process in an Android-approved foreground state with a persistent notification, then keeps LAN/Wi-Fi Direct discovery and relay running after the activity leaves the screen.

Android may still pause Wi-Fi work during aggressive battery saver modes. For field testing, keep battery saver off and avoid OEM app-killer profiles until transport stability has been measured.

While background mesh is enabled and the AirChat UI is not visible, AirChat can post separate message/file alerts. These alerts intentionally avoid message bodies and file names; they only show the sender and conversation label.

## Permissions

- Android 13+: `NEARBY_WIFI_DEVICES`.
- Android 13+: `POST_NOTIFICATIONS` so the foreground mesh notification can be shown.
- Android 12 and lower: `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` for discovery.
- Manifest-only Wi-Fi permissions: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`.
- Manifest-only foreground-service permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- `INTERNET` is still needed for sockets even when no internet is used.
