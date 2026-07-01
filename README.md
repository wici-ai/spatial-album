# Spatial Album

Turn one photo into an interactive 3D scene on Android using 3D Gaussian
Splatting spatial reframing.

Landing page: https://wici.ai/spatial-album/

## What It Does

Spatial Album lets you pick a photo, lift it into a 3D Gaussian-splat scene,
move the camera, and generate a new reframed image.

Basic flow:

1. Tap a photo.
2. The app reframes it into a 3D scene.
3. Drag to orbit, use two fingers to pan, pinch to zoom, or tap **Reset**.
4. Tap **Generate** to run refine + fill for the newly revealed areas.
5. Tap **Download** to save the final image.

The Android app renders the splat scene on-device with OpenGL ES 3. The backend
does the heavy model work.

## Install

Download the latest signed APK from:

https://github.com/wici-ai/spatial-album/releases/latest

Then sideload it on Android and allow installation from unknown sources when
prompted.

Current release: `v0.2` (`versionName` `0.2`, `versionCode` `5`).

Spatial Album is a non-commercial research/demo app because its backend uses
non-commercial model licenses. See [License](#license).

## Backend Options

Spatial Album can use either WiCi Cloud or your own local GPU backend.

### Option 1: WiCi Cloud

Use this if you do not have a GPU box.

- Default server: `http://app.wici.ai:54228`
- No setup required.
- Requires **Sign in with Google** in the app.

Google sign-in is handled in the app with Android Credential Manager and
Supabase auth. The current app stores the Supabase session locally; backend JWT
verification is a follow-up.

### Option 2: Local GPU Backend

Use this if you have a local NVIDIA GPU machine and want the app to run against
your own backend on the LAN.

Backend repo:

https://github.com/wici-ai/spatial-album-backend

One-line install:

```bash
curl -fsSL https://raw.githubusercontent.com/wici-ai/spatial-album-backend/main/deploy/install.sh | bash
```

The local backend advertises `_wici-backend._tcp` on port `54228` via mDNS/NSD.
In the app, **Server -> Automatic** tries to discover that local backend first
and falls back to WiCi Cloud if none is found. **Server -> Manual** lets you
enter a server address such as:

```text
http://192.168.1.50:54228
```

Local backends do not require Google sign-in.

## How It Works

The pipeline is:

1. **SHARP** reconstructs a single photo into Gaussian splats.
2. The Android app streams/caches the splat and renders it with a native GLES3
   Gaussian-splat viewer.
3. The user moves the camera with bounded orbit, pan, zoom, and reset controls.
4. **Generate** captures the current view and runs:
   - **Difix3D** to refine the rendered novel view.
   - **FLUX.1 Fill [dev]** to fill peripheral disocclusion regions.
5. The app displays and saves the final generated image.

The app includes clear server-unavailable and generate-unavailable states
instead of leaving the viewer blank when a backend is unreachable.

## Project Status

- Current app release: `v0.2`
- Android package: `com.wici.androidalbumdemo`
- Cloud login: Google sign-in through Supabase, currently used as a client-side
  gate for WiCi Cloud.
- Backend token verification is not yet enforced by the GPU backend.
- The public APK defaults to WiCi Cloud and can auto-discover a local backend
  on the same LAN.

## Development

This repository contains the Android app.

Key files:

- `app/build.gradle.kts` - Android package, SDK, version, and dependencies.
- `app/src/main/java/com/wici/androidalbumdemo/MainActivity.kt` - programmatic
  UI, album, server settings, backend health checks, reframe/generate flow.
- `app/src/main/java/com/wici/androidalbumdemo/SplatGlView.kt` - touch handling
  for orbit, two-finger pan, pinch zoom, scroll zoom, and reset dispatch.
- `app/src/main/java/com/wici/androidalbumdemo/SplatRenderer.kt` - GLES3
  Gaussian-splat renderer.
- `app/src/main/java/com/wici/androidalbumdemo/SupabaseAuth.kt` - Google
  sign-in and Supabase token exchange.

There are no XML layout files; the app UI is built programmatically. Release
APKs are signed so users can update in place. Keep release signing keys outside
the repository.

Build a local debug APK:

```bash
./gradlew :app:assembleDebug
```

The landing page is maintained separately on the `gh-pages` branch.

## License

WiCi-authored app source is provided under MIT terms, but the working
model-backed product is constrained by upstream non-commercial model licenses.
In particular, the backend uses or is designed to use:

- Apple SHARP model weights under an Apple research-only model license.
- NVIDIA Difix / Difix3D / `nvidia/difix_ref` under NVIDIA non-commercial
  research/evaluation terms.
- Black Forest Labs FLUX.1 Fill [dev] under the FLUX.1 [dev] Non-Commercial
  License.

Treat Spatial Album as non-commercial research/evaluation/demo software unless
the restricted model components are replaced or separately commercially
licensed.

See [LICENSE](LICENSE) and
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). The license inventory is a
draft for legal review, not legal advice.
