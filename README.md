# Sky Model Viewer

Sky Model Viewer is a small Windows desktop tool for inspecting local mesh files from a legally installed copy of *Sky: Children of the Light*. It indexes a local install, lets you browse discovered meshes, and renders supported meshes with basic material and texture lookup.

## Uses

- Browse mesh files from a local Sky install or extracted local asset tree.
- Preview supported static meshes in a simple 3D viewport.
- Inspect mesh metadata such as vertex counts, face counts, UV data, skeleton counts, material names, and inferred scale.
- Resolve common material definitions and display supported texture formats when the required local decoder support is available.

## Limitations

- This is a viewer, not an editor or asset extraction tool.
- The project does not export models, textures, animations, levels, or other game assets.
- Mesh, material, shader, and texture support is incomplete and based on reverse-engineered local file formats.
- Some compressed KTX textures require a local Python environment with `texture2ddecoder`; set `SKYMODELVIEWER_TEXTURE_PYTHON` if the viewer cannot find it automatically.
- The app targets Windows/WPF and requires the .NET 9 SDK to build from source.
- No game files are included. You must provide your own local install path.


## Legal Disclaimer

This project is independent and is not affiliated with, endorsed by, sponsored by, or approved by thatgamecompany. *Sky: Children of the Light*, related names, trademarks, game code, and game assets belong to their respective owners.

This repository does not contain thatgamecompany code, game binaries, models, textures, audio, levels, or other game assets. The tool only reads files that already exist on the user's machine for local viewing inside the application. It is not intended for, and does not provide functionality for, exporting or redistributing game assets.

Use this project only with files you are legally permitted to access. This README is not legal advice.
