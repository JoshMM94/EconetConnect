# Econet Connect — Service Edition (development scaffold)

This is the first real Android project scaffold, not a functioning cloud client yet.

## Confirmed from the supplied ecoNET Cloud APK / PCAP
- Android package: `com.plum.econetcloud`
- Cognito endpoint: `cognito-idp.eu-central-1.amazonaws.com`
- Cognito Identity endpoint: `cognito-identity.eu-central-1.amazonaws.com`
- API Gateway: `6tx17a8ijh.execute-api.eu-central-1.amazonaws.com`
- AWS IoT endpoint: `a24t7r3f2r1nrr-ats.iot.eu-central-1.amazonaws.com`
- APK DEX contains `HeatingCurveConfig`, `getHeatingCurveConfig`, `setHeatingCurveConfig`,
  `getWizardHeatingCurveConfig`, `setWizardHeatingCurveConfig`, and MQTT state-related symbols.
- The capture shows Cognito traffic, then a ~2.49 MB API Gateway response, and an IoT TLS connection.
  Packet metadata cannot reveal encrypted request paths, payloads, or whether a login attempt succeeded.

## Important unknowns
- Cognito User Pool ID, App Client ID, auth flow, and exact challenge sequence.
- Exact API routes, request/response schemas, signing requirements, and IoT topic protocol.
- Whether the user's account is authorized for every service-level operation.

Do not guess these. Extract/verify them from the official APK and observed app behaviour before implementing network calls.

## Safety plan
1. Implement and test authentication without logging credentials.
2. Store refresh/session material using Android Keystore-backed encrypted storage.
3. Read installations and telemetry first.
4. Implement heating curve and shift reads.
5. Only then implement writes with range validation, explicit confirmation, audit log, and rollback guidance.
6. Boost remains out of initial scope.

## Build
Push this folder to a GitHub repository. GitHub Actions builds a debug APK and publishes it as an Actions artifact.


## Portal WebView diagnostics
The portal is loaded only after the Android WebView is attached. The app uses a standard mobile browser user-agent and reports whether the document body contains readable text after page load.

Build note: AndroidView now returns the existing WebView instance from its factory, preserving the correct Compose factory contract.

Build note: restored the AWS BasicAWSCredentials import required by the recovered MQTT signer.
