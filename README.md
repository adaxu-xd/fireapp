# FireApp

FireApp is an Android per-app internet blocker based on Android's built-in `VpnService`. It does not use a remote VPN provider or external API.

## Included
- Installed launcher-app list with checkboxes.
- Local saving of selected packages.
- A local VPN interface that receives selected apps' IPv4 and IPv6 traffic and discards those packets.
- Foreground notification while active.
- Enable/apply and disable controls.
- GitHub Actions workflow to build a debug APK.

## Build an APK
Open the repository in Android Studio, sync Gradle, then choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

The GitHub Actions workflow runs on pushes to `main`. Open the repository's **Actions** tab, choose the successful **Build FireApp APK** run, and download its `FireApp-debug` artifact.

## First launch
Android shows its system VPN consent dialog. Approve it to enable the local firewall. Android permits only one active VPN at a time; starting another VPN app replaces FireApp.

## Important limitations
- This is an initial implementation and has not yet been built and tested on the target Redmi/MIUI device in this session.
- Test with a non-essential app first. Confirm that the selected app loses network access while other apps retain theirs.
- MIUI battery management may affect background operation; allow FireApp background activity if needed.
- The list includes apps with launcher icons; some system components are not listed.
- FireApp uses a local VPN firewall rather than changing Android's native per-app Wi-Fi/mobile-data switches.
