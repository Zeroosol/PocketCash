// Prepares ios/ for an xtool build (Linux/WSL, no Mac): writes the SwiftData @Model expansion that xtool can't
// generate and regenerates the default presets. Run before `xtool dev` (from ios/) after changing the models.
// Usage: node tools/xtool_prepare.js
require('./expand_models').expandModels();
require('./build_presets');
