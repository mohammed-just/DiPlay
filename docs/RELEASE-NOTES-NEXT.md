# DiPlay next release notes

Changes through DiPlay 0.2.17 are documented in [0.2.17 release notes](RELEASE-NOTES-0.2.17.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

Add future unreleased changes here.

## Vehicle

- Show CarPlay arrows and the next-turn distance in the cluster's navigation view on head units built on the FCE/Coagent T7 platform, through their stock `fce_misc_service_hal_property` service, without ADB. The output follows **Navigation on HUD and dashboard**, whose card now also appears on these head units. See [BYD navigation](BYD_NAVIGATION.md#cluster-guidance-on-fce-head-units-needs-no-adb). The property path was confirmed in a car with Android Auto guidance; this CarPlay integration has not yet run in a car.
