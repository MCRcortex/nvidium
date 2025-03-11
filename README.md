# Amdium

A fork of the Nvidium mod that works on AMD GPUs supporting the GL_NV_mesh_shader extension.

## Overview

Amdium is a modified version of the Nvidium mod that removes dependencies on NVIDIA-specific OpenGL extensions, making it compatible with recent AMD GPUs that support the GL_NV_mesh_shader extension.

The original Nvidium mod requires the following NVIDIA-specific extensions:
- GL_NV_mesh_shader
- GL_NV_uniform_buffer_unified_memory
- GL_NV_vertex_buffer_unified_memory
- GL_NV_representative_fragment_test
- GL_ARB_sparse_buffer
- GL_NV_bindless_multi_draw_indirect

Amdium only requires GL_NV_mesh_shader, which is supported by recent AMD GPUs.

## Changes Made

1. Removed dependencies on NVIDIA-specific extensions:
   - Replaced GL_NV_uniform_buffer_unified_memory with standard GL_UNIFORM_BUFFER
   - Replaced GL_NV_vertex_buffer_unified_memory with standard buffer binding
   - Replaced GL_NV_representative_fragment_test with standard early-z testing
   - Replaced GL_ARB_sparse_buffer with standard buffer allocation
   - Replaced GL_NV_bindless_multi_draw_indirect with standard indirect drawing

2. Added AMD-compatible mesh shader rendering:
   - Created a helper class for AMD-compatible mesh shader rendering
   - Modified all renderers to use standard OpenGL buffer binding
   - Implemented alternative approaches for features that relied on NVIDIA-specific extensions

3. Other changes:
   - Renamed the mod to "Amdium" in logs and messages
   - Updated compatibility checks to only require GL_NV_mesh_shader
   - Disabled sparse buffer support which is NVIDIA-specific

## Requirements

- A GPU that supports the GL_NV_mesh_shader extension (recent AMD GPUs or NVIDIA GPUs)
- OpenGL 4.3 or higher

## Installation

Install like any other Fabric mod. Amdium is compatible with the same Minecraft versions as the original Nvidium mod.

## Credits

This mod is a fork of [Nvidium](https://github.com/MCRcortex/nvidium) by MCRcortex.