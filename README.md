<img src="src/main/resources/assets/nvidium/icon.png" width="128">

# Nvidium

[![Modrinth](https://img.shields.io/modrinth/dt/nvidium?logo=modrinth)](https://modrinth.com/mod/nvidium)

Nvidium is a replacement rendering backend for Sodium that uses NVIDIA-exclusive OpenGL extensions to increase FPS by a significant amount.

### Compatibility

This mod explicitly requires an NVIDIA graphics card that supports mesh shaders. This feature was introduced in the Turing architecture, anything that is a 16xx series or newer (20xx series also works) that supports mesh shaders.

#### Q: Will this mod work on my non-NVIDIA system?
#### A: No, the mod is not functional on non-NVIDIA systems, but Nvidium will automatically disable itself. Your gameplay will not be affected.
### Warning

This mod uses uncommon technology (mesh shaders). This may result in Minecraft terminating unexpectedly.

### How does it work

With mesh shaders, a near fully GPU-driven rendering pipeline is used, enabling very fast and performant geometry culling of terrain, meaning your GPU can work much more efficiently.

# Requires Sodium to run

## Disables itself when Iris is actively using shaders
