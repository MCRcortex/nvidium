# nvidium

[![Modrinth](https://img.shields.io/modrinth/dt/nvidium?logo=modrinth)](https://modrinth.com/mod/nvidium)

nvidium is an alternative rendering backend designed for [Sodium](https://github.com/CaffeineMC/sodium-fabric). It utilizes exclusive NVIDIA OpenGL extensions and cutting-edge features to efficiently render vast amounts of terrain geometry, resulting in highly playable experiences with consistently high framerates.

## Requirements
- [Sodium](https://github.com/CaffeineMC/sodium-fabric)
- An NVIDIA GPU that supports mesh shaders (GTX 1600 series/Turing architecture or newer)

## FAQ
> Will this mod work even if dont have an NVIDIA graphics card?
> 
No, nvidium will automatically disable itself

> Can I use this mod with Iris?
> 
See [this](https://github.com/MCRcortex/nvidium/issues/3#issuecomment-1512757604) issue

## How does it work?
With mesh shaders, a nearly fully GPU-driven rendering pipeline is used, enabling very fast and performant geometry culling of terrain meaning your GPU can work much more efficiently.
