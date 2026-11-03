# Remote Management Check (Android)

Enter an IP address, an IP range or a CIDR block and see which management ports are open,
whether each uses HTTP or HTTPS, and whether the host replies to ping.

Accepted input: `10.11.0.1`, `10.11.0.1-50`, `10.11.0.1-10.11.1.80`, `10.11.0.0/24`, `10.11.0.*`,
or a list separated by comma / space / new line. IPv6 works for single addresses.
Maximum 1024 hosts per scan.

## Build the APK (no Android Studio needed)
1. Create a GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab, choose **Build Android APK**, press **Run workflow**.
3. After a few minutes download **RemoteManagementCheck-apk** from the run page, unzip it,
   and install `RemoteManagementCheck.apk` on the phone (allow "Install unknown apps" once).
   The APK is signed with the key in `app/release.jks`, so new versions install over the old one.
   Keep the repository PRIVATE (the signing key is inside it).

The phone must be on the same network as the devices (WiFi / VPN) to reach private IPs.
Use only on devices in your own network.
