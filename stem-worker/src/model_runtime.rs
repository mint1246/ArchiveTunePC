use std::path::{Path, PathBuf};

use anyhow::{anyhow, Context, Result};
use ort::{ep, session::Session};

pub fn init_onnx_runtime() -> Result<()> {
    let ort_dll = std::env::var_os("ARCHIVETUNE_ONNXRUNTIME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            std::env::current_exe()
                .ok()
                .and_then(|path| path.parent().map(Path::to_path_buf))
                .unwrap_or_else(|| PathBuf::from("."))
                .join("onnxruntime.dll")
        });
    ort::init_from(&ort_dll)
        .with_context(|| format!("load ONNX Runtime from {}", ort_dll.display()))?
        .commit();
    Ok(())
}

pub fn load_directml_session(model: &Path) -> Result<Session> {
    Session::builder()?
        // The runner is one-shot and every stem segment has the same fixed shape. Retaining
        // CPU arena/prepacked copies only inflates its peak working set; DirectML owns the
        // device-side execution buffers for the active segment.
        .with_memory_pattern(false)
        .map_err(|error| anyhow!(error.to_string()))?
        .with_prepacking(false)
        .map_err(|error| anyhow!(error.to_string()))?
        .with_parallel_execution(false)
        .map_err(|error| anyhow!(error.to_string()))?
        .with_execution_providers([ep::DirectML::default().with_device_id(0).build()])
        .map_err(|error| anyhow!(error.to_string()))?
        .commit_from_file(model)
        .with_context(|| format!("load model {}", model.display()))
}
