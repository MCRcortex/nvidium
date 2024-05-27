<img src="src/main/resources/assets/nvidium/nvidium.png" width="128">

# Nvidium

[![Modrinth](https://img.shields.io/modrinth/dt/nvidium?logo=modrinth)](https://modrinth.com/mod/nvidium)

Nvidium is a Minecraft mod that replaces the rendering backend for Sodium. It uses NVIDIA-exclusive OpenGL extensions to increase FPS by a significant amount, and to render huge amounts of terrain geometry at very playable framerates.

## Compatibility

Nvidium requires an NVIDIA graphics card that supports mesh shaders. This feature is available in the Turing architecture and newer (16xx series or newer, 20xx series also works). The Nvidium renderer will only be enabled on systems that meet its requirements. However, the mod will remain active regardless. This design prevents game crashes and potential issues. Even if a system with Nvidium installed doesn’t meet these requirements, it won’t cause any problems because of this design.

## Warning

Nvidium uses mesh shaders, an uncommon technology. This may cause Minecraft to crash unexpectedly. If this happens, please report the issue on our GitHub issue tracker.

## How it works

Nvidium uses a GPU-driven rendering pipeline with mesh shaders. This allows for fast and efficient geometry culling of terrain, making your GPU work more efficiently.

# Requires Sodium to run

## Disables itself when Iris is actively using shaders
